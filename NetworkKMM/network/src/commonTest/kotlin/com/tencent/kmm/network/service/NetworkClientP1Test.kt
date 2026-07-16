/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tencent.kmm.network.service

import com.tencent.kmm.network.export.NetworkErrorKind
import com.tencent.kmm.network.export.NetworkBody
import com.tencent.kmm.network.export.NetworkByteStream
import com.tencent.kmm.network.export.NetworkByteStreamSink
import com.tencent.kmm.network.export.NetworkMultipartPart
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkResponseBody
import com.tencent.kmm.network.export.VBTransportResultCode
import com.tencent.kmm.network.export.VBTransportMethod
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkClientP1Test {
    private fun retryableFailure(request: NetworkRequest) = NetworkResponse(
        request = request,
        statusCode = 503,
        headers = emptyMap(),
        body = NetworkResponseBody(),
        error = com.tencent.kmm.network.export.NetworkError(
            NetworkErrorKind.UNKNOWN,
            "retryable",
            503,
        ),
    )

    @Test
    fun executeOnAlreadyCancelledScopeStillPublishesOneTerminal() = runBlocking {
        val parent = Job().apply { cancel() }
        val callbacks = mutableListOf<NetworkResponse>()
        val callbackDelivered = CompletableDeferred<Unit>()
        var engineCalls = 0
        val client = NetworkClient(
            engine = object : NetworkEngine {
                override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse {
                    engineCalls++
                    error("cancelled scope must not dispatch")
                }
            },
            scope = CoroutineScope(parent)
        )

        val call = client.execute(NetworkRequest()) {
            callbacks += it
            callbackDelivered.complete(Unit)
        }
        val terminal = call.await()
        callbackDelivered.await()

        assertEquals(0, engineCalls)
        assertEquals(NetworkErrorKind.CANCELLED, terminal.error?.kind)
        assertEquals(1, callbacks.size)
        assertEquals(NetworkErrorKind.CANCELLED, callbacks.single().error?.kind)
    }

    @Test
    fun downloadOnAlreadyCancelledScopeStillPublishesOneTerminal() = runBlocking {
        val parent = Job().apply { cancel() }
        val callbacks = mutableListOf<NetworkResponse>()
        val callbackDelivered = CompletableDeferred<Unit>()
        var engineCalls = 0
        val client = NetworkClient(
            engine = object : NetworkEngine {
                override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse {
                    engineCalls++
                    error("cancelled scope must not dispatch")
                }
            },
            scope = CoroutineScope(parent)
        )

        val call = client.downloadStream(NetworkRequest(), onChunk = {}, onComplete = {
            callbacks += it
            callbackDelivered.complete(Unit)
        })
        val terminal = call.await()
        callbackDelivered.await()

        assertEquals(0, engineCalls)
        assertEquals(NetworkErrorKind.CANCELLED, terminal.error?.kind)
        assertEquals(1, callbacks.size)
        assertEquals(NetworkErrorKind.CANCELLED, callbacks.single().error?.kind)
    }

    @Test
    fun throwingPolicySelectorStillPublishesOneTerminal() = runBlocking {
        val callbacks = mutableListOf<NetworkResponse>()
        val client = NetworkClient(
            config = NetworkClientConfig(
                policySelector = { error("policy selection failed") }
            )
        )

        val executeCall = client.execute(NetworkRequest(), callbacks::add)
        val executeTerminal = executeCall.await()
        val streamCall = client.downloadStream(
            NetworkRequest(),
            onChunk = {},
            onComplete = callbacks::add
        )
        val streamTerminal = streamCall.await()

        assertEquals(NetworkErrorKind.UNKNOWN, executeTerminal.error?.kind)
        assertEquals("policy selection failed", executeTerminal.error?.message)
        assertEquals(NetworkErrorKind.UNKNOWN, streamTerminal.error?.kind)
        assertEquals("policy selection failed", streamTerminal.error?.message)
        assertEquals(2, callbacks.size)
    }

    @Test
    fun interceptorsRunInDeclaredOrder() = runBlocking {
        val events = mutableListOf<String>()
        val client = NetworkClient(
            config = NetworkClientConfig(
                interceptors = listOf(
                    object : NetworkInterceptor {
                        override suspend fun intercept(chain: NetworkInterceptorChain): NetworkResponse {
                            events.add("first-before")
                            val response = chain.proceed(chain.request.apply { setHeader("X-First", "1") })
                            events.add("first-after")
                            return response
                        }
                    },
                    object : NetworkInterceptor {
                        override suspend fun intercept(chain: NetworkInterceptorChain): NetworkResponse {
                            events.add("second-before")
                            val response = chain.proceed(chain.request.apply { setHeader("X-Second", "2") })
                            events.add("second-after")
                            return response
                        }
                    }
                )
            ),
            engine = object : NetworkEngine {
                override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse {
                    events.add("engine:${request.headers["X-First"]}:${request.headers["X-Second"]}")
                    return NetworkResponse(
                        request = request,
                        statusCode = 200,
                        headers = emptyMap(),
                        body = NetworkResponseBody(bytes = byteArrayOf(1))
                    )
                }
            }
        )

        client.execute(NetworkRequest())

        assertEquals(
            listOf("first-before", "second-before", "engine:1:2", "second-after", "first-after"),
            events
        )
    }

    @Test
    fun errorClassifierMapsStableTaxonomy() {
        assertEquals(
            NetworkErrorKind.CANCELLED,
            classifyNetworkErrorKind(VBTransportResultCode.CODE_CANCELED, "", null)
        )
        assertEquals(
            NetworkErrorKind.TIMEOUT,
            classifyNetworkErrorKind(VBTransportResultCode.CODE_FORCE_TIMEOUT, "", null)
        )
        assertEquals(
            NetworkErrorKind.DNS,
            classifyNetworkErrorKind(-1, "Could not resolve host", null)
        )
        assertEquals(
            NetworkErrorKind.TLS,
            classifyNetworkErrorKind(-1, "SSL certificate verify failed", null)
        )
        assertEquals(
            NetworkErrorKind.CONNECT,
            classifyNetworkErrorKind(-1, "Connection refused", null)
        )
        assertEquals(
            NetworkErrorKind.AUTH,
            classifyNetworkErrorKind(401, "Unauthorized", 401)
        )
        assertEquals(
            NetworkErrorKind.HTTP_STATUS,
            classifyNetworkErrorKind(503, "Service unavailable", 503)
        )
    }

    @Test
    fun cancelHandlerRegisteredAfterCancellationRunsImmediately() {
        val request = NetworkRequest()
        val call = NetworkCall(request)
        var invocations = 0

        call.cancel()
        call.addCancelHandler { invocations++ }

        assertEquals(1, invocations)
    }

    @Test
    fun cancelContinuesAfterOneHandlerThrows() {
        val request = NetworkRequest()
        val call = NetworkCall(request)
        val events = mutableListOf<String>()
        call.addCancelHandler {
            events += "first"
            error("cancel callback failed")
        }
        call.addCancelHandler { events += "second" }

        call.cancel()
        call.cancel()

        assertEquals(listOf("first", "second"), events)
    }

    @Test
    fun streamingCancelDuringPreparationStillCompletesExactlyOnce() = runBlocking {
        val preparationEntered = CompletableDeferred<Unit>()
        val neverRelease = CompletableDeferred<Unit>()
        val callbacks = mutableListOf<NetworkResponse>()
        val client = NetworkClient(
            config = NetworkClientConfig(
                requestMiddlewares = listOf(
                    object : NetworkRequestMiddleware {
                        override suspend fun prepare(request: NetworkRequest): NetworkRequest {
                            preparationEntered.complete(Unit)
                            neverRelease.await()
                            return request
                        }
                    }
                )
            ),
            engine = object : NetworkEngine {
                override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse =
                    error("stream engine must not start")
            },
            scope = CoroutineScope(coroutineContext + SupervisorJob())
        )

        val call = client.downloadStream(NetworkRequest(), onChunk = {}, onComplete = callbacks::add)
        preparationEntered.await()
        call.cancel()

        val awaited = call.await()
        assertEquals(NetworkErrorKind.CANCELLED, awaited.error?.kind)
        assertEquals(1, callbacks.size)
        assertEquals(NetworkErrorKind.CANCELLED, callbacks.single().error?.kind)
    }

    @Test
    fun middlewareReplacementBodyIsOwnedBeforeEngineEntry() = runBlocking {
        var originalCancels = 0
        var replacementCancels = 0
        var engineCalls = 0
        val replacementInstalled = CompletableDeferred<Unit>()
        val blockAfterReplacement = CompletableDeferred<Unit>()
        val parent = SupervisorJob()
        val request = NetworkRequest().apply {
            body = NetworkBody.Stream(
                NetworkByteStream.fromChunks(cancelBlock = { originalCancels++ }) {}
            )
        }
        val client = NetworkClient(
            config = NetworkClientConfig(
                requestMiddlewares = listOf(
                    object : NetworkRequestMiddleware {
                        override suspend fun prepare(request: NetworkRequest): NetworkRequest = request.apply {
                            body = NetworkBody.Stream(
                                NetworkByteStream.fromChunks(cancelBlock = {
                                    replacementCancels++
                                    error("replacement cancel failed")
                                }) {}
                            )
                            replacementInstalled.complete(Unit)
                        }
                    },
                    object : NetworkRequestMiddleware {
                        override suspend fun prepare(request: NetworkRequest): NetworkRequest {
                            blockAfterReplacement.await()
                            return request
                        }
                    }
                )
            ),
            engine = object : NetworkEngine {
                override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse {
                    engineCalls++
                    error("engine must not start")
                }
            },
            scope = CoroutineScope(Dispatchers.Default + parent)
        )

        val call = client.execute(request) {}
        replacementInstalled.await()
        parent.cancel()

        assertEquals(NetworkErrorKind.CANCELLED, call.await().error?.kind)
        assertEquals(1, originalCancels)
        assertEquals(1, replacementCancels)
        assertEquals(0, engineCalls)
    }

    @Test
    fun streamingCancelWinsAgainstLateEngineSuccess() = runBlocking {
        val engineEntered = CompletableDeferred<Unit>()
        val releaseEngine = CompletableDeferred<Unit>()
        val engineFinished = CompletableDeferred<Unit>()
        val callbacks = mutableListOf<NetworkResponse>()
        val client = NetworkClient(
            engine = object : NetworkEngine {
                override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse =
                    error("buffered execute is unused")

                override suspend fun downloadStream(
                    request: NetworkRequest,
                    call: NetworkCall,
                    onResponseStart: (Int, Long?, Map<String, List<String>>) -> Unit,
                    onChunk: (ByteArray) -> Unit
                ): NetworkResponse {
                    engineEntered.complete(Unit)
                    withContext(NonCancellable) { releaseEngine.await() }
                    engineFinished.complete(Unit)
                    return NetworkResponse(
                        request = request,
                        statusCode = 200,
                        headers = emptyMap(),
                        body = NetworkResponseBody()
                    )
                }
            },
            scope = CoroutineScope(coroutineContext + SupervisorJob())
        )

        val call = client.downloadStream(NetworkRequest(), onChunk = {}, onComplete = callbacks::add)
        engineEntered.await()
        call.cancel()
        releaseEngine.complete(Unit)
        engineFinished.await()
        yield()

        val awaited = call.await()
        assertEquals(NetworkErrorKind.CANCELLED, awaited.error?.kind)
        assertEquals(1, callbacks.size)
        assertEquals(NetworkErrorKind.CANCELLED, callbacks.single().error?.kind)
    }

    @Test
    fun streamingCancelSuppressesLateStartAndChunksFromNonCooperativeEngine() = runBlocking {
        val engineEntered = CompletableDeferred<Unit>()
        val releaseEngine = CompletableDeferred<Unit>()
        val engineFinished = CompletableDeferred<Unit>()
        val callbacks = mutableListOf<NetworkResponse>()
        var starts = 0
        var chunks = 0
        val client = NetworkClient(
            engine = object : NetworkEngine {
                override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse =
                    error("buffered execute is unused")

                override suspend fun downloadStream(
                    request: NetworkRequest,
                    call: NetworkCall,
                    onResponseStart: (Int, Long?, Map<String, List<String>>) -> Unit,
                    onChunk: (ByteArray) -> Unit
                ): NetworkResponse {
                    engineEntered.complete(Unit)
                    withContext(NonCancellable) { releaseEngine.await() }
                    onResponseStart(200, 1, emptyMap())
                    onChunk(byteArrayOf(1))
                    engineFinished.complete(Unit)
                    return NetworkResponse(
                        request = request,
                        statusCode = 200,
                        headers = emptyMap(),
                        body = NetworkResponseBody()
                    )
                }
            },
            scope = CoroutineScope(coroutineContext + SupervisorJob())
        )

        val call = client.downloadStream(
            NetworkRequest(),
            onResponseStart = { _, _, _ -> starts++ },
            onChunk = { chunks++ },
            onComplete = callbacks::add
        )
        engineEntered.await()
        call.cancel()
        releaseEngine.complete(Unit)
        engineFinished.await()
        yield()

        assertEquals(0, starts)
        assertEquals(0, chunks)
        assertEquals(NetworkErrorKind.CANCELLED, call.await().error?.kind)
        assertEquals(1, callbacks.size)
    }

    @Test
    fun admittedStreamCallbackFinishesBeforeCancellationTerminalDelivery() = runBlocking {
        val engineEntered = CompletableDeferred<Unit>()
        val releaseEngine = CompletableDeferred<Unit>()
        val callbackAdmitted = CompletableDeferred<Unit>()
        val releaseCallback = CompletableDeferred<Unit>()
        val cancelReturned = CompletableDeferred<Unit>()
        val terminalDelivered = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val client = NetworkClient(
            engine = object : NetworkEngine {
                override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse =
                    error("buffered execute is unused")

                override suspend fun downloadStream(
                    request: NetworkRequest,
                    call: NetworkCall,
                    onResponseStart: (Int, Long?, Map<String, List<String>>) -> Unit,
                    onChunk: (ByteArray) -> Unit
                ): NetworkResponse {
                    engineEntered.complete(Unit)
                    withContext(NonCancellable) { releaseEngine.await() }
                    onResponseStart(200, 1, emptyMap())
                    onChunk(byteArrayOf(1))
                    return NetworkResponse(
                        request = request,
                        statusCode = 200,
                        headers = emptyMap(),
                        body = NetworkResponseBody()
                    )
                }
            },
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        )

        val call = client.downloadStream(
            NetworkRequest(),
            onResponseStart = { _, _, _ -> events += "start" },
            onChunk = { events += "chunk" },
            onComplete = {
                events += "terminal"
                terminalDelivered.complete(Unit)
            }
        )
        call.beforeActiveCallbackActionForTest = {
            callbackAdmitted.complete(Unit)
            runBlocking { releaseCallback.await() }
        }
        engineEntered.await()
        releaseEngine.complete(Unit)
        callbackAdmitted.await()
        launch(Dispatchers.Default) {
            call.cancel()
            cancelReturned.complete(Unit)
        }
        cancelReturned.await()
        assertFalse(terminalDelivered.isCompleted)

        releaseCallback.complete(Unit)
        terminalDelivered.await()
        call.await()

        assertEquals(listOf("start", "terminal"), events)
    }

    @Test
    fun throwingMultipartCancellationStillReachesEveryOwnerAndTerminal() {
        val bodyCancels = mutableListOf<String>()
        val request = NetworkRequest().apply {
            body = NetworkBody.Multipart(
                parts = listOf(
                    NetworkMultipartPart(
                        name = "first",
                        body = NetworkBody.Stream(
                            NetworkByteStream.fromChunks(cancelBlock = {
                                bodyCancels += "first"
                                error("first cancel failed")
                            }) {}
                        )
                    ),
                    NetworkMultipartPart(
                        name = "second",
                        body = NetworkBody.Stream(
                            NetworkByteStream.fromChunks(cancelBlock = {
                                bodyCancels += "second"
                            }) {}
                        )
                    )
                )
            )
        }
        val call = NetworkCall(request)
        val job = Job()
        val handlers = mutableListOf<String>()
        val completions = mutableListOf<NetworkResponse>()
        call.attachJob(job)
        call.addCancelHandler {
            handlers += "first"
            error("transport cancel failed")
        }
        call.addCancelHandler { handlers += "second" }
        call.addCompletionHandler(completions::add)

        call.cancel()
        call.addCancelHandler { error("late cancel handler failed") }

        assertEquals(listOf("first", "second"), bodyCancels)
        assertEquals(listOf("first", "second"), handlers)
        assertEquals(true, job.isCancelled)
        assertEquals(1, completions.size)
        assertEquals(NetworkErrorKind.CANCELLED, completions.single().error?.kind)
    }

    @Test
    fun streamingUploadCancellationOwnersAreIndependentAndOneShot() = runBlocking {
        var originalBodyCancels = 0
        var replacementBodyCancels = 0
        var derivedSourceCancels = 0
        var transportCancels = 0
        val request = NetworkRequest().apply {
            body = NetworkBody.Stream(
                NetworkByteStream.fromChunks(cancelBlock = { originalBodyCancels++ }) {}
            )
        }
        val prepared = request.copyMutable().apply {
            body = NetworkBody.FileRef(
                path = "/virtual/upload.bin",
                cancelBlock = {
                    replacementBodyCancels++
                    error("request body cancel failed")
                },
                openStreamBlock = {
                    NetworkByteStream.fromChunks(cancelBlock = {
                        derivedSourceCancels++
                        error("derived source cancel failed")
                    }) {}
                }
            )
        }
        val source = requireNotNull(networkUploadStreamSourceOrNull(prepared))
        assertTrue(source.cancelSeparatelyFromRequestBody)
        val call = NetworkCall(request)
        val owners = StreamingUploadCancellationOwners(
            cancelOriginalRequestBody = call::cancelRequestBodyOnce,
            cancelPreparedRequestBody = { call.cancelBodyOnce(prepared.body) },
            cancelDerivedSource = source.stream::cancel,
            cancelNativeRequest = null,
            closePullBridge = null,
            cancelTransport = { transportCancels++ }
        )
        call.addCancelHandler(owners::cancelAll)

        call.cancel()
        // Models the continuation cancellation callback racing the public
        // NetworkCall cancellation handler.
        owners.cancelAll()

        assertEquals(1, originalBodyCancels)
        assertEquals(1, replacementBodyCancels)
        assertEquals(1, derivedSourceCancels)
        assertEquals(1, transportCancels)
        assertEquals(NetworkErrorKind.CANCELLED, call.await().error?.kind)
    }

    @Test
    fun middlewareReplacementMultipartCancelsEveryLogicalOwnerOnce() = runBlocking {
        var originalCancels = 0
        val partCancels = mutableListOf<String>()
        var derivedCancels = 0
        var transportCancels = 0
        val request = NetworkRequest().apply {
            body = NetworkBody.Stream(
                NetworkByteStream.fromChunks(cancelBlock = { originalCancels++ }) {}
            )
        }
        val prepared = request.copyMutable().apply {
            body = NetworkBody.Multipart(
                parts = listOf(
                    NetworkMultipartPart(
                        "first",
                        NetworkBody.Stream(
                            NetworkByteStream.fromChunks(cancelBlock = {
                                partCancels += "first"
                                error("first failed")
                            }) {}
                        )
                    ),
                    NetworkMultipartPart(
                        "second",
                        NetworkBody.FileRef(
                            path = "/virtual/second.bin",
                            cancelBlock = { partCancels += "second" },
                            openStreamBlock = {
                                NetworkByteStream.fromChunks(cancelBlock = { derivedCancels++ }) {
                                    it.write(byteArrayOf(1))
                                }
                            }
                        )
                    )
                )
            )
        }
        val source = requireNotNull(networkUploadStreamSourceOrNull(prepared))
        assertTrue(source.cancelSeparatelyFromRequestBody)
        val call = NetworkCall(request)
        val owners = StreamingUploadCancellationOwners(
            cancelOriginalRequestBody = call::cancelRequestBodyOnce,
            cancelPreparedRequestBody = { call.cancelBodyOnce(prepared.body) },
            cancelDerivedSource = source.stream::cancel,
            cancelNativeRequest = null,
            closePullBridge = null,
            cancelTransport = { transportCancels++ }
        )
        call.addCancelHandler(owners::cancelAll)

        source.stream.readChunks(object : NetworkByteStreamSink {
            override suspend fun write(bytes: ByteArray) {
                if (bytes.contentEquals(byteArrayOf(1))) call.cancel()
            }
        })

        call.cancel()
        owners.cancelAll()

        assertEquals(1, originalCancels)
        assertEquals(listOf("first", "second"), partCancels)
        assertEquals(1, derivedCancels)
        assertEquals(1, transportCancels)
    }

    @Test
    fun uploadTransportCancelIsClaimedBeforePullCloseCanDisarmOwners() {
        var transportCancels = 0
        lateinit var owners: StreamingUploadCancellationOwners
        owners = StreamingUploadCancellationOwners(
            cancelOriginalRequestBody = {},
            cancelPreparedRequestBody = {},
            cancelDerivedSource = null,
            cancelNativeRequest = null,
            closePullBridge = { owners.disarmNativeTransportOwners() },
            cancelTransport = { transportCancels++ },
        )

        owners.cancelAll()
        owners.cancelAll()

        assertEquals(1, transportCancels)
    }

    @Test
    fun unsupportedStreamingMethodDoesNotOpenTopLevelFileRef() = runBlocking {
        var opens = 0
        val request = NetworkRequest(method = VBTransportMethod.GET).apply {
            body = NetworkBody.FileRef(
                path = "/virtual/never-open.bin",
                openStreamBlock = {
                    opens++
                    NetworkByteStream.fromChunks {}
                },
            )
        }

        val response = VBTransportNetworkEngine.execute(request, NetworkCall(request))

        assertEquals(0, opens)
        assertEquals(NetworkErrorKind.UNKNOWN, response.error?.kind)
    }

    @Test
    fun streamingMiddlewareFailurePublishesOneTerminal() = runBlocking {
        val callbacks = mutableListOf<NetworkResponse>()
        val callbackDelivered = CompletableDeferred<Unit>()
        var mutatedBodyCancels = 0
        val client = NetworkClient(
            config = NetworkClientConfig(
                requestMiddlewares = listOf(
                    object : NetworkRequestMiddleware {
                        override suspend fun prepare(request: NetworkRequest): NetworkRequest {
                            request.body = NetworkBody.Stream(
                                NetworkByteStream.fromChunks(cancelBlock = { mutatedBodyCancels++ }) {}
                            )
                            error("middleware failed")
                        }
                    }
                )
            ),
            scope = CoroutineScope(coroutineContext + SupervisorJob())
        )

        val call = client.downloadStream(NetworkRequest(), onChunk = {}, onComplete = {
            callbacks += it
            callbackDelivered.complete(Unit)
        })
        val awaited = call.await()
        callbackDelivered.await()

        assertEquals(NetworkErrorKind.UNKNOWN, awaited.error?.kind)
        assertEquals("middleware failed", awaited.error?.message)
        assertEquals(1, callbacks.size)
        assertEquals(1, mutatedBodyCancels)
    }

    @Test
    fun laterMiddlewareFailureCancelsEarlierReplacementBodyExactlyOnce() = runBlocking {
        var replacementCancels = 0
        var engineCalls = 0
        val client = NetworkClient(
            config = NetworkClientConfig(
                requestMiddlewares = listOf(
                    object : NetworkRequestMiddleware {
                        override suspend fun prepare(request: NetworkRequest): NetworkRequest = request.apply {
                            body = NetworkBody.Stream(
                                NetworkByteStream.fromChunks(cancelBlock = { replacementCancels++ }) {}
                            )
                        }
                    },
                    object : NetworkRequestMiddleware {
                        override suspend fun prepare(request: NetworkRequest): NetworkRequest =
                            error("later middleware failed")
                    },
                )
            ),
            engine = object : NetworkEngine {
                override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse {
                    engineCalls++
                    error("engine must not start")
                }
            },
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
        )

        val response = client.execute(NetworkRequest())

        assertEquals(NetworkErrorKind.UNKNOWN, response.error?.kind)
        assertEquals("later middleware failed", response.error?.message)
        assertEquals(1, replacementCancels)
        assertEquals(0, engineCalls)
    }

    @Test
    fun bufferedProgressAfterCancellationTerminalIsSuppressed() = runBlocking {
        val engineEntered = CompletableDeferred<NetworkRequest>()
        val releaseEngine = CompletableDeferred<Unit>()
        var progressCalls = 0
        val request = NetworkRequest().apply {
            progress = com.tencent.kmm.network.export.NetworkProgressCallbacks(
                downloadProgress = { progressCalls++ }
            )
        }
        val client = NetworkClient(
            engine = object : NetworkEngine {
                override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse {
                    engineEntered.complete(request)
                    withContext(NonCancellable) {
                        releaseEngine.await()
                        request.progress.downloadProgress?.invoke(
                            com.tencent.kmm.network.export.NetworkTransferProgress(1, 1)
                        )
                    }
                    return NetworkResponse(request, 200, emptyMap(), NetworkResponseBody())
                }
            },
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
        )

        val call = client.execute(request) {}
        engineEntered.await()
        call.cancel()
        releaseEngine.complete(Unit)
        val response = call.await()
        yield()

        assertEquals(NetworkErrorKind.CANCELLED, response.error?.kind)
        assertEquals(0, progressCalls)
    }

    @Test
    fun interceptorSameRequestProgressReplacementAfterTerminalIsSuppressed() = runBlocking {
        val engineEntered = CompletableDeferred<Unit>()
        val releaseEngine = CompletableDeferred<Unit>()
        var progressCalls = 0
        val client = NetworkClient(
            config = NetworkClientConfig(
                interceptors = listOf(
                    object : NetworkInterceptor {
                        override suspend fun intercept(chain: NetworkInterceptorChain): NetworkResponse {
                            val replacement = chain.request.apply {
                                progress = com.tencent.kmm.network.export.NetworkProgressCallbacks(
                                    downloadProgress = { progressCalls++ }
                                )
                            }
                            return chain.proceed(replacement)
                        }
                    }
                )
            ),
            engine = object : NetworkEngine {
                override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse {
                    engineEntered.complete(Unit)
                    withContext(NonCancellable) {
                        releaseEngine.await()
                        request.progress.downloadProgress?.invoke(
                            com.tencent.kmm.network.export.NetworkTransferProgress(1, 1)
                        )
                    }
                    return NetworkResponse(request, 200, emptyMap(), NetworkResponseBody())
                }
            },
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
        )

        val call = client.execute(NetworkRequest()) {}
        engineEntered.await()
        call.cancel()
        releaseEngine.complete(Unit)
        assertEquals(NetworkErrorKind.CANCELLED, call.await().error?.kind)
        yield()

        assertEquals(0, progressCalls)
    }

    @Test
    fun interceptorActualNonrepeatableBodyOwnsFinalFailureAndDisablesRetry() = runBlocking {
        var bodyCancels = 0
        var engineAttempts = 0
        val request = NetworkRequest(method = VBTransportMethod.POST).apply {
            policy = com.tencent.kmm.network.export.NetworkRequestPolicy(
                retry = com.tencent.kmm.network.export.NetworkRetryPolicy(
                    maxRetries = 1,
                    backoff = com.tencent.kmm.network.export.NetworkBackoffPolicy(
                        initialDelayMillis = 0,
                        maxDelayMillis = 0,
                    ),
                )
            )
        }
        val client = NetworkClient(
            config = NetworkClientConfig(
                interceptors = listOf(
                    object : NetworkInterceptor {
                        override suspend fun intercept(chain: NetworkInterceptorChain): NetworkResponse {
                            chain.request.body = NetworkBody.Stream(
                                NetworkByteStream.fromChunks(cancelBlock = { bodyCancels++ }) {}
                            )
                            return chain.proceed(chain.request)
                        }
                    }
                )
            ),
            engine = object : NetworkEngine {
                override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse {
                    engineAttempts++
                    return NetworkResponse(
                        request,
                        503,
                        emptyMap(),
                        NetworkResponseBody(),
                        com.tencent.kmm.network.export.NetworkError(
                            NetworkErrorKind.UNKNOWN,
                            "retryable",
                            503,
                        ),
                    )
                }
            },
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
        )

        assertEquals(503, client.execute(request).statusCode)
        assertEquals(1, engineAttempts)
        assertEquals(1, bodyCancels)
    }

    @Test
    fun laterAttemptShortCircuitBindsItsOwnBodyInsteadOfPreviousEngineBody() = runBlocking {
        var interceptorAttempts = 0
        var engineAttempts = 0
        var shortCircuitBodyCancels = 0
        val request = NetworkRequest(method = VBTransportMethod.POST).apply {
            policy = com.tencent.kmm.network.export.NetworkRequestPolicy(
                retry = com.tencent.kmm.network.export.NetworkRetryPolicy(
                    maxRetries = 2,
                    backoff = com.tencent.kmm.network.export.NetworkBackoffPolicy(0, 0),
                )
            )
        }
        val client = NetworkClient(
            config = NetworkClientConfig(
                interceptors = listOf(object : NetworkInterceptor {
                    override suspend fun intercept(chain: NetworkInterceptorChain): NetworkResponse {
                        interceptorAttempts++
                        return if (interceptorAttempts == 1) {
                            chain.request.body = NetworkBody.Bytes(byteArrayOf(1))
                            chain.proceed(chain.request)
                        } else {
                            chain.request.body = NetworkBody.Stream(
                                NetworkByteStream.fromChunks(cancelBlock = { shortCircuitBodyCancels++ }) {}
                            )
                            retryableFailure(chain.request)
                        }
                    }
                })
            ),
            engine = object : NetworkEngine {
                override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse {
                    engineAttempts++
                    return retryableFailure(request)
                }
            },
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
        )

        assertEquals(503, client.execute(request).statusCode)
        assertEquals(2, interceptorAttempts)
        assertEquals(1, engineAttempts)
        assertEquals(1, shortCircuitBodyCancels)
    }

    @Test
    fun multiProceedResponseIdentitySelectsTheBodyThatProducedReturnedResponse() = runBlocking {
        suspend fun runCase(returnFirst: Boolean): Int {
            var engineAttempts = 0
            val request = NetworkRequest(method = VBTransportMethod.POST).apply {
                policy = com.tencent.kmm.network.export.NetworkRequestPolicy(
                    retry = com.tencent.kmm.network.export.NetworkRetryPolicy(
                        maxRetries = 1,
                        backoff = com.tencent.kmm.network.export.NetworkBackoffPolicy(0, 0),
                    )
                )
            }
            val client = NetworkClient(
                config = NetworkClientConfig(
                    interceptors = listOf(object : NetworkInterceptor {
                        override suspend fun intercept(chain: NetworkInterceptorChain): NetworkResponse {
                            val firstRequest = chain.request.apply {
                                body = NetworkBody.Stream(NetworkByteStream.fromChunks {})
                            }
                            val first = chain.proceed(firstRequest)
                            val secondRequest = NetworkRequest(method = VBTransportMethod.POST).apply {
                                body = NetworkBody.Bytes(byteArrayOf(2))
                            }
                            val second = chain.proceed(secondRequest)
                            return if (returnFirst) first else second
                        }
                    })
                ),
                engine = object : NetworkEngine {
                    override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse {
                        engineAttempts++
                        return retryableFailure(request)
                    }
                },
                scope = CoroutineScope(coroutineContext + SupervisorJob()),
            )
            client.execute(request)
            return engineAttempts
        }

        assertEquals(2, runCase(returnFirst = true))
        assertEquals(4, runCase(returnFirst = false))
    }

    @Test
    fun statusOnlyFinalFailureReleasesActualInterceptorBody() = runBlocking {
        var bodyCancels = 0
        val client = NetworkClient(
            config = NetworkClientConfig(
                interceptors = listOf(
                    object : NetworkInterceptor {
                        override suspend fun intercept(chain: NetworkInterceptorChain): NetworkResponse {
                            chain.request.body = NetworkBody.FileRef(
                                path = "/virtual/status-only.bin",
                                cancelBlock = { bodyCancels++ },
                                readAllBlock = { byteArrayOf(1) },
                            )
                            return chain.proceed(chain.request)
                        }
                    }
                )
            ),
            engine = object : NetworkEngine {
                override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse =
                    NetworkResponse(request, 503, emptyMap(), NetworkResponseBody(), error = null)
            },
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
        )

        assertEquals(503, client.execute(NetworkRequest()).statusCode)
        assertEquals(1, bodyCancels)
    }

    @Test
    fun authRefreshDoesNotReplayNonrepeatableActualBody() = runBlocking {
        var refreshCalls = 0
        var engineAttempts = 0
        val request = NetworkRequest(method = VBTransportMethod.POST).apply {
            body = NetworkBody.Stream(NetworkByteStream.fromChunks {})
        }
        val client = NetworkClient(
            config = NetworkClientConfig(
                auth = NetworkAuthConfig(
                    tokenProvider = object : NetworkTokenProvider {
                        override suspend fun currentToken(request: NetworkRequest): String? = "old"
                        override suspend fun refreshToken(
                            request: NetworkRequest,
                            response: NetworkResponse,
                        ): String? {
                            refreshCalls++
                            return "new"
                        }
                    }
                )
            ),
            engine = object : NetworkEngine {
                override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse {
                    engineAttempts++
                    return NetworkResponse(
                        request,
                        401,
                        emptyMap(),
                        NetworkResponseBody(),
                        com.tencent.kmm.network.export.NetworkError(
                            NetworkErrorKind.UNKNOWN,
                            "unauthorized",
                            401,
                        ),
                    )
                }
            },
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
        )

        assertEquals(401, client.execute(request).statusCode)
        assertEquals(1, engineAttempts)
        assertEquals(0, refreshCalls)
    }

    @Test
    fun streamingSourceOpenFailureCancelsPreparedFileOwner() = runBlocking {
        var fileCancels = 0
        val request = NetworkRequest(method = VBTransportMethod.POST).apply {
            body = NetworkBody.FileRef(
                path = "/virtual/open-fails.bin",
                cancelBlock = { fileCancels++ },
                openStreamBlock = { error("open failed") },
            )
        }

        val failure = runCatching {
            VBTransportNetworkEngine.execute(request, NetworkCall(request))
        }.exceptionOrNull()

        assertEquals("open failed", failure?.message)
        assertEquals(1, fileCancels)
    }

    @Test
    fun uploadAttemptFailureReleasesOnlyDerivedOwnerOnce() {
        var original = 0
        var prepared = 0
        var derived = 0
        var transport = 0
        val owners = StreamingUploadCancellationOwners(
            cancelOriginalRequestBody = { original++ },
            cancelPreparedRequestBody = { prepared++ },
            cancelDerivedSource = { derived++ },
            cancelNativeRequest = null,
            closePullBridge = null,
            cancelTransport = { transport++ },
        )

        owners.releaseAttemptSourceOnFailure()
        owners.releaseAttemptSourceOnFailure()

        assertEquals(0, original)
        assertEquals(0, prepared)
        assertEquals(1, derived)
        assertEquals(0, transport)
    }

    @Test
    fun retryableUploadFailureKeepsLogicalBodyUntilFinalOutcome() = runBlocking {
        fun request(cancel: () -> Unit) = NetworkRequest(method = VBTransportMethod.POST).apply {
            body = NetworkBody.FileRef(
                path = "/virtual/retryable.bin",
                cancelBlock = cancel,
                openStreamBlock = { NetworkByteStream.fromChunks {} },
            )
            policy = com.tencent.kmm.network.export.NetworkRequestPolicy(
                retry = com.tencent.kmm.network.export.NetworkRetryPolicy(
                    maxRetries = 1,
                    backoff = com.tencent.kmm.network.export.NetworkBackoffPolicy(
                        initialDelayMillis = 0,
                        maxDelayMillis = 0,
                    ),
                )
            )
        }
        fun failure(request: NetworkRequest) = NetworkResponse(
            request = request,
            statusCode = 503,
            headers = emptyMap(),
            body = NetworkResponseBody(),
            error = com.tencent.kmm.network.export.NetworkError(
                kind = NetworkErrorKind.UNKNOWN,
                message = "retryable",
                statusCode = 503,
            ),
        )

        var successBodyCancels = 0
        var successAttempts = 0
        val successRequest = request { successBodyCancels++ }
        val successClient = NetworkClient(
            engine = object : NetworkEngine {
                override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse {
                    successAttempts++
                    return if (successAttempts == 1) failure(request)
                    else NetworkResponse(request, 200, emptyMap(), NetworkResponseBody())
                }
            },
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
        )

        assertEquals(200, successClient.execute(successRequest).statusCode)
        assertEquals(2, successAttempts)
        assertEquals(0, successBodyCancels)

        var failedBodyCancels = 0
        var failedAttempts = 0
        val failedRequest = request { failedBodyCancels++ }
        val failedClient = NetworkClient(
            engine = object : NetworkEngine {
                override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse {
                    failedAttempts++
                    return failure(request)
                }
            },
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
        )

        assertEquals(503, failedClient.execute(failedRequest).statusCode)
        assertEquals(2, failedAttempts)
        assertEquals(1, failedBodyCancels)
    }

    @Test
    fun finalFailureContainsThrowingLogicalOwnerAndCleansRemainingBodies() {
        var throwingCancels = 0
        var remainingCancels = 0
        var terminals = 0
        val request = NetworkRequest()
        val call = NetworkCall(request)
        call.ownBody(
            NetworkBody.FileRef(
                path = "/virtual/throwing-final-owner",
                cancelBlock = {
                    throwingCancels++
                    error("cleanup failed")
                },
            )
        )
        call.ownBody(
            NetworkBody.Stream(
                NetworkByteStream.fromChunks(cancelBlock = { remainingCancels++ }) {}
            )
        )
        call.addCompletionHandler { terminals++ }

        call.tryComplete(
            NetworkResponse(
                request,
                503,
                emptyMap(),
                NetworkResponseBody(),
                com.tencent.kmm.network.export.NetworkError(
                    NetworkErrorKind.UNKNOWN,
                    "final failure",
                    503,
                ),
            )
        )

        assertEquals(1, throwingCancels)
        assertEquals(1, remainingCancels)
        assertEquals(1, terminals)
    }

    @Test
    fun fallbackCallbackAndTerminalObserverFailuresCannotEscapeOrHang() = runBlocking {
        var terminalCalls = 0
        val client = NetworkClient(
            engine = object : NetworkEngine {
                override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse =
                    NetworkResponse(
                        request = request,
                        statusCode = 200,
                        headers = emptyMap(),
                        body = NetworkResponseBody(bytes = byteArrayOf(1))
                    )
            },
            scope = CoroutineScope(coroutineContext + SupervisorJob())
        )

        val call = client.downloadStream(
            request = NetworkRequest(),
            onResponseStart = { _, _, _ -> error("start failed") },
            onChunk = {},
            onComplete = {
                terminalCalls++
                error("terminal observer failed")
            }
        )
        val awaited = call.await()

        assertEquals(NetworkErrorKind.UNKNOWN, awaited.error?.kind)
        assertEquals("start failed", awaited.error?.message)
        assertEquals(1, terminalCalls)
    }

    @Test
    fun cancellationTerminalRejectsEveryLateSuccessStress() = runBlocking {
        repeat(1_000) {
            val request = NetworkRequest()
            val callbacks = mutableListOf<NetworkResponse>()
            val call = NetworkCall(request)
            call.addCompletionHandler(callbacks::add)

            call.cancel()
            assertFalse(
                call.tryComplete(
                    NetworkResponse(
                        request = request,
                        statusCode = 200,
                        headers = emptyMap(),
                        body = NetworkResponseBody()
                    )
                )
            )

            assertEquals(NetworkErrorKind.CANCELLED, call.await().error?.kind)
            assertEquals(1, callbacks.size)
            assertEquals(NetworkErrorKind.CANCELLED, callbacks.single().error?.kind)
        }
    }
}

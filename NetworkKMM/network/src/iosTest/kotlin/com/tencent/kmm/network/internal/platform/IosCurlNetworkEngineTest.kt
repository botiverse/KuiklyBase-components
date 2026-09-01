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
package com.tencent.kmm.network.internal.platform

import com.tencent.kmm.network.curl.CurlNativeResponse
import com.tencent.kmm.network.export.NetworkBody
import com.tencent.kmm.network.export.NetworkByteStream
import com.tencent.kmm.network.export.NetworkEngineCapabilities
import com.tencent.kmm.network.export.NetworkErrorKind
import com.tencent.kmm.network.export.NetworkHttpProtocol
import com.tencent.kmm.network.export.NetworkProgressCallbacks
import com.tencent.kmm.network.export.NetworkRequestPolicy
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkTransferProgress
import com.tencent.kmm.network.export.NetworkCurlProxyConfiguration
import com.tencent.kmm.network.export.NetworkCurlRuntimeConfiguration
import com.tencent.kmm.network.export.NetworkCurlTrustStore
import com.tencent.kmm.network.export.VBTransportElapseStatistics
import com.tencent.kmm.network.export.VBTransportCurl
import com.tencent.kmm.network.export.VBTransportMethod
import com.tencent.kmm.network.export.VBTransportResultCode
import com.tencent.kmm.network.export.networkCurlSha256Hex
import com.tencent.kmm.network.service.NetworkCall
import com.tencent.kmm.network.service.NetworkClient
import com.tencent.kmm.network.service.NetworkClientConfig
import com.tencent.kmm.network.service.NetworkRequestMiddleware
import com.tencent.kmm.network.service.NetworkEngineSelection
import com.tencent.kmm.network.service.NetworkTransportEngine
import com.tencent.kmm.network.service.VBTransportNetworkEngine
import com.tencent.kmm.network.service.resolveNetworkEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.remove
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IosCurlNetworkEngineTest {
    private val trustStorePath = "/tmp/networkkmm-ios-unit-ca.pem"

    @BeforeTest
    fun configureCurlRuntime() {
        val bytes = "unit-test-ca".encodeToByteArray()
        val file = checkNotNull(fopen(trustStorePath, "wb"))
        try {
            bytes.usePinned { pinned ->
                check(fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file).toInt() == bytes.size)
            }
        } finally {
            fclose(file)
        }
        VBTransportCurl.configure(
            NetworkCurlRuntimeConfiguration(
                trustStore = NetworkCurlTrustStore(trustStorePath, networkCurlSha256Hex(bytes)),
                proxy = NetworkCurlProxyConfiguration.direct()
            )
        )
    }

    @AfterTest
    fun resetBridge() {
        IosCurlEngineProvider.testBridge = null
        VBTransportCurl.clear()
        remove(trustStorePath)
    }

    @Test
    fun providerRequiresNativeArtifactAndVerifiedRuntimeConfiguration() {
        IosCurlEngineProvider.testBridge = FakeBridge(isAvailable = false)
        assertNull(resolvePlatformNetworkEngine(NetworkTransportEngine.CURL))

        IosCurlEngineProvider.testBridge = FakeBridge(isAvailable = true)
        VBTransportCurl.clear()
        val curl = assertNotNull(resolvePlatformNetworkEngine(NetworkTransportEngine.CURL))
        assertFalse(VBTransportCurl.configured)
        assertFalse(curl.availability(NetworkRequest()).available)

        configureCurlRuntime()
        assertNotNull(resolvePlatformNetworkEngine(NetworkTransportEngine.CURL))
        assertTrue(VBTransportCurl.configured)
    }

    @Test
    fun platformDefaultRemainsKtorDarwin() {
        assertEquals(NetworkTransportEngine.KTOR, platformDefaultNetworkTransportEngine)
        assertSame(
            VBTransportNetworkEngine,
            resolvePlatformNetworkEngine(NetworkTransportEngine.KTOR)
        )
    }

    @Test
    fun bufferedRequestMapsBodyHeadersTimingAndCaPath() = runBlocking {
        val bridge = FakeBridge().apply {
            executeResponse = CurlNativeResponse(
                code = 0,
                httpCode = 201,
                headers = "HTTP/1.1 201 Created\r\nContent-Length: 2\r\nX-Test: yes\r\n",
                data = "ok".encodeToByteArray(),
                elapse = VBTransportElapseStatistics(totalTimeMs = 12.5)
            )
        }
        val progress = mutableListOf<NetworkTransferProgress>()
        val engine = IosCurlNetworkEngine(bridge)
        val request = NetworkRequest(
            method = VBTransportMethod.POST,
            url = "https://example.test",
            path = "/v1/resource",
            body = NetworkBody.Json("{\"ok\":true}"),
            progress = NetworkProgressCallbacks(downloadProgress = progress::add)
        ).apply {
            addQuery("q", "a b")
            setHeader("X-Request", "value")
        }

        val response = engine.execute(request, NetworkCall(request))

        assertEquals(201, response.statusCode)
        assertEquals("ok", response.body.text())
        assertEquals(listOf("yes"), response.headers["X-Test"])
        assertEquals(12.5, response.timing.totalTimeMs)
        assertNull(response.error)
        assertEquals(listOf(NetworkTransferProgress(2, 2)), progress)
        val nativeRequest = assertNotNull(bridge.lastRequest)
        assertEquals("https://example.test/v1/resource?q=a%20b", nativeRequest.url)
        assertEquals("POST", nativeRequest.method)
        assertEquals("application/json", nativeRequest.headers["Content-Type"])
        assertEquals("value", nativeRequest.headers["X-Request"])
        assertContentEquals("{\"ok\":true}".encodeToByteArray(), nativeRequest.body)
        assertEquals(trustStorePath, nativeRequest.caInfoPath)
        assertEquals("", nativeRequest.proxyUrl)
        assertEquals(7_000L, nativeRequest.bufferedBodyIdleTimeoutMillis)
        assertEquals(com.tencent.kmm.network.export.DEFAULT_CURL_MAX_BUFFERED_RESPONSE_BYTES, nativeRequest.maxBufferedResponseBytes)
    }

    @Test
    fun bufferedBodyStallRetriesGetOnceAndNeverReplaysPost() = runBlocking {
        val firstTiming = VBTransportElapseStatistics(
            curlFinalHeadersObserved = true,
            curlFirstBodyObserved = true,
            curlBodyProgressObserved = true,
            curlLastBodyProgressElapsedMs = 11.0,
            curlBodyBytes = 3,
        )
        val bridge = FakeBridge().apply {
            executeResponses += CurlNativeResponse(
                code = 28,
                errorMsg = "buffered body idle timeout after 7000ms",
                elapse = firstTiming,
            )
            executeResponses += CurlNativeResponse(code = 0, httpCode = 200, data = "ok".encodeToByteArray())
        }
        val get = NetworkRequest(
            method = VBTransportMethod.GET,
            url = "https://example.test",
            policy = NetworkRequestPolicy(timeoutMillis = 20_000),
        )

        val recovered = IosCurlNetworkEngine(bridge).execute(get, NetworkCall(get))

        assertEquals(2, bridge.executeRequests.size)
        assertTrue(bridge.executeRequests[0].requestId != bridge.executeRequests[1].requestId)
        assertEquals("ok", recovered.body.text())
        assertTrue(recovered.timing.freshRetry)
        assertEquals(3L, recovered.timing.curlFirstAttemptBodyBytes)
        assertEquals(11.0, recovered.timing.curlFirstAttemptLastBodyProgressElapsedMs)

        val postBridge = FakeBridge().apply {
            executeResponses += CurlNativeResponse(
                code = 28,
                errorMsg = "buffered body idle timeout after 7000ms"
            )
        }
        val post = NetworkRequest(
            method = VBTransportMethod.POST,
            url = "https://example.test",
            body = NetworkBody.Text("write")
        )
        val failedWrite = IosCurlNetworkEngine(postBridge).execute(post, NetworkCall(post))
        assertEquals(1, postBridge.executeRequests.size)
        assertTrue(failedWrite.timing.curlBodyStallDetected)
        assertFalse(failedWrite.timing.freshRetry)
    }

    @Test
    fun androidSystemProxyModeFailsClosedOnIos() = runBlocking {
        VBTransportCurl.configure(
            NetworkCurlRuntimeConfiguration(
                trustStore = NetworkCurlTrustStore(
                    trustStorePath,
                    networkCurlSha256Hex("unit-test-ca".encodeToByteArray())
                ),
                proxy = NetworkCurlProxyConfiguration.androidSystem()
            )
        )
        val bridge = FakeBridge()
        val request = NetworkRequest(url = "https://example.test")

        val response = IosCurlNetworkEngine(bridge).execute(request, NetworkCall(request))

        assertEquals(NetworkErrorKind.CONNECT, response.error?.kind)
        assertNull(bridge.lastRequest)
        assertFalse(IosCurlNetworkEngine(bridge).capabilities.pacProxy.rolloutEligible)
    }

    @Test
    fun responseTaxonomyPreservesHttpAndCurlFailures() = runBlocking {
        val bridge = FakeBridge()
        val engine = IosCurlNetworkEngine(bridge)
        val request = NetworkRequest(url = "https://example.test")

        bridge.executeResponse = CurlNativeResponse(code = 0, httpCode = 401)
        assertEquals(NetworkErrorKind.AUTH, engine.execute(request, NetworkCall(request)).error?.kind)

        bridge.executeResponse = CurlNativeResponse(code = 0, httpCode = 503)
        assertEquals(NetworkErrorKind.HTTP_STATUS, engine.execute(request, NetworkCall(request)).error?.kind)

        val curlFailures = listOf(
            6 to NetworkErrorKind.DNS,
            7 to NetworkErrorKind.CONNECT,
            28 to NetworkErrorKind.TIMEOUT,
            35 to NetworkErrorKind.TLS,
            42 to NetworkErrorKind.CANCELLED
        )
        curlFailures.forEach { (code, expected) ->
            bridge.executeResponse = CurlNativeResponse(code = code, errorMsg = "native failure")
            assertEquals(expected, engine.execute(request, NetworkCall(request)).error?.kind)
        }
    }

    @Test
    fun downloadStreamDeliversHeadersChunksAndProgress() = runBlocking {
        val bridge = FakeBridge().apply {
            streamStatus = 206
            streamHeaders = "HTTP/1.1 206 Partial Content\r\nContent-Length: 6\r\n"
            streamChunks = listOf("abc".encodeToByteArray(), "def".encodeToByteArray())
            streamResponse = CurlNativeResponse(
                code = 0,
                httpCode = 206,
                headers = streamHeaders,
                elapse = VBTransportElapseStatistics(totalTimeMs = 7.0)
            )
        }
        val progress = mutableListOf<NetworkTransferProgress>()
        val request = NetworkRequest(
            url = "https://example.test/file",
            progress = NetworkProgressCallbacks(downloadProgress = progress::add)
        )
        val starts = mutableListOf<Triple<Int, Long?, Map<String, List<String>>>>()
        val chunks = mutableListOf<ByteArray>()

        val response = IosCurlNetworkEngine(bridge).downloadStream(
            request = request,
            call = NetworkCall(request),
            onResponseStart = { status, length, headers -> starts += Triple(status, length, headers) },
            onChunk = chunks::add
        )

        assertEquals(206, starts.single().first)
        assertEquals(6L, starts.single().second)
        assertContentEquals("abcdef".encodeToByteArray(), chunks.reduce(ByteArray::plus))
        assertEquals(
            listOf(NetworkTransferProgress(3, 6), NetworkTransferProgress(6, 6)),
            progress
        )
        assertNull(response.body.bytes)
        assertEquals(7.0, response.timing.totalTimeMs)
    }

    @Test
    fun uploadStreamPullsBoundedChunksAndReportsProgress() = runBlocking {
        val bridge = FakeBridge().apply {
            uploadReadSize = 2
            uploadResponse = CurlNativeResponse(code = 0, httpCode = 200, data = "done".encodeToByteArray())
        }
        val progress = mutableListOf<NetworkTransferProgress>()
        val request = NetworkRequest(
            method = VBTransportMethod.PUT,
            url = "https://example.test/upload",
            body = NetworkBody.Stream(
                stream = NetworkByteStream.fromChunks(contentLength = 6) { sink ->
                    sink.write("abc".encodeToByteArray())
                    sink.write("def".encodeToByteArray())
                },
                contentType = "application/octet-stream"
            ),
            progress = NetworkProgressCallbacks(uploadProgress = progress::add)
        )

        val response = IosCurlNetworkEngine(bridge)
            .execute(request, NetworkCall(request))

        assertEquals("done", response.body.text())
        assertContentEquals("abcdef".encodeToByteArray(), bridge.uploadedBytes)
        assertTrue(bridge.uploadChunkSizes.all { it in 1..2 })
        assertEquals(6L, bridge.lastRequest?.uploadContentLength)
        assertEquals("application/octet-stream", bridge.lastRequest?.headers?.get("Content-Type"))
        assertEquals(
            listOf(
                NetworkTransferProgress(2, 6),
                NetworkTransferProgress(3, 6),
                NetworkTransferProgress(5, 6),
                NetworkTransferProgress(6, 6)
            ),
            progress
        )
    }

    @Test
    fun throwingUploadBodyCancelStillClosesPullAndCancelsNativeExactlyOnce() = runBlocking {
        val bridge = BlockingUploadBridge()
        var originalBodyCancels = 0
        var replacementBodyCancels = 0
        val callbacks = mutableListOf<com.tencent.kmm.network.export.NetworkResponse>()
        val parent = SupervisorJob()
        val request = NetworkRequest(
            method = VBTransportMethod.PUT,
            url = "https://example.test/upload-cancel",
            body = NetworkBody.Stream(
                NetworkByteStream.fromChunks(cancelBlock = { originalBodyCancels++ }) {}
            )
        )
        val client = NetworkClient(
            config = NetworkClientConfig(
                requestMiddlewares = listOf(
                    object : NetworkRequestMiddleware {
                        override suspend fun prepare(request: NetworkRequest): NetworkRequest = request.apply {
                            body = NetworkBody.Stream(
                                NetworkByteStream.fromChunks(cancelBlock = {
                                    replacementBodyCancels++
                                    error("replacement cancel failed")
                                }) {
                                    bridge.writerStarted.complete(Unit)
                                    awaitCancellation()
                                }
                            )
                        }
                    }
                )
            ),
            engine = IosCurlNetworkEngine(bridge),
            scope = CoroutineScope(Dispatchers.Default + parent)
        )

        val call = client.execute(request, callbacks::add)
        bridge.started.await()
        bridge.writerStarted.await()
        parent.cancel()

        val terminal = withTimeout(2_000) { call.await() }
        withTimeout(2_000) { bridge.pullClosed.await() }
        assertEquals(NetworkErrorKind.CANCELLED, terminal.error?.kind)
        assertEquals(1, callbacks.size)
        assertEquals(1, originalBodyCancels)
        assertEquals(1, replacementBodyCancels)
        assertEquals(1, bridge.cancelledIds.size)
        assertTrue(assertNotNull(bridge.lastRequest).cancellationSignal.isCancelled())
    }

    @Test
    fun streamingGetFailsInsteadOfChangingTheWireMethod() = runBlocking {
        val bridge = FakeBridge()
        val request = NetworkRequest(
            method = VBTransportMethod.GET,
            url = "https://example.test/upload",
            body = NetworkBody.Stream(
                NetworkByteStream.fromChunks { sink -> sink.write("body".encodeToByteArray()) }
            )
        )

        val response = IosCurlNetworkEngine(bridge)
            .execute(request, NetworkCall(request))

        assertEquals(NetworkErrorKind.UNKNOWN, response.error?.kind)
        assertEquals(
            "streaming request body is not supported for GET " +
                "(CURLOPT_UPLOAD would rewrite the verb); use POST/PUT/PATCH/DELETE/OPTIONS",
            response.error?.message
        )
        assertEquals(VBTransportResultCode.CODE_NETWORK_ERROR, response.error?.rawCode)
        assertNull(bridge.lastRequest)
    }

    @Test
    fun cancellingCallCancelsNativeRequestAndMapsTerminalResponse() = runBlocking {
        val bridge = BlockingBridge()
        val request = NetworkRequest(url = "https://example.test/slow")
        val call = NetworkCall(request)
        val result = CompletableDeferred<NetworkErrorKind?>()

        val job = launch {
            result.complete(
                IosCurlNetworkEngine(bridge)
                    .execute(request, call).error?.kind
            )
        }
        val requestId = bridge.started.await()
        call.cancel()

        assertEquals(requestId, bridge.cancelledRequestId.await())
        assertEquals(NetworkErrorKind.CANCELLED, result.await())
        assertTrue(assertNotNull(bridge.lastRequest).cancellationSignal.isCancelled())
        job.join()
    }

    @Test
    fun alreadyCancelledCallNeverStartsNativeRequest() = runBlocking {
        val bridge = FakeBridge()
        val request = NetworkRequest(url = "https://example.test/cancelled")
        val call = NetworkCall(request).apply { cancel() }

        val response = IosCurlNetworkEngine(bridge).execute(request, call)

        assertEquals(NetworkErrorKind.CANCELLED, response.error?.kind)
        assertNull(bridge.lastRequest)
        assertEquals(1, bridge.cancelledIds.size)
    }

    @Test
    fun selectedDelegateReportsItsOwnCapabilities() {
        val curl = IosCurlNetworkEngine(FakeBridge())
        val ktorCapabilities = NetworkEngineCapabilities(responseBodyStreaming = false)
        val ktor = object : com.tencent.kmm.network.service.NetworkEngine {
            override val capabilities = ktorCapabilities

            override suspend fun execute(request: NetworkRequest, call: NetworkCall) = error("unused")
        }
        val resolvedCurl = resolveNetworkEngine(
            selection = NetworkEngineSelection(requestedEngine = NetworkTransportEngine.CURL),
            platformDefault = NetworkTransportEngine.KTOR,
            resolver = { engine -> if (engine == NetworkTransportEngine.KTOR) ktor else curl }
        )
        val resolvedKtor = resolveNetworkEngine(
            selection = NetworkEngineSelection(forcePlatformDefault = true),
            platformDefault = NetworkTransportEngine.KTOR,
            resolver = { engine -> if (engine == NetworkTransportEngine.KTOR) ktor else curl }
        )

        assertSame(curl, resolvedCurl.engine)
        assertTrue(resolvedCurl.diagnostics.capabilities.responseBodyStreaming)
        assertFalse(resolvedKtor.diagnostics.capabilities.responseBodyStreaming)
    }

    @Test
    fun http3OptInRequiresNativeFeatureAndReachesTheRequest() = runBlocking {
        VBTransportCurl.configure(
            NetworkCurlRuntimeConfiguration(
                trustStore = NetworkCurlTrustStore(
                    path = trustStorePath,
                    sha256 = networkCurlSha256Hex("unit-test-ca".encodeToByteArray())
                ),
                proxy = NetworkCurlProxyConfiguration.direct(),
                http3Enabled = true
            )
        )
        val bridge = FakeBridge(supportsHttp3 = true).apply {
            executeResponse = CurlNativeResponse(
                code = 0,
                httpCode = 200,
                elapse = VBTransportElapseStatistics(protocol = "h3")
            )
        }
        val request = NetworkRequest(url = "https://example.test")
        val engine = IosCurlNetworkEngine(bridge)

        val response = engine.execute(request, NetworkCall(request))

        assertTrue(assertNotNull(bridge.lastRequest).http3Enabled)
        assertTrue(engine.capabilities.http3.rolloutEligible)
        assertEquals(NetworkHttpProtocol.HTTP_3, response.protocol)
    }

    @Test
    fun http3OptInFailsClosedWhenArtifactLacksFeature() = runBlocking {
        VBTransportCurl.configure(
            NetworkCurlRuntimeConfiguration(
                trustStore = NetworkCurlTrustStore(
                    path = trustStorePath,
                    sha256 = networkCurlSha256Hex("unit-test-ca".encodeToByteArray())
                ),
                proxy = NetworkCurlProxyConfiguration.direct(),
                http3Enabled = true
            )
        )
        val bridge = FakeBridge(supportsHttp3 = false)
        val request = NetworkRequest(url = "https://example.test")
        val engine = IosCurlNetworkEngine(bridge)

        val response = engine.execute(request, NetworkCall(request))

        assertNull(bridge.lastRequest)
        assertFalse(engine.capabilities.http3.rolloutEligible)
        assertTrue(response.error?.message?.contains("CURL_VERSION_HTTP3") == true)
    }

    private open class FakeBridge(
        override val isAvailable: Boolean = true,
        override val supportsHttp3: Boolean = false
    ) : IosCurlNativeBridge {
        var executeResponse = CurlNativeResponse(code = 0, httpCode = 200)
        val executeResponses = mutableListOf<CurlNativeResponse>()
        val executeRequests = mutableListOf<IosCurlNativeRequest>()
        var streamResponse = CurlNativeResponse(code = 0, httpCode = 200)
        var uploadResponse = CurlNativeResponse(code = 0, httpCode = 200)
        var streamStatus = 200L
        var streamHeaders = "HTTP/1.1 200 OK\r\n"
        var streamChunks: List<ByteArray> = emptyList()
        var uploadReadSize = 4
        var lastRequest: IosCurlNativeRequest? = null
        var uploadedBytes = ByteArray(0)
        val uploadChunkSizes = mutableListOf<Int>()
        val cancelledIds = mutableListOf<Int>()

        override suspend fun execute(request: IosCurlNativeRequest): CurlNativeResponse {
            lastRequest = request
            executeRequests += request
            return if (executeResponses.isEmpty()) executeResponse else executeResponses.removeAt(0)
        }

        override suspend fun downloadStream(
            request: IosCurlNativeRequest,
            onResponseStart: (Long, String) -> Unit,
            onChunk: (ByteArray) -> Unit
        ): CurlNativeResponse {
            lastRequest = request
            onResponseStart(streamStatus, streamHeaders)
            streamChunks.forEach(onChunk)
            return streamResponse
        }

        override suspend fun uploadStream(
            request: IosCurlNativeRequest,
            source: IosCurlUploadSource
        ): CurlNativeResponse {
            lastRequest = request
            val chunks = mutableListOf<ByteArray>()
            while (true) {
                val chunk = source.read(uploadReadSize) ?: error("Upload source aborted")
                if (chunk.isEmpty()) break
                uploadChunkSizes += chunk.size
                chunks += chunk
            }
            uploadedBytes = chunks.fold(ByteArray(0), ByteArray::plus)
            return uploadResponse
        }

        override fun cancel(requestId: Int) {
            cancelledIds += requestId
        }
    }

    private class BlockingBridge : FakeBridge() {
        val started = CompletableDeferred<Int>()
        val cancelledRequestId = CompletableDeferred<Int>()
        private val response = CompletableDeferred<CurlNativeResponse>()

        override suspend fun execute(request: IosCurlNativeRequest): CurlNativeResponse {
            lastRequest = request
            started.complete(request.requestId)
            return response.await()
        }

        override fun cancel(requestId: Int) {
            cancelledRequestId.complete(requestId)
            response.complete(CurlNativeResponse(code = 42, errorMsg = "cancelled by caller"))
        }
    }

    private class BlockingUploadBridge : FakeBridge() {
        val started = CompletableDeferred<Unit>()
        val writerStarted = CompletableDeferred<Unit>()
        val pullClosed = CompletableDeferred<Unit>()

        override suspend fun uploadStream(
            request: IosCurlNativeRequest,
            source: IosCurlUploadSource
        ): CurlNativeResponse {
            lastRequest = request
            started.complete(Unit)
            if (source.read(4) == null) {
                pullClosed.complete(Unit)
            }
            return CurlNativeResponse(code = 42, errorMsg = "cancelled by caller")
        }
    }
}

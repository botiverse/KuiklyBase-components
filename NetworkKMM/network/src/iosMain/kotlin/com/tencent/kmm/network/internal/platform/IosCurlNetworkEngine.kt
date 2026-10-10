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

import com.tencent.kmm.network.curl.CURL_CODE_COULDNT_RESOLVE_HOST
import com.tencent.kmm.network.curl.CurlNativeResponse
import com.tencent.kmm.network.curl.contentLength
import com.tencent.kmm.network.curl.curlDohPreference
import com.tencent.kmm.network.curl.runCurlDohFallback
import com.tencent.kmm.network.curl.runDohFallback
import com.tencent.kmm.network.curl.curlPooledEngineRetirement
import com.tencent.kmm.network.curl.isBufferedBodyIdleTimeout
import com.tencent.kmm.network.curl.isBufferedResponseHeadersTimeout
import com.tencent.kmm.network.curl.isConnectionFailureBeforeResponse
import com.tencent.kmm.network.curl.CURL_AFTER_TRANSPORT_REPLAY_PREFIX
import com.tencent.kmm.network.curl.retainFirstAttemptCurlFacts
import com.tencent.kmm.network.curl.shouldFreshRetryCurlBufferedStall
import com.tencent.kmm.network.curl.parseCurlHeaders
import com.tencent.kmm.network.curl.toNetworkResponse
import com.tencent.kmm.network.export.NetworkByteStreamSink
import com.tencent.kmm.network.export.NetworkBody
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkResponseBody
import com.tencent.kmm.network.export.NetworkTransferProgress
import com.tencent.kmm.network.export.VBTransportMethod
import com.tencent.kmm.network.export.toBytes
import com.tencent.kmm.network.internal.VBPBRequestIdGenerator
import com.tencent.kmm.network.internal.RequestIdOwnerRegistry
import com.tencent.kmm.network.service.NetworkCall
import com.tencent.kmm.network.service.NetworkEngine
import com.tencent.kmm.network.service.NetworkEngineAvailability
import com.tencent.kmm.network.service.NetworkUploadStreamSource
import com.tencent.kmm.network.service.StreamingUploadCancellationOwners
import com.tencent.kmm.network.service.curlNetworkEngineCapabilities
import com.tencent.kmm.network.service.curlRuntimeFailureResponse
import com.tencent.kmm.network.service.hasPotentialStreamingSource
import com.tencent.kmm.network.service.networkUploadStreamSourceOrNull
import com.tencent.kmm.network.service.prepareCurlRuntime
import com.tencent.kmm.network.service.preparedCurlCaInfoPath
import com.tencent.kmm.network.service.preparedCurlDohFallbackProviders
import com.tencent.kmm.network.service.preparedCurlHttp3Enabled
import com.tencent.kmm.network.service.preparedCurlProxyUrl
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal object IosCurlEngineProvider {
    internal var testBridge: IosCurlNativeBridge? = null

    val nativeLinked: Boolean
        get() = (testBridge ?: IosCurlCInteropBridge).isAvailable

    val nativeSupportsHttp3: Boolean
        get() = (testBridge ?: IosCurlCInteropBridge).supportsHttp3

    fun resolve(): NetworkEngine? {
        val bridge = testBridge ?: IosCurlCInteropBridge
        return bridge.takeIf { it.isAvailable }?.let(::IosCurlNetworkEngine)
    }
}

internal class IosCurlNetworkEngine(
    private val bridge: IosCurlNativeBridge
) : NetworkEngine {
    override val capabilities
        get() = curlNetworkEngineCapabilities(bridge.supportsHttp3)

    override fun availability(request: NetworkRequest): NetworkEngineAvailability =
        prepareCurlRuntime(request, nativeHttp3Supported = bridge.supportsHttp3)

    override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse {
        val availability = prepareCurlRuntime(request, nativeHttp3Supported = bridge.supportsHttp3)
        if (!availability.available) return curlRuntimeFailureResponse(request, availability)
        if ((request.method == VBTransportMethod.GET || request.method == VBTransportMethod.HEAD) &&
            request.body.hasPotentialStreamingSource()
        ) {
            return unsupportedStreamingRequestBodyResponse(request)
        }
        val streamingSource = try {
            networkUploadStreamSourceOrNull(request)
        } catch (throwable: Throwable) {
            runCatching { call.cancelBodyOnce(request.body) }
            throw throwable
        }
        streamingSource?.let { source ->
            return executeUpload(request, call, source)
        }
        val body = request.body.toBytes(request.progress.uploadProgress) { stream ->
            call.ownBodyIfActive(NetworkBody.Stream(stream))
        }
        body.error?.let { error ->
            return NetworkResponse(
                request = request,
                statusCode = null,
                headers = emptyMap(),
                body = NetworkResponseBody(),
                error = error
            )
        }
        val startedAt = TimeSource.Monotonic.markNow()
        val dohProviders = preparedCurlDohFallbackProviders(request)
        val preferredDohProvider = curlDohPreference.preferredProvider(dohProviders)
        val first = executeBufferedAttempt(
            request = request,
            call = call,
            body = body.bytes,
            contentType = body.contentType,
            timeoutMillis = request.policy.timeoutMillis,
            dohFallbackProvider = preferredDohProvider,
        )
        runCurlDohFallback(
            first = first,
            firstProvider = preferredDohProvider,
            configuredProviders = dohProviders,
            isCancelled = { call.isCancelled },
            remainingTimeoutMillis = { remainingCurlTimeoutMillis(request.policy.timeoutMillis, startedAt) },
        ) { provider, timeoutMillis ->
            executeBufferedAttempt(
                request = request,
                call = call,
                body = body.bytes,
                contentType = body.contentType,
                timeoutMillis = timeoutMillis,
                dohFallbackProvider = provider,
            )
        }?.let { return it.toNetworkResponse(request) }
        val stalled = first.isBufferedBodyIdleTimeout()
        // One retry per request end to end: none here once libcurl already replayed it.
        if (first.errorMsg.startsWith(CURL_AFTER_TRANSPORT_REPLAY_PREFIX) ||
            (!stalled && !first.isConnectionFailureBeforeResponse())
        ) {
            return first.toNetworkResponse(request)
        }
        if (stalled) first.elapse.curlBodyStallDetected = true
        val remainingTimeout = remainingCurlTimeoutMillis(request.policy.timeoutMillis, startedAt)
        if (!shouldFreshRetryCurlBufferedStall(
                method = request.method,
                bodyRepeatable = request.body.repeatable,
                policy = request.policy.curlBufferedResponse,
                cancelled = call.isCancelled,
                remainingTimeoutMillis = remainingTimeout,
            )) {
            return first.toNetworkResponse(request)
        }

        // raft.46 (Hands 79db9fe6): see AndroidCurlNetworkEngine: replay on a new connection and, for
        // a response-headers stall, retire the pooled engine whose HTTP/2 connection died.
        if (first.isBufferedResponseHeadersTimeout()) {
            curlPooledEngineRetirement.retireIfDue(preparedCurlHttp3Enabled(request)) {
                bridge.retirePooledEngine(it)
            }
        }
        val retried = executeBufferedAttempt(
            request = request,
            call = call,
            body = body.bytes,
            contentType = body.contentType,
            timeoutMillis = remainingTimeout ?: 0L,
            freshConnection = true,
        )
        if (stalled) retried.elapse.curlBodyStallDetected = true
        retried.elapse.retainFirstAttemptCurlFacts(first.elapse)
        retried.elapse.freshRetry = true
        retried.elapse.freshRetryResult = if (retried.code == 0) "success" else "failure"
        return retried.toNetworkResponse(request)
    }

    override suspend fun downloadStream(
        request: NetworkRequest,
        call: NetworkCall,
        onResponseStart: (statusCode: Int, contentLength: Long?, headers: Map<String, List<String>>) -> Unit,
        onChunk: (ByteArray) -> Unit
    ): NetworkResponse {
        val availability = prepareCurlRuntime(request, nativeHttp3Supported = bridge.supportsHttp3)
        if (!availability.available) return curlRuntimeFailureResponse(request, availability)
        val dohProviders = preparedCurlDohFallbackProviders(request)
        val preferredDohProvider = curlDohPreference.preferredProvider(dohProviders)
        val owner = Any()
        val requestId = iosCurlRequestOwners.reserve(owner)
        val nativeRequest = try {
            request.toNativeRequest(requestId = requestId, dohFallbackProvider = preferredDohProvider)
        } catch (throwable: Throwable) {
            iosCurlRequestOwners.release(requestId, owner)
            throw throwable
        }
        var transferred = 0L
        var responseStarted = false
        var responseLength: Long? = null
        call.addCancelHandler {
            nativeRequest.cancel()
            iosCurlRequestOwners.cancelIfOwner(requestId, owner) { bridge.cancel(requestId) }
        }
        if (call.isCancelled) {
            iosCurlRequestOwners.release(requestId, owner)
            return cancelledResponse(request)
        }
        suspend fun downloadAttempt(attemptRequest: IosCurlNativeRequest): CurlNativeResponse =
            bridge.downloadStream(
                request = attemptRequest,
                onResponseStart = { httpCode, headerText ->
                    responseStarted = true
                    val headers = parseCurlHeaders(headerText)
                    responseLength = contentLength(headers)
                    onResponseStart(httpCode.toInt(), responseLength, headers)
                },
                onChunk = { chunk ->
                    transferred += chunk.size
                    call.runWhileActive {
                        request.progress.downloadProgress?.invoke(
                            NetworkTransferProgress(transferred, responseLength)
                        )
                    }
                    onChunk(chunk)
                }
            )
        return try {
            val first = downloadAttempt(nativeRequest)
            // Raft task #153: an unresolved host delivered nothing, so the stream can start over
            // through DoH. Never once a response started or a byte reached the caller.
            (runDohFallback(
                first = first,
                firstProvider = preferredDohProvider,
                configuredProviders = dohProviders,
                isUnresolved = { it.code == CURL_CODE_COULDNT_RESOLVE_HOST && !responseStarted && transferred == 0L },
                timing = { it.elapse },
                isCancelled = { call.isCancelled },
                remainingTimeoutMillis = { null },
            ) { provider, _ ->
                downloadAttempt(nativeRequest.copy(dohFallbackProvider = provider))
            } ?: first).toNetworkResponse(request)
        } finally {
            iosCurlRequestOwners.release(requestId, owner)
        }
    }

    private suspend fun executeUpload(
        request: NetworkRequest,
        call: NetworkCall,
        source: NetworkUploadStreamSource
    ): NetworkResponse = coroutineScope {
        val owner = Any()
        val requestId = try {
            iosCurlRequestOwners.reserve(owner)
        } catch (throwable: Throwable) {
            runCatching { source.stream.cancel() }
            throw throwable
        }
        val pullBridge = IosCurlUploadPullBridge()
        val dohProviders = preparedCurlDohFallbackProviders(request)
        val preferredDohProvider = curlDohPreference.preferredProvider(dohProviders)
        val nativeRequest = try {
            request.toNativeRequest(
                requestId = requestId,
                contentType = source.contentType,
                uploadContentLength = source.contentLength,
                dohFallbackProvider = preferredDohProvider,
            )
        } catch (throwable: Throwable) {
            iosCurlRequestOwners.release(requestId, owner)
            runCatching { source.stream.cancel() }
            throw throwable
        }
        val writer = launch(IosCurlExecutionDispatchers.uploadWriter) {
            try {
                source.stream.readChunks(object : NetworkByteStreamSink {
                    override suspend fun write(bytes: ByteArray) {
                        if (bytes.isNotEmpty()) pullBridge.write(bytes)
                    }
                })
                pullBridge.closeSuccess()
            } catch (throwable: Throwable) {
                pullBridge.closeFailure(throwable)
            }
        }
        val cancellationOwners = StreamingUploadCancellationOwners(
            cancelOriginalRequestBody = call::cancelRequestBodyOnce,
            cancelPreparedRequestBody = { call.cancelBodyOnce(request.body) },
            cancelDerivedSource = source.stream.takeIf { source.cancelSeparatelyFromRequestBody }
                ?.let { stream -> stream::cancel },
            cancelAttemptSource = source.cancelAttemptSource,
            cancelNativeRequest = nativeRequest::cancel,
            closePullBridge = {
                pullBridge.closeFailure(CancellationException("Upload cancelled"))
            },
            cancelTransport = {
                iosCurlRequestOwners.cancelIfOwner(requestId, owner) { bridge.cancel(requestId) }
            }
        )
        call.addCancelHandler(cancellationOwners::cancelAll)
        if (call.isCancelled) {
            writer.cancel()
            iosCurlRequestOwners.release(requestId, owner)
            return@coroutineScope cancelledResponse(request)
        }
        try {
            var sent = 0L
            val uploadSource = IosCurlUploadSource { maxLength ->
                pullBridge.read(maxLength)?.also { bytes ->
                    if (bytes.isNotEmpty()) {
                        sent += bytes.size
                        call.runWhileActive {
                            request.progress.uploadProgress?.invoke(
                                NetworkTransferProgress(sent, source.contentLength)
                            )
                        }
                    }
                }
            }
            val first = bridge.uploadStream(nativeRequest, uploadSource)
            // Raft task #153: curl resolves the host before it pulls any body bytes, so an
            // unresolved host left the pull bridge untouched and the same source can be offered
            // again through DoH. Never once a byte was sent.
            val nativeResponse = runDohFallback(
                first = first,
                firstProvider = preferredDohProvider,
                configuredProviders = dohProviders,
                isUnresolved = { it.code == CURL_CODE_COULDNT_RESOLVE_HOST && sent == 0L },
                timing = { it.elapse },
                isCancelled = { call.isCancelled },
                remainingTimeoutMillis = { null },
            ) { provider, _ ->
                bridge.uploadStream(nativeRequest.copy(dohFallbackProvider = provider), uploadSource)
            } ?: first
            if (nativeResponse.isBufferedBodyIdleTimeout()) {
                nativeResponse.elapse.curlBodyStallDetected = true
            }
            val response = nativeResponse.toNetworkResponse(request)
            if (response.error != null) cancellationOwners.releaseAttemptSourceOnFailure()
            response
        } catch (throwable: Throwable) {
            cancellationOwners.releaseAttemptSourceOnFailure()
            throw throwable
        } finally {
            cancellationOwners.disarmNativeTransportOwners()
            iosCurlRequestOwners.release(requestId, owner)
            writer.cancel()
        }
    }

    private suspend fun executeBufferedAttempt(
        request: NetworkRequest,
        call: NetworkCall,
        body: ByteArray?,
        contentType: String?,
        timeoutMillis: Long,
        dohFallbackProvider: Int = 0,
        freshConnection: Boolean = false,
    ): CurlNativeResponse {
        val owner = Any()
        val requestId = iosCurlRequestOwners.reserve(owner)
        val nativeRequest = try {
            request.toNativeRequest(
                requestId = requestId,
                body = body,
                contentType = contentType,
                timeoutMillis = timeoutMillis,
                dohFallbackProvider = dohFallbackProvider,
            )
        } catch (throwable: Throwable) {
            iosCurlRequestOwners.release(requestId, owner)
            throw throwable
        }
        call.addCancelHandler {
            nativeRequest.cancel()
            iosCurlRequestOwners.cancelIfOwner(requestId, owner) { bridge.cancel(requestId) }
        }
        if (call.isCancelled) {
            iosCurlRequestOwners.release(requestId, owner)
            return CurlNativeResponse(code = 42, errorMsg = "cancelled before iOS curl native start")
        }
        return try {
            if (freshConnection) bridge.executeFresh(nativeRequest) else bridge.execute(nativeRequest)
        } finally {
            iosCurlRequestOwners.release(requestId, owner)
        }
    }

    private fun NetworkRequest.toNativeRequest(
        requestId: Int,
        body: ByteArray? = null,
        contentType: String? = null,
        uploadContentLength: Long? = null,
        timeoutMillis: Long = policy.timeoutMillis,
        dohFallbackProvider: Int = 0,
    ): IosCurlNativeRequest {
        val nativeHeaders = headers.toMutableMap()
        contentType?.let { type ->
            if (nativeHeaders.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                nativeHeaders["Content-Type"] = type
            }
        }
        return IosCurlNativeRequest(
            requestId = requestId,
            url = resolvedUrl(),
            method = method.name,
            headers = nativeHeaders,
            timeoutMillis = timeoutMillis,
            streamConnectTimeoutMillis = policy.streamTimeouts.connectTimeoutMillis,
            streamResponseHeadersTimeoutMillis = policy.streamTimeouts.responseHeadersTimeoutMillis,
            streamIdleTimeoutMillis = policy.streamTimeouts.interChunkIdleTimeoutMillis,
            streamWholeTimeoutMillis = policy.streamTimeouts.wholeTransferTimeoutMillis,
            bufferedBodyIdleTimeoutMillis = policy.curlBufferedResponse.bodyIdleTimeoutMillis,
            maxBufferedResponseBytes = policy.curlBufferedResponse.maxDecodedBytes,
            body = body,
            uploadContentLength = uploadContentLength,
            caInfoPath = checkNotNull(preparedCurlCaInfoPath(this)) {
                "Curl CA path missing after runtime preparation"
            },
            proxyUrl = checkNotNull(preparedCurlProxyUrl(this)) {
                "Curl proxy decision missing after runtime preparation"
            },
            http3Enabled = preparedCurlHttp3Enabled(this),
            dohFallbackProvider = dohFallbackProvider
        )
    }

    private fun cancelledResponse(request: NetworkRequest): NetworkResponse =
        CurlNativeResponse(
            code = 42,
            errorMsg = "cancelled before iOS curl native start"
        ).toNetworkResponse(request)

}

private fun remainingCurlTimeoutMillis(totalTimeoutMillis: Long, startedAt: TimeMark): Long? {
    if (totalTimeoutMillis <= 0) return null
    return (totalTimeoutMillis - startedAt.elapsedNow().inWholeMilliseconds).coerceAtLeast(0)
}

private val iosCurlRequestOwners = RequestIdOwnerRegistry()

internal class IosCurlUploadPullBridge {
    private val channel = Channel<ByteArray>(capacity = 4)
    private var leftover = ByteArray(0)
    private var leftoverOffset = 0

    suspend fun write(bytes: ByteArray) {
        channel.send(bytes.copyOf())
    }

    fun closeSuccess() {
        channel.close()
    }

    fun closeFailure(throwable: Throwable) {
        channel.close(throwable)
    }

    fun read(maxLength: Int): ByteArray? {
        if (maxLength <= 0) return ByteArray(0)
        if (leftoverOffset >= leftover.size) {
            val result = runBlocking { channel.receiveCatching() }
            val next = result.getOrNull()
            if (next == null) {
                return if (result.exceptionOrNull() == null) ByteArray(0) else null
            }
            leftover = next
            leftoverOffset = 0
        }
        val count = minOf(maxLength, leftover.size - leftoverOffset)
        return leftover.copyOfRange(leftoverOffset, leftoverOffset + count).also {
            leftoverOffset += count
        }
    }
}

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

import com.tencent.kmm.network.curl.contentLength
import com.tencent.kmm.network.curl.CurlNativeResponse
import com.tencent.kmm.network.curl.isBufferedBodyIdleTimeout
import com.tencent.kmm.network.curl.isCurlProxyHttp3Incompatibility
import com.tencent.kmm.network.curl.retainFirstAttemptCurlFacts
import com.tencent.kmm.network.curl.shouldFreshRetryCurlBufferedStall
import com.tencent.kmm.network.curl.shouldFreshRetryCurlProxyHttp3Failure
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
import com.tencent.kmm.network.service.forcePreparedCurlHttp2
import com.tencent.kmm.network.service.hasPotentialStreamingSource
import com.tencent.kmm.network.service.networkUploadStreamSourceOrNull
import com.tencent.kmm.network.service.latchPreparedCurlProxyHttp3Fallback
import com.tencent.kmm.network.service.prepareCurlRuntime
import com.tencent.kmm.network.service.preparedCurlCaInfoPath
import com.tencent.kmm.network.service.preparedCurlHttp3Enabled
import com.tencent.kmm.network.service.preparedCurlProxyUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal object AndroidCurlEngineProvider {
    @Volatile
    internal var testBridge: AndroidCurlNativeBridge? = null

    val nativeLinked: Boolean
        get() = testBridge?.isAvailable ?: AndroidCurlJniBridge.isAvailable

    val nativeSupportsHttp3: Boolean
        get() = testBridge?.supportsHttp3 ?: AndroidCurlJniBridge.supportsHttp3

    fun resolve(): NetworkEngine? {
        val bridge = testBridge ?: AndroidCurlJniBridge
        return bridge.takeIf { it.isAvailable }?.let(::AndroidCurlNetworkEngine)
    }
}

internal class AndroidCurlNetworkEngine(
    private val bridge: AndroidCurlNativeBridge
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
        val first = executeBufferedAttempt(
            request = request,
            call = call,
            body = body.bytes,
            contentType = body.contentType,
            timeoutMillis = request.policy.timeoutMillis,
        )
        if (preparedCurlHttp3Enabled(request) &&
            !preparedCurlProxyUrl(request).isNullOrBlank() &&
            isCurlProxyHttp3Incompatibility(first.code, first.errorMsg)
        ) {
            val remainingTimeout = remainingCurlTimeoutMillis(request.policy.timeoutMillis, startedAt)
            val environmentIsCurrent = latchPreparedCurlProxyHttp3Fallback(request)
            if (!environmentIsCurrent || !shouldFreshRetryCurlProxyHttp3Failure(
                    method = request.method,
                    cancelled = call.isCancelled,
                    remainingTimeoutMillis = remainingTimeout,
                )) {
                return first.toNetworkResponse(request)
            }
            forcePreparedCurlHttp2(request)
            val retried = executeBufferedAttempt(
                request = request,
                call = call,
                body = body.bytes,
                contentType = body.contentType,
                timeoutMillis = remainingTimeout ?: 0L,
                freshConnection = true,
            )
            retried.elapse.retainFirstAttemptCurlFacts(first.elapse)
            retried.elapse.freshRetry = true
            retried.elapse.freshRetryResult =
                if (retried.code == 0) "proxy_h3_to_h2_success" else "proxy_h3_to_h2_failure"
            return retried.toNetworkResponse(request)
        }
        if (!first.isBufferedBodyIdleTimeout()) {
            return first.toNetworkResponse(request)
        }
        first.elapse.curlBodyStallDetected = true
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

        // Each attempt reserves a new request id; the JNI bridge creates a new
        // easy/client handle. CONNECT sharing is disabled, so the retry cannot
        // re-enter the failed attempt's connection cache.
        val retried = executeBufferedAttempt(
            request = request,
            call = call,
            body = body.bytes,
            contentType = body.contentType,
            timeoutMillis = remainingTimeout ?: 0L,
        )
        retried.elapse.curlBodyStallDetected = true
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
        val owner = Any()
        val requestId = androidCurlRequestOwners.reserve(owner)
        val nativeRequest = try {
            request.toNativeRequest(requestId = requestId)
        } catch (throwable: Throwable) {
            androidCurlRequestOwners.release(requestId, owner)
            throw throwable
        }
        var transferred = 0L
        var responseLength: Long? = null
        call.addCancelHandler {
            nativeRequest.cancel()
            androidCurlRequestOwners.cancelIfOwner(requestId, owner) { bridge.cancel(requestId) }
        }
        if (call.isCancelled) {
            androidCurlRequestOwners.release(requestId, owner)
            return cancelledResponse(request)
        }
        return try {
            bridge.downloadStream(
                request = nativeRequest,
                onResponseStart = { httpCode, headerText ->
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
            ).toNetworkResponse(request)
        } finally {
            androidCurlRequestOwners.release(requestId, owner)
        }
    }

    private suspend fun executeUpload(
        request: NetworkRequest,
        call: NetworkCall,
        source: NetworkUploadStreamSource
    ): NetworkResponse = coroutineScope {
        val owner = Any()
        val requestId = try {
            androidCurlRequestOwners.reserve(owner)
        } catch (throwable: Throwable) {
            runCatching { source.stream.cancel() }
            throw throwable
        }
        val pullBridge = AndroidCurlUploadPullBridge()
        val nativeRequest = try {
            request.toNativeRequest(
                requestId = requestId,
                contentType = source.contentType,
                uploadContentLength = source.contentLength
            )
        } catch (throwable: Throwable) {
            androidCurlRequestOwners.release(requestId, owner)
            runCatching { source.stream.cancel() }
            throw throwable
        }
        val writer = launch(Dispatchers.IO) {
            try {
                source.stream.readChunks(object : NetworkByteStreamSink {
                    override suspend fun write(bytes: ByteArray) {
                        if (bytes.isEmpty()) return
                        pullBridge.write(bytes)
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
                androidCurlRequestOwners.cancelIfOwner(requestId, owner) { bridge.cancel(requestId) }
            }
        )
        call.addCancelHandler(cancellationOwners::cancelAll)
        if (call.isCancelled) {
            writer.cancel()
            androidCurlRequestOwners.release(requestId, owner)
            return@coroutineScope cancelledResponse(request)
        }
        try {
            var sent = 0L
            val uploadSource = AndroidCurlUploadSource { maxLength ->
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
            val nativeResponse = bridge.uploadStream(nativeRequest, uploadSource)
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
            androidCurlRequestOwners.release(requestId, owner)
            writer.cancel()
        }
    }

    private suspend fun executeBufferedAttempt(
        request: NetworkRequest,
        call: NetworkCall,
        body: ByteArray?,
        contentType: String?,
        timeoutMillis: Long,
        freshConnection: Boolean = false,
    ): CurlNativeResponse {
        val owner = Any()
        val requestId = androidCurlRequestOwners.reserve(owner)
        val nativeRequest = try {
            request.toNativeRequest(
                requestId = requestId,
                body = body,
                contentType = contentType,
                timeoutMillis = timeoutMillis,
            )
        } catch (throwable: Throwable) {
            androidCurlRequestOwners.release(requestId, owner)
            throw throwable
        }
        call.addCancelHandler {
            nativeRequest.cancel()
            androidCurlRequestOwners.cancelIfOwner(requestId, owner) { bridge.cancel(requestId) }
        }
        if (call.isCancelled) {
            androidCurlRequestOwners.release(requestId, owner)
            return CurlNativeResponse(code = 42, errorMsg = "cancelled before Android curl native start")
        }
        return try {
            if (freshConnection) bridge.executeFresh(nativeRequest) else bridge.execute(nativeRequest)
        } finally {
            androidCurlRequestOwners.release(requestId, owner)
        }
    }

    private fun NetworkRequest.toNativeRequest(
        requestId: Int,
        body: ByteArray? = null,
        contentType: String? = null,
        uploadContentLength: Long? = null,
        timeoutMillis: Long = policy.timeoutMillis,
    ): AndroidCurlNativeRequest {
        val nativeHeaders = headers.toMutableMap()
        contentType?.let { type ->
            if (nativeHeaders.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                nativeHeaders["Content-Type"] = type
            }
        }
        return AndroidCurlNativeRequest(
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
            http3Enabled = preparedCurlHttp3Enabled(this)
        )
    }

    private fun cancelledResponse(request: NetworkRequest): NetworkResponse =
        com.tencent.kmm.network.curl.CurlNativeResponse(
            code = 42,
            errorMsg = "cancelled before Android curl native start"
        ).toNetworkResponse(request)

}

private fun remainingCurlTimeoutMillis(totalTimeoutMillis: Long, startedAt: TimeMark): Long? {
    if (totalTimeoutMillis <= 0) return null
    return (totalTimeoutMillis - startedAt.elapsedNow().inWholeMilliseconds).coerceAtLeast(0)
}

private val androidCurlRequestOwners = RequestIdOwnerRegistry()

internal class AndroidCurlUploadPullBridge {
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

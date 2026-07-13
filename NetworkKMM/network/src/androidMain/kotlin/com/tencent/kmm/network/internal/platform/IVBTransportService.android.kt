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

import com.tencent.kmm.network.export.VBTransportBaseRequest
import com.tencent.kmm.network.export.VBTransportAndroidEngine
import com.tencent.kmm.network.export.VBTransportBaseResponse
import com.tencent.kmm.network.export.VBTransportBytesRequest
import com.tencent.kmm.network.export.VBTransportBytesResponse
import com.tencent.kmm.network.export.VBTransportGetRequest
import com.tencent.kmm.network.export.VBTransportGetResponse
import com.tencent.kmm.network.export.VBTransportPostRequest
import com.tencent.kmm.network.export.VBTransportPostResponse
import com.tencent.kmm.network.export.VBTransportRequest
import com.tencent.kmm.network.export.VBTransportResponse
import com.tencent.kmm.network.export.VBTransportResultCode
import com.tencent.kmm.network.export.VBTransportStringRequest
import com.tencent.kmm.network.export.VBTransportStringResponse
import com.tencent.kmm.network.internal.VBPBLog
import com.tencent.kmm.network.internal.utils.ByteReadChannelWrapper
import com.tencent.kmm.network.internal.utils.AndroidTransportPhaseTracer
import com.tencent.kmm.network.internal.utils.AndroidTransportClientLease
import com.tencent.kmm.network.internal.utils.AndroidTransportClientProvider
import com.tencent.kmm.network.internal.utils.AndroidReusedH2RetryState
import com.tencent.kmm.network.internal.utils.NETWORK_KMM_TRACE_HEADER
import com.tencent.kmm.network.internal.utils.VBTransportCommonUtils.buildResponseAndCallback
import com.tencent.kmm.network.internal.utils.describeTransportFailure
import com.tencent.kmm.network.internal.utils.transportConnectTimeoutMillis
import com.tencent.kmm.network.internal.utils.VBTransportCommonUtils.wrapBytesCallback
import com.tencent.kmm.network.internal.utils.VBTransportCommonUtils.wrapGetCallback
import com.tencent.kmm.network.internal.utils.VBTransportCommonUtils.wrapPostCallback
import com.tencent.kmm.network.internal.utils.VBTransportCommonUtils.wrapRequestCallback
import com.tencent.kmm.network.internal.utils.VBTransportCommonUtils.wrapStringCallback
import com.tencent.kmm.network.internal.utils.readKnownSize
import com.tencent.kmm.network.internal.utils.readUnknownSize
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.HttpResponse
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import com.tencent.kmm.network.export.NetworkByteStreamSink
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.utils.io.core.isEmpty
import io.ktor.utils.io.core.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val scope = CoroutineScope(Dispatchers.IO)
private val taskMap: MutableMap<Int, Job> = mutableMapOf()

// Upper bound per streamed chunk read off the response channel (fork #8).
private const val STREAM_CHUNK_BYTES = 16L * 1024L

private class AndroidResponseLease(
    val response: HttpResponse,
    private val clientLease: AndroidTransportClientLease,
) : AutoCloseable {
    override fun close() = clientLease.close()
}

object AndroidTransportImpl : IVBTransportService {
    private fun triggerRequest(
        request: VBTransportBaseRequest,
        kmmCallback: (response: VBTransportBaseResponse) -> Unit,
        uploadBody: StreamingUploadBody? = null
    ) {
        AndroidTransportPhaseTracer.scheduled(request.requestId)
        val job = scope.launch {
            try {
                AndroidTransportPhaseTracer.transportCoroutineStarted(request.requestId)
                val startMark = kotlin.time.TimeSource.Monotonic.markNow()
                val responseLease = executeWithReusedH2Recovery(request, startMark, uploadBody)
                val response = responseLease.response
                try {

                    // raft.13 chain bracket 2/3: headers arrived — everything before
                    // this line is connect/TLS/TTFB, everything after is body read.
                    logI(
                        "response received, id:${request.requestId}, status:${response.status.value}, " +
                            "contentLength:${response.contentLength() ?: -1}, " +
                            "elapsedMs:${startMark.elapsedNow().inWholeMilliseconds}",
                        request.logTag
                    )

                    var errMsg = ""
                    var errorCode = 0
                    if (response.status != HttpStatusCode.OK) {
                        errorCode = response.status.value
                        errMsg = response.status.description
                    }

                    val channel = response.bodyAsChannel()
                    val contentLength = response.contentLength()
                    val data = if (contentLength == null) {
                        // 动态扩容方案
                        readUnknownSize(ByteReadChannelWrapper(channel))
                    } else {
                        // 预分配方案
                        readKnownSize(ByteReadChannelWrapper(channel), contentLength)
                    }
                // raft.9: a delivered size differing from the header length is
                // legal (see ByteReadChannelWrapper.readAvailable) but almost
                // always the thing you need to know when a payload looks wrong.
                    if (contentLength != null && data.size.toLong() != contentLength) {
                        logE(
                            "response body length mismatch, id:${request.requestId}, " +
                                "content-length:$contentLength, read:${data.size}, " +
                                "encoding:${response.headers["Content-Encoding"] ?: "-"}",
                            request.logTag
                        )
                    }

                // raft.13 chain bracket 3/3: body fully read — a hang between
                // bracket 2 and here is a body-read stall, not a network wait.
                    logI(
                        "body read, id:${request.requestId}, bytes:${data.size}, " +
                            "totalElapsedMs:${startMark.elapsedNow().inWholeMilliseconds}",
                        request.logTag
                    )
                    AndroidTransportPhaseTracer.responseBodyRead(request.requestId)
                    AndroidTransportPhaseTracer.markFreshRetryResult(request.requestId, success = true)
                    request.transportElapseStatistics = AndroidTransportPhaseTracer.complete(request.requestId)

                    buildResponseAndCallback(
                        taskMap,
                        errorCode,
                        errMsg,
                        response.headers.entries().associate { it.key to it.value },
                        data,
                        request,
                        kmmCallback
                    )
                } finally {
                    responseLease.close()
                }
            } catch (throwable: Throwable) {
                AndroidTransportPhaseTracer.markFreshRetryResult(request.requestId, success = false)
                if (throwable is CancellationException) {
                    taskMap.remove(request.requestId)
                    AndroidTransportPhaseTracer.cancel(request.requestId)
                    throw throwable
                }
                request.transportElapseStatistics = AndroidTransportPhaseTracer.complete(request.requestId)
                callbackFailure(request, throwable, kmmCallback)
            }
        }
        taskMap[request.requestId] = job
    }

    override fun sendBytesRequest(
        kmmBytesRequest: VBTransportBytesRequest,
        kmmBytesResponseCallback: (response: VBTransportBytesResponse) -> Unit
    ) {
        logI("send bytes request, id:${kmmBytesRequest.requestId}, url:${kmmBytesRequest.url}, " +
                "headerKeys:${kmmBytesRequest.header.keys}", kmmBytesRequest.logTag)
        triggerRequest(kmmBytesRequest, wrapBytesCallback(kmmBytesResponseCallback))
    }

    override fun sendStringRequest(
        kmmStringRequest: VBTransportStringRequest,
        kmmStringResponseCallback: (response: VBTransportStringResponse) -> Unit
    ) {
        logI("send string request, id:${kmmStringRequest.requestId}, url:${kmmStringRequest.url}, " +
                "headerKeys:${kmmStringRequest.header.keys}", kmmStringRequest.logTag)
        triggerRequest(kmmStringRequest, wrapStringCallback(kmmStringResponseCallback))
    }

    override fun post(
        kmmPostRequest: VBTransportPostRequest,
        kmmPostResponseCallback: (response: VBTransportPostResponse) -> Unit
    ) {
        logI("send post request, id:${kmmPostRequest.requestId}, url:${kmmPostRequest.url}, " +
                "headerKeys:${kmmPostRequest.header.keys}", kmmPostRequest.logTag)

        if (!kmmPostRequest.isDataInitialize()) {
            callbackFailure(
                kmmPostRequest,
                IllegalArgumentException("Data is not initialized"),
                wrapPostCallback(kmmPostResponseCallback)
            )
            return
        }

        triggerRequest(kmmPostRequest, wrapPostCallback(kmmPostResponseCallback))
    }

    override fun get(
        kmmGetRequest: VBTransportGetRequest,
        kmmGetResponseCallback: (response: VBTransportGetResponse) -> Unit
    ) {
        logI("send get request, id:${kmmGetRequest.requestId}, url:${kmmGetRequest.url}, " +
                "headerKeys:${kmmGetRequest.header.keys}", kmmGetRequest.logTag)
        triggerRequest(kmmGetRequest, wrapGetCallback(kmmGetResponseCallback))
    }

    override fun request(
        kmmRequest: VBTransportRequest,
        kmmResponseCallback: (response: VBTransportResponse) -> Unit
    ) {
        logI("send ${kmmRequest.method} request, id:${kmmRequest.requestId}, url:${kmmRequest.url}, " +
                "headerKeys:${kmmRequest.header.keys}", kmmRequest.logTag)
        triggerRequest(kmmRequest, wrapRequestCallback(kmmResponseCallback))
    }

    // fork #8: stream the response body in chunks straight off ktor's
    // ByteReadChannel instead of readKnownSize/readUnknownSize buffering the
    // whole payload. onComplete carries status/headers/error only.
    override fun requestStream(
        kmmRequest: VBTransportRequest,
        onResponseStart: (statusCode: Int, headers: Map<String, List<String>>) -> Unit,
        onChunk: (chunk: ByteArray) -> Unit,
        onComplete: (response: VBTransportResponse) -> Unit
    ) {
        logI("stream ${kmmRequest.method} request, id:${kmmRequest.requestId}, url:${kmmRequest.url}", kmmRequest.logTag)
        val job = scope.launch {
            try {
                val streamStart = kotlin.time.TimeSource.Monotonic.markNow()
                AndroidTransportPhaseTracer.scheduled(kmmRequest.requestId)
                AndroidTransportPhaseTracer.transportCoroutineStarted(kmmRequest.requestId)
                val responseLease = executeWithReusedH2Recovery(kmmRequest, streamStart)
                val response = responseLease.response
                try {

                    var errorCode = 0
                    var errMsg = ""
                    if (response.status != HttpStatusCode.OK) {
                        errorCode = response.status.value
                        errMsg = response.status.description
                    }

                // raft.13 stream bracket: headers arrived.
                    logI(
                        "stream response received, id:${kmmRequest.requestId}, status:${response.status.value}, " +
                            "contentLength:${response.contentLength() ?: -1}, elapsedMs:${streamStart.elapsedNow().inWholeMilliseconds}",
                        kmmRequest.logTag
                    )
                    val responseHeaders = response.headers.entries().associate { it.key to it.value }
                    onResponseStart(errorCode, responseHeaders)

                    val channel = response.bodyAsChannel()
                    var streamedBytes = 0L
                    while (!channel.isClosedForRead) {
                        val packet = channel.readRemaining(STREAM_CHUNK_BYTES)
                        while (!packet.isEmpty) {
                            val bytes = packet.readBytes()
                            if (bytes.isNotEmpty()) {
                                streamedBytes += bytes.size
                                onChunk(bytes)
                            }
                        }
                    }
                // raft.13 stream bracket: body fully streamed.
                    logI(
                        "stream complete, id:${kmmRequest.requestId}, bytes:$streamedBytes, " +
                            "totalElapsedMs:${streamStart.elapsedNow().inWholeMilliseconds}",
                        kmmRequest.logTag
                    )
                    AndroidTransportPhaseTracer.responseBodyRead(kmmRequest.requestId)
                    AndroidTransportPhaseTracer.markFreshRetryResult(kmmRequest.requestId, success = true)
                    kmmRequest.transportElapseStatistics = AndroidTransportPhaseTracer.complete(kmmRequest.requestId)

                    taskMap.remove(kmmRequest.requestId)
                    onComplete(
                        VBTransportResponse().apply {
                            this.errorCode = errorCode
                            this.errorMessage = errMsg
                            this.header = response.headers.entries().associate { it.key to it.value }
                            this.data = null
                            this.request = kmmRequest
                            this.elapseStatis = kmmRequest.transportElapseStatistics
                        }
                    )
                } finally {
                    responseLease.close()
                }
            } catch (throwable: Throwable) {
                AndroidTransportPhaseTracer.markFreshRetryResult(kmmRequest.requestId, success = false)
                if (throwable is CancellationException) {
                    taskMap.remove(kmmRequest.requestId)
                    AndroidTransportPhaseTracer.cancel(kmmRequest.requestId)
                    throw throwable
                }
                taskMap.remove(kmmRequest.requestId)
                kmmRequest.transportElapseStatistics =
                    AndroidTransportPhaseTracer.complete(kmmRequest.requestId)
                // raft.9: classified failure reason, same shape as callbackFailure.
                val describedFailure = describeTransportFailure(throwable)
                logE("stream request failed, id:${kmmRequest.requestId}, error:$describedFailure", kmmRequest.logTag)
                onComplete(
                    VBTransportResponse().apply {
                        this.errorCode = VBTransportResultCode.CODE_NETWORK_ERROR
                        this.errorMessage = describedFailure
                        this.data = null
                        this.request = kmmRequest
                        this.elapseStatis = kmmRequest.transportElapseStatistics
                    }
                )
            }
        }
        taskMap[kmmRequest.requestId] = job
    }

    private suspend fun executeWithReusedH2Recovery(
        request: VBTransportBaseRequest,
        overallStart: kotlin.time.TimeMark,
        uploadBody: StreamingUploadBody? = null,
    ): AndroidResponseLease {
        // Sample rollout settings once: a mid-request flag change must not send
        // the recovery attempt back through the retired shared pool.
        val okHttpEnabled = VBTransportAndroidEngine.okHttpEnabled
        val recovery = VBTransportAndroidEngine.reusedHttp2Recovery
        val retryState = AndroidReusedH2RetryState(request.method)
        var avoidShard: Int? = null
        while (true) {
            val lease = AndroidTransportClientProvider.acquire(
                request = request,
                okHttpEnabled = okHttpEnabled,
                recovery = recovery,
                avoidShard = avoidShard,
            )
            val attemptToken = AndroidTransportPhaseTracer.beginAttempt(
                requestId = request.requestId,
                lease = lease,
                watchdogMillis = recovery.responseHeadersWatchdogMillis,
                watchdogEnabled = okHttpEnabled && recovery.enabled,
            )
            try {
                val remainingTimeout = remainingRequestTimeout(request.totalTimeout, overallStart)
                val response = lease.client.request(request.url) {
                    method = HttpMethod(request.method.name)
                    if (remainingTimeout > 0L) {
                        timeout {
                            requestTimeoutMillis = remainingTimeout
                            connectTimeoutMillis = transportConnectTimeoutMillis(remainingTimeout)
                            socketTimeoutMillis = remainingTimeout
                        }
                    }
                    constructRequest(request)
                    header(NETWORK_KMM_TRACE_HEADER, request.requestId.toString())
                    if (uploadBody != null) setBody(uploadBody.toOutgoingContent())
                }
                return AndroidResponseLease(response, lease)
            } catch (throwable: Throwable) {
                val watchdogTriggered =
                    AndroidTransportPhaseTracer.watchdogTriggered(request.requestId, attemptToken)
                val hasBudget = request.totalTimeout <= 0L ||
                    remainingRequestTimeout(request.totalTimeout, overallStart) > 0L
                if (retryState.claimRetry(watchdogTriggered, hasBudget)) {
                    avoidShard = lease.shard
                    AndroidTransportPhaseTracer.markFreshRetry(request.requestId)
                    logI(
                        "stale reused h2 detected, id:${request.requestId}, origin:${lease.origin}, " +
                            "shard:${lease.shard}, generation:${lease.generation}, freshRetry:true",
                        request.logTag,
                    )
                    lease.close()
                    continue
                }
                if (retryState.attempted) {
                    AndroidTransportPhaseTracer.markFreshRetryResult(request.requestId, success = false)
                }
                lease.close()
                throw throwable
            }
        }
    }

    private fun remainingRequestTimeout(totalTimeout: Long, start: kotlin.time.TimeMark): Long {
        if (totalTimeout <= 0L) return 0L
        return (totalTimeout - start.elapsedNow().inWholeMilliseconds).coerceAtLeast(0L)
    }

    private fun HttpRequestBuilder.constructRequest(kmmRequest: VBTransportBaseRequest) {
        val hasContentType = hasExplicitContentType(kmmRequest.header)

        // 设置 header
        kmmRequest.header
            .filter { (k, v) -> k.isNotEmpty() && v.isNotEmpty() }
            .forEach { (k, v) -> header(k, v) }

        // 设置 post body
        kmmRequest.bodyData()?.let {
            setBody(it)
        }

        if (!hasContentType) {
            contentType(ContentType.Application.OctetStream)
        }
    }

    // issue #8: true streaming upload via ktor WriteChannelContent — the
    // interface's buffered default is bypassed on Android.
    override fun requestUploadStream(
        kmmRequest: VBTransportRequest,
        contentLength: Long?,
        writeBody: suspend (NetworkByteStreamSink) -> Unit,
        kmmResponseCallback: (response: VBTransportResponse) -> Unit
    ) {
        logI(
            "send upload-stream request, id:${kmmRequest.requestId}, url:${kmmRequest.url}, " +
                "contentLength:${contentLength ?: -1}, headerKeys:${kmmRequest.header.keys}",
            kmmRequest.logTag
        )
        triggerRequest(
            kmmRequest,
            wrapRequestCallback(kmmResponseCallback),
            uploadBody = StreamingUploadBody(contentLength, writeBody)
        )
    }

    private fun logI(content: String, logTag: String = "") {
        VBPBLog.i(VBPBLog.HMTRANSPORTIMPL, "$logTag $content")
    }

    private fun logE(content: String, logTag: String = "") {
        VBPBLog.e(VBPBLog.HMTRANSPORTIMPL, "$logTag $content")
    }

    override fun cancel(requestId: Int) {
        logI("requestID -> $requestId task cancel by user")
        taskMap[requestId]?.cancel()
    }

    private fun callbackFailure(
        request: VBTransportBaseRequest,
        throwable: Throwable,
        kmmCallback: (response: VBTransportBaseResponse) -> Unit
    ) {
        // raft.9: classify the failure so callers see WHY ([timeout]/[dns]/
        // [tls]/[connection_lost]/…) instead of a bare engine message.
        val errorMessage = describeTransportFailure(throwable)
        logE("request failed, id:${request.requestId}, error:${errorMessage}", request.logTag)
        buildResponseAndCallback(
            taskMap,
            VBTransportResultCode.CODE_NETWORK_ERROR,
            errorMessage,
            emptyMap(),
            byteArrayOf(),
            request,
            kmmCallback
        )
    }
}

internal fun hasExplicitContentType(headers: Map<String, String>): Boolean =
    headers.any { (key, value) ->
        key.equals("Content-Type", ignoreCase = true) && value.isNotBlank()
    }

actual fun getIVBTransportService(): IVBTransportService = AndroidTransportImpl


// issue #8: adapter from the transport's push-sink contract to ktor's
// streaming request body. contentLength known -> real Content-Length header;
// null -> chunked transfer encoding (ktor decides from contentLength).
internal class StreamingUploadBody(
    private val length: Long?,
    private val writeBody: suspend (NetworkByteStreamSink) -> Unit
) {
    fun toOutgoingContent(): OutgoingContent = object : OutgoingContent.WriteChannelContent() {
        override val contentLength: Long? = length
        override suspend fun writeTo(channel: ByteWriteChannel) {
            writeBody(object : NetworkByteStreamSink {
                override suspend fun write(bytes: ByteArray) {
                    if (bytes.isEmpty()) return
                    channel.writeFully(bytes, 0, bytes.size)
                }
            })
            channel.flush()
        }
    }
}

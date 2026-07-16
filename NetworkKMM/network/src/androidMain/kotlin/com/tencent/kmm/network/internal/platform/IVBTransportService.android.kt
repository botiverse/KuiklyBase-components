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
import com.tencent.kmm.network.internal.remainingStreamWholeTimeoutMillis
import com.tencent.kmm.network.internal.streamHeadersUpperBoundMillis
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

private val scope = CoroutineScope(Dispatchers.IO)

// Written by the caller thread, removed by the transport coroutine on
// Dispatchers.IO, and read by cancel() from any thread — must be a concurrent
// map, and the entry must be registered before the coroutine body can run so
// a fast-completing request cannot remove its entry before it exists (which
// would leave a completed Job resident and invisible to cancel()).
private val taskMap: ConcurrentHashMap<Int, Job> = ConcurrentHashMap()
private val preparedTaskRegistry = AndroidPreparedTransportTaskRegistry()

internal fun registerTransportTask(taskMap: ConcurrentHashMap<Int, Job>, requestId: Int, job: Job): Boolean {
    if (taskMap.putIfAbsent(requestId, job) != null) return false
    // SOLE registry cleanup on Android — coroutine bodies and the common
    // callback helper must not remove entries (Android passes
    // removeOnComplete = false): a keyed remove racing an id reuse would
    // evict the newer request. This hook fires on every terminal path
    // (completion, failure, and a cancel() landing between the put above and
    // start() below), and the two-arg remove is a no-op once a newer job has
    // replaced this one under the same id.
    job.invokeOnCompletion { taskMap.remove(requestId, job) }
    job.start()
    return true
}

// Upper bound per streamed chunk read off the response channel (fork #8).
private const val STREAM_CHUNK_BYTES = 16L * 1024L

private class AndroidResponseLease(
    val response: HttpResponse,
    private val clientLease: AndroidTransportClientLease,
) : AutoCloseable {
    override fun close() = clientLease.close()
}

internal sealed interface AndroidRequestTimeoutBudget {
    data object Unlimited : AndroidRequestTimeoutBudget
    data object Expired : AndroidRequestTimeoutBudget
    data class Remaining(val millis: Long) : AndroidRequestTimeoutBudget
}

internal fun androidRequestTimeoutBudget(
    totalTimeoutMillis: Long,
    elapsedMillis: Long,
): AndroidRequestTimeoutBudget {
    if (totalTimeoutMillis <= 0L) return AndroidRequestTimeoutBudget.Unlimited
    val remaining = totalTimeoutMillis - elapsedMillis
    return if (remaining <= 0L) {
        AndroidRequestTimeoutBudget.Expired
    } else {
        AndroidRequestTimeoutBudget.Remaining(remaining)
    }
}

internal fun requireAndroidRequestTimeoutBudget(budget: AndroidRequestTimeoutBudget) {
    if (budget == AndroidRequestTimeoutBudget.Expired) {
        throw SocketTimeoutException("Request total timeout budget exhausted")
    }
}

internal fun androidAttemptTimeoutBudget(
    totalTimeoutMillis: Long,
    streamWholeTimeoutMillis: Long,
    elapsedMillis: Long,
    streamTimeouts: Boolean,
): AndroidRequestTimeoutBudget {
    if (!streamTimeouts) {
        return androidRequestTimeoutBudget(totalTimeoutMillis, elapsedMillis)
    }
    return when (val remaining = remainingStreamWholeTimeoutMillis(streamWholeTimeoutMillis, elapsedMillis)) {
        null -> AndroidRequestTimeoutBudget.Unlimited
        0L -> AndroidRequestTimeoutBudget.Expired
        else -> AndroidRequestTimeoutBudget.Remaining(remaining)
    }
}

internal object AndroidTransportTestHooks {
    @Volatile
    var beforeTransportCoroutineStart: (() -> Unit)? = null

    fun reset() {
        beforeTransportCoroutineStart = null
    }
}

object AndroidTransportImpl : IVBTransportService {
    override fun prepareRequest(requestId: Int): Boolean = preparedTaskRegistry.prepare(requestId)

    override fun abortPreparedRequest(requestId: Int) {
        preparedTaskRegistry.abort(requestId)
    }

    private fun triggerRequest(
        request: VBTransportBaseRequest,
        kmmCallback: (response: VBTransportBaseResponse) -> Unit,
        uploadBody: StreamingUploadBody? = null
    ) {
        AndroidTransportPhaseTracer.scheduled(request.requestId)
        val requestStart = request.serviceRequestStartMark
            ?: kotlin.time.TimeSource.Monotonic.markNow()
        val transportJob = AtomicReference<Job?>(null)
        lateinit var hardDeadline: AndroidTransportHardDeadline
        val guardedCallback: (VBTransportBaseResponse) -> Unit = { response ->
            if (hardDeadline.tryDeliverTransportCallback()) {
                kmmCallback(response)
            }
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                AndroidTransportTestHooks.beforeTransportCoroutineStart?.invoke()
                AndroidTransportPhaseTracer.transportCoroutineStarted(request.requestId)
                val responseLease = executeWithReusedH2Recovery(request, requestStart, uploadBody)
                val response = responseLease.response
                try {

                    // raft.13 chain bracket 2/3: headers arrived — everything before
                    // this line is connect/TLS/TTFB, everything after is body read.
                    logI(
                        "response received, id:${request.requestId}, status:${response.status.value}, " +
                            "contentLength:${response.contentLength() ?: -1}, " +
                            "elapsedMs:${requestStart.elapsedNow().inWholeMilliseconds}",
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
                            "totalElapsedMs:${requestStart.elapsedNow().inWholeMilliseconds}",
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
                        guardedCallback,
                        // Registry cleanup happens solely in the completion
                        // hook (registerTransportTask): a keyed remove here
                        // could evict a newer request that reused the id.
                        removeOnComplete = false
                    )
                } finally {
                    responseLease.close()
                }
            } catch (throwable: Throwable) {
                AndroidTransportPhaseTracer.markFreshRetryResult(request.requestId, success = false)
                if (throwable is CancellationException) {
                    if (!hardDeadline.deadlineWon()) {
                        AndroidTransportPhaseTracer.cancel(request.requestId)
                    }
                    throw throwable
                }
                request.transportElapseStatistics = AndroidTransportPhaseTracer.complete(request.requestId)
                callbackFailure(request, throwable, guardedCallback)
            }
        }
        transportJob.set(job)
        hardDeadline =
            AndroidTransportHardDeadline(
                configuredTimeoutMillis = request.totalTimeout,
                elapsedMillis = { requestStart.elapsedNow().inWholeMilliseconds },
                cancelTransport = cancelTransport@{
                    val activeJob = transportJob.get()
                        ?: return@cancelTransport AndroidTransportCancellationResult.Missing
                    if (activeJob.isCompleted) {
                        AndroidTransportCancellationResult.AlreadyComplete
                    } else {
                        activeJob.cancel(CancellationException("Android transport wall-clock deadline"))
                        AndroidTransportCancellationResult.Requested
                    }
                },
                onDeadline = { diagnostics ->
                    AndroidTransportPhaseTracer.markFreshRetryResult(request.requestId, success = false)
                    request.transportElapseStatistics = AndroidTransportPhaseTracer.complete(request.requestId)
                    logE(
                        "request hard deadline, id:${request.requestId}, " +
                            "configuredTimeoutMs:${diagnostics.configuredTimeoutMillis}, " +
                            "deadlineElapsedMs:${diagnostics.deadlineElapsedMillis}, " +
                            "transportCallbackDelayMs:${diagnostics.transportCallbackDelayMillis}, " +
                            "cancellationResult:${diagnostics.cancellationResult.name}",
                        request.logTag
                    )
                    callbackFailure(
                        request,
                        SocketTimeoutException(
                            "Request wall-clock deadline exceeded " +
                                "[configuredTimeoutMs=${diagnostics.configuredTimeoutMillis}, " +
                                "deadlineElapsedMs=${diagnostics.deadlineElapsedMillis}]"
                        ),
                        kmmCallback
                    )
                },
                onLateTransportCallback = { callbackDelayMillis ->
                    logE(
                        "request late callback suppressed, id:${request.requestId}, " +
                            "configuredTimeoutMs:${request.totalTimeout}, " +
                            "deadlineElapsedMs:${hardDeadline.deadlineElapsedMillis()}, " +
                            "transportCallbackDelayMs:$callbackDelayMillis, " +
                            "cancellationResult:${hardDeadline.cancellationResult().name}",
                        request.logTag
                    )
                }
            )
        job.invokeOnCompletion(hardDeadline::transportJobCompleted)
        hardDeadline.start()
        if (!preparedTaskRegistry.register(request.requestId, job)) {
            job.cancel()
            callbackFailure(
                request,
                IllegalStateException("Android transport request id already active"),
                guardedCallback
            )
        }
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
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val streamStart = kotlin.time.TimeSource.Monotonic.markNow()
                AndroidTransportPhaseTracer.scheduled(kmmRequest.requestId)
                AndroidTransportPhaseTracer.transportCoroutineStarted(kmmRequest.requestId)
                suspend fun openResponse() =
                    executeWithReusedH2Recovery(
                        kmmRequest,
                        streamStart,
                        streamTimeouts = true
                    )
                val responseHeadersBudget = streamHeadersUpperBoundMillis(
                    kmmRequest.streamConnectTimeoutMillis,
                    kmmRequest.streamResponseHeadersTimeoutMillis
                )
                val responseLease = if (responseHeadersBudget != null) {
                    withTimeout(responseHeadersBudget) { openResponse() }
                } else {
                    openResponse()
                }
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
                if (throwable is CancellationException && throwable !is TimeoutCancellationException) {
                    AndroidTransportPhaseTracer.cancel(kmmRequest.requestId)
                    throw throwable
                }
                kmmRequest.transportElapseStatistics =
                    AndroidTransportPhaseTracer.complete(kmmRequest.requestId)
                // raft.9: classified failure reason, same shape as callbackFailure.
                val describedFailure = describeTransportFailure(throwable)
                logE("stream request failed, id:${kmmRequest.requestId}, error:$describedFailure", kmmRequest.logTag)
                onComplete(
                    VBTransportResponse().apply {
                        this.errorCode = if (throwable is TimeoutCancellationException ||
                            describedFailure.startsWith("[timeout]")) {
                            VBTransportResultCode.CODE_FORCE_TIMEOUT
                        } else {
                            VBTransportResultCode.CODE_NETWORK_ERROR
                        }
                        this.errorMessage = describedFailure
                        this.data = null
                        this.request = kmmRequest
                        this.elapseStatis = kmmRequest.transportElapseStatistics
                    }
                )
            }
        }
        if (!preparedTaskRegistry.register(kmmRequest.requestId, job)) {
            job.cancel()
            onComplete(
                VBTransportResponse().apply {
                    this.errorCode = VBTransportResultCode.CODE_NETWORK_ERROR
                    this.errorMessage = "Android transport request id already active"
                    this.request = kmmRequest
                }
            )
        }
    }

    private suspend fun executeWithReusedH2Recovery(
        request: VBTransportBaseRequest,
        overallStart: kotlin.time.TimeMark,
        uploadBody: StreamingUploadBody? = null,
        streamTimeouts: Boolean = false,
    ): AndroidResponseLease {
        // Sample rollout settings once: a mid-request flag change must not send
        // the recovery attempt back through the retired shared pool.
        val transportConfiguration = AndroidTransportClientProvider.snapshot()
        val okHttpEnabled = transportConfiguration.okHttpEnabled
        val recovery = transportConfiguration.recovery
        val retryState = AndroidReusedH2RetryState(
            method = request.method,
            hasReplayUnsafeBody = uploadBody != null || request.bodyData() != null,
        )
        var avoidShard: Int? = null
        while (true) {
            val budget = androidAttemptTimeoutBudget(
                totalTimeoutMillis = request.totalTimeout,
                streamWholeTimeoutMillis = request.streamWholeTimeoutMillis,
                elapsedMillis = overallStart.elapsedNow().inWholeMilliseconds,
                streamTimeouts = streamTimeouts,
            )
            requireAndroidRequestTimeoutBudget(budget)
            val lease = AndroidTransportClientProvider.acquire(
                request = request,
                configuration = transportConfiguration,
                avoidShard = avoidShard,
            )
            val attemptToken = AndroidTransportPhaseTracer.beginAttempt(
                requestId = request.requestId,
                lease = lease,
                watchdogMillis = recovery.responseHeadersWatchdogMillis,
                watchdogEnabled = okHttpEnabled && recovery.enabled,
                minimumConcurrentStalledRequests = recovery.minimumConcurrentStalledRequests,
                canFreshRetry = retryState.canFreshRetry,
            )
            try {
                val requestBudget = androidAttemptTimeoutBudget(
                    totalTimeoutMillis = request.totalTimeout,
                    streamWholeTimeoutMillis = request.streamWholeTimeoutMillis,
                    elapsedMillis = overallStart.elapsedNow().inWholeMilliseconds,
                    streamTimeouts = streamTimeouts,
                )
                requireAndroidRequestTimeoutBudget(requestBudget)
                val response = lease.client.request(request.url) {
                    method = HttpMethod(request.method.name)
                    if (streamTimeouts) {
                        val remainingWholeMillis = remainingStreamWholeTimeoutMillis(
                            request.streamWholeTimeoutMillis,
                            overallStart.elapsedNow().inWholeMilliseconds
                        )
                        if (remainingWholeMillis == 0L) {
                            throw SocketTimeoutException("stream whole-transfer timeout exhausted")
                        }
                        timeout {
                            if (remainingWholeMillis != null) {
                                requestTimeoutMillis = remainingWholeMillis
                            }
                            connectTimeoutMillis = request.streamConnectTimeoutMillis
                            socketTimeoutMillis = request.streamIdleTimeoutMillis
                        }
                    } else if (requestBudget is AndroidRequestTimeoutBudget.Remaining) {
                        timeout {
                            requestTimeoutMillis = requestBudget.millis
                            connectTimeoutMillis = transportConnectTimeoutMillis(requestBudget.millis)
                            socketTimeoutMillis = requestBudget.millis
                        }
                    }
                    constructRequest(request)
                    header(NETWORK_KMM_TRACE_HEADER, request.requestId.toString())
                    if (uploadBody != null) setBody(uploadBody.toOutgoingContent())
                }
                return AndroidResponseLease(response, lease)
            } catch (throwable: Throwable) {
                try {
                    // User/scope cancellation must win over a simultaneous
                    // watchdog. Ktor propagates this cancellation to the
                    // engine call, which cancels the OkHttp Call/H2 stream.
                    currentCoroutineContext().ensureActive()
                    val watchdogTriggered =
                        AndroidTransportPhaseTracer.watchdogTriggered(request.requestId, attemptToken)
                    if (watchdogTriggered && !AndroidTransportClientProvider.isCurrent(transportConfiguration)) {
                        throw throwable
                    }
                    val retryBudget = androidAttemptTimeoutBudget(
                        totalTimeoutMillis = request.totalTimeout,
                        streamWholeTimeoutMillis = request.streamWholeTimeoutMillis,
                        elapsedMillis = overallStart.elapsedNow().inWholeMilliseconds,
                        streamTimeouts = streamTimeouts,
                    )
                    if (watchdogTriggered && retryBudget == AndroidRequestTimeoutBudget.Expired) {
                        throw SocketTimeoutException(
                            if (streamTimeouts) "stream whole-transfer timeout exhausted"
                            else "Request total timeout budget exhausted"
                        )
                    }
                    val hasBudget = retryBudget != AndroidRequestTimeoutBudget.Expired
                    if (retryState.claimRetry(watchdogTriggered, hasBudget)) {
                        avoidShard = lease.shard
                        AndroidTransportPhaseTracer.markFreshRetry(request.requestId)
                        logI(
                            "stale reused h2 detected, id:${request.requestId}, origin:${lease.origin}, " +
                                "shard:${lease.shard}, generation:${lease.generation}, freshRetry:true",
                            request.logTag,
                        )
                        continue
                    }
                    if (retryState.attempted) {
                        AndroidTransportPhaseTracer.markFreshRetryResult(request.requestId, success = false)
                    }
                    throw throwable
                } finally {
                    // Every non-success attempt relinquishes its generation
                    // lease exactly once. AndroidTransportClientLease.close()
                    // is atomic/idempotent, so deadline/caller cancellation
                    // cannot retain a pool generation behind a stuck stream.
                    lease.close()
                }
            }
        }
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
        preparedTaskRegistry.cancel(requestId)
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
            kmmCallback,
            // Registry cleanup happens solely in the completion hook.
            removeOnComplete = false
        )
    }
}

internal class AndroidPreparedTransportTaskRegistry {
    private sealed interface Entry {
        data object Reserved : Entry
        data object Cancelled : Entry
        class Running(val job: Job) : Entry
    }

    private val entries = mutableMapOf<Int, Entry>()

    @Synchronized
    fun prepare(requestId: Int): Boolean {
        if (entries.containsKey(requestId)) return false
        entries[requestId] = Entry.Reserved
        return true
    }

    @Synchronized
    fun abort(requestId: Int) {
        val current = entries[requestId]
        if (current === Entry.Reserved || current === Entry.Cancelled) entries.remove(requestId)
    }

    fun register(requestId: Int, job: Job): Boolean {
        val start = synchronized(this) {
            when (entries[requestId]) {
                Entry.Reserved -> {
                    entries[requestId] = Entry.Running(job)
                    true
                }
                Entry.Cancelled -> {
                    entries.remove(requestId)
                    false
                }
                else -> return false
            }
        }
        if (!start) {
            job.cancel()
            return true
        }
        job.invokeOnCompletion {
            synchronized(this) {
                val current = entries[requestId]
                if (current is Entry.Running && current.job === job) entries.remove(requestId)
            }
        }
        job.start()
        return true
    }

    fun cancel(requestId: Int) {
        val job = synchronized(this) {
            val current = entries[requestId]
            if (current is Entry.Running) {
                entries.remove(requestId)
                current.job
            } else if (current === Entry.Reserved) {
                entries[requestId] = Entry.Cancelled
                null
            } else {
                null
            }
        }
        job?.cancel()
    }
}

internal fun hasExplicitContentType(headers: Map<String, String>): Boolean =
    headers.any { (key, value) ->
        key.equals("Content-Type", ignoreCase = true) && value.isNotBlank()
    }

actual fun getIVBTransportService(): IVBTransportService = AndroidTransportImpl

actual val platformOwnsRequestHardDeadline: Boolean = true


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

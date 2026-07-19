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

import com.tencent.kmm.network.curl.retainFirstAttemptCurlFacts
import com.tencent.kmm.network.curl.shouldFreshRetryCurlBufferedStall
import com.tencent.kmm.network.export.NetworkBody
import com.tencent.kmm.network.export.NetworkBodyBytes
import com.tencent.kmm.network.export.streamingUploadStreamOrNull
import com.tencent.kmm.network.export.NetworkByteStream
import com.tencent.kmm.network.export.NetworkByteStreamSink
import com.tencent.kmm.network.export.NetworkDispatcher
import com.tencent.kmm.network.export.NetworkError
import com.tencent.kmm.network.export.NetworkErrorKind
import com.tencent.kmm.network.export.NetworkEngineCapabilities
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkRequestPolicy
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkResponseBody
import com.tencent.kmm.network.export.NetworkTransferProgress
import com.tencent.kmm.network.export.VBTransportBaseResponse
import com.tencent.kmm.network.export.VBTransportMethod
import com.tencent.kmm.network.export.VBTransportRequest
import com.tencent.kmm.network.export.VBTransportResponse
import com.tencent.kmm.network.export.VBTransportResultCode
import com.tencent.kmm.network.export.cancel
import com.tencent.kmm.network.export.toBytes
import com.tencent.kmm.network.export.toNetworkHttpProtocol
import com.tencent.kmm.network.internal.InflightCallbackGate
import com.tencent.kmm.network.internal.platform.unsupportedStreamingRequestBodyResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.coroutines.resume
import kotlin.time.TimeSource
import kotlin.time.TimeMark

interface NetworkRequestMiddleware {
    suspend fun prepare(request: NetworkRequest): NetworkRequest
}

interface NetworkResponseMiddleware {
    suspend fun observe(response: NetworkResponse): NetworkResponse
}

interface NetworkInterceptorChain {
    val request: NetworkRequest
    val call: NetworkCall

    suspend fun proceed(request: NetworkRequest): NetworkResponse
}

interface NetworkInterceptor {
    suspend fun intercept(chain: NetworkInterceptorChain): NetworkResponse
}

class NetworkStaticHeadersMiddleware(
    private val headers: Map<String, String>,
    private val overrideExisting: Boolean = false
) : NetworkRequestMiddleware {
    override suspend fun prepare(request: NetworkRequest): NetworkRequest {
        headers.forEach { (name, value) ->
            if (overrideExisting || !request.headers.containsKey(name)) {
                request.headers[name] = value
            }
        }
        return request
    }
}

interface NetworkTokenProvider {
    suspend fun currentToken(request: NetworkRequest): String?

    suspend fun refreshToken(request: NetworkRequest, response: NetworkResponse): String?
}

class NetworkAuthConfig(
    val tokenProvider: NetworkTokenProvider,
    val headerName: String = "Authorization",
    val refreshStatusCodes: Set<Int> = setOf(401),
    val formatToken: (String) -> String = { "Bearer $it" }
)

class NetworkClientConfig(
    val defaultPolicy: NetworkRequestPolicy = NetworkRequestPolicy(),
    val policySelector: ((NetworkRequest) -> NetworkRequestPolicy)? = null,
    val requestMiddlewares: List<NetworkRequestMiddleware> = emptyList(),
    val responseMiddlewares: List<NetworkResponseMiddleware> = emptyList(),
    val interceptors: List<NetworkInterceptor> = emptyList(),
    val auth: NetworkAuthConfig? = null,
    /**
     * Returns a typed transport selection for each request. Hosts can read
     * remote config or apply stable gray-routing here; raw `ktor|curl` values
     * should be mapped once with [NetworkEngineSelection.fromExternalConfig].
     */
    val engineSelector: ((NetworkRequest) -> NetworkEngineSelection)? = null,
    val engineDiagnostics: NetworkEngineDiagnosticsListener? = null
)

interface NetworkEngine {
    val capabilities: NetworkEngineCapabilities
        get() = NetworkEngineCapabilities()

    /** Per-request rollout gate evaluated before this delegate is selected. */
    fun availability(request: NetworkRequest): NetworkEngineAvailability =
        NetworkEngineAvailability.Available

    suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse

    /**
     * Streams a response when supported, otherwise falls back to one buffered
     * chunk. Engines with native streaming should override this method.
     */
    suspend fun downloadStream(
        request: NetworkRequest,
        call: NetworkCall,
        onResponseStart: (statusCode: Int, contentLength: Long?, headers: Map<String, List<String>>) -> Unit,
        onChunk: (ByteArray) -> Unit
    ): NetworkResponse {
        val response = execute(request, call)
        onResponseStart(
            response.statusCode ?: 0,
            contentLengthFromHeaders(response.headers) ?: response.body.bytes?.size?.toLong(),
            response.headers
        )
        response.body.bytes?.takeIf { it.isNotEmpty() }?.let(onChunk)
        return response.withoutBody()
    }
}

class NetworkCall internal constructor(
    val originalRequest: NetworkRequest
) {
    private val enqueuedAt = TimeSource.Monotonic.markNow()
    private var clientQueueTimeMs = 0.0
    private var requestPreparationTimeMs = 0.0
    private val completion = CompletableDeferred<NetworkResponse>()
    private val stateLock = SynchronizedObject()
    private val callbackDeliveryGate = InflightCallbackGate()
    private val cancelHandlers = mutableListOf<() -> Unit>()
    private val completionHandlers = mutableListOf<(NetworkResponse) -> Unit>()
    private var job: Job? = null
    private var cancelled = false
    private val cancelledBodies = mutableListOf<NetworkBody>()
    private val ownedBodies = mutableListOf<NetworkBody>()
    private val progressGateRecords = mutableListOf<ProgressGateRecord>()
    private var terminalResponse: NetworkResponse? = null
    private var resolvedEngine: ResolvedNetworkEngine? = null
    private var engineSelectionReported = false

    val isCancelled: Boolean
        get() = synchronized(stateLock) { cancelled }

    internal fun attachJob(job: Job) {
        val cancelNow = synchronized(stateLock) {
            if (terminalResponse == null) {
                this.job = job
            }
            cancelled
        }
        if (cancelNow) {
            job.cancel()
        }
    }

    internal fun markClientStarted() {
        clientQueueTimeMs = enqueuedAt.elapsedNow().inWholeNanoseconds / 1_000_000.0
    }

    internal fun markRequestPrepared(startedAt: TimeMark) {
        requestPreparationTimeMs = startedAt.elapsedNow().inWholeNanoseconds / 1_000_000.0
    }

    internal fun applyClientTiming(response: NetworkResponse): NetworkResponse {
        response.timing.clientQueueTimeMs = clientQueueTimeMs
        response.timing.requestPreparationTimeMs = requestPreparationTimeMs
        return response
    }

    internal fun addCancelHandler(handler: () -> Unit) {
        val invokeNow = synchronized(stateLock) {
            if (cancelled) true
            else if (terminalResponse != null) false
            else {
                cancelHandlers.add(handler)
                false
            }
        }
        if (invokeNow) {
            try {
                handler()
            } catch (_: Throwable) {
                // Late registration must not re-open or escape cancellation.
            }
        }
    }

    internal fun addCompletionHandler(handler: (NetworkResponse) -> Unit) {
        val completed = synchronized(stateLock) {
            val terminal = terminalResponse
            if (terminal == null) {
                completionHandlers += handler
            }
            terminal
        }
        if (completed != null) {
            callbackDeliveryGate.enqueueAfterTerminal {
                invokeCompletionHandler(handler, completed)
            }
        }
    }

    internal fun tryComplete(response: NetworkResponse): Boolean {
        val handlers = synchronized(stateLock) {
            if (terminalResponse != null) return false
            terminalResponse = response
            job = null
            cancelHandlers.clear()
            completionHandlers.toList().also { completionHandlers.clear() }
        }
        if (!response.isSuccess) {
            try {
                cancelRequestBodyOnce()
            } catch (_: Throwable) {
                // Final failure cleanup must continue through every owner.
            }
            cancelOwnedBodies()
        }
        deliverTerminal(response, handlers)
        return true
    }

    internal fun getOrResolveEngine(resolve: () -> ResolvedNetworkEngine): ResolvedNetworkEngine {
        return synchronized(stateLock) {
            resolvedEngine ?: resolve().also { resolvedEngine = it }
        }
    }

    internal fun markEngineSelectionReported(): Boolean = synchronized(stateLock) {
        if (engineSelectionReported) false
        else {
            engineSelectionReported = true
            true
        }
    }

    internal fun runWhileActive(action: () -> Unit) {
        callbackDeliveryGate.runIfOpen {
            beforeActiveCallbackActionForTest?.invoke()
            action()
        }
    }

    internal fun gateProgressCallbacks(request: NetworkRequest) {
        synchronized(stateLock) {
            val current = request.progress
            val record = progressGateRecords.firstOrNull { it.request === request }
            if (record?.callbacks === current) return
            val gated = com.tencent.kmm.network.export.NetworkProgressCallbacks(
                uploadProgress = current.uploadProgress?.let { callback ->
                    { progress -> runWhileActive { callback(progress) } }
                },
                downloadProgress = current.downloadProgress?.let { callback ->
                    { progress -> runWhileActive { callback(progress) } }
                },
            )
            request.progress = gated
            if (record == null) progressGateRecords += ProgressGateRecord(request, gated)
            else record.callbacks = gated
        }
    }

    internal var beforeActiveCallbackActionForTest: (() -> Unit)? = null

    /**
     * Returns the winning terminal response. Registered completion callbacks
     * observe the same winner exactly once, but may finish just after this
     * suspension resumes; callers that need both must synchronize explicitly.
     */
    suspend fun await(): NetworkResponse = completion.await()

    fun cancel() {
        val cancellation = synchronized(stateLock) {
            if (terminalResponse != null) {
                null
            } else {
                cancelled = true
                val handlers = cancelHandlers.toList()
                cancelHandlers.clear()
                val callbacks = completionHandlers.toList()
                completionHandlers.clear()
                val response = cancelledResponse(originalRequest)
                terminalResponse = response
                val attachedJob = job
                job = null
                CancellationDelivery(handlers, attachedJob, callbacks, response)
            }
        } ?: return
        cancelRequestBodyOnce()
        cancellation.cancelHandlers.forEach { handler ->
            try {
                handler()
            } catch (_: Throwable) {
                // Cancellation must continue to every registered owner.
            }
        }
        cancellation.job?.cancel()
        deliverTerminal(cancellation.response, cancellation.completionHandlers)
    }

    internal fun cancelRequestBodyOnce() {
        cancelBodyOnce(originalRequest.body)
    }

    internal fun cancelBodyOnce(body: NetworkBody) {
        val shouldCancel = synchronized(stateLock) {
            if (cancelledBodies.any { it === body }) false
            else {
                cancelledBodies += body
                true
            }
        }
        if (shouldCancel) {
            body.cancel()
        }
    }

    internal fun ownBody(body: NetworkBody) {
        synchronized(stateLock) {
            if (ownedBodies.none { it === body }) {
                ownedBodies += body
            }
        }
        addCancelHandler { cancelBodyOnce(body) }
    }

    internal fun ownBodyIfActive(body: NetworkBody): Boolean {
        ownBody(body)
        return synchronized(stateLock) { !cancelled && terminalResponse == null }
    }

    internal fun ownCurrentBody(body: () -> NetworkBody) {
        addCancelHandler { cancelBodyOnce(body()) }
    }

    internal fun cancelOwnedBodies() {
        val bodies = synchronized(stateLock) { ownedBodies.toList() }
        bodies.forEach { body ->
            try {
                cancelBodyOnce(body)
            } catch (_: Throwable) {
                // Preparation failure must release every registered owner.
            }
        }
    }

    private fun deliverTerminal(
        response: NetworkResponse,
        handlers: List<(NetworkResponse) -> Unit>
    ) = callbackDeliveryGate.closeAndRun {
        completion.complete(response)
        handlers.forEach { handler -> invokeCompletionHandler(handler, response) }
    }

    private fun invokeCompletionHandler(
        handler: (NetworkResponse) -> Unit,
        response: NetworkResponse
    ) {
        try {
            handler(response)
        } catch (_: Throwable) {
            // One terminal observer must not suppress delivery to the others.
        }
    }

    private data class CancellationDelivery(
        val cancelHandlers: List<() -> Unit>,
        val job: Job?,
        val completionHandlers: List<(NetworkResponse) -> Unit>,
        val response: NetworkResponse
    )
}

private class ProgressGateRecord(
    val request: NetworkRequest,
    var callbacks: com.tencent.kmm.network.export.NetworkProgressCallbacks,
)

class NetworkClient(
    private val config: NetworkClientConfig = NetworkClientConfig(),
    private val engine: NetworkEngine = RoutingNetworkEngine(
        selector = config.engineSelector,
        diagnosticsListener = config.engineDiagnostics
    ),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val refreshMutex = Mutex()
    private var refreshTask: Deferred<String?>? = null

    fun execute(
        request: NetworkRequest,
        callback: (NetworkResponse) -> Unit
    ): NetworkCall {
        val call = NetworkCall(request)
        call.addCompletionHandler(callback)
        val policy = try {
            selectPolicy(request)
        } catch (throwable: Throwable) {
            call.tryComplete(workerFailureResponse(request, throwable))
            return call
        }
        val job = scope.launch(dispatcherFor(policy.dispatcher)) {
            val response = try {
                call.markClientStarted()
                call.applyClientTiming(executeInternal(request.copyMutable(), call, policy))
            } catch (throwable: Throwable) {
                workerFailureResponse(request, throwable)
            }
            call.tryComplete(response)
        }
        attachCancellationOwner(job, call)
        job.invokeOnCompletion { cause ->
            if (cause != null) {
                call.tryComplete(workerFailureResponse(request, cause))
            }
        }
        call.attachJob(job)
        return call
    }

    suspend fun execute(request: NetworkRequest): NetworkResponse {
        val deferred = CompletableDeferred<NetworkResponse>()
        execute(request) { deferred.complete(it) }
        return deferred.await()
    }

    /**
     * Streaming download (fork #8): the response body is delivered to [onChunk]
     * as it arrives — never buffered whole — and [onComplete] receives the
     * body-less [NetworkResponse] (status/headers/error/timing). Request
     * middlewares and the current auth token are applied up front; there is no
     * mid-stream auth refresh or retry (a stream cannot be replayed once chunks
     * are handed out). Cancel via the returned [NetworkCall].
     *
     * On Android/iOS this streams straight off ktor's response channel. On
     * platforms whose engine cannot stream, the transport falls back to
     * buffering the full body and delivering it as a single chunk — same
     * result, no memory saving — until native streaming lands there.
     * The terminal closes admission and drains an already admitted start,
     * chunk, or progress callback before [onComplete]. [NetworkCall.await]
     * observes the same terminal winner but does not promise that an external
     * completion observer has returned before the suspension resumes.
     */
    fun downloadStream(
        request: NetworkRequest,
        onResponseStart: (statusCode: Int, contentLength: Long?, headers: Map<String, List<String>>) -> Unit = { _, _, _ -> },
        onChunk: (chunk: ByteArray) -> Unit,
        onComplete: (NetworkResponse) -> Unit
    ): NetworkCall {
        val call = NetworkCall(request)
        call.addCompletionHandler(onComplete)
        val policy = try {
            selectPolicy(request)
        } catch (throwable: Throwable) {
            call.tryComplete(workerFailureResponse(request, throwable))
            return call
        }
        val job = scope.launch(dispatcherFor(policy.dispatcher)) {
            val response = try {
                var prepared = request.copyMutable()
                try {
                    config.requestMiddlewares.forEach { middleware ->
                        call.ownCurrentBody { prepared.body }
                        prepared = middleware.prepare(prepared)
                        call.ownBody(prepared.body)
                    }
                } catch (throwable: Throwable) {
                    call.ownBody(prepared.body)
                    call.cancelOwnedBodies()
                    throw throwable
                }
                call.gateProgressCallbacks(prepared)
                prepared.policy = policy
                applyCurrentAuthToken(prepared)
                if (call.isCancelled) {
                    cancelledResponse(prepared)
                } else {
                    engine.downloadStream(
                        prepared,
                        call,
                        onResponseStart = { statusCode, contentLength, headers ->
                            call.runWhileActive {
                                onResponseStart(statusCode, contentLength, headers)
                            }
                        },
                        onChunk = { chunk ->
                            call.runWhileActive { onChunk(chunk) }
                        }
                    )
                }
            } catch (throwable: Throwable) {
                workerFailureResponse(request, throwable)
            }
            call.tryComplete(response)
        }
        attachCancellationOwner(job, call)
        job.invokeOnCompletion { cause ->
            if (cause != null) {
                call.tryComplete(workerFailureResponse(request, cause))
            }
        }
        call.attachJob(job)
        return call
    }

    private suspend fun executeInternal(
        request: NetworkRequest,
        call: NetworkCall,
        policy: NetworkRequestPolicy
    ): NetworkResponse {
        val preparationStartedAt = TimeSource.Monotonic.markNow()
        var prepared = request
        try {
            config.requestMiddlewares.forEach { middleware ->
                call.ownCurrentBody { prepared.body }
                prepared = middleware.prepare(prepared)
                call.ownBody(prepared.body)
            }
        } catch (throwable: Throwable) {
            call.ownBody(prepared.body)
            call.cancelOwnedBodies()
            throw throwable
        }
        call.gateProgressCallbacks(prepared)
        prepared.policy = policy
        applyCurrentAuthToken(prepared)
        call.markRequestPrepared(preparationStartedAt)

        var attempt = 0
        var refreshedAuth = false
        while (true) {
            if (call.isCancelled) {
                return cancelledResponse(prepared)
            }
            val outcome = executeWithInterceptors(prepared, call)
            val response = outcome.response
            val actualBody = outcome.body
            call.ownBody(actualBody)
            val authRetry = maybeRefreshAuth(prepared, response, refreshedAuth, actualBody)
            if (authRetry) {
                refreshedAuth = true
                continue
            }
            if (!shouldRetry(response, policy, attempt, actualBody)) {
                return observe(response)
            }
            delay(policy.retry.backoff.delayForAttempt(attempt))
            attempt += 1
        }
    }

    private fun selectPolicy(request: NetworkRequest): NetworkRequestPolicy {
        return config.policySelector?.invoke(request) ?: request.policy.takeIf {
            it != NetworkRequestPolicy()
        } ?: config.defaultPolicy
    }

    @OptIn(InternalCoroutinesApi::class)
    private fun attachCancellationOwner(job: Job, call: NetworkCall) {
        job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause is CancellationException) {
                call.cancel()
            }
        }
    }

    private suspend fun applyCurrentAuthToken(request: NetworkRequest) {
        val auth = config.auth ?: return
        if (request.headers.containsKey(auth.headerName)) {
            return
        }
        val token = auth.tokenProvider.currentToken(request) ?: return
        request.headers[auth.headerName] = auth.formatToken(token)
    }

    private suspend fun maybeRefreshAuth(
        request: NetworkRequest,
        response: NetworkResponse,
        alreadyRefreshed: Boolean,
        body: NetworkBody,
    ): Boolean {
        val auth = config.auth ?: return false
        val status = response.statusCode ?: return false
        if (alreadyRefreshed || status !in auth.refreshStatusCodes || !body.repeatable) {
            return false
        }
        val token = refreshTokenDedup(request, response) ?: return false
        request.headers[auth.headerName] = auth.formatToken(token)
        return true
    }

    private suspend fun refreshTokenDedup(
        request: NetworkRequest,
        response: NetworkResponse
    ): String? {
        val auth = config.auth ?: return null
        val task = refreshMutex.withLock {
            val activeTask = refreshTask
            if (activeTask != null && !activeTask.isCompleted) {
                activeTask
            } else {
                scope.async {
                    auth.tokenProvider.refreshToken(request, response)
                }.also { refreshTask = it }
            }
        }
        return try {
            task.await()
        } finally {
            refreshMutex.withLock {
                if (refreshTask == task) {
                    refreshTask = null
                }
            }
        }
    }

    private fun shouldRetry(
        response: NetworkResponse,
        policy: NetworkRequestPolicy,
        attempt: Int,
        body: NetworkBody
    ): Boolean {
        // Curl body-stall recovery is already bounded to one physical fresh
        // attempt inside the selected engine. Never stack the generic policy
        // retry on top, and never replay a write-method stall here.
        if (response.error?.rawCode == 63 ||
            response.timing.curlBodyStallDetected || response.timing.freshRetry
        ) {
            return false
        }
        if (attempt >= policy.retry.maxRetries || !body.repeatable) {
            return false
        }
        return policy.retry.shouldRetry(response)
    }

    private suspend fun observe(response: NetworkResponse): NetworkResponse {
        var observed = response
        config.responseMiddlewares.forEach { middleware ->
            observed = middleware.observe(observed)
        }
        return observed
    }

    private suspend fun executeWithInterceptors(
        request: NetworkRequest,
        call: NetworkCall
    ): NetworkAttemptOutcome {
        val outcomes = AttemptResponseBodyTracker()
        val response = RealNetworkInterceptorChain(
            interceptors = config.interceptors,
            index = 0,
            request = request,
            call = call,
            engine = engine,
            outcomes = outcomes,
        ).proceed(request)
        return NetworkAttemptOutcome(response, outcomes.bodyFor(response) ?: request.body)
    }

    private fun dispatcherFor(dispatcher: NetworkDispatcher): CoroutineDispatcher {
        return when (dispatcher) {
            NetworkDispatcher.IO -> Dispatchers.IO
            NetworkDispatcher.DEFAULT -> Dispatchers.Default
        }
    }
}

object VBTransportNetworkEngine : NetworkEngine {
    override val capabilities: NetworkEngineCapabilities
        get() = if (usesCurlPlatformDefault) curlNetworkEngineCapabilities().copy(
            // issue #8: every current platform has true request streaming
            // (ktor WriteChannelContent or curl READFUNCTION).
            requestBodyStreaming = com.tencent.kmm.network.internal.platform.platformRequestBodyStreaming,
            multipartStreaming = com.tencent.kmm.network.internal.platform.platformRequestBodyStreaming
        ) else NetworkEngineCapabilities(
            requestBodyStreaming = com.tencent.kmm.network.internal.platform.platformRequestBodyStreaming,
            responseBodyStreaming = true,
            multipartStreaming = com.tencent.kmm.network.internal.platform.platformRequestBodyStreaming,
            uploadProgress = true,
            downloadProgress = true
        )

    override fun availability(request: NetworkRequest): NetworkEngineAvailability =
        if (usesCurlPlatformDefault) prepareCurlRuntime(request) else NetworkEngineAvailability.Available

    override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse {
        if (usesCurlPlatformDefault) {
            val availability = prepareCurlRuntime(request)
            if (!availability.available) return curlRuntimeFailureResponse(request, availability)
        }
        if ((request.method == VBTransportMethod.GET || request.method == VBTransportMethod.HEAD) &&
            request.body.hasPotentialStreamingSource()
        ) {
            return unsupportedStreamingRequestBodyResponse(request)
        }
        // issue #8 slice 1: Stream/FileRef bodies go out as a true byte stream
        // on platforms whose transport supports it; everything else (and every
        // body on non-streaming platforms) keeps the buffered path below.
        if (capabilities.requestBodyStreaming) {
            val source = try {
                networkUploadStreamSourceOrNull(request)
            } catch (throwable: Throwable) {
                runCatching { call.cancelBodyOnce(request.body) }
                throw throwable
            }
            source?.let {
                return executeStreaming(request, call, it)
            }
        }
        val bodyBytes = request.body.toBytes(request.progress.uploadProgress) { stream ->
            call.ownBodyIfActive(NetworkBody.Stream(stream))
        }
        bodyBytes.error?.let {
            return NetworkResponse(
                request = request,
                statusCode = null,
                headers = emptyMap(),
                body = NetworkResponseBody(),
                error = it
            )
        }

        val startedAt = TimeSource.Monotonic.markNow()
        val first = executeBufferedPlatformAttempt(
            request = request,
            call = call,
            bodyBytes = bodyBytes,
            timeoutMillis = request.policy.timeoutMillis,
        )
        if (!usesCurlPlatformDefault || !first.isCurlBufferedBodyIdleTimeout()) {
            return first
        }
        first.timing.curlBodyStallDetected = true
        val remainingTimeout = remainingPlatformCurlTimeoutMillis(
            request.policy.timeoutMillis,
            startedAt
        )
        if (!shouldFreshRetryCurlBufferedStall(
                method = request.method,
                bodyRepeatable = request.body.repeatable,
                policy = request.policy.curlBufferedResponse,
                cancelled = call.isCancelled,
                remainingTimeoutMillis = remainingTimeout,
            )) {
            return first
        }
        val retried = executeBufferedPlatformAttempt(
            request = request,
            call = call,
            bodyBytes = bodyBytes,
            timeoutMillis = remainingTimeout ?: 0L,
        )
        retried.timing.curlBodyStallDetected = true
        retried.timing.retainFirstAttemptCurlFacts(first.timing)
        retried.timing.freshRetry = true
        retried.timing.freshRetryResult = if (retried.statusCode != null) "success" else "failure"
        return retried
    }

    private suspend fun executeBufferedPlatformAttempt(
        request: NetworkRequest,
        call: NetworkCall,
        bodyBytes: NetworkBodyBytes,
        timeoutMillis: Long,
    ): NetworkResponse = suspendCancellableCoroutine { continuation ->
            val vbRequest = VBTransportRequest().apply {
                method = request.method
                url = request.resolvedUrl()
                header.putAll(request.headers)
                bodyBytes.contentType?.let {
                    if (!header.keys.any { key -> key.equals("Content-Type", ignoreCase = true) }) {
                        header["Content-Type"] = it
                    }
                }
                totalTimeout = timeoutMillis
                curlBufferedBodyIdleTimeoutMillis =
                    request.policy.curlBufferedResponse.bodyIdleTimeoutMillis
                curlMaxBufferedResponseBytes = request.policy.curlBufferedResponse.maxDecodedBytes
                curlCaInfoPath = preparedCurlCaInfoPath(request)
                curlProxyUrl = preparedCurlProxyUrl(request)
                curlHttp3Enabled = preparedCurlHttp3Enabled(request)
                bodyBytes.bytes?.let { data = it }
            }

            VBTransportService.sendRequest(vbRequest) { response ->
                if (continuation.isActive) {
                    continuation.resume(response.toNetworkResponse(request))
                }
            }
            call.addCancelHandler {
                VBTransportService.cancel(vbRequest.requestId)
            }
            continuation.invokeOnCancellation {
                VBTransportService.cancel(vbRequest.requestId)
            }
        }

    override suspend fun downloadStream(
        request: NetworkRequest,
        call: NetworkCall,
        onResponseStart: (statusCode: Int, contentLength: Long?, headers: Map<String, List<String>>) -> Unit,
        onChunk: (ByteArray) -> Unit
    ): NetworkResponse = suspendCancellableCoroutine { continuation ->
        if (usesCurlPlatformDefault) {
            val availability = prepareCurlRuntime(request)
            if (!availability.available) {
                return@suspendCancellableCoroutine continuation.resume(
                    curlRuntimeFailureResponse(request, availability)
                )
            }
        }
        val vbRequest = VBTransportRequest().apply {
            method = request.method
            url = request.resolvedUrl()
            header.putAll(request.headers)
            totalTimeout = request.policy.timeoutMillis
            streamConnectTimeoutMillis = request.policy.streamTimeouts.connectTimeoutMillis
            streamResponseHeadersTimeoutMillis = request.policy.streamTimeouts.responseHeadersTimeoutMillis
            streamIdleTimeoutMillis = request.policy.streamTimeouts.interChunkIdleTimeoutMillis
            streamWholeTimeoutMillis = request.policy.streamTimeouts.wholeTransferTimeoutMillis
            curlCaInfoPath = preparedCurlCaInfoPath(request)
            curlProxyUrl = preparedCurlProxyUrl(request)
            curlHttp3Enabled = preparedCurlHttp3Enabled(request)
        }
        val transportCancelArmed = atomic(true)
        fun cancelTransportOnce() {
            if (transportCancelArmed.compareAndSet(expect = true, update = false)) {
                VBTransportService.cancel(vbRequest.requestId)
            }
        }
        VBTransportService.streamRequest(
            vbRequest,
            onResponseStart = { rawStatus, headers ->
                // rawStatus follows the transport errorCode convention
                // (0 == OK); callers receive the real HTTP status.
                val httpStatus = statusCodeFromErrorCode(rawStatus) ?: rawStatus
                onResponseStart(httpStatus, contentLengthFromHeaders(headers), headers)
            },
            onChunk = onChunk
        ) { response ->
            transportCancelArmed.value = false
            if (continuation.isActive) {
                // Preserve the existing VBTransport streaming completion
                // response exactly; callers already treat it as body-less.
                continuation.resume(response.toNetworkResponse(request))
            }
        }
        call.addCancelHandler(::cancelTransportOnce)
        continuation.invokeOnCancellation { cancelTransportOnce() }
    }

    private suspend fun executeStreaming(
        request: NetworkRequest,
        call: NetworkCall,
        source: NetworkUploadStreamSource
    ): NetworkResponse {
        val uploadProgress = request.progress.uploadProgress
        return suspendCancellableCoroutine { continuation ->
            val vbRequest = VBTransportRequest().apply {
                method = request.method
                url = request.resolvedUrl()
                header.putAll(request.headers)
                source.contentType?.let {
                    if (!header.keys.any { key -> key.equals("Content-Type", ignoreCase = true) }) {
                        header["Content-Type"] = it
                    }
                }
                totalTimeout = request.policy.timeoutMillis
                curlBufferedBodyIdleTimeoutMillis =
                    request.policy.curlBufferedResponse.bodyIdleTimeoutMillis
                curlMaxBufferedResponseBytes = request.policy.curlBufferedResponse.maxDecodedBytes
                curlCaInfoPath = preparedCurlCaInfoPath(request)
                curlProxyUrl = preparedCurlProxyUrl(request)
                curlHttp3Enabled = preparedCurlHttp3Enabled(request)
            }
            val writeBody: suspend (NetworkByteStreamSink) -> Unit = { sink ->
                var sent = 0L
                source.stream.readChunks(object : NetworkByteStreamSink {
                    override suspend fun write(bytes: ByteArray) {
                        if (bytes.isEmpty()) return
                        sink.write(bytes)
                        sent += bytes.size
                        call.runWhileActive {
                            uploadProgress?.invoke(NetworkTransferProgress(sent, source.contentLength))
                        }
                    }
                })
            }
            val cancellationOwners = StreamingUploadCancellationOwners(
                cancelOriginalRequestBody = call::cancelRequestBodyOnce,
                cancelPreparedRequestBody = { call.cancelBodyOnce(request.body) },
                cancelDerivedSource = source.stream.takeIf { source.cancelSeparatelyFromRequestBody }
                    ?.let { stream -> stream::cancel },
                cancelAttemptSource = source.cancelAttemptSource,
                cancelNativeRequest = null,
                closePullBridge = null,
                cancelTransport = { VBTransportService.cancel(vbRequest.requestId) }
            )
            try {
                VBTransportService.uploadStream(vbRequest, source.contentLength, writeBody) { response ->
                    cancellationOwners.disarmTransport()
                    if (continuation.isActive) {
                        val mapped = response.toNetworkResponse(request)
                        if (usesCurlPlatformDefault && mapped.isCurlBufferedBodyIdleTimeout()) {
                            mapped.timing.curlBodyStallDetected = true
                        }
                        if (mapped.error != null) cancellationOwners.releaseAttemptSourceOnFailure()
                        continuation.resume(mapped)
                    }
                }
            } catch (throwable: Throwable) {
                cancellationOwners.releaseAttemptSourceOnFailure()
                throw throwable
            }
            call.addCancelHandler(cancellationOwners::cancelAll)
            continuation.invokeOnCancellation { cancellationOwners.cancelAll() }
        }
    }

    private val usesCurlPlatformDefault: Boolean
        get() = com.tencent.kmm.network.internal.platform.platformDefaultNetworkTransportEngine ==
            NetworkTransportEngine.CURL
}

private fun NetworkResponse.isCurlBufferedBodyIdleTimeout(): Boolean =
    error?.rawCode == 28 && error.message.contains("buffered body idle timeout")

private fun remainingPlatformCurlTimeoutMillis(totalTimeoutMillis: Long, startedAt: TimeMark): Long? {
    if (totalTimeoutMillis <= 0) return null
    return (totalTimeoutMillis - startedAt.elapsedNow().inWholeMilliseconds).coerceAtLeast(0)
}

internal class NetworkUploadStreamSource(
    val stream: NetworkByteStream,
    val contentType: String?,
    val contentLength: Long?,
    val cancelSeparatelyFromRequestBody: Boolean = false,
    val cancelAttemptSource: () -> Unit = stream::cancel,
)

internal suspend fun networkUploadStreamSourceOrNull(request: NetworkRequest): NetworkUploadStreamSource? =
    when (val body = request.body) {
        is NetworkBody.Stream ->
            NetworkUploadStreamSource(body.stream, body.contentType, body.contentLength)
        is NetworkBody.FileRef ->
            body.openStream()?.let { stream ->
                NetworkUploadStreamSource(
                    stream,
                    body.contentType,
                    stream.contentLength ?: body.contentLength,
                    cancelSeparatelyFromRequestBody = true
                )
            }
        // issue #8 slice 2: multiparts stream when they carry at least one
        // Stream/FileRef part; all-scalar multiparts keep the buffered path.
        is NetworkBody.Multipart ->
            body.streamingUploadStreamOrNull()?.let { stream ->
                NetworkUploadStreamSource(
                    stream,
                    body.contentType,
                    stream.contentLength,
                    cancelSeparatelyFromRequestBody = true,
                    cancelAttemptSource = stream::cancelAttempt,
                )
            }
        else -> null
    }

internal fun NetworkBody.hasPotentialStreamingSource(): Boolean = when (this) {
    is NetworkBody.Stream, is NetworkBody.FileRef -> true
    is NetworkBody.Multipart -> parts.any { it.body.hasPotentialStreamingSource() }
    else -> false
}

internal class StreamingUploadCancellationOwners(
    private val cancelOriginalRequestBody: () -> Unit,
    private val cancelPreparedRequestBody: () -> Unit,
    private val cancelDerivedSource: (() -> Unit)?,
    private val cancelAttemptSource: (() -> Unit)? = cancelDerivedSource,
    private val cancelNativeRequest: (() -> Unit)?,
    private val closePullBridge: (() -> Unit)?,
    private val cancelTransport: () -> Unit
) {
    private val originalRequestBodyArmed = atomic(true)
    private val preparedRequestBodyArmed = atomic(true)
    private val derivedSourceArmed = atomic(cancelDerivedSource != null)
    private val attemptSourceArmed = atomic(cancelAttemptSource != null)
    private val nativeRequestArmed = atomic(cancelNativeRequest != null)
    private val pullBridgeArmed = atomic(closePullBridge != null)
    private val transportArmed = atomic(true)

    fun cancelAll() {
        // Claim transport cancellation before closing the pull bridge: close
        // can wake the transport coroutine, whose finally block disarms these
        // owners. Reversing this order can therefore lose the native cancel.
        cancelOnce(transportArmed, cancelTransport)
        cancelOnce(originalRequestBodyArmed, cancelOriginalRequestBody)
        cancelOnce(preparedRequestBodyArmed, cancelPreparedRequestBody)
        cancelDerivedSource?.let { cancelOnce(derivedSourceArmed, it) }
        cancelNativeRequest?.let { cancelOnce(nativeRequestArmed, it) }
        closePullBridge?.let { cancelOnce(pullBridgeArmed, it) }
    }

    fun disarmTransport() {
        transportArmed.value = false
    }

    fun releaseAttemptSourceOnFailure() {
        cancelAttemptSource?.let { cancelOnce(attemptSourceArmed, it) }
    }

    fun disarmNativeTransportOwners() {
        nativeRequestArmed.value = false
        pullBridgeArmed.value = false
        transportArmed.value = false
    }

    private fun cancelOnce(armed: kotlinx.atomicfu.AtomicBoolean, cancel: () -> Unit) {
        if (armed.compareAndSet(expect = true, update = false)) {
            runCatching(cancel)
        }
    }
}

internal data class NetworkAttemptOutcome(
    val response: NetworkResponse,
    val body: NetworkBody,
)

private class AttemptResponseBodyTracker : SynchronizedObject() {
    private val records = mutableListOf<Pair<NetworkResponse, NetworkBody>>()

    fun bindEngine(response: NetworkResponse, body: NetworkBody) {
        synchronized(this) {
            if (records.none { it.first === response }) records += response to body
        }
    }

    fun bindInterceptor(response: NetworkResponse, body: NetworkBody) {
        synchronized(this) {
            if (records.none { it.first === response }) {
                val provenanceBody = records.firstOrNull { it.first.request === response.request }?.second
                records += response to (provenanceBody ?: body)
            }
        }
    }

    fun bodyFor(response: NetworkResponse): NetworkBody? = synchronized(this) {
        records.firstOrNull { it.first === response }?.second
    }
}

private class RealNetworkInterceptorChain(
    private val interceptors: List<NetworkInterceptor>,
    private val index: Int,
    override val request: NetworkRequest,
    override val call: NetworkCall,
    private val engine: NetworkEngine,
    private val outcomes: AttemptResponseBodyTracker,
) : NetworkInterceptorChain {
    override suspend fun proceed(request: NetworkRequest): NetworkResponse {
        if (index >= interceptors.size) {
            val engineRequest = request.copyMutable()
            call.gateProgressCallbacks(engineRequest)
            call.ownBody(engineRequest.body)
            if (call.isCancelled) return cancelledResponse(engineRequest)
            return engine.execute(engineRequest, call).also {
                outcomes.bindEngine(it, engineRequest.body)
            }
        }
        call.ownCurrentBody { request.body }
        return interceptors[index].intercept(
            RealNetworkInterceptorChain(
                interceptors = interceptors,
                index = index + 1,
                request = request,
                call = call,
                engine = engine,
                outcomes = outcomes,
            )
        ).also { outcomes.bindInterceptor(it, it.request.body) }
    }
}

private fun VBTransportBaseResponse.toNetworkResponse(request: NetworkRequest): NetworkResponse {
    val bytes = when (this) {
        is VBTransportResponse -> data.toResponseBytes()
        else -> null
    }
    bytes?.let {
        request.progress.downloadProgress?.invoke(
            NetworkTransferProgress(
                bytesTransferred = it.size.toLong(),
                bytesTotal = contentLengthFromHeaders(header) ?: it.size.toLong()
            )
        )
    }
    val status = statusCodeFromErrorCode(errorCode)
    val error = errorFromResponse(errorCode, errorMessage, status)
    return NetworkResponse(
        request = request,
        statusCode = status,
        headers = header,
        body = NetworkResponseBody(
            bytes = bytes,
            stream = bytes?.let { NetworkByteStream(contentLength = it.size.toLong(), readAllBlock = { it }) }
        ),
        error = error,
        rawResponse = this,
        timing = elapseStatis,
        protocol = elapseStatis.protocol.toNetworkHttpProtocol()
    )
}

private fun NetworkResponse.withoutBody(): NetworkResponse = NetworkResponse(
    request = request,
    statusCode = statusCode,
    headers = headers,
    body = NetworkResponseBody(),
    error = error,
    rawResponse = rawResponse,
    timing = timing,
    protocol = protocol
)

private fun Any?.toResponseBytes(): ByteArray? {
    return when (this) {
        is ByteArray -> this
        is String -> encodeToByteArray()
        null -> null
        else -> toString().encodeToByteArray()
    }
}

private fun statusCodeFromErrorCode(errorCode: Int): Int? {
    return when {
        errorCode in 100..599 -> errorCode
        errorCode == VBTransportResultCode.CODE_OK -> 200
        else -> null
    }
}

private fun contentLengthFromHeaders(headers: Map<String, List<String>>): Long? {
    return headers.entries.firstOrNull { (name, _) ->
        name.equals("Content-Length", ignoreCase = true)
    }?.value?.firstOrNull()?.toLongOrNull()
}

private fun errorFromResponse(
    errorCode: Int,
    errorMessage: String,
    statusCode: Int?
): NetworkError? {
    if (errorCode == VBTransportResultCode.CODE_OK || (statusCode != null && statusCode < 400)) {
        return null
    }
    val kind = classifyNetworkErrorKind(errorCode, errorMessage, statusCode)
    return NetworkError(
        kind = kind,
        message = errorMessage.ifBlank { kind.name },
        statusCode = statusCode,
        rawCode = errorCode
    )
}

internal fun classifyNetworkErrorKind(
    errorCode: Int,
    errorMessage: String,
    statusCode: Int?
): NetworkErrorKind {
    val normalizedMessage = errorMessage.lowercase()
    return when {
        errorCode == VBTransportResultCode.CODE_CANCELED -> NetworkErrorKind.CANCELLED
        errorCode == VBTransportResultCode.CODE_FORCE_TIMEOUT -> NetworkErrorKind.TIMEOUT
        statusCode == 401 || statusCode == 403 -> NetworkErrorKind.AUTH
        statusCode != null -> NetworkErrorKind.HTTP_STATUS
        "cancelled" in normalizedMessage || "canceled" in normalizedMessage -> NetworkErrorKind.CANCELLED
        "timeout" in normalizedMessage || "timed out" in normalizedMessage -> NetworkErrorKind.TIMEOUT
        "dns" in normalizedMessage ||
            "resolve" in normalizedMessage ||
            "unknown host" in normalizedMessage ||
            "host not found" in normalizedMessage -> NetworkErrorKind.DNS
        "tls" in normalizedMessage ||
            "ssl" in normalizedMessage ||
            "certificate" in normalizedMessage -> NetworkErrorKind.TLS
        "connect" in normalizedMessage ||
            "connection refused" in normalizedMessage ||
            "network is unreachable" in normalizedMessage -> NetworkErrorKind.CONNECT
        else -> NetworkErrorKind.UNKNOWN
    }
}

private fun cancelledResponse(request: NetworkRequest): NetworkResponse {
    return NetworkResponse(
        request = request,
        statusCode = null,
        headers = emptyMap(),
        body = NetworkResponseBody(),
        error = NetworkError(NetworkErrorKind.CANCELLED, "Request has been cancelled")
    )
}

private fun workerFailureResponse(request: NetworkRequest, throwable: Throwable): NetworkResponse {
    if (throwable is CancellationException) {
        return cancelledResponse(request)
    }
    return NetworkResponse(
        request = request,
        statusCode = null,
        headers = emptyMap(),
        body = NetworkResponseBody(),
        error = NetworkError(
            NetworkErrorKind.UNKNOWN,
            throwable.message ?: "Network worker failed"
        )
    )
}

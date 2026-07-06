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

import com.tencent.kmm.network.export.NetworkBody
import com.tencent.kmm.network.export.NetworkByteStream
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
import com.tencent.kmm.network.export.VBTransportRequest
import com.tencent.kmm.network.export.VBTransportResponse
import com.tencent.kmm.network.export.VBTransportResultCode
import com.tencent.kmm.network.export.cancel
import com.tencent.kmm.network.export.toBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

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
    val auth: NetworkAuthConfig? = null
)

interface NetworkEngine {
    val capabilities: NetworkEngineCapabilities
        get() = NetworkEngineCapabilities()

    suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse
}

class NetworkCall internal constructor(
    val originalRequest: NetworkRequest
) {
    private val completion = CompletableDeferred<NetworkResponse>()
    private val cancelHandlers = mutableListOf<() -> Unit>()
    private var job: Job? = null
    private var cancelled = false

    val isCancelled: Boolean
        get() = cancelled

    internal fun attachJob(job: Job) {
        this.job = job
        if (cancelled) {
            job.cancel()
        }
    }

    internal fun addCancelHandler(handler: () -> Unit) {
        if (cancelled) {
            handler()
        } else {
            cancelHandlers.add(handler)
        }
    }

    internal fun complete(response: NetworkResponse) {
        if (!completion.isCompleted) {
            completion.complete(response)
        }
    }

    suspend fun await(): NetworkResponse = completion.await()

    fun cancel() {
        if (cancelled) {
            return
        }
        cancelled = true
        originalRequest.body.cancel()
        cancelHandlers.forEach { it() }
        cancelHandlers.clear()
        job?.cancel()
        if (!completion.isCompleted) {
            completion.complete(
                NetworkResponse(
                    request = originalRequest,
                    statusCode = null,
                    headers = emptyMap(),
                    body = NetworkResponseBody(),
                    error = NetworkError(NetworkErrorKind.CANCELLED, "Request has been cancelled")
                )
            )
        }
    }
}

class NetworkClient(
    private val config: NetworkClientConfig = NetworkClientConfig(),
    private val engine: NetworkEngine = VBTransportNetworkEngine,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val refreshMutex = Mutex()
    private var refreshTask: Deferred<String?>? = null

    fun execute(
        request: NetworkRequest,
        callback: (NetworkResponse) -> Unit
    ): NetworkCall {
        val call = NetworkCall(request)
        val policy = selectPolicy(request)
        val job = scope.launch(dispatcherFor(policy.dispatcher)) {
            val response = executeInternal(request.copyMutable(), call, policy)
            call.complete(response)
            callback(response)
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
     */
    fun downloadStream(
        request: NetworkRequest,
        onChunk: (chunk: ByteArray) -> Unit,
        onComplete: (NetworkResponse) -> Unit
    ): NetworkCall {
        val call = NetworkCall(request)
        val policy = selectPolicy(request)
        val job = scope.launch(dispatcherFor(policy.dispatcher)) {
            var prepared = request.copyMutable()
            config.requestMiddlewares.forEach { middleware ->
                prepared = middleware.prepare(prepared)
            }
            prepared.policy = policy
            applyCurrentAuthToken(prepared)
            if (call.isCancelled) {
                val cancelled = cancelledResponse(prepared)
                call.complete(cancelled)
                onComplete(cancelled)
                return@launch
            }
            val vbRequest = VBTransportRequest().apply {
                method = prepared.method
                url = prepared.resolvedUrl()
                header.putAll(prepared.headers)
                totalTimeout = prepared.policy.timeoutMillis
            }
            call.addCancelHandler { VBTransportService.cancel(vbRequest.requestId) }
            VBTransportService.streamRequest(vbRequest, onChunk) { response ->
                val networkResponse = response.toNetworkResponse(prepared)
                call.complete(networkResponse)
                onComplete(networkResponse)
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
        var prepared = request
        config.requestMiddlewares.forEach { middleware ->
            prepared = middleware.prepare(prepared)
        }
        prepared.policy = policy
        applyCurrentAuthToken(prepared)

        var attempt = 0
        var refreshedAuth = false
        while (true) {
            if (call.isCancelled) {
                return cancelledResponse(prepared)
            }
            val response = executeWithInterceptors(prepared, call)
            val authRetry = maybeRefreshAuth(prepared, response, refreshedAuth)
            if (authRetry) {
                refreshedAuth = true
                continue
            }
            if (!shouldRetry(response, policy, attempt, prepared.body)) {
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
        alreadyRefreshed: Boolean
    ): Boolean {
        val auth = config.auth ?: return false
        val status = response.statusCode ?: return false
        if (alreadyRefreshed || status !in auth.refreshStatusCodes) {
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
    ): NetworkResponse {
        return RealNetworkInterceptorChain(
            interceptors = config.interceptors,
            index = 0,
            request = request,
            call = call,
            engine = engine
        ).proceed(request)
    }

    private fun dispatcherFor(dispatcher: NetworkDispatcher): CoroutineDispatcher {
        return when (dispatcher) {
            NetworkDispatcher.IO -> Dispatchers.IO
            NetworkDispatcher.DEFAULT -> Dispatchers.Default
        }
    }
}

object VBTransportNetworkEngine : NetworkEngine {
    override val capabilities: NetworkEngineCapabilities = NetworkEngineCapabilities(
        requestBodyStreaming = false,
        responseBodyStreaming = false,
        multipartStreaming = false,
        uploadProgress = true,
        downloadProgress = true
    )

    override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse {
        val bodyBytes = request.body.toBytes(request.progress.uploadProgress)
        bodyBytes.error?.let {
            return NetworkResponse(
                request = request,
                statusCode = null,
                headers = emptyMap(),
                body = NetworkResponseBody(),
                error = it
            )
        }

        return suspendCancellableCoroutine { continuation ->
            val vbRequest = VBTransportRequest().apply {
                method = request.method
                url = request.resolvedUrl()
                header.putAll(request.headers)
                bodyBytes.contentType?.let {
                    if (!header.keys.any { key -> key.equals("Content-Type", ignoreCase = true) }) {
                        header["Content-Type"] = it
                    }
                }
                totalTimeout = request.policy.timeoutMillis
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
    }
}

private class RealNetworkInterceptorChain(
    private val interceptors: List<NetworkInterceptor>,
    private val index: Int,
    override val request: NetworkRequest,
    override val call: NetworkCall,
    private val engine: NetworkEngine
) : NetworkInterceptorChain {
    override suspend fun proceed(request: NetworkRequest): NetworkResponse {
        if (index >= interceptors.size) {
            return engine.execute(request, call)
        }
        return interceptors[index].intercept(
            RealNetworkInterceptorChain(
                interceptors = interceptors,
                index = index + 1,
                request = request,
                call = call,
                engine = engine
            )
        )
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
        timing = elapseStatis
    )
}

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

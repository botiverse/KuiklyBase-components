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
import com.tencent.kmm.network.curl.CurlResponseCodec
import com.tencent.kmm.network.curl.CurlResponseFields
import com.tencent.kmm.network.curl.CurlTransferFactsV1
import com.tencent.kmm.network.curl.applyCurlTransferFacts
import com.tencent.kmm.network.curl.native.Cancel as cancelNative
import com.tencent.kmm.network.curl.native.CreateCurlClient
import com.tencent.kmm.network.curl.native.CurlSupportsHttp3
import com.tencent.kmm.network.curl.native.CurlWrapperAbiVersion
import com.tencent.kmm.network.curl.native.CURL_WRAPPER_ABI_VERSION
import com.tencent.kmm.network.curl.native.CURL_TRANSFER_INFO_ABI_VERSION
import com.tencent.kmm.network.curl.native.CURL_MULTI_INFO_ABI_VERSION
import com.tencent.kmm.network.curl.native.CurlCallback
import com.tencent.kmm.network.curl.native.CurlRequest
import com.tencent.kmm.network.curl.native.CurlResponse
import com.tencent.kmm.network.curl.native.CurlStreamCallback
import com.tencent.kmm.network.curl.native.CurlUploadSource
import com.tencent.kmm.network.curl.native.CurlTransferInfoV1
import com.tencent.kmm.network.curl.native.CurlMultiInfoV1
import com.tencent.kmm.network.curl.native.DeleteCurlClient
import com.tencent.kmm.network.curl.native.GetCurlNegotiatedProtocol
import com.tencent.kmm.network.curl.native.NetworkKmmGetCurlTransferInfoV1IfAvailable
import com.tencent.kmm.network.curl.native.NetworkKmmCurlMultiApiAvailable
import com.tencent.kmm.network.curl.native.NetworkKmmCreateCurlMultiEngineIfAvailable
import com.tencent.kmm.network.curl.native.NetworkKmmSubmitBufferedRequestV27IfAvailable
import com.tencent.kmm.network.curl.native.NetworkKmmCancelCurlMultiRequestIfAvailable
import com.tencent.kmm.network.curl.native.NetworkKmmGetCurlMultiInfoV1IfAvailable
import com.tencent.kmm.network.curl.native.NetworkKmmSetCurlBufferedBodyIdleTimeoutMsIfAvailable
import com.tencent.kmm.network.curl.native.NetworkKmmSetCurlMaxBufferedResponseBytesIfAvailable
import com.tencent.kmm.network.curl.native.SetCurlCaInfo
import com.tencent.kmm.network.curl.native.SetCurlHttp3Enabled
import com.tencent.kmm.network.curl.native.SetCurlProxy
import com.tencent.kmm.network.curl.native.SetCurlResolve
import com.tencent.kmm.network.curl.native.StartRequestV27
import com.tencent.kmm.network.curl.native.StartStreamRequestV27
import com.tencent.kmm.network.curl.native.StartUploadRequestV27
import com.tencent.kmm.network.curl.native.StringDic
import com.tencent.kmm.network.curl.native.StringPair
import com.tencent.kmm.network.export.VBTransportElapseStatistics
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cstr
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private const val IOS_CURL_PERFORM_THREADS = 4
private const val IOS_CURL_UPLOAD_WRITER_THREADS = 2

@OptIn(ObsoleteCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal object IosCurlExecutionDispatchers {
    // Blocking curl performs must never share a bounded pool with upload producers.
    val perform: CoroutineDispatcher =
        newFixedThreadPoolContext(IOS_CURL_PERFORM_THREADS, "NetworkKmmIosCurlPerform")
    val uploadWriter: CoroutineDispatcher =
        newFixedThreadPoolContext(IOS_CURL_UPLOAD_WRITER_THREADS, "NetworkKmmIosCurlUploadWriter")
}

internal class IosCurlCancellationSignal {
    private val cancelled = atomic(false)

    fun cancel() {
        cancelled.value = true
    }

    fun isCancelled(): Boolean = cancelled.value
}

internal data class IosCurlNativeRequest(
    val requestId: Int,
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val timeoutMillis: Long,
    val streamConnectTimeoutMillis: Long = 0,
    val streamResponseHeadersTimeoutMillis: Long = 0,
    val streamIdleTimeoutMillis: Long = 0,
    val streamWholeTimeoutMillis: Long = 0,
    val bufferedBodyIdleTimeoutMillis: Long = 0,
    val maxBufferedResponseBytes: Long = 0,
    val body: ByteArray? = null,
    val uploadContentLength: Long? = null,
    val caInfoPath: String,
    /** Empty string means explicit direct mode. */
    val proxyUrl: String,
    val http3Enabled: Boolean = false,
    val resolveEntry: String? = null,
    val cancellationSignal: IosCurlCancellationSignal = IosCurlCancellationSignal()
) {
    fun cancel() {
        cancellationSignal.cancel()
    }
}

internal fun interface IosCurlUploadSource {
    /** Positive bytes = data, empty = EOF, null = abort. */
    fun read(maxLength: Int): ByteArray?
}

internal interface IosCurlNativeBridge {
    val isAvailable: Boolean
    val supportsHttp3: Boolean

    suspend fun execute(request: IosCurlNativeRequest): CurlNativeResponse

    suspend fun downloadStream(
        request: IosCurlNativeRequest,
        onResponseStart: (Long, String) -> Unit,
        onChunk: (ByteArray) -> Unit
    ): CurlNativeResponse

    suspend fun uploadStream(
        request: IosCurlNativeRequest,
        source: IosCurlUploadSource
    ): CurlNativeResponse

    fun cancel(requestId: Int)
}

internal data class IosCurlOptionalApiDiagnostics(
    val bufferedBodyIdleTimeoutSetterAvailable: Boolean,
    val maxBufferedResponseBytesSetterAvailable: Boolean,
    val transferFactsAvailable: Boolean
)

internal data class IosCurlDiagnosticResponse(
    val response: CurlNativeResponse,
    val optionalApi: IosCurlOptionalApiDiagnostics
)

@OptIn(ExperimentalForeignApi::class)
internal object IosCurlCInteropBridge : IosCurlNativeBridge {
    override val isAvailable: Boolean
        get() = CurlWrapperAbiVersion() == CURL_WRAPPER_ABI_VERSION
    override val supportsHttp3: Boolean
        get() = CurlSupportsHttp3() != 0

    override suspend fun execute(request: IosCurlNativeRequest): CurlNativeResponse =
        if (IosCurlMultiEngines.isApiAvailable()) {
            executeBufferedMulti(request)
        } else {
            executeWithOptionalApiDiagnostics(request).response
        }

    private suspend fun executeBufferedMulti(
        request: IosCurlNativeRequest
    ): CurlNativeResponse = suspendCancellableCoroutine { continuation ->
        val engine = IosCurlMultiEngines.engine(request.http3Enabled)
        if (engine == null) {
            continuation.resume(unavailable("iOS curl multi engine creation failed"))
            return@suspendCancellableCoroutine
        }
        val handle = CreateCurlClient("NetworkKMM-iOS-multi-${request.requestId}")
        if (handle == null) {
            continuation.resume(unavailable("iOS curl failed to create native client"))
            return@suspendCancellableCoroutine
        }
        val terminalOnce = atomic(false)
        lateinit var context: IosCurlAsyncContext
        fun cleanupAndResume(response: CurlNativeResponse) {
            if (!terminalOnce.compareAndSet(expect = false, update = true)) return
            var removed = false
            try {
                response.elapse.protocol = GetCurlNegotiatedProtocol(handle)
                    ?.toKString()?.takeIf { it != "unknown" }
                response.elapse.applyCurlTransferFacts(readCurlTransferFacts(handle))
                readCurlMultiFacts(handle)?.let { facts ->
                    response.elapse.curlEnqueueToNativeStartElapsedMs = facts.first.toDouble()
                    response.elapse.curlMultiOwnerThreadObserved = facts.second
                }
                IosCurlHandleRegistry.remove(request.requestId, handle)
                removed = true
                if (continuation.isActive) continuation.resume(response)
            } catch (throwable: Throwable) {
                IosCurlHandleRegistry.remove(request.requestId, handle)
                removed = true
                if (continuation.isActive) {
                    continuation.resume(unavailable(
                        throwable.message ?: "iOS curl async terminal failed"))
                }
            } finally {
                if (!removed) IosCurlHandleRegistry.remove(request.requestId, handle)
                context.dispose()
                DeleteCurlClient(handle)
            }
        }
        context = IosCurlAsyncContext(::cleanupAndResume)
        try {
            if (SetCurlHttp3Enabled(handle, if (request.http3Enabled) 1 else 0) == 0) {
                cleanupAndResume(unavailable("HTTP/3 requested but iOS curl backend is unavailable"))
                return@suspendCancellableCoroutine
            }
            SetCurlCaInfo(handle, request.caInfoPath)
            SetCurlProxy(handle, request.proxyUrl)
            NetworkKmmSetCurlBufferedBodyIdleTimeoutMsIfAvailable(
                handle, request.bufferedBodyIdleTimeoutMillis)
            NetworkKmmSetCurlMaxBufferedResponseBytesIfAvailable(
                handle, request.maxBufferedResponseBytes)
            request.resolveEntry?.let { entry ->
                if (SetCurlResolve(handle, entry) == 0) {
                    cleanupAndResume(unavailable("iOS curl failed to apply resolve entry"))
                    return@suspendCancellableCoroutine
                }
            }
            if (!IosCurlHandleRegistry.publish(request.requestId, handle, engine)) {
                cleanupAndResume(unavailable("iOS curl request id already active"))
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { IosCurlHandleRegistry.cancel(request.requestId) }
            if (request.cancellationSignal.isCancelled()) {
                IosCurlHandleRegistry.cancel(request.requestId)
            }
            val accepted = withCurlRequest(request) { nativeRequest ->
                memScoped {
                    val callback = alloc<CurlCallback> {
                        callbackRef = context.stableRef.asCPointer()
                        callback = staticCFunction(::iosCurlAsyncComplete)
                    }
                    NetworkKmmSubmitBufferedRequestV27IfAvailable(
                        engine, request.requestId.toLong(), handle, nativeRequest,
                        sizeOf<CurlRequest>().convert(), CURL_WRAPPER_ABI_VERSION, callback.ptr) != 0
                }
            }
            if (!accepted) {
                cleanupAndResume(unavailable("iOS curl multi submit rejected"))
            } else if (!continuation.isActive || request.cancellationSignal.isCancelled()) {
                IosCurlHandleRegistry.cancel(request.requestId)
            }
        } catch (throwable: Throwable) {
            cleanupAndResume(unavailable(
                throwable.message ?: "iOS curl multi invocation failed"))
        }
    }

    internal suspend fun executeWithOptionalApiDiagnostics(
        request: IosCurlNativeRequest
    ): IosCurlDiagnosticResponse = performWithDiagnostics(request) { handle, callbackContext ->
        withCurlRequest(request) { nativeRequest ->
            memScoped {
                val callback = alloc<CurlCallback> {
                    callbackRef = callbackContext.stableRef.asCPointer()
                    callback = staticCFunction(::iosCurlComplete)
                }
                check(
                    StartRequestV27(
                        handle,
                        nativeRequest,
                        sizeOf<CurlRequest>().convert(),
                        CURL_WRAPPER_ABI_VERSION,
                        callback.ptr
                    ) != 0
                ) { "iOS curl request ABI rejected" }
            }
        }
    }

    override suspend fun downloadStream(
        request: IosCurlNativeRequest,
        onResponseStart: (Long, String) -> Unit,
        onChunk: (ByteArray) -> Unit
    ): CurlNativeResponse = perform(
        request = request,
        onResponseStart = onResponseStart,
        onChunk = onChunk
    ) { handle, callbackContext ->
        withCurlRequest(request) { nativeRequest ->
            memScoped {
                val callback = alloc<CurlStreamCallback>()
                callback.callbackRef = callbackContext.stableRef.asCPointer()
                callback.onResponseStart = staticCFunction(::iosCurlResponseStart)
                callback.onChunk = staticCFunction(::iosCurlChunk)
                callback.onComplete = staticCFunction(::iosCurlComplete)
                check(
                    StartStreamRequestV27(
                        handle,
                        nativeRequest,
                        sizeOf<CurlRequest>().convert(),
                        CURL_WRAPPER_ABI_VERSION,
                        callback.ptr
                    ) != 0
                ) { "iOS curl stream request ABI rejected" }
            }
        }
    }

    override suspend fun uploadStream(
        request: IosCurlNativeRequest,
        source: IosCurlUploadSource
    ): CurlNativeResponse = perform(request, uploadSource = source) { handle, callbackContext ->
        withCurlRequest(request) { nativeRequest ->
            memScoped {
                val callback = alloc<CurlCallback> {
                    callbackRef = callbackContext.stableRef.asCPointer()
                    callback = staticCFunction(::iosCurlComplete)
                }
                val upload = alloc<CurlUploadSource> {
                    readRef = callbackContext.stableRef.asCPointer()
                    readChunk = staticCFunction(::iosCurlReadUploadChunk)
                    totalLength = request.uploadContentLength ?: -1L
                }
                check(
                    StartUploadRequestV27(
                        handle,
                        nativeRequest,
                        sizeOf<CurlRequest>().convert(),
                        CURL_WRAPPER_ABI_VERSION,
                        upload.ptr,
                        callback.ptr
                    ) != 0
                ) { "iOS curl upload request ABI rejected" }
            }
        }
    }

    override fun cancel(requestId: Int) {
        IosCurlHandleRegistry.cancel(requestId)
    }

    private suspend fun perform(
        request: IosCurlNativeRequest,
        onResponseStart: ((Long, String) -> Unit)? = null,
        onChunk: ((ByteArray) -> Unit)? = null,
        uploadSource: IosCurlUploadSource? = null,
        start: (COpaquePointer, IosCurlCallbackContext) -> Unit
    ): CurlNativeResponse = performWithDiagnostics(
        request = request,
        onResponseStart = onResponseStart,
        onChunk = onChunk,
        uploadSource = uploadSource,
        start = start
    ).response

    private suspend fun performWithDiagnostics(
        request: IosCurlNativeRequest,
        onResponseStart: ((Long, String) -> Unit)? = null,
        onChunk: ((ByteArray) -> Unit)? = null,
        uploadSource: IosCurlUploadSource? = null,
        start: (COpaquePointer, IosCurlCallbackContext) -> Unit
    ): IosCurlDiagnosticResponse = withContext(IosCurlExecutionDispatchers.perform) {
        fun unavailableWithDiagnostics(message: String) = IosCurlDiagnosticResponse(
            response = unavailable(message),
            optionalApi = IosCurlOptionalApiDiagnostics(
                bufferedBodyIdleTimeoutSetterAvailable = false,
                maxBufferedResponseBytesSetterAvailable = false,
                transferFactsAvailable = false
            )
        )
        if (!isAvailable) {
            return@withContext unavailableWithDiagnostics("iOS curl wrapper ABI mismatch")
        }
        val handle = CreateCurlClient("NetworkKMM-iOS-${request.requestId}")
            ?: return@withContext unavailableWithDiagnostics("iOS curl failed to create native client")
        val callbackContext = IosCurlCallbackContext(
            request = request,
            onResponseStart = onResponseStart,
            onChunk = onChunk,
            uploadSource = uploadSource
        )
        try {
            if (SetCurlHttp3Enabled(handle, if (request.http3Enabled) 1 else 0) == 0) {
                return@withContext unavailableWithDiagnostics(
                    "HTTP/3 requested but iOS curl backend is unavailable"
                )
            }
            SetCurlCaInfo(handle, request.caInfoPath)
            SetCurlProxy(handle, request.proxyUrl)
            val bufferedBodyIdleTimeoutSetterAvailable =
                NetworkKmmSetCurlBufferedBodyIdleTimeoutMsIfAvailable(
                    handle,
                    request.bufferedBodyIdleTimeoutMillis
                ) != 0
            val maxBufferedResponseBytesSetterAvailable =
                NetworkKmmSetCurlMaxBufferedResponseBytesIfAvailable(
                    handle,
                    request.maxBufferedResponseBytes
                ) != 0
            request.resolveEntry?.let { entry ->
                if (SetCurlResolve(handle, entry) == 0) {
                    return@withContext unavailableWithDiagnostics(
                        "iOS curl failed to apply resolve entry"
                    )
                }
            }
            if (!IosCurlHandleRegistry.publish(request.requestId, handle)) {
                return@withContext unavailableWithDiagnostics(
                    "iOS curl request id already active"
                )
            }
            if (request.cancellationSignal.isCancelled()) {
                cancelNative(handle)
            }
            runCatching { start(handle, callbackContext) }
                .getOrElse { throwable ->
                    return@withContext unavailableWithDiagnostics(
                        throwable.message ?: "iOS curl cinterop invocation failed"
                    )
                }
            callbackContext.failureMessage()?.let { message ->
                return@withContext unavailableWithDiagnostics(message)
            }
            val response = callbackContext.response
                ?: return@withContext unavailableWithDiagnostics(
                    "iOS curl returned without a completion callback"
                )
            response.elapse.protocol = GetCurlNegotiatedProtocol(handle)
                ?.toKString()
                ?.takeIf { it != "unknown" }
            val transferFacts = readCurlTransferFacts(handle)
            response.elapse.applyCurlTransferFacts(transferFacts)
            IosCurlDiagnosticResponse(
                response = response,
                optionalApi = IosCurlOptionalApiDiagnostics(
                    bufferedBodyIdleTimeoutSetterAvailable =
                        bufferedBodyIdleTimeoutSetterAvailable,
                    maxBufferedResponseBytesSetterAvailable =
                        maxBufferedResponseBytesSetterAvailable,
                    transferFactsAvailable = transferFacts != null
                )
            )
        } finally {
            IosCurlHandleRegistry.remove(request.requestId, handle)
            callbackContext.dispose()
            DeleteCurlClient(handle)
        }
    }

    private fun unavailable(message: String) = CurlNativeResponse(code = -1, errorMsg = message)
}

@OptIn(ExperimentalForeignApi::class)
private fun readCurlTransferFacts(handle: COpaquePointer): CurlTransferFactsV1? = memScoped {
    val native = alloc<CurlTransferInfoV1>()
    if (NetworkKmmGetCurlTransferInfoV1IfAvailable(
            handle,
            native.ptr,
            sizeOf<CurlTransferInfoV1>().convert(),
            CURL_TRANSFER_INFO_ABI_VERSION
        ) == 0) {
        return@memScoped null
    }
    CurlTransferFactsV1(
        finalHeadersObserved = native.finalHeadersObserved != 0,
        firstBodyObserved = native.firstBodyObserved != 0,
        bodyProgressObserved = native.bodyProgressObserved != 0,
        finalHeadersElapsedMs = native.finalHeadersElapsedMs,
        firstBodyElapsedMs = native.firstBodyElapsedMs,
        lastBodyProgressElapsedMs = native.lastBodyProgressElapsedMs,
        bodyBytes = native.bodyBytes,
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun readCurlMultiFacts(handle: COpaquePointer): Pair<Long, Boolean>? = memScoped {
    val native = alloc<CurlMultiInfoV1>()
    if (NetworkKmmGetCurlMultiInfoV1IfAvailable(
            handle, native.ptr, sizeOf<CurlMultiInfoV1>().convert(),
            CURL_MULTI_INFO_ABI_VERSION) == 0) return@memScoped null
    native.enqueueToNativeStartElapsedMs to (native.ownerThreadObserved != 0)
}

@OptIn(ExperimentalForeignApi::class)
private object IosCurlMultiEngines : SynchronizedObject() {
    private var capability: Boolean? = null
    private var defaultEngine: COpaquePointer? = null
    private var http3Engine: COpaquePointer? = null

    fun isApiAvailable(): Boolean = synchronized(this) {
        capability ?: (NetworkKmmCurlMultiApiAvailable() != 0).also { capability = it }
    }

    fun engine(http3: Boolean): COpaquePointer? = synchronized(this) {
        val available = capability ?: (NetworkKmmCurlMultiApiAvailable() != 0)
            .also { capability = it }
        if (!available) return@synchronized null
        if (http3) {
            http3Engine ?: NetworkKmmCreateCurlMultiEngineIfAvailable("NetworkKMM-iOS-h3")
                ?.also { http3Engine = it }
        } else {
            defaultEngine ?: NetworkKmmCreateCurlMultiEngineIfAvailable("NetworkKMM-iOS-default")
                ?.also { defaultEngine = it }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosCurlAsyncContext(
    private val terminal: (CurlNativeResponse) -> Unit
) {
    val stableRef: StableRef<IosCurlAsyncContext> = StableRef.create(this)
    fun complete(response: CPointer<CurlResponse>?) {
        runCatching { response.toCurlNativeResponse() }
            .fold(terminal, { terminal(CurlNativeResponse(
                code = -1, errorMsg = it.message ?: "iOS curl callback failed")) })
    }
    fun dispose() = stableRef.dispose()
}

@OptIn(ExperimentalForeignApi::class)
private class IosCurlCallbackContext(
    val request: IosCurlNativeRequest,
    private val onResponseStart: ((Long, String) -> Unit)?,
    private val onChunk: ((ByteArray) -> Unit)?,
    private val uploadSource: IosCurlUploadSource?
) {
    val stableRef: StableRef<IosCurlCallbackContext> = StableRef.create(this)
    var response: CurlNativeResponse? = null
        private set
    private val callbackFailure = atomic<Throwable?>(null)

    fun onResponseStart(httpCode: Long, headers: String) {
        if (shouldSuppressBusinessCallbacks()) return
        runCatching { onResponseStart?.invoke(httpCode, headers) }
            .onFailure(::recordFailure)
    }

    fun onChunk(chunk: ByteArray) {
        if (shouldSuppressBusinessCallbacks()) return
        runCatching { onChunk?.invoke(chunk) }
            .onFailure(::recordFailure)
    }

    fun readUploadChunk(maxLength: Int): ByteArray? {
        if (shouldSuppressBusinessCallbacks()) return null
        return runCatching { uploadSource?.read(maxLength) }
            .onFailure(::recordFailure)
            .getOrNull()
    }

    fun onComplete(nativeResponse: CPointer<CurlResponse>?) {
        response = nativeResponse.toCurlNativeResponse()
    }

    fun failureMessage(): String? = callbackFailure.value?.let { failure ->
        "iOS curl callback failed: ${failure.message ?: failure::class.simpleName.orEmpty()}"
    }

    fun onTrampolineFailure(throwable: Throwable) {
        recordFailure(throwable)
    }

    fun dispose() {
        stableRef.dispose()
    }

    private fun shouldSuppressBusinessCallbacks(): Boolean =
        request.cancellationSignal.isCancelled() || callbackFailure.value != null

    private fun recordFailure(throwable: Throwable) {
        if (callbackFailure.compareAndSet(null, throwable)) {
            request.cancel()
            IosCurlHandleRegistry.cancel(request.requestId)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private object IosCurlHandleRegistry : SynchronizedObject() {
    private data class Target(val client: COpaquePointer, val engine: COpaquePointer?)
    private val handles = mutableMapOf<Int, Target>()

    fun publish(
        requestId: Int,
        handle: COpaquePointer,
        engine: COpaquePointer? = null
    ): Boolean = synchronized(this) {
        if (handles.containsKey(requestId)) false
        else {
            handles[requestId] = Target(handle, engine)
            true
        }
    }

    fun remove(requestId: Int, handle: COpaquePointer) {
        synchronized(this) {
            if (handles[requestId]?.client == handle) handles.remove(requestId)
        }
    }

    fun cancel(requestId: Int) {
        val target = synchronized(this) { handles[requestId] }
        target?.let {
            if (it.engine != null) {
                NetworkKmmCancelCurlMultiRequestIfAvailable(it.engine, requestId.toLong())
            } else {
                cancelNative(it.client)
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun iosCurlAsyncComplete(
    callbackRef: COpaquePointer?,
    response: CPointer<CurlResponse>?
) {
    try {
        callbackRef?.asStableRef<IosCurlAsyncContext>()?.get()?.complete(response)
    } catch (_: Throwable) {
        // Async context owns classified terminal + cleanup; never unwind into C.
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun iosCurlComplete(callbackRef: COpaquePointer?, response: CPointer<CurlResponse>?) {
    try {
        callbackRef?.asStableRef<IosCurlCallbackContext>()?.get()?.onComplete(response)
    } catch (throwable: Throwable) {
        recordIosTrampolineFailure(callbackRef, throwable)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun iosCurlResponseStart(
    callbackRef: COpaquePointer?,
    httpCode: Long,
    headers: CPointer<ByteVar>?,
    headerLength: Int
) {
    try {
        callbackRef?.asStableRef<IosCurlCallbackContext>()?.get()?.onResponseStart(
            httpCode = httpCode,
            headers = headers.readUtf8(headerLength)
        )
    } catch (throwable: Throwable) {
        recordIosTrampolineFailure(callbackRef, throwable)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun iosCurlChunk(callbackRef: COpaquePointer?, data: CPointer<ByteVar>?, length: Int) {
    try {
        if (length <= 0 || data == null) return
        callbackRef?.asStableRef<IosCurlCallbackContext>()?.get()?.onChunk(data.readBytes(length))
    } catch (throwable: Throwable) {
        recordIosTrampolineFailure(callbackRef, throwable)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun iosCurlReadUploadChunk(
    callbackRef: COpaquePointer?,
    buffer: CPointer<ByteVar>?,
    maxLength: Int
): Int {
    return try {
        if (buffer == null || maxLength <= 0) return 0
        val context = callbackRef?.asStableRef<IosCurlCallbackContext>()?.get() ?: return -1
        val bytes = context.readUploadChunk(maxLength) ?: return -1
        if (bytes.isEmpty()) return 0
        val count = minOf(bytes.size, maxLength)
        for (index in 0 until count) {
            buffer[index] = bytes[index]
        }
        count
    } catch (throwable: Throwable) {
        recordIosTrampolineFailure(callbackRef, throwable)
        -1
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun recordIosTrampolineFailure(callbackRef: COpaquePointer?, throwable: Throwable) {
    try {
        callbackRef?.asStableRef<IosCurlCallbackContext>()?.get()?.onTrampolineFailure(throwable)
    } catch (_: Throwable) {
        // A corrupt/expired callback ref cannot be diagnosed safely, but no
        // Kotlin exception may cross the C callback boundary.
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun <T> withCurlRequest(
    request: IosCurlNativeRequest,
    block: MemScope.(CPointer<CurlRequest>) -> T
): T = memScoped {
    val entries = request.headers.entries.toList()
    val pairs = entries.takeIf { it.isNotEmpty() }?.let { allocArray<StringPair>(it.size) }
    entries.forEachIndexed { index, entry ->
        val pair = pairs!![index]
        pair.first = entry.key.cstr.getPointer(this)
        pair.second = entry.value.cstr.getPointer(this)
    }
    val dictionary = alloc<StringDic> {
        stringPairs = pairs
        size = entries.size
    }
    val nativeRequest = alloc<CurlRequest> {
        url = request.url.cstr.getPointer(this@memScoped)
        method = request.method.cstr.getPointer(this@memScoped)
        headers = dictionary.ptr
        timeout = request.timeoutMillis
        streamConnectTimeoutMs = request.streamConnectTimeoutMillis
        streamResponseHeadersTimeoutMs = request.streamResponseHeadersTimeoutMillis
        streamIdleTimeoutMs = request.streamIdleTimeoutMillis
        streamWholeTimeoutMs = request.streamWholeTimeoutMillis
        postBodyLen = request.body?.size ?: 0
        postBody = null
    }
    val body = request.body
    if (body == null || body.isEmpty()) {
        block(nativeRequest.ptr)
    } else {
        body.usePinned { pinned ->
            nativeRequest.postBody = pinned.addressOf(0)
            block(nativeRequest.ptr)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun CPointer<CurlResponse>?.toCurlNativeResponse(): CurlNativeResponse {
    val native = this?.pointed ?: return CurlNativeResponse(
        code = -1,
        errorMsg = "iOS curl returned a null response"
    )
    val timing = native.elapse
    return CurlResponseCodec.decode(
        CurlResponseFields(
            code = native.code,
            httpCode = native.httpCode.toInt(),
            errorMsg = native.errorMsg.readUtf8(native.errorMsgLen),
            errorMsgLen = native.errorMsgLen,
            headers = native.headers.readUtf8(native.headerLen),
            headerLen = native.headerLen,
            redirectUrl = native.redirectUrl?.toKString().orEmpty(),
            data = native.data?.takeIf { native.dataLen > 0 }?.readBytes(native.dataLen),
            dataLen = native.dataLen,
            elapse = VBTransportElapseStatistics(
                nameLookupTimeMs = timing.nameLookupTimeMs,
                connectTimeMs = timing.connectTimeMs,
                sslCostTimeMs = timing.sslCostTimeMs,
                preTransferTime = timing.preTransferTime,
                startTransferTimeMs = timing.startTransferTimeMs,
                redirectTime = timing.redirectTime,
                recvTime = timing.recvTime,
                totalTimeMs = timing.totalTimeMs
            )
        )
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun CPointer<ByteVar>?.readUtf8(length: Int): String =
    if (this == null || length <= 0) "" else readBytes(length).decodeToString()

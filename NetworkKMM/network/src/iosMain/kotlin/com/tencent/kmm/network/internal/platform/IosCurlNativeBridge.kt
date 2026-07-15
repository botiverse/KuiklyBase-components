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
import com.tencent.kmm.network.curl.native.Cancel as cancelNative
import com.tencent.kmm.network.curl.native.CreateCurlClient
import com.tencent.kmm.network.curl.native.CurlSupportsHttp3
import com.tencent.kmm.network.curl.native.CurlWrapperAbiVersion
import com.tencent.kmm.network.curl.native.CURL_WRAPPER_ABI_VERSION
import com.tencent.kmm.network.curl.native.CurlCallback
import com.tencent.kmm.network.curl.native.CurlRequest
import com.tencent.kmm.network.curl.native.CurlResponse
import com.tencent.kmm.network.curl.native.CurlStreamCallback
import com.tencent.kmm.network.curl.native.CurlUploadSource
import com.tencent.kmm.network.curl.native.DeleteCurlClient
import com.tencent.kmm.network.curl.native.GetCurlNegotiatedProtocol
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
import kotlinx.coroutines.withContext

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

@OptIn(ExperimentalForeignApi::class)
internal object IosCurlCInteropBridge : IosCurlNativeBridge {
    override val isAvailable: Boolean
        get() = CurlWrapperAbiVersion() == CURL_WRAPPER_ABI_VERSION
    override val supportsHttp3: Boolean
        get() = CurlSupportsHttp3() != 0

    override suspend fun execute(request: IosCurlNativeRequest): CurlNativeResponse =
        perform(request) { handle, callbackContext ->
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
    ): CurlNativeResponse = withContext(IosCurlExecutionDispatchers.perform) {
        if (!isAvailable) {
            return@withContext unavailable("iOS curl wrapper ABI mismatch")
        }
        val handle = CreateCurlClient("NetworkKMM-iOS-${request.requestId}")
            ?: return@withContext unavailable("iOS curl failed to create native client")
        val callbackContext = IosCurlCallbackContext(
            request = request,
            onResponseStart = onResponseStart,
            onChunk = onChunk,
            uploadSource = uploadSource
        )
        try {
            if (SetCurlHttp3Enabled(handle, if (request.http3Enabled) 1 else 0) == 0) {
                return@withContext unavailable(
                    "HTTP/3 requested but iOS curl backend is unavailable"
                )
            }
            SetCurlCaInfo(handle, request.caInfoPath)
            SetCurlProxy(handle, request.proxyUrl)
            request.resolveEntry?.let { entry ->
                if (SetCurlResolve(handle, entry) == 0) {
                    return@withContext unavailable("iOS curl failed to apply resolve entry")
                }
            }
            IosCurlHandleRegistry.publish(request.requestId, handle)
            if (request.cancellationSignal.isCancelled()) {
                cancelNative(handle)
            }
            runCatching { start(handle, callbackContext) }
                .getOrElse { throwable ->
                    return@withContext unavailable(
                        throwable.message ?: "iOS curl cinterop invocation failed"
                    )
                }
            callbackContext.failureMessage()?.let { message ->
                return@withContext unavailable(message)
            }
            val response = callbackContext.response
                ?: return@withContext unavailable("iOS curl returned without a completion callback")
            response.elapse.protocol = GetCurlNegotiatedProtocol(handle)
                ?.toKString()
                ?.takeIf { it != "unknown" }
            response
        } finally {
            IosCurlHandleRegistry.remove(request.requestId, handle)
            callbackContext.dispose()
            DeleteCurlClient(handle)
        }
    }

    private fun unavailable(message: String) = CurlNativeResponse(code = -1, errorMsg = message)
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
    private val handles = mutableMapOf<Int, COpaquePointer>()

    fun publish(requestId: Int, handle: COpaquePointer) {
        synchronized(this) { handles[requestId] = handle }
    }

    fun remove(requestId: Int, handle: COpaquePointer) {
        synchronized(this) {
            if (handles[requestId] == handle) handles.remove(requestId)
        }
    }

    fun cancel(requestId: Int) {
        synchronized(this) {
            handles[requestId]?.let(::cancelNative)
        }
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

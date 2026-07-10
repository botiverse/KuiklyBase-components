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
import com.tencent.kmm.network.export.VBTransportElapseStatistics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.jvm.JvmName

internal class AndroidCurlCancellationSignal {
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    fun isCancelled(): Boolean = cancelled.get()
}

internal data class AndroidCurlNativeRequest(
    val requestId: Int,
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val timeoutMillis: Long,
    val body: ByteArray? = null,
    val uploadContentLength: Long? = null,
    val caInfoPath: String,
    /** Empty string means explicit direct mode. */
    val proxyUrl: String,
    val cancellationSignal: AndroidCurlCancellationSignal = AndroidCurlCancellationSignal()
) {
    fun cancel() {
        cancellationSignal.cancel()
    }
}

internal fun interface AndroidCurlUploadSource {
    /** Positive bytes = data, empty = EOF, null = abort. */
    fun read(maxLength: Int): ByteArray?
}

internal interface AndroidCurlNativeBridge {
    val isAvailable: Boolean

    suspend fun execute(request: AndroidCurlNativeRequest): CurlNativeResponse

    suspend fun downloadStream(
        request: AndroidCurlNativeRequest,
        onResponseStart: (Long, String) -> Unit,
        onChunk: (ByteArray) -> Unit
    ): CurlNativeResponse

    suspend fun uploadStream(
        request: AndroidCurlNativeRequest,
        source: AndroidCurlUploadSource
    ): CurlNativeResponse

    fun cancel(requestId: Int)
}

internal object AndroidCurlJniBridge : AndroidCurlNativeBridge {
    private const val MODE_BUFFERED = 0
    private const val MODE_STREAM_DOWNLOAD = 1
    private const val MODE_STREAM_UPLOAD = 2

    private val loaded: Boolean by lazy {
        runCatching { System.loadLibrary("networkkmmcurl") }.isSuccess
    }

    override val isAvailable: Boolean
        get() = loaded

    override suspend fun execute(request: AndroidCurlNativeRequest): CurlNativeResponse =
        perform(request, MODE_BUFFERED, null, null, null)

    override suspend fun downloadStream(
        request: AndroidCurlNativeRequest,
        onResponseStart: (Long, String) -> Unit,
        onChunk: (ByteArray) -> Unit
    ): CurlNativeResponse = perform(request, MODE_STREAM_DOWNLOAD, onResponseStart, onChunk, null)

    override suspend fun uploadStream(
        request: AndroidCurlNativeRequest,
        source: AndroidCurlUploadSource
    ): CurlNativeResponse = perform(request, MODE_STREAM_UPLOAD, null, null, source)

    override fun cancel(requestId: Int) {
        if (loaded) {
            nativeCancel(requestId)
        }
    }

    private suspend fun perform(
        request: AndroidCurlNativeRequest,
        mode: Int,
        onResponseStart: ((Long, String) -> Unit)?,
        onChunk: ((ByteArray) -> Unit)?,
        uploadSource: AndroidCurlUploadSource?
    ): CurlNativeResponse = withContext(Dispatchers.IO) {
        if (!loaded) {
            return@withContext unavailableResponse()
        }
        var response: CurlNativeResponse? = null
        val callback = AndroidCurlJniCallback(
            onResponseStartBlock = onResponseStart,
            onChunkBlock = onChunk,
            uploadSource = uploadSource,
            cancellationSignal = request.cancellationSignal,
            onCompleteBlock = { response = it }
        )
        val names = request.headers.keys.toTypedArray()
        val values = request.headers.values.toTypedArray()
        runCatching {
            nativePerform(
                requestId = request.requestId,
                url = request.url,
                method = request.method,
                headerNames = names,
                headerValues = values,
                timeoutMillis = request.timeoutMillis,
                body = request.body,
                uploadContentLength = request.uploadContentLength ?: -1L,
                caInfoPath = request.caInfoPath,
                proxyUrl = request.proxyUrl,
                mode = mode,
                callback = callback
            )
        }.getOrElse { throwable ->
            return@withContext unavailableResponse(throwable.message ?: "Android curl JNI failed")
        }
        callback.failureMessage()?.let { message ->
            return@withContext unavailableResponse(message)
        }
        response ?: unavailableResponse("Android curl JNI returned without a completion callback")
    }

    private fun unavailableResponse(message: String = "Android curl native library is unavailable") =
        CurlNativeResponse(code = -1, errorMsg = message)

    @JvmStatic
    private external fun nativePerform(
        requestId: Int,
        url: String,
        method: String,
        headerNames: Array<String>,
        headerValues: Array<String>,
        timeoutMillis: Long,
        body: ByteArray?,
        uploadContentLength: Long,
        caInfoPath: String,
        proxyUrl: String,
        mode: Int,
        callback: AndroidCurlJniCallback
    )

    @JvmStatic
    private external fun nativeCancel(requestId: Int)
}

internal class AndroidCurlJniCallback(
    private val onResponseStartBlock: ((Long, String) -> Unit)?,
    private val onChunkBlock: ((ByteArray) -> Unit)?,
    private val uploadSource: AndroidCurlUploadSource?,
    private val cancellationSignal: AndroidCurlCancellationSignal,
    private val onCompleteBlock: (CurlNativeResponse) -> Unit
) {
    private val callbackFailure = AtomicReference<Throwable?>(null)

    @JvmName("onResponseStart")
    fun onResponseStart(httpCode: Long, headers: String) {
        runCatching { onResponseStartBlock?.invoke(httpCode, headers) }
            .onFailure(::recordFailure)
    }

    @JvmName("onChunk")
    fun onChunk(chunk: ByteArray) {
        runCatching { onChunkBlock?.invoke(chunk) }
            .onFailure(::recordFailure)
    }

    @JvmName("readUploadChunk")
    fun readUploadChunk(maxLength: Int): ByteArray? {
        return runCatching { uploadSource?.read(maxLength) }
            .onFailure(::recordFailure)
            .getOrNull()
    }

    @JvmName("isCancelled")
    fun isCancelled(): Boolean = cancellationSignal.isCancelled()

    fun failureMessage(): String? = callbackFailure.get()?.let { failure ->
        "Android curl callback failed: ${failure.message ?: failure::class.simpleName.orEmpty()}"
    }

    private fun recordFailure(throwable: Throwable) {
        if (callbackFailure.compareAndSet(null, throwable)) {
            cancellationSignal.cancel()
        }
    }

    @Suppress("LongParameterList")
    @JvmName("onComplete")
    fun onComplete(
        code: Int,
        httpCode: Long,
        errorMessage: String,
        headers: String,
        redirectUrl: String,
        data: ByteArray?,
        nameLookupTimeMs: Double,
        connectTimeMs: Double,
        sslCostTimeMs: Double,
        preTransferTimeMs: Double,
        startTransferTimeMs: Double,
        redirectTimeMs: Double,
        receiveTimeMs: Double,
        totalTimeMs: Double
    ) {
        onCompleteBlock(
            CurlResponseCodec.decode(
                CurlResponseFields(
                    code = code,
                    httpCode = httpCode.toInt(),
                    errorMsg = errorMessage,
                    errorMsgLen = errorMessage.length,
                    headers = headers,
                    headerLen = headers.length,
                    redirectUrl = redirectUrl,
                    data = data,
                    dataLen = data?.size ?: 0,
                    elapse = VBTransportElapseStatistics(
                        nameLookupTimeMs = nameLookupTimeMs,
                        connectTimeMs = connectTimeMs,
                        sslCostTimeMs = sslCostTimeMs,
                        preTransferTime = preTransferTimeMs,
                        startTransferTimeMs = startTransferTimeMs,
                        redirectTime = redirectTimeMs,
                        recvTime = receiveTimeMs,
                        totalTimeMs = totalTimeMs
                    )
                )
            )
        )
    }
}

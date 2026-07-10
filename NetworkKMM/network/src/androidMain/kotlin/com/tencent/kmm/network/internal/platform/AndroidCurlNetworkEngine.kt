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
import com.tencent.kmm.network.curl.parseCurlHeaders
import com.tencent.kmm.network.curl.toNetworkResponse
import com.tencent.kmm.network.export.NetworkByteStreamSink
import com.tencent.kmm.network.export.NetworkEngineCapabilities
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkResponseBody
import com.tencent.kmm.network.export.NetworkTransferProgress
import com.tencent.kmm.network.export.VBTransportAndroidCurl
import com.tencent.kmm.network.export.VBTransportMethod
import com.tencent.kmm.network.export.toBytes
import com.tencent.kmm.network.internal.VBPBRequestIdGenerator
import com.tencent.kmm.network.service.NetworkCall
import com.tencent.kmm.network.service.NetworkEngine
import com.tencent.kmm.network.service.NetworkUploadStreamSource
import com.tencent.kmm.network.service.networkUploadStreamSourceOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException

internal object AndroidCurlEngineProvider {
    @Volatile
    internal var testBridge: AndroidCurlNativeBridge? = null

    val nativeAvailable: Boolean
        get() = testBridge?.isAvailable ?: AndroidCurlJniBridge.isAvailable

    fun resolve(): NetworkEngine? {
        val bridge = testBridge ?: AndroidCurlJniBridge
        return bridge.takeIf { it.isAvailable }?.let(::AndroidCurlNetworkEngine)
    }
}

internal class AndroidCurlNetworkEngine(
    private val bridge: AndroidCurlNativeBridge,
    private val caInfoPathProvider: () -> String? = { VBTransportAndroidCurl.caInfoPath }
) : NetworkEngine {
    override val capabilities: NetworkEngineCapabilities = NetworkEngineCapabilities(
        requestBodyStreaming = true,
        responseBodyStreaming = true,
        multipartStreaming = true,
        uploadProgress = true,
        downloadProgress = true
    )

    override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse {
        networkUploadStreamSourceOrNull(request)?.let { source ->
            return executeUpload(request, call, source)
        }
        val body = request.body.toBytes(request.progress.uploadProgress)
        body.error?.let { error ->
            return NetworkResponse(
                request = request,
                statusCode = null,
                headers = emptyMap(),
                body = NetworkResponseBody(),
                error = error
            )
        }
        val requestId = VBPBRequestIdGenerator.getRequestId()
        val nativeRequest = request.toNativeRequest(
            requestId = requestId,
            body = body.bytes,
            contentType = body.contentType
        )
        call.addCancelHandler {
            nativeRequest.cancel()
            bridge.cancel(requestId)
        }
        if (call.isCancelled) {
            return cancelledResponse(request)
        }
        return bridge.execute(nativeRequest).toNetworkResponse(request)
    }

    override suspend fun downloadStream(
        request: NetworkRequest,
        call: NetworkCall,
        onResponseStart: (statusCode: Int, contentLength: Long?, headers: Map<String, List<String>>) -> Unit,
        onChunk: (ByteArray) -> Unit
    ): NetworkResponse {
        val requestId = VBPBRequestIdGenerator.getRequestId()
        val nativeRequest = request.toNativeRequest(requestId = requestId)
        var transferred = 0L
        var responseLength: Long? = null
        call.addCancelHandler {
            nativeRequest.cancel()
            bridge.cancel(requestId)
        }
        if (call.isCancelled) {
            return cancelledResponse(request)
        }
        val response = bridge.downloadStream(
            request = nativeRequest,
            onResponseStart = { httpCode, headerText ->
                val headers = parseCurlHeaders(headerText)
                responseLength = contentLength(headers)
                onResponseStart(httpCode.toInt(), responseLength, headers)
            },
            onChunk = { chunk ->
                transferred += chunk.size
                request.progress.downloadProgress?.invoke(
                    NetworkTransferProgress(transferred, responseLength)
                )
                onChunk(chunk)
            }
        )
        return response.toNetworkResponse(request)
    }

    private suspend fun executeUpload(
        request: NetworkRequest,
        call: NetworkCall,
        source: NetworkUploadStreamSource
    ): NetworkResponse = coroutineScope {
        if (request.method == VBTransportMethod.GET || request.method == VBTransportMethod.HEAD) {
            return@coroutineScope unsupportedStreamingRequestBodyResponse(request)
        }
        val requestId = VBPBRequestIdGenerator.getRequestId()
        val pullBridge = AndroidCurlUploadPullBridge()
        val nativeRequest = request.toNativeRequest(
            requestId = requestId,
            contentType = source.contentType,
            uploadContentLength = source.contentLength
        )
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
        call.addCancelHandler {
            nativeRequest.cancel()
            source.stream.cancel()
            pullBridge.closeFailure(CancellationException("Upload cancelled"))
            bridge.cancel(requestId)
        }
        if (call.isCancelled) {
            writer.cancel()
            return@coroutineScope cancelledResponse(request)
        }
        try {
            var sent = 0L
            val uploadSource = AndroidCurlUploadSource { maxLength ->
                pullBridge.read(maxLength)?.also { bytes ->
                    if (bytes.isNotEmpty()) {
                        sent += bytes.size
                        request.progress.uploadProgress?.invoke(
                            NetworkTransferProgress(sent, source.contentLength)
                        )
                    }
                }
            }
            bridge.uploadStream(nativeRequest, uploadSource)
                .toNetworkResponse(request)
        } finally {
            writer.cancel()
        }
    }

    private fun NetworkRequest.toNativeRequest(
        requestId: Int,
        body: ByteArray? = null,
        contentType: String? = null,
        uploadContentLength: Long? = null
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
            timeoutMillis = policy.timeoutMillis,
            body = body,
            uploadContentLength = uploadContentLength,
            caInfoPath = caInfoPathProvider()
        )
    }

    private fun cancelledResponse(request: NetworkRequest): NetworkResponse =
        com.tencent.kmm.network.curl.CurlNativeResponse(
            code = 42,
            errorMsg = "cancelled before Android curl native start"
        ).toNetworkResponse(request)

}

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

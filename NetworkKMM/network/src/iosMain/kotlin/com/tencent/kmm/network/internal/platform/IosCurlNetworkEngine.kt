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
import com.tencent.kmm.network.curl.contentLength
import com.tencent.kmm.network.curl.parseCurlHeaders
import com.tencent.kmm.network.curl.toNetworkResponse
import com.tencent.kmm.network.export.NetworkByteStreamSink
import com.tencent.kmm.network.export.NetworkEngineCapabilities
import com.tencent.kmm.network.export.NetworkError
import com.tencent.kmm.network.export.NetworkErrorKind
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkResponseBody
import com.tencent.kmm.network.export.NetworkTransferProgress
import com.tencent.kmm.network.export.VBTransportIosCurl
import com.tencent.kmm.network.export.VBTransportMethod
import com.tencent.kmm.network.export.toBytes
import com.tencent.kmm.network.internal.VBPBRequestIdGenerator
import com.tencent.kmm.network.service.NetworkCall
import com.tencent.kmm.network.service.NetworkEngine
import com.tencent.kmm.network.service.NetworkUploadStreamSource
import com.tencent.kmm.network.service.networkUploadStreamSourceOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException

internal object IosCurlEngineProvider {
    internal var testBridge: IosCurlNativeBridge? = null

    private fun configuredCaPath(): String? =
        VBTransportIosCurl.caInfoPath?.trim()?.takeIf(String::isNotEmpty)

    val nativeAvailable: Boolean
        get() = (testBridge ?: IosCurlCInteropBridge).isAvailable && configuredCaPath() != null

    fun resolve(): NetworkEngine? {
        val bridge = testBridge ?: IosCurlCInteropBridge
        val caPath = configuredCaPath() ?: return null
        return bridge.takeIf { it.isAvailable }?.let {
            IosCurlNetworkEngine(it) { caPath }
        }
    }
}

internal class IosCurlNetworkEngine(
    private val bridge: IosCurlNativeBridge,
    private val caInfoPathProvider: () -> String?
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
        val caPath = requiredCaPath() ?: return missingCaResponse(request)
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
            caPath = caPath,
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
        val caPath = requiredCaPath() ?: return missingCaResponse(request)
        val requestId = VBPBRequestIdGenerator.getRequestId()
        val nativeRequest = request.toNativeRequest(requestId = requestId, caPath = caPath)
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
            return@coroutineScope unsupportedStreamingMethodResponse(request)
        }
        val caPath = requiredCaPath() ?: return@coroutineScope missingCaResponse(request)
        val requestId = VBPBRequestIdGenerator.getRequestId()
        val pullBridge = IosCurlUploadPullBridge()
        val nativeRequest = request.toNativeRequest(
            requestId = requestId,
            caPath = caPath,
            contentType = source.contentType,
            uploadContentLength = source.contentLength
        )
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
            val uploadSource = IosCurlUploadSource { maxLength ->
                pullBridge.read(maxLength)?.also { bytes ->
                    if (bytes.isNotEmpty()) {
                        sent += bytes.size
                        request.progress.uploadProgress?.invoke(
                            NetworkTransferProgress(sent, source.contentLength)
                        )
                    }
                }
            }
            bridge.uploadStream(nativeRequest, uploadSource).toNetworkResponse(request)
        } finally {
            writer.cancel()
        }
    }

    private fun NetworkRequest.toNativeRequest(
        requestId: Int,
        caPath: String,
        body: ByteArray? = null,
        contentType: String? = null,
        uploadContentLength: Long? = null
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
            timeoutMillis = policy.timeoutMillis,
            body = body,
            uploadContentLength = uploadContentLength,
            caInfoPath = caPath
        )
    }

    private fun requiredCaPath(): String? =
        caInfoPathProvider()?.trim()?.takeIf(String::isNotEmpty)

    private fun missingCaResponse(request: NetworkRequest): NetworkResponse = NetworkResponse(
        request = request,
        statusCode = null,
        headers = emptyMap(),
        body = NetworkResponseBody(),
        error = NetworkError(
            kind = NetworkErrorKind.TLS,
            message = "iOS curl requires an app-owned CA bundle path."
        )
    )

    private fun cancelledResponse(request: NetworkRequest): NetworkResponse =
        CurlNativeResponse(
            code = 42,
            errorMsg = "cancelled before iOS curl native start"
        ).toNetworkResponse(request)

    private fun unsupportedStreamingMethodResponse(request: NetworkRequest): NetworkResponse =
        NetworkResponse(
            request = request,
            statusCode = null,
            headers = emptyMap(),
            body = NetworkResponseBody(),
            error = NetworkError(
                kind = NetworkErrorKind.UNKNOWN,
                message = "iOS curl does not stream request bodies for ${request.method}."
            )
        )
}

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

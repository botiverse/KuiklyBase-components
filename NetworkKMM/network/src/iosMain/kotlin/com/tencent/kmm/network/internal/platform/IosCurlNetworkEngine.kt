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
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkResponseBody
import com.tencent.kmm.network.export.NetworkTransferProgress
import com.tencent.kmm.network.export.VBTransportMethod
import com.tencent.kmm.network.export.toBytes
import com.tencent.kmm.network.internal.VBPBRequestIdGenerator
import com.tencent.kmm.network.service.NetworkCall
import com.tencent.kmm.network.service.NetworkEngine
import com.tencent.kmm.network.service.NetworkEngineAvailability
import com.tencent.kmm.network.service.NetworkUploadStreamSource
import com.tencent.kmm.network.service.curlNetworkEngineCapabilities
import com.tencent.kmm.network.service.curlRuntimeFailureResponse
import com.tencent.kmm.network.service.networkUploadStreamSourceOrNull
import com.tencent.kmm.network.service.prepareCurlRuntime
import com.tencent.kmm.network.service.preparedCurlCaInfoPath
import com.tencent.kmm.network.service.preparedCurlHttp3Enabled
import com.tencent.kmm.network.service.preparedCurlProxyUrl
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException

internal object IosCurlEngineProvider {
    internal var testBridge: IosCurlNativeBridge? = null

    val nativeLinked: Boolean
        get() = (testBridge ?: IosCurlCInteropBridge).isAvailable

    val nativeSupportsHttp3: Boolean
        get() = (testBridge ?: IosCurlCInteropBridge).supportsHttp3

    fun resolve(): NetworkEngine? {
        val bridge = testBridge ?: IosCurlCInteropBridge
        return bridge.takeIf { it.isAvailable }?.let(::IosCurlNetworkEngine)
    }
}

internal class IosCurlNetworkEngine(
    private val bridge: IosCurlNativeBridge
) : NetworkEngine {
    override val capabilities
        get() = curlNetworkEngineCapabilities(bridge.supportsHttp3)

    override fun availability(request: NetworkRequest): NetworkEngineAvailability =
        prepareCurlRuntime(request, nativeHttp3Supported = bridge.supportsHttp3)

    override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse {
        val availability = prepareCurlRuntime(request, nativeHttp3Supported = bridge.supportsHttp3)
        if (!availability.available) return curlRuntimeFailureResponse(request, availability)
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
        val availability = prepareCurlRuntime(request, nativeHttp3Supported = bridge.supportsHttp3)
        if (!availability.available) return curlRuntimeFailureResponse(request, availability)
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
        val availability = prepareCurlRuntime(request, nativeHttp3Supported = bridge.supportsHttp3)
        if (!availability.available) return@coroutineScope curlRuntimeFailureResponse(request, availability)
        if (request.method == VBTransportMethod.GET || request.method == VBTransportMethod.HEAD) {
            return@coroutineScope unsupportedStreamingRequestBodyResponse(request)
        }
        val requestId = VBPBRequestIdGenerator.getRequestId()
        val pullBridge = IosCurlUploadPullBridge()
        val nativeRequest = request.toNativeRequest(
            requestId = requestId,
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
            streamConnectTimeoutMillis = policy.streamTimeouts.connectTimeoutMillis,
            streamResponseHeadersTimeoutMillis = policy.streamTimeouts.responseHeadersTimeoutMillis,
            streamIdleTimeoutMillis = policy.streamTimeouts.interChunkIdleTimeoutMillis,
            streamWholeTimeoutMillis = policy.streamTimeouts.wholeTransferTimeoutMillis,
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
        CurlNativeResponse(
            code = 42,
            errorMsg = "cancelled before iOS curl native start"
        ).toNetworkResponse(request)

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

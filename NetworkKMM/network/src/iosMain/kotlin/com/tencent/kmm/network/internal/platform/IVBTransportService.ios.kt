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
import com.tencent.kmm.network.export.VBTransportBaseResponse
import com.tencent.kmm.network.export.VBTransportBytesRequest
import com.tencent.kmm.network.export.VBTransportBytesResponse
import com.tencent.kmm.network.export.VBTransportContentType
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
import com.tencent.kmm.network.internal.utils.describeTransportFailure
import com.tencent.kmm.network.internal.utils.transportConnectTimeoutMillis
import com.tencent.kmm.network.internal.utils.VBTransportCommonUtils.buildResponseAndCallback
import com.tencent.kmm.network.internal.utils.VBTransportCommonUtils.wrapBytesCallback
import com.tencent.kmm.network.internal.utils.VBTransportCommonUtils.wrapGetCallback
import com.tencent.kmm.network.internal.utils.VBTransportCommonUtils.wrapPostCallback
import com.tencent.kmm.network.internal.utils.VBTransportCommonUtils.wrapRequestCallback
import com.tencent.kmm.network.internal.utils.VBTransportCommonUtils.wrapStringCallback
import com.tencent.kmm.network.internal.utils.getHttpClient
import com.tencent.kmm.network.internal.utils.readKnownSize
import com.tencent.kmm.network.internal.utils.readUnknownSize
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
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
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val iOSTransportImpl: IVBTransportService = IOSTransportImpl()
private val scope = CoroutineScope(Dispatchers.IO)
private val taskMap: MutableMap<Int, Job> = mutableMapOf()
private const val TAG = "IOSTransportImpl"

// Upper bound per streamed chunk read off the response channel (fork #8).
private const val STREAM_CHUNK_BYTES = 16L * 1024L

class IOSTransportImpl : IVBTransportService {
    private fun startRequest(
        request: VBTransportBaseRequest,
        kmmCallback: (response: VBTransportBaseResponse) -> Unit
    ) {
        val job = scope.launch {
            try {
                val client = getHttpClient(request) as HttpClient
                val startMark = kotlin.time.TimeSource.Monotonic.markNow()
                val response = client.request(request.url) {
                    method = HttpMethod(request.method.name)
                    if (request.totalTimeout > 0) {
                        timeout {
                            requestTimeoutMillis = request.totalTimeout
                            // raft.9: connect gets its own short budget so a dead
                            // address family can't eat the whole request timeout
                            // (see TransportTimeouts.kt for the 3s rationale).
                            connectTimeoutMillis = transportConnectTimeoutMillis(request.totalTimeout)
                            socketTimeoutMillis = request.totalTimeout
                        }
                    }
                    constructRequest(request)
                }

                // raft.13 chain bracket 2/3: headers arrived — everything before
                // this line is connect/TLS/TTFB, everything after is body read.
                VBPBLog.i(
                    VBPBLog.HMTRANSPORTIMPL,
                    "${request.logTag} response received, id:${request.requestId}, " +
                        "status:${response.status.value}, contentLength:${response.contentLength() ?: -1}, " +
                        "elapsedMs:${startMark.elapsedNow().inWholeMilliseconds}"
                )

                var errMsg = ""
                val errorCode = when (response.status) {
                    HttpStatusCode.OK -> 0
                    else -> {
                        errMsg = response.status.description
                        response.status.value
                    }
                }

                val channel = response.bodyAsChannel()
                val contentLength = response.contentLength()
                val data = when (contentLength) {
                    null -> readUnknownSize(ByteReadChannelWrapper(channel))  // 动态扩容方案
                    else -> readKnownSize(ByteReadChannelWrapper(channel), contentLength)  // 预分配方案
                }
                // raft.9: a delivered size differing from the header length is
                // legal (see ByteReadChannelWrapper.readAvailable) but almost
                // always the thing you need to know when a payload looks wrong.
                if (contentLength != null && data.size.toLong() != contentLength) {
                    VBPBLog.e(
                        VBPBLog.HMTRANSPORTIMPL,
                        "${request.logTag} response body length mismatch, id:${request.requestId}, " +
                            "content-length:$contentLength, read:${data.size}, " +
                            "encoding:${response.headers["Content-Encoding"] ?: "-"}"
                    )
                }

                // raft.13 chain bracket 3/3: body fully read — a hang between
                // bracket 2 and here is a body-read stall, not a network wait.
                VBPBLog.i(
                    VBPBLog.HMTRANSPORTIMPL,
                    "${request.logTag} body read, id:${request.requestId}, bytes:${data.size}, " +
                        "totalElapsedMs:${startMark.elapsedNow().inWholeMilliseconds}"
                )

                buildResponseAndCallback(
                    taskMap,
                    errorCode,
                    errMsg,
                    response.headers.entries().associate { it.key to it.value },
                    data,
                    request,
                    kmmCallback
                )
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) {
                    taskMap.remove(request.requestId)
                    throw throwable
                }
                callbackFailure(request, throwable, kmmCallback)
            }
        }
        taskMap[request.requestId] = job
    }

    override fun sendBytesRequest(
        kmmBytesRequest: VBTransportBytesRequest,
        kmmBytesResponseCallback: (response: VBTransportBytesResponse) -> Unit
    ) {
        VBPBLog.i(VBPBLog.HMTRANSPORTIMPL, "${kmmBytesRequest.logTag} send bytes request, " +
                "id:${kmmBytesRequest.requestId}, url:${kmmBytesRequest.url}, " +
                "headerKeys:${kmmBytesRequest.header.keys}")
        startRequest(kmmBytesRequest, wrapBytesCallback(kmmBytesResponseCallback))
    }

    override fun sendStringRequest(
        kmmStringRequest: VBTransportStringRequest,
        kmmStringResponseCallback: (response: VBTransportStringResponse) -> Unit
    ) {
        VBPBLog.i(VBPBLog.HMTRANSPORTIMPL, "${kmmStringRequest.logTag} send string request, " +
                "id:${kmmStringRequest.requestId}, url:${kmmStringRequest.url}, " +
                "headerKeys:${kmmStringRequest.header.keys}")
        startRequest(kmmStringRequest, wrapStringCallback(kmmStringResponseCallback))
    }

    override fun post(
        kmmPostRequest: VBTransportPostRequest,
        kmmPostResponseCallback: (response: VBTransportPostResponse) -> Unit
    ) {
        VBPBLog.i(VBPBLog.HMTRANSPORTIMPL, "${kmmPostRequest.logTag} send post request, " +
                "id:${kmmPostRequest.requestId}, url:${kmmPostRequest.url}, " +
                "headerKeys:${kmmPostRequest.header.keys}")

        if (!kmmPostRequest.isDataInitialize()) {
            callbackFailure(
                kmmPostRequest,
                IllegalArgumentException("Data is not initialized"),
                wrapPostCallback(kmmPostResponseCallback)
            )
            return
        }

        startRequest(kmmPostRequest, wrapPostCallback(kmmPostResponseCallback))
    }

    override fun get(
        kmmGetRequest: VBTransportGetRequest,
        kmmGetResponseCallback: (response: VBTransportGetResponse) -> Unit
    ) {
        VBPBLog.i(VBPBLog.HMTRANSPORTIMPL, "${kmmGetRequest.logTag} send get request, " +
                "id:${kmmGetRequest.requestId}, url:${kmmGetRequest.url}, " +
                "headerKeys:${kmmGetRequest.header.keys}")
        startRequest(kmmGetRequest, wrapGetCallback(kmmGetResponseCallback))
    }

    override fun request(
        kmmRequest: VBTransportRequest,
        kmmResponseCallback: (response: VBTransportResponse) -> Unit
    ) {
        VBPBLog.i(VBPBLog.HMTRANSPORTIMPL, "${kmmRequest.logTag} send ${kmmRequest.method} request, " +
                "id:${kmmRequest.requestId}, url:${kmmRequest.url}, " +
                "headerKeys:${kmmRequest.header.keys}")
        startRequest(kmmRequest, wrapRequestCallback(kmmResponseCallback))
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
        VBPBLog.i(VBPBLog.HMTRANSPORTIMPL, "${kmmRequest.logTag} stream ${kmmRequest.method} request, " +
                "id:${kmmRequest.requestId}, url:${kmmRequest.url}")
        val job = scope.launch {
            try {
                val client = getHttpClient(kmmRequest) as HttpClient
                val streamStart = kotlin.time.TimeSource.Monotonic.markNow()
                val response = client.request(kmmRequest.url) {
                    method = HttpMethod(kmmRequest.method.name)
                    if (kmmRequest.totalTimeout > 0) {
                        timeout {
                            requestTimeoutMillis = kmmRequest.totalTimeout
                            // raft.9: connect gets its own short budget so a dead
                            // address family can't eat the whole request timeout
                            // (see TransportTimeouts.kt for the 3s rationale).
                            connectTimeoutMillis = transportConnectTimeoutMillis(kmmRequest.totalTimeout)
                            socketTimeoutMillis = kmmRequest.totalTimeout
                        }
                    }
                    constructRequest(kmmRequest)
                }

                var errMsg = ""
                val errorCode = when (response.status) {
                    HttpStatusCode.OK -> 0
                    else -> {
                        errMsg = response.status.description
                        response.status.value
                    }
                }

                // raft.13 stream bracket: headers arrived.
                VBPBLog.i(
                    VBPBLog.HMTRANSPORTIMPL,
                    "${kmmRequest.logTag} stream response received, id:${kmmRequest.requestId}, " +
                        "status:${response.status.value}, contentLength:${response.contentLength() ?: -1}, " +
                        "elapsedMs:${streamStart.elapsedNow().inWholeMilliseconds}"
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
                VBPBLog.i(
                    VBPBLog.HMTRANSPORTIMPL,
                    "${kmmRequest.logTag} stream complete, id:${kmmRequest.requestId}, bytes:$streamedBytes, " +
                        "totalElapsedMs:${streamStart.elapsedNow().inWholeMilliseconds}"
                )

                taskMap.remove(kmmRequest.requestId)
                onComplete(
                    VBTransportResponse().apply {
                        this.errorCode = errorCode
                        this.errorMessage = errMsg
                        this.header = response.headers.entries().associate { it.key to it.value }
                        this.data = null
                        this.request = kmmRequest
                    }
                )
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) {
                    taskMap.remove(kmmRequest.requestId)
                    throw throwable
                }
                taskMap.remove(kmmRequest.requestId)
                // raft.9: classified failure reason, same shape as callbackFailure.
                val describedFailure = describeTransportFailure(throwable)
                VBPBLog.e(TAG, "${kmmRequest.logTag} stream request failed, id:${kmmRequest.requestId}, error:$describedFailure")
                onComplete(
                    VBTransportResponse().apply {
                        this.errorCode = VBTransportResultCode.CODE_NETWORK_ERROR
                        this.errorMessage = describedFailure
                        this.data = null
                        this.request = kmmRequest
                    }
                )
            }
        }
        taskMap[kmmRequest.requestId] = job
    }

    private fun HttpRequestBuilder.constructRequest(kmmRequest: VBTransportBaseRequest) {
        // 设置 post body
        kmmRequest.bodyData()?.let {
            setBody(it)
        }

        kmmRequest.header.forEach {
            if (it.key.isNotEmpty() && it.value.isNotEmpty()) {
                header(it.key, it.value)
            }
        }
        val requestContentType = when {
            kmmRequest.header["Content-Type"]?.contains(
                VBTransportContentType.JSON.toString(),
                ignoreCase = true
            ) == true -> ContentType.Application.Json

            else -> ContentType.Application.OctetStream
        }
        contentType(requestContentType)
    }

    override fun cancel(requestId: Int) {
        VBPBLog.i(TAG, "requestID -> $requestId task cancel by user")
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
        VBPBLog.e(TAG, "${request.logTag} request failed, id:${request.requestId}, error:${errorMessage}")
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

actual fun getIVBTransportService(): IVBTransportService = iOSTransportImpl

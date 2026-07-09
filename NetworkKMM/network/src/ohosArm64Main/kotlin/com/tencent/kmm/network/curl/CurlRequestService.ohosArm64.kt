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
package com.tencent.kmm.network.curl

import com.tencent.kmm.network.export.IVBPBLog
import com.tencent.kmm.network.internal.VBPBLog
import com.tencent.kmm.network.internal.transportLaunch
import com.tencent.kmm.network.internal.utils.VBTransportCommonUtils.wrapBytesCallback
import com.tencent.kmm.network.internal.utils.VBTransportCommonUtils.wrapGetCallback
import com.tencent.kmm.network.internal.utils.VBTransportCommonUtils.wrapPostCallback
import com.tencent.kmm.network.internal.utils.VBTransportCommonUtils.wrapRequestCallback
import com.tencent.kmm.network.internal.utils.VBTransportCommonUtils.wrapStringCallback
import com.tencent.qqlive.kmm.native.libcurl.Cancel
import com.tencent.qqlive.kmm.native.libcurl.CreateCurlClient
import com.tencent.qqlive.kmm.native.libcurl.CurlCallback
import com.tencent.qqlive.kmm.native.libcurl.CurlRequest
import com.tencent.qqlive.kmm.native.libcurl.CurlResponse
import com.tencent.qqlive.kmm.native.libcurl.DeleteCurlClient
import com.tencent.qqlive.kmm.native.libcurl.CurlStreamCallback
import com.tencent.qqlive.kmm.native.libcurl.CurlUploadSource
import com.tencent.qqlive.kmm.native.libcurl.StartRequest
import com.tencent.qqlive.kmm.native.libcurl.StartStreamRequest
import com.tencent.qqlive.kmm.native.libcurl.StartUploadRequest
import com.tencent.qqlive.kmm.native.libcurl.StringDic
import com.tencent.qqlive.kmm.native.libcurl.StringPair
import com.tencent.qqlive.kmm.native.libcurl.setCurlLogImpl
import com.tencent.kmm.network.export.NetworkByteStreamSink
import com.tencent.kmm.network.export.VBTransportBaseRequest
import com.tencent.kmm.network.export.VBTransportBaseResponse
import com.tencent.kmm.network.export.VBTransportBytesRequest
import com.tencent.kmm.network.export.VBTransportBytesResponse
import com.tencent.kmm.network.export.VBTransportContentType
import com.tencent.kmm.network.export.VBTransportElapseStatistics
import com.tencent.kmm.network.export.VBTransportGetRequest
import com.tencent.kmm.network.export.VBTransportGetResponse
import com.tencent.kmm.network.export.VBTransportPostRequest
import com.tencent.kmm.network.export.VBTransportPostResponse
import com.tencent.kmm.network.export.VBTransportRequest
import com.tencent.kmm.network.export.VBTransportResponse
import com.tencent.kmm.network.export.VBTransportResultCode
import com.tencent.kmm.network.export.VBTransportStringRequest
import com.tencent.kmm.network.export.VBTransportStringResponse
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cValue
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.int8_tVar
import platform.posix.memcpy
import kotlin.reflect.KFunction1

private const val TAG = "CurlRequestServiceHM"

// raft.11: transfers slower than this log their curl phase breakdown.
private const val SLOW_TRANSFER_LOG_THRESHOLD_MS = 3_000.0

var curlLogNative: IVBPBLog? = null
private const val CURL_LOG_LEVEL_DEBUG = 0
private const val CURL_LOG_LEVEL_INFO = 1
private const val CURL_LOG_LEVEL_WARN = 2
private const val CURL_LOG_LEVEL_ERROR = 3

fun curlLogImpl(level: Int, tag: CPointer<ByteVar>?, content: CPointer<ByteVar>?): Int {
    when (level) {
        CURL_LOG_LEVEL_DEBUG -> {
            curlLogNative?.d(toSafeString(tag), "[Debug] " + toSafeString(content))
        }
        CURL_LOG_LEVEL_INFO -> {
            curlLogNative?.i(toSafeString(tag), "[Info] " + toSafeString(content))
        }
        CURL_LOG_LEVEL_WARN -> {
            curlLogNative?.e(toSafeString(tag), "[Warn] " + toSafeString(content))
        }
        CURL_LOG_LEVEL_ERROR -> {
            curlLogNative?.e(toSafeString(tag), "[Error] " + toSafeString(content))
        }
        else ->
            VBPBLog.i(toSafeString(tag), "Leve:$level: Content:${toSafeString(content)}")
    }
    return 1
}

private fun toSafeString(content: CPointer<ByteVar>?): String {
    return content?.toKString() ?: ""
}

// Curl 鸿蒙平台实现
object CurlRequestServiceHM : ICurlRequestService {

    private val taskMap: MutableMap<Int, CPointer<out CPointed>?> = mutableMapOf()

    override fun initNativeCurlLog(log: IVBPBLog) {
        curlLogNative = log
        memScoped {
            setCurlLogImpl(staticCFunction(::curlLogImpl))
        }
    }

    // String kmm -> c
    private fun toCSTR(string: String?, memScope: MemScope): CPointer<ByteVar> {
        return string?.cstr?.getPointer(memScope) ?: "".cstr.getPointer(memScope)
    }

    // Map kmm -> c
    private fun toStringDic(stringPair: Map<String, String>, memScope: MemScope): CPointer<StringDic> {
        val listSize = stringPair.size
        val stringPairList = stringPair.toList()
        val stringPairsNative = memScope.allocArray<StringPair>(stringPairList.size)
        for (i in stringPairList.indices) {
            val (key, value) = stringPairList[i]
            stringPairsNative[i].first = toCSTR(key, memScope)
            stringPairsNative[i].second = toCSTR(value, memScope)
        }
        return memScope.alloc<StringDic> {
            this.size = listSize
            this.stringPairs = stringPairsNative
        }.ptr
    }

    override fun cancel(requestId: Int) {
        logI("TaskManager remove task, id:${requestId}")
        taskMap[requestId]?.let {
            logI("TaskManager remove task, id:${requestId} handler:${it}")
            Cancel(it)
        }
    }

    private fun getCurlRequestParams(
        request: VBTransportBaseRequest,
        headers: StringDic,
        memScope: MemScope,
        logTag: String
    ): CValue<CurlRequest> {
        return cValue<CurlRequest> {
            this.url = toCSTR(request.url, memScope)
            this.method = toCSTR(request.method.name, memScope)
            this.headers = headers.ptr
            this.timeout = request.totalTimeout
            this.postBodyLen = 0
            when (val data = request.bodyData()) {
                is ByteArray -> {
                    logI("[$logTag] generate native ${request.method} curl params with bytearray data. " +
                            "size: ${data.size}, data: $data")
                    val buffer = nativeHeap.allocArray<int8_tVar>(data.size)
                    if (data.isNotEmpty()) {
                        data.usePinned { pinnedData ->
                            memcpy(buffer, pinnedData.addressOf(0), data.size.convert())
                        }
                    }
                    this.postBodyLen = data.size
                    this.postBody = buffer
                }

                null -> {
                    // no body
                }

                else -> {
                    val strData = data.toString()
                    this.postBodyLen = strData.encodeToByteArray().size
                    this.postBody = toCSTR(strData, memScope)
                    logI("[$logTag] generate native ${request.method} params with string data. " +
                            "size: ${postBodyLen}, data: $strData")
                }
            }
        }
    }

    override fun get(
        kmmGetRequest: VBTransportGetRequest,
        kmmGetResponseCallback: (response: VBTransportGetResponse) -> Unit,
        logTag: String
    ) {
        logI("[${logTag}] send get request, id: ${kmmGetRequest.requestId}, " +
                "url: ${kmmGetRequest.url}, header: ${kmmGetRequest.header}")
        startRequest(kmmGetRequest, wrapGetCallback(kmmGetResponseCallback), logTag)
    }

    override fun post(
        kmmPostRequest: VBTransportPostRequest,
        kmmPostResponseCallback: (response: VBTransportPostResponse) -> Unit,
        logTag: String
    ) {
        logI("[${logTag}] send post request, id: ${kmmPostRequest.requestId}, " +
                "url: ${kmmPostRequest.url}, header: ${kmmPostRequest.header}")
        startRequest(kmmPostRequest, wrapPostCallback(kmmPostResponseCallback), logTag)
    }

    override fun sendStringRequest(
        kmmStringRequest: VBTransportStringRequest,
        kmmStringResponseCallback: (response: VBTransportStringResponse) -> Unit,
        logTag: String
    ) {
        logI("[${logTag}] send string request, id: ${kmmStringRequest.requestId}, " +
                "url: ${kmmStringRequest.url}, header: ${kmmStringRequest.header}")
        startRequest(kmmStringRequest, wrapStringCallback(kmmStringResponseCallback), logTag)
    }

    override fun sendBytesRequest(
        kmmBytesRequest: VBTransportBytesRequest,
        kmmBytesResponseCallback: (response: VBTransportBytesResponse) -> Unit,
        logTag: String
    ) {
        logI("[${logTag}] send byte request, id: ${kmmBytesRequest.requestId}, " +
                "url: ${kmmBytesRequest.url}, header: ${kmmBytesRequest.header}")
        startRequest(kmmBytesRequest, wrapBytesCallback(kmmBytesResponseCallback), logTag)
    }

    override fun request(
        kmmRequest: VBTransportRequest,
        kmmResponseCallback: (response: VBTransportResponse) -> Unit,
        logTag: String
    ) {
        logI("[${logTag}] send ${kmmRequest.method} request, id: ${kmmRequest.requestId}, " +
                "url: ${kmmRequest.url}, header: ${kmmRequest.header}")
        startRequest(kmmRequest, wrapRequestCallback(kmmResponseCallback), logTag)
    }

    private fun buildPBRequestHeader(headers: Map<String, String>): Map<String, String> {
        // 如果没有显式指定 Content-Type,需要添加默认 Content-Type 为 application/octet-stream
        val tmpHeaders = headers.takeIf {
            it.keys.any { key -> key.equals("Content-Type", ignoreCase = true) }
        } ?: run {
            headers + mapOf("Content-Type" to "application/octet-stream")
        }
        return tmpHeaders
    }

    private fun buildRequestHeader(
        request: VBTransportBaseRequest
    ): Map<String, String> {
        // 如果没有显式指定 Content-Type,需要添加默认 Content-Type
        var tmpHeaders = request.header.takeIf {
            it.keys.any { key -> key.equals("Content-Type", ignoreCase = true) }
        } ?: run {
            val contentType = when (request) {
                is VBTransportStringRequest -> "application/json"
                is VBTransportBytesRequest,
                is VBTransportPostRequest,
                is VBTransportGetRequest -> "application/octet-stream"
                else -> "application/octet-stream"
            }
            request.header + mapOf("Content-Type" to contentType)
        }
        // 默认开启 gzip: libcurl wrapper 只有在请求带 Accept-Encoding: gzip 时才
        // 协商并解压 gzip,否则退回 identity 拿到未压缩响应。A/iOS 的引擎透明处理
        // gzip、不看这个 header,所以只在 OHOS 这一层补默认值即可。调用方若已显式
        // 指定 Accept-Encoding,则尊重其选择,不覆盖。
        if (tmpHeaders.keys.none { it.equals("Accept-Encoding", ignoreCase = true) }) {
            tmpHeaders = tmpHeaders + mapOf("Accept-Encoding" to "gzip")
        }
        request.header = tmpHeaders.toMutableMap()
        return tmpHeaders
    }

    private fun handleCurlNativeResponse(result: CurlResponse, logTag: String): CurlNativeResponse {
        val response = result.toCurlNativeResponse()
//        VBPBLog.i(logTag, "[$logTag] libcurl request callback, code:${response.code}," +
//                " errorMsg:${response.errorMsg}, dataSize:${result.dataLen}, " +
//                "nameLookupTimeMs:${response.elapse.nameLookupTimeMs}, connectTimeMs:" +
//                "${response.elapse.connectTimeMs}, sslCostTimeMs:${response.elapse.sslCostTimeMs}, " +
//                "preTransferTime:${response.elapse.preTransferTime}, " +
//                "startTransferTimeMs:${response.elapse.startTransferTimeMs}, " +
//                "redirectTime:${response.elapse.redirectTime}, recvTime:${response.elapse.recvTime}, " +
//                "totalTimeMs:${response.elapse.totalTimeMs}, redirectUrl:${response.redirectUrl}, " +
//                "header:${response.headers}")
        return response
    }

    private fun startRequest(
        request: VBTransportBaseRequest,
        responseCallback: (response: VBTransportBaseResponse) -> Unit,
        logTag: String
    ) {
        // Never let an exception escape to the transport scope: the caller
        // contract is "always exactly one callback" (upstream issue #31 —
        // escaped exceptions crashed the app when the network was down).
        try {
            startRequestUnsafe(request, responseCallback, logTag)
        } catch (throwable: Throwable) {
            logI("[$logTag] startRequest failed: ${throwable.message ?: throwable::class.simpleName}")
            val failure = CurlNativeResponse(
                code = VBTransportResultCode.CODE_NETWORK_ERROR,
                errorMsg = throwable.message ?: "native request failed"
            )
            buildResponseAndCallback(request, failure, responseCallback)
        }
    }

    private fun startRequestUnsafe(
        request: VBTransportBaseRequest,
        responseCallback: (response: VBTransportBaseResponse) -> Unit,
        logTag: String
    ) {
        memScoped {
            // header kmm-> c
            val headers = toStringDic(buildRequestHeader(request), memScope)
            val curlRequest = getCurlRequestParams(request, headers.pointed, memScope, logTag)
            var nativeResponse = CurlNativeResponse()
            val callback = object : ICurlCallback {
                override fun onResponse(result: CurlResponse) {
                    nativeResponse = handleCurlNativeResponse(result, logTag)
                }
            }

            // 使用 libcurl 发起请求
            val callbackWrapper = CurlCallbackWrapper(callback)
            val callbackWrapperPtr = callbackWrapper.getCallbackNativePtr()
            val handle = CreateCurlClient(logTag)
            taskMap[request.requestId] = handle
            logI("[$logTag] TaskManager add transport task, id:${request.requestId}, handle:${handle}")
            StartRequest(handle, curlRequest, callbackWrapperPtr)
            DeleteCurlClient(handle)
            taskMap.remove(request.requestId)

            callbackWrapper.release()

            // 构造回调信息
            buildResponseAndCallback(request, nativeResponse, responseCallback)
            logI("[$logTag] invoke callback.")
        }
    }

    private fun buildResponseAndCallback(
        request: VBTransportBaseRequest,
        nativeResponse: CurlNativeResponse,
        responseCallback: (response: VBTransportBaseResponse) -> Unit
    ) {
        if (request is VBTransportRequest) {
            // 自定义 method 请求回调
            val kmmResponse = VBTransportResponse().apply {
                updateResponse(request.logTag, nativeResponse, request, this)
            }
            responseCallback(kmmResponse)
        } else if (request is VBTransportGetRequest) {
            // Get 请求回调
            val kmmGetResponse = VBTransportGetResponse().apply {
                updateResponse(request.logTag, nativeResponse, request, this)
            }
            responseCallback(kmmGetResponse)
        } else if (request is VBTransportStringRequest) {
            // String 请求回调
            val kmmStringResponse = VBTransportStringResponse().apply {
                updateResponse(request.logTag, nativeResponse, request, this)
            }
            responseCallback(kmmStringResponse)
        } else if (request is VBTransportPostRequest) {
            // Post 请求回调
            val kmmPostResponse = VBTransportPostResponse().apply {
                updateResponse(request.logTag, nativeResponse, request, this)
            }
            responseCallback(kmmPostResponse)
        } else if ((request is VBTransportBytesRequest)) {
            // Bytes 请求回调
            val kmmBytesResponse = VBTransportBytesResponse().apply {
                updateResponse(request.logTag, nativeResponse, request, this)
            }
            responseCallback(kmmBytesResponse)
        }
    }

    // fork #8: 流式下载 — body 逐块通过 [onChunk] 交付, 不缓冲整包; 响应头就绪时
    // [onResponseStart], 结束时 [onComplete] (body-less)。异常不外逸 (upstream #31)。
    fun streamRequest(
        request: VBTransportRequest,
        onResponseStart: (statusCode: Int, headers: Map<String, List<String>>) -> Unit,
        onChunk: (chunk: ByteArray) -> Unit,
        onComplete: (response: VBTransportResponse) -> Unit,
        logTag: String
    ) {
        try {
            streamRequestUnsafe(request, onResponseStart, onChunk, onComplete, logTag)
        } catch (throwable: Throwable) {
            logI("[$logTag] streamRequest failed: ${throwable.message ?: throwable::class.simpleName}")
            onComplete(
                VBTransportResponse().apply {
                    this.request = request
                    this.errorCode = VBTransportResultCode.CODE_NETWORK_ERROR
                    this.errorMessage = throwable.message ?: "native stream request failed"
                }
            )
        }
    }

    private fun streamRequestUnsafe(
        request: VBTransportRequest,
        onResponseStart: (statusCode: Int, headers: Map<String, List<String>>) -> Unit,
        onChunk: (chunk: ByteArray) -> Unit,
        onComplete: (response: VBTransportResponse) -> Unit,
        logTag: String
    ) {
        memScoped {
            // Streaming decodes identity only, so do NOT default Accept-Encoding:
            // gzip here (chunks would arrive compressed and undecodable).
            val headers = toStringDic(buildStreamRequestHeader(request), memScope)
            val curlRequest = getCurlRequestParams(request, headers.pointed, memScope, logTag)
            val handler = object : IStreamHandler {
                override fun onResponseStart(httpCode: Long, headers: String) {
                    onResponseStart(httpCode.toInt(), convertHeaderMap(headers))
                }

                override fun onChunk(chunk: ByteArray) {
                    onChunk(chunk)
                }

                override fun onComplete(result: CurlResponse) {
                    val nativeResponse = handleCurlNativeResponse(result, logTag)
                    onComplete(
                        VBTransportResponse().apply {
                            updateResponse(request.logTag, nativeResponse, request, this)
                        }
                    )
                }
            }
            val wrapper = CurlStreamCallbackWrapper(handler)
            val handle = CreateCurlClient(logTag)
            taskMap[request.requestId] = handle
            logI("[$logTag] stream transport task add, id:${request.requestId}, handle:${handle}")
            StartStreamRequest(handle, curlRequest, wrapper.getCallbackNativePtr())
            DeleteCurlClient(handle)
            taskMap.remove(request.requestId)
            wrapper.release()
        }
    }

    // issue #8 slice 3: 流式上传 — 请求体经 [writeBody] 推入桥, curl perform 线程
    // 通过 READFUNCTION 逐块拉取, 不整包进内存; 响应仍整包缓冲 (与 request 一致)。
    // 异常不外逸 (upstream #31: 恰好一次回调)。
    fun uploadStreamRequest(
        request: VBTransportRequest,
        contentLength: Long?,
        writeBody: suspend (NetworkByteStreamSink) -> Unit,
        responseCallback: (response: VBTransportResponse) -> Unit,
        logTag: String
    ) {
        try {
            uploadStreamRequestUnsafe(request, contentLength, writeBody, responseCallback, logTag)
        } catch (throwable: Throwable) {
            logI("[$logTag] uploadStreamRequest failed: ${throwable.message ?: throwable::class.simpleName}")
            responseCallback(
                VBTransportResponse().apply {
                    this.request = request
                    this.errorCode = VBTransportResultCode.CODE_NETWORK_ERROR
                    this.errorMessage = throwable.message ?: "native upload stream request failed"
                }
            )
        }
    }

    private fun uploadStreamRequestUnsafe(
        request: VBTransportRequest,
        contentLength: Long?,
        writeBody: suspend (NetworkByteStreamSink) -> Unit,
        responseCallback: (response: VBTransportResponse) -> Unit,
        logTag: String
    ) {
        val bridge = UploadPullBridge()
        // The writer pushes writeBody's chunks into the bridge from its own
        // worker; the curl perform thread blocks in the READFUNCTION pulling
        // them out. Dispatchers.IO keeps the (blocking) producer off the
        // Default pool the perform coroutine already occupies.
        val writerJob = uploadWriterScope.transportLaunch {
            try {
                writeBody(bridge.sink)
                bridge.closeSuccess()
            } catch (throwable: Throwable) {
                logI("[$logTag] upload writeBody failed: ${throwable.message ?: throwable::class.simpleName}")
                bridge.closeFailure(throwable)
            }
        }
        try {
            memScoped {
                val headers = toStringDic(buildRequestHeader(request), memScope)
                val curlRequest = getCurlRequestParams(request, headers.pointed, memScope, logTag)
                var nativeResponse = CurlNativeResponse()
                val callback = object : ICurlCallback {
                    override fun onResponse(result: CurlResponse) {
                        nativeResponse = handleCurlNativeResponse(result, logTag)
                    }
                }
                val callbackWrapper = CurlCallbackWrapper(callback)
                val bridgeRef = StableRef.create(bridge)
                try {
                    val source = memScope.alloc<CurlUploadSource> {
                        this.readRef = bridgeRef.asCPointer()
                        this.readChunk = staticCFunction(::uploadReadChunk)
                        this.totalLength = contentLength ?: -1L
                    }
                    val handle = CreateCurlClient(logTag)
                    taskMap[request.requestId] = handle
                    logI("[$logTag] upload-stream transport task add, id:${request.requestId}, " +
                            "handle:${handle}, contentLength:${contentLength ?: -1}")
                    StartUploadRequest(handle, curlRequest, source.ptr, callbackWrapper.getCallbackNativePtr())
                    DeleteCurlClient(handle)
                    taskMap.remove(request.requestId)
                } finally {
                    bridgeRef.dispose()
                    callbackWrapper.release()
                }
                buildResponseAndCallback(request, nativeResponse, responseCallback)
            }
        } finally {
            // perform is over — a writer still blocked in send() (abort paths)
            // must not leak.
            writerJob.cancel()
        }
    }

    // Like buildRequestHeader but never defaults Accept-Encoding: gzip — streaming
    // does not incrementally decompress, so the download must be identity.
    private fun buildStreamRequestHeader(request: VBTransportBaseRequest): Map<String, String> {
        val tmpHeaders = request.header.takeIf {
            it.keys.any { key -> key.equals("Content-Type", ignoreCase = true) }
        } ?: (request.header + mapOf("Content-Type" to "application/octet-stream"))
        request.header = tmpHeaders.toMutableMap()
        return tmpHeaders
    }

    // raft.9: CURLcode → coarse failure reason, same tag vocabulary as the
    // ktor transports (TransportFailureClassifier). curl's own strerror text
    // is kept after the tag.
    private fun describeCurlFailure(code: Int, errorMsg: String): String {
        val reason = when (code) {
            28 -> "timeout"                                    // CURLE_OPERATION_TIMEDOUT
            6, 8 -> "dns"                                      // CURLE_COULDNT_RESOLVE_HOST / WEIRD_SERVER_REPLY
            35, 51, 53, 54, 58, 59, 60, 64, 66, 77, 80, 82, 83, 90, 91 -> "tls"
            18, 55, 56 -> "connection_lost"                    // PARTIAL_FILE / SEND / RECV
            7 -> "connect"                                     // CURLE_COULDNT_CONNECT
            42 -> "cancelled"                                  // CURLE_ABORTED_BY_CALLBACK
            else -> "engine"
        }
        return "[$reason] CURLcode:$code $errorMsg"
    }

    private fun updateResponse(
        logTag: String,
        nativeResponse: CurlNativeResponse,
        request: VBTransportBaseRequest,
        response: VBTransportBaseResponse
    ) {
        val data = convertDataWithContentType(nativeResponse.data, request)
        // The wrapper's code is the CURLcode (0 = transfer completed), NOT the
        // HTTP status. A completed transfer used to be blanket-mapped to 200
        // upstream, which swallowed 401/403/5xx bodies as successes — auth
        // middleware never saw a 401, so token refresh never fired. The wrapper
        // now reports the HTTP status explicitly (CURLINFO_RESPONSE_CODE);
        // surface it whenever the transfer itself completed.
        response.errorCode =
            if (nativeResponse.code == 0 && nativeResponse.httpCode in 100..599) {
                nativeResponse.httpCode
            } else {
                nativeResponse.code
            }
        // raft.9: failed transfers carry a classified reason tag, aligned with
        // the Android/iOS transports' describeTransportFailure vocabulary, so
        // the same failure reads the same on every platform.
        response.errorMessage =
            if (nativeResponse.code == 0) {
                nativeResponse.errorMsg
            } else {
                describeCurlFailure(nativeResponse.code, nativeResponse.errorMsg)
            }
        response.header = convertHeaderMap(nativeResponse.headers)
        // 处理重定向情况
        if (nativeResponse.redirectUrl.isNotEmpty()) {
            logI("[$logTag] curl redirect url, old: ${request.url}, new: ${nativeResponse.redirectUrl}")
            request.url = nativeResponse.redirectUrl
        }

        response.elapseStatis = nativeResponse.elapse
        // raft.11: phase breakdown for failed or slow transfers, so "connect
        // slow vs transfer slow" is one log line instead of a manual ledger of
        // repeated requests (the transport P1 was located exactly that way).
        // curl collects these timings on every transfer; only anomalies log.
        val elapse = nativeResponse.elapse
        if (nativeResponse.code != 0 || elapse.totalTimeMs >= SLOW_TRANSFER_LOG_THRESHOLD_MS) {
            logE(
                "[$logTag] transport_timing id:${request.requestId} code:${nativeResponse.code} " +
                    "http:${nativeResponse.httpCode} totalMs:${elapse.totalTimeMs.toLong()} " +
                    "dnsMs:${elapse.nameLookupTimeMs.toLong()} connectMs:${elapse.connectTimeMs.toLong()} " +
                    "tlsMs:${elapse.sslCostTimeMs.toLong()} ttfbMs:${elapse.startTransferTimeMs.toLong()} " +
                    "redirectMs:${elapse.redirectTime.toLong()}"
            )
        }
        when (response) {
            is VBTransportPostResponse -> {
                response.data = data
                response.request = request as VBTransportPostRequest
            }

            is VBTransportGetResponse -> {
                response.data = data
                response.request = request as VBTransportGetRequest
            }

            is VBTransportBytesResponse -> {
                data?.let { response.data = data as ByteArray }
                response.request = request as VBTransportBytesRequest
            }

            is VBTransportStringResponse -> {
                data?.let { response.data = data as String }
                response.request = request as VBTransportStringRequest
            }

            is VBTransportResponse -> {
                response.data = data
                response.request = request as VBTransportRequest
            }
        }
    }

    private fun convertHeaderMap(headerStr: String): Map<String, List<String>> {
        return headerStr.lines()
            // 过滤空行和 HTTP 状态行（如 "HTTP/1.1 200 OK"）
            .filter { it.isNotBlank() && !it.startsWith("HTTP/") }
            // 处理每一行，分割键值对
            .mapNotNull { line ->
                val colonIndex = line.indexOf(':')
                if (colonIndex == -1) {
                    null
                } else {
                    val key = line.substring(0, colonIndex).trim()
                    val value = line.substring(colonIndex + 1).trim()
                    key to value
                }
            }
            // 合并相同键的值到列表（兼容多值头字段）
            .groupBy({ it.first }, { it.second })
    }

    private fun convertDataWithContentType(
        bodyBytes: ByteArray?,
        request: VBTransportBaseRequest
    ): Any? {
        val headers = request.header.mapKeys { it.key.lowercase() }
        return if (bodyBytes != null
            && headers.containsKey("content-type")
            && headers["content-type"] == VBTransportContentType.JSON.toString()) {
            bodyBytes.decodeToString()
        } else {
            bodyBytes
        }
    }

    private fun logI(content: String) {
        VBPBLog.i(VBPBLog.HMCURLIMPL, content)
    }

    private fun logE(content: String) {
        VBPBLog.e(VBPBLog.HMCURLIMPL, content)
    }

    private fun CurlResponse.toCurlNativeResponse(): CurlNativeResponse {
        return CurlResponseCodec.decode(
            CurlResponseFields(
                code = code,
                httpCode = httpCode.toInt(),
                errorMsg = errorMsg.toKStringOrNull(errorMsgLen),
                errorMsgLen = errorMsgLen,
                headers = headers.toKStringOrNull(headerLen),
                headerLen = headerLen,
                redirectUrl = redirectUrl?.toKString(),
                data = data.readBytesOrNull(dataLen),
                dataLen = dataLen,
                elapse = VBTransportElapseStatistics(
                    nameLookupTimeMs = elapse.nameLookupTimeMs,
                    connectTimeMs = elapse.connectTimeMs,
                    sslCostTimeMs = elapse.sslCostTimeMs,
                    preTransferTime = elapse.preTransferTime,
                    startTransferTimeMs = elapse.startTransferTimeMs,
                    redirectTime = elapse.redirectTime,
                    recvTime = elapse.recvTime,
                    totalTimeMs = elapse.totalTimeMs
                )
            )
        )
    }

    private fun CPointer<ByteVar>?.toKStringOrNull(length: Int): String? {
        return if (this == null || length <= 0) null else toKString()
    }

    private fun CPointer<ByteVar>?.readBytesOrNull(length: Int): ByteArray? {
        return this?.let { if (length > 0) it.readBytes(length) else ByteArray(0) }
    }
}

interface ICurlCallback {
    fun onResponse(result: CurlResponse)
}

// 图片加载回调 kotlin->c
class CurlCallbackWrapper(private val curlCallback: ICurlCallback) {
    private var callbackPtr: CPointer<CFunction<(COpaquePointer?, CPointer<CurlResponse>?) -> Unit>>
    private var callbackStableRef: StableRef<KFunction1<CPointer<CurlResponse>, Unit>>
    private var callBlackNative: CurlCallback

    init {
        callbackStableRef = StableRef.create(::onResponse)
        callbackPtr = staticCFunction(::createStableRef)
        callBlackNative = nativeHeap.alloc()
        callBlackNative.callbackRef = callbackStableRef.asCPointer()
        callBlackNative.callback = callbackPtr
    }

    private fun onResponse(result: CPointer<CurlResponse>) =
        curlCallback.onResponse(result.pointed)

    fun getCallbackNativePtr(): CPointer<CurlCallback> = callBlackNative.ptr

    fun release() {
        callbackStableRef.dispose()
    }
}

internal fun createStableRef(
    callbackRef: COpaquePointer?, result: CPointer<CurlResponse>?
) {
    callbackRef?.asStableRef<(CPointer<CurlResponse>?) -> Unit>()?.get()?.invoke(result)
}

// fork #8 streaming callbacks kotlin->c. One StableRef to the handler backs all
// three C function pointers; each recovers the handler and dispatches.
interface IStreamHandler {
    fun onResponseStart(httpCode: Long, headers: String)
    fun onChunk(chunk: ByteArray)
    fun onComplete(result: CurlResponse)
}

class CurlStreamCallbackWrapper(handler: IStreamHandler) {
    private val stableRef: StableRef<IStreamHandler> = StableRef.create(handler)
    private val native: CurlStreamCallback = nativeHeap.alloc()

    init {
        native.callbackRef = stableRef.asCPointer()
        native.onResponseStart = staticCFunction(::streamOnResponseStart)
        native.onChunk = staticCFunction(::streamOnChunk)
        native.onComplete = staticCFunction(::streamOnComplete)
    }

    fun getCallbackNativePtr(): CPointer<CurlStreamCallback> = native.ptr

    fun release() {
        stableRef.dispose()
        nativeHeap.free(native.rawPtr)
    }
}

internal fun streamOnResponseStart(
    callbackRef: COpaquePointer?, httpCode: Long, headers: CPointer<ByteVar>?, headerLen: Int
) {
    callbackRef?.asStableRef<IStreamHandler>()?.get()?.onResponseStart(httpCode, headers?.toKString() ?: "")
}

internal fun streamOnChunk(callbackRef: COpaquePointer?, data: CPointer<ByteVar>?, len: Int) {
    if (data == null || len <= 0) return
    callbackRef?.asStableRef<IStreamHandler>()?.get()?.onChunk(data.readBytes(len))
}

internal fun streamOnComplete(callbackRef: COpaquePointer?, result: CPointer<CurlResponse>?) {
    val res = result ?: return
    callbackRef?.asStableRef<IStreamHandler>()?.get()?.onComplete(res.pointed)
}

// issue #8 slice 3: push→pull adapter between the transport's writeBody sink
// (producer coroutine) and curl's READFUNCTION (consumer on the perform
// thread). A bounded channel provides the backpressure: the producer suspends
// when the consumer falls behind, so no more than a few chunks are in flight.
internal class UploadPullBridge {
    private val channel = kotlinx.coroutines.channels.Channel<ByteArray>(capacity = 4)
    private var leftover: ByteArray = ByteArray(0)
    private var leftoverOffset = 0

    val sink: NetworkByteStreamSink = object : NetworkByteStreamSink {
        override suspend fun write(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            channel.send(bytes.copyOf())
        }
    }

    fun closeSuccess() = channel.close()

    fun closeFailure(cause: Throwable) {
        channel.close(cause)
    }

    // Runs on the curl perform thread (blocking there is the contract).
    // Returns bytes copied into [buffer]; 0 = EOF; negative = abort.
    fun fill(buffer: CPointer<ByteVar>, maxLen: Int): Int {
        if (leftoverOffset >= leftover.size) {
            val next = kotlinx.coroutines.runBlocking { channel.receiveCatching() }
            val chunk = next.getOrNull()
            if (chunk == null) {
                // closed: cleanly (EOF) or with the writer's failure (abort).
                return if (next.exceptionOrNull() != null) -1 else 0
            }
            leftover = chunk
            leftoverOffset = 0
        }
        val count = minOf(maxLen, leftover.size - leftoverOffset)
        leftover.usePinned { pinned ->
            memcpy(buffer, pinned.addressOf(leftoverOffset), count.convert())
        }
        leftoverOffset += count
        return count
    }
}

// C-side curlReadChunk trampoline: readRef is a StableRef<UploadPullBridge>.
// Never throw across the C boundary — any failure becomes an abort (-1).
internal fun uploadReadChunk(readRef: COpaquePointer?, buffer: CPointer<ByteVar>?, maxLen: Int): Int {
    val bridge = readRef?.asStableRef<UploadPullBridge>()?.get() ?: return -1
    val target = buffer ?: return -1
    if (maxLen <= 0) return 0
    return try {
        bridge.fill(target, maxLen)
    } catch (throwable: Throwable) {
        VBPBLog.e(VBPBLog.HMCURLIMPL, "uploadReadChunk failed: ${throwable.message ?: throwable::class.simpleName}")
        -1
    }
}

// Producer side of the upload bridge. IO keeps the writers off the pool that
// runs the blocking curl performs; SupervisorJob isolates per-request failures.
private val uploadWriterScope = kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
)

actual fun getCurlRequestService(): ICurlRequestService = CurlRequestServiceHM

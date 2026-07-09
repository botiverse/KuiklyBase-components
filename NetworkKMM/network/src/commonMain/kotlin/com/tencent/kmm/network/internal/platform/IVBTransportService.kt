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

import com.tencent.kmm.network.export.VBTransportBytesRequest
import com.tencent.kmm.network.export.VBTransportBytesResponse
import com.tencent.kmm.network.export.VBTransportGetRequest
import com.tencent.kmm.network.export.VBTransportGetResponse
import com.tencent.kmm.network.export.VBTransportPostRequest
import com.tencent.kmm.network.export.VBTransportPostResponse
import com.tencent.kmm.network.export.NetworkByteStreamSink
import kotlinx.coroutines.launch
import com.tencent.kmm.network.export.VBTransportRequest
import com.tencent.kmm.network.export.VBTransportResponse
import com.tencent.kmm.network.export.VBTransportStringRequest
import com.tencent.kmm.network.export.VBTransportStringResponse

interface IVBTransportService {
    /**
     * 发送字节数组类型请求
     */
    fun sendBytesRequest(
        kmmBytesRequest: VBTransportBytesRequest,
        kmmBytesResponseCallback: (response: VBTransportBytesResponse) -> Unit
    )

    /**
     * 发送字节数组类型请求
     */
    fun sendStringRequest(
        kmmStringRequest: VBTransportStringRequest,
        kmmStringResponseCallback: (response: VBTransportStringResponse) -> Unit
    )

    /**
     * 发送POST请求
     */
    fun post(
        kmmPostRequest: VBTransportPostRequest,
        kmmPostResponseCallback: (response: VBTransportPostResponse) -> Unit
    )

    /**
     * 发送GET请求
     */
    fun get(
        kmmGetRequest: VBTransportGetRequest,
        kmmGetResponseCallback: (response: VBTransportGetResponse) -> Unit
    )

    /**
     * 发送自定义 HTTP method 请求
     */
    fun request(
        kmmRequest: VBTransportRequest,
        kmmResponseCallback: (response: VBTransportResponse) -> Unit
    )

    /**
     * 流式下载 (fork #8): 响应头一就绪即以 [onResponseStart] 交付状态码+响应头
     * (用于确定进度: Content-Length), 响应体逐块回调 [onChunk] 不缓冲整包, 结束
     * 时以 [onComplete] 交付状态/头/错误 (其 data 置 null, body 已通过 chunk 交付)。
     *
     * 默认实现回退到 [request] 的全量缓冲: 拿到整包后先 onResponseStart, 再把整包
     * 作为单个 chunk 交付——语义正确但不省内存, 且 onResponseStart 直到响应读完才
     * 触发。引擎本身支持流式的平台 (Android/iOS 的 ktor ByteReadChannel) 覆写此方法
     * 在响应头就绪时立即 onResponseStart, 再交付真正的逐块数据。
     */
    fun requestStream(
        kmmRequest: VBTransportRequest,
        onResponseStart: (statusCode: Int, headers: Map<String, List<String>>) -> Unit,
        onChunk: (chunk: ByteArray) -> Unit,
        onComplete: (response: VBTransportResponse) -> Unit
    ) {
        request(kmmRequest) { response ->
            onResponseStart(response.errorCode, response.header)
            when (val body = response.data) {
                is ByteArray -> if (body.isNotEmpty()) onChunk(body)
                is String -> if (body.isNotEmpty()) onChunk(body.encodeToByteArray())
                else -> Unit
            }
            response.data = null
            onComplete(response)
        }
    }

    /**
     * 流式上传 (issue #8): 请求体由 [writeBody] 向 sink 逐块推送, 而不是预先
     * 缓冲成 ByteArray。[contentLength] 已知时按真实 Content-Length 发送,
     * null 时按 chunked 传输。
     *
     * 默认实现回退到全量缓冲: 先把 [writeBody] 推的所有块收进内存, 再走
     * [request]——语义正确但不省内存 (与 requestStream 下载侧的默认回退对称)。
     * ktor 平台 (Android/iOS) 覆写此方法用 WriteChannelContent 真流式发送。
     */
    fun requestUploadStream(
        kmmRequest: VBTransportRequest,
        contentLength: Long?,
        writeBody: suspend (NetworkByteStreamSink) -> Unit,
        kmmResponseCallback: (response: VBTransportResponse) -> Unit
    ) {
        uploadStreamFallbackScope.launch {
            val chunks = mutableListOf<ByteArray>()
            var total = 0
            writeBody(object : NetworkByteStreamSink {
                override suspend fun write(bytes: ByteArray) {
                    if (bytes.isEmpty()) return
                    chunks.add(bytes.copyOf())
                    total += bytes.size
                }
            })
            val buffered = ByteArray(total)
            var offset = 0
            chunks.forEach { chunk ->
                chunk.copyInto(buffered, destinationOffset = offset)
                offset += chunk.size
            }
            kmmRequest.data = buffered
            request(kmmRequest, kmmResponseCallback)
        }
    }

    /**
     * 取消网络请求
     */
    fun cancel(requestId: Int)

}

// requestUploadStream 缓冲回退需要一个执行 suspend writeBody 的作用域;
// SupervisorJob 隔离单请求失败 (与 VBTransportService.networkScope 同理)。
private val uploadStreamFallbackScope =
    kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )

// 需要各平台实现获取传输能力的实力
expect fun getIVBTransportService(): IVBTransportService

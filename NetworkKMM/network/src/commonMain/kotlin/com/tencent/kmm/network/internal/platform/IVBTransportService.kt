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
     * 流式下载 (fork #8): 响应体逐块回调 [onChunk], 不在内存里缓冲整包; 结束时以
     * [onComplete] 交付状态/头/错误 (其 data 置 null, body 已通过 chunk 交付)。
     *
     * 默认实现回退到 [request] 的全量缓冲, 再把整包作为单个 chunk 交付——语义正确
     * 但不省内存。引擎本身支持流式的平台 (Android/iOS 的 ktor ByteReadChannel)
     * 覆写此方法交付真正的逐块数据。
     */
    fun requestStream(
        kmmRequest: VBTransportRequest,
        onChunk: (chunk: ByteArray) -> Unit,
        onComplete: (response: VBTransportResponse) -> Unit
    ) {
        request(kmmRequest) { response ->
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
     * 取消网络请求
     */
    fun cancel(requestId: Int)

}

// 需要各平台实现获取传输能力的实力
expect fun getIVBTransportService(): IVBTransportService

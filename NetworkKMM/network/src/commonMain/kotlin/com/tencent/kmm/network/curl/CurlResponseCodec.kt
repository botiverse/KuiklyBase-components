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

import com.tencent.kmm.network.export.VBTransportElapseStatistics

data class CurlNativeResponse(
    var code: Int = 0,
    var httpCode: Int = 0,
    var errorMsg: String = "",
    var headers: String = "",
    var data: ByteArray? = null,
    var redirectUrl: String = "",
    var elapse: VBTransportElapseStatistics = VBTransportElapseStatistics()
)

internal data class CurlResponseFields(
    val code: Int = 0,
    val httpCode: Int = 0,
    val errorMsg: String? = null,
    val errorMsgLen: Int = 0,
    val headers: String? = null,
    val headerLen: Int = 0,
    val data: ByteArray? = null,
    val dataLen: Int = 0,
    val redirectUrl: String? = null,
    val elapse: VBTransportElapseStatistics = VBTransportElapseStatistics()
)

internal object CurlResponseCodec {
    fun decode(fields: CurlResponseFields): CurlNativeResponse {
        return CurlNativeResponse(
            code = fields.code,
            httpCode = fields.httpCode,
            errorMsg = fields.errorMsg.takeIf { fields.errorMsgLen > 0 }.orEmpty(),
            headers = fields.headers.takeIf { fields.headerLen > 0 }.orEmpty(),
            redirectUrl = fields.redirectUrl.orEmpty(),
            elapse = fields.elapse,
            data = fields.data?.let { data ->
                when {
                    fields.dataLen <= 0 -> ByteArray(0)
                    fields.dataLen >= data.size -> data
                    else -> data.copyOf(fields.dataLen)
                }
            }
        )
    }
}

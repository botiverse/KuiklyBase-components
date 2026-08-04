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

internal fun CurlNativeResponse.isBufferedBodyIdleTimeout(): Boolean =
    code == 28 && errorMsg.contains("buffered body idle timeout")

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

internal fun parseCurlHeaders(headerText: String): Map<String, List<String>> {
    return headerText.lines()
        .filter { it.isNotBlank() && !it.startsWith("HTTP/") }
        .mapNotNull { line ->
            val colonIndex = line.indexOf(':')
            if (colonIndex < 0) {
                null
            } else {
                line.substring(0, colonIndex).trim() to line.substring(colonIndex + 1).trim()
            }
        }
        .groupBy({ it.first }, { it.second })
}

/** Stable failure tags shared by every curl-backed platform engine. */
internal fun describeCurlFailure(code: Int, errorMessage: String): String {
    val reason = when (code) {
        28 -> "timeout"
        6, 8 -> "dns"
        35, 51, 53, 54, 58, 59, 60, 64, 66, 77, 80, 82, 83, 90, 91 -> "tls"
        18, 55, 56 -> "connection_lost"
        7 -> "connect"
        42 -> "cancelled"
        else -> "engine"
    }
    return "[$reason] CURLcode:$code $errorMessage"
}

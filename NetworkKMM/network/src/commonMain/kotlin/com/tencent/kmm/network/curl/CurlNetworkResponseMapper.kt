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

import com.tencent.kmm.network.export.NetworkByteStream
import com.tencent.kmm.network.export.NetworkError
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkResponseBody
import com.tencent.kmm.network.export.NetworkTransferProgress
import com.tencent.kmm.network.export.toNetworkHttpProtocol
import com.tencent.kmm.network.service.classifyNetworkErrorKind

internal fun CurlNativeResponse.toNetworkResponse(request: NetworkRequest): NetworkResponse {
    val statusCode = when {
        code == 0 && httpCode in 100..599 -> httpCode
        code == 0 -> 200
        else -> null
    }
    val describedError = if (code == 0) errorMsg else describeCurlFailure(code, errorMsg)
    val error = when {
        code == 0 && (statusCode == null || statusCode < 400) -> null
        else -> {
            val rawCode = if (code == 0) statusCode ?: code else code
            val kind = classifyNetworkErrorKind(rawCode, describedError, statusCode)
            NetworkError(
                kind = kind,
                message = describedError.ifBlank { kind.name },
                statusCode = statusCode,
                rawCode = rawCode
            )
        }
    }
    data?.let { bytes ->
        request.progress.downloadProgress?.invoke(
            NetworkTransferProgress(
                bytesTransferred = bytes.size.toLong(),
                bytesTotal = contentLength(parseCurlHeaders(headers)) ?: bytes.size.toLong()
            )
        )
    }
    return NetworkResponse(
        request = request,
        statusCode = statusCode,
        headers = parseCurlHeaders(headers),
        body = NetworkResponseBody(
            bytes = data,
            stream = data?.let { bytes ->
                NetworkByteStream(contentLength = bytes.size.toLong(), readAllBlock = { bytes })
            }
        ),
        error = error,
        timing = elapse,
        protocol = elapse.protocol.toNetworkHttpProtocol()
    )
}

internal fun contentLength(headers: Map<String, List<String>>): Long? {
    return headers.entries.firstOrNull { (name, _) ->
        name.equals("Content-Length", ignoreCase = true)
    }?.value?.firstOrNull()?.toLongOrNull()
}

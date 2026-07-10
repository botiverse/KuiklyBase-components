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

import com.tencent.kmm.network.export.NetworkError
import com.tencent.kmm.network.export.NetworkErrorKind
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkResponseBody
import com.tencent.kmm.network.export.VBTransportResultCode

/**
 * Single-source message for the RFC #67/#68 fail-explicit GET/HEAD streaming-body
 * caller-contract error. Referenced by every CURL end so the three-end wording
 * cannot drift: Android/iOS via [unsupportedStreamingRequestBodyResponse]
 * (NetworkResponse layer), OHOS via `IVBTransportService.requestUploadStream`'s
 * GET/HEAD branch (VBTransportResponse layer). Caller-contract error, not a
 * transport failure → no raft.9 cause-tag prefix; plain method semantics.
 * [method] must already be upper-cased by the caller.
 */
internal fun unsupportedStreamingRequestBodyMessage(method: String): String =
    "streaming request body is not supported for $method " +
        "(CURLOPT_UPLOAD would rewrite the verb); use POST/PUT/PATCH/DELETE/OPTIONS"

internal fun unsupportedStreamingRequestBodyResponse(request: NetworkRequest): NetworkResponse =
    NetworkResponse(
        request = request,
        statusCode = null,
        headers = emptyMap(),
        body = NetworkResponseBody(),
        error = NetworkError(
            kind = NetworkErrorKind.UNKNOWN,
            message = unsupportedStreamingRequestBodyMessage(request.method.name.uppercase()),
            rawCode = VBTransportResultCode.CODE_NETWORK_ERROR
        )
    )

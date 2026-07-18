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
package com.tencent.kmm.network.export

import com.tencent.qqlive.kmm.native.libcurl.CURL_WRAPPER_ABI_VERSION
import com.tencent.qqlive.kmm.native.libcurl.CurlSupportsHttp3
import com.tencent.qqlive.kmm.native.libcurl.CurlWrapperAbiVersion
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
internal actual fun platformNetworkCurlNativeStatus(): NetworkCurlNativeStatus {
    val actualAbi = runCatching { CurlWrapperAbiVersion() }.getOrNull()
    val linked = actualAbi == CURL_WRAPPER_ABI_VERSION
    return NetworkCurlNativeStatus(
        linked = linked,
        http3FeatureAvailable = linked && runCatching { CurlSupportsHttp3() != 0 }.getOrDefault(false),
        detail = when {
            actualAbi == null -> "OHOS curl runtime is unavailable."
            !linked -> "OHOS curl wrapper ABI mismatch: expected $CURL_WRAPPER_ABI_VERSION, actual $actualAbi."
            else -> null
        }
    )
}

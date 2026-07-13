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

import com.tencent.qqlive.kmm.native.libcurl.CurlSupportsHttp3
import com.tencent.kmm.network.service.NetworkEngine
import com.tencent.kmm.network.service.NetworkTransportEngine
import com.tencent.kmm.network.service.VBTransportNetworkEngine
import kotlinx.cinterop.ExperimentalForeignApi

internal actual val platformDefaultNetworkTransportEngine: NetworkTransportEngine =
    NetworkTransportEngine.CURL

internal actual fun resolvePlatformNetworkEngine(engine: NetworkTransportEngine): NetworkEngine? =
    when (engine) {
        NetworkTransportEngine.KTOR -> null
        NetworkTransportEngine.CURL -> VBTransportNetworkEngine
    }

@OptIn(ExperimentalForeignApi::class)
internal actual fun platformCurlSupportsHttp3(): Boolean = CurlSupportsHttp3() != 0

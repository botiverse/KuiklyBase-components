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

import com.tencent.kmm.network.internal.platform.IosCurlEngineProvider

/** iOS curl runtime inputs supplied by the host application. */
object VBTransportIosCurl {
    /** Absolute path to the app-owned CA bundle required by the curl/OpenSSL delegate. */
    var caInfoPath: String? = null

    /** True when the native artifact is linked and a non-blank CA path is configured. */
    val nativeAvailable: Boolean
        get() = IosCurlEngineProvider.nativeAvailable
}

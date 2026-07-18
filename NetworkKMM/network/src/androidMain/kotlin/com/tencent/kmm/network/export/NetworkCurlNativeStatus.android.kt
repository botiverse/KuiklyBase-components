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

import com.tencent.kmm.network.internal.platform.AndroidCurlEngineProvider

internal actual fun platformNetworkCurlNativeStatus(): NetworkCurlNativeStatus {
    val linked = AndroidCurlEngineProvider.nativeLinked
    return NetworkCurlNativeStatus(
        linked = linked,
        http3FeatureAvailable = linked && AndroidCurlEngineProvider.nativeSupportsHttp3,
        detail = if (linked) null else "Android curl JNI runtime is not packaged or could not be loaded."
    )
}

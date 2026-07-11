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
package com.tencent.kmm.network.service

// iOS curl links against OpenSSL, not Security.framework: there is no
// verified compiled CA default in the app sandbox, so unconfigured curl
// stays gated here (the Darwin/ktor engine is the platform-trust path).
internal actual val curlPlatformDefaultTrust: CurlPlatformDefaultTrust =
    CurlPlatformDefaultTrust(
        available = false,
        detail = "iOS curl has no verified platform CA default; call VBTransportCurl.configure()."
    )

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

// OHOS libcurl is compiled with CURL_CA_BUNDLE=/etc/ssl/certs/cacert.pem
// (scripts/build-ohos-native.sh) and that default completed TLS on real
// devices throughout the pre-raft.20 production line — it is a verified
// trust source, so unconfigured curl proceeds on it instead of failing
// closed (the raft.20 mandatory gate was a compatibility regression).
internal actual val curlPlatformDefaultTrust: CurlPlatformDefaultTrust =
    CurlPlatformDefaultTrust(
        available = true,
        detail = "compiled CURL_CA_BUNDLE=/etc/ssl/certs/cacert.pem (device-verified), proxy pinned to explicit direct."
    )

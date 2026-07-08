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

/**
 * Android transport engine selection.
 *
 * The default engine is Ktor-OkHttp with `fastFallback = true` (RFC 8305
 * Happy Eyeballs): IPv6 and IPv4 connect attempts race in parallel, so a
 * blackholed address family (VPN/proxy, broken IPv6) costs ~250ms instead of
 * serially exhausting the whole connect budget.
 *
 * [okHttpEnabled] is a kill switch back to the legacy HttpURLConnection-based
 * Ktor `Android` engine. The shared client is created once per process on
 * first request, so hosts must flip this during app startup — changes after
 * the first request have no effect.
 */
object VBTransportAndroidEngine {
    @Volatile
    var okHttpEnabled: Boolean = true
}

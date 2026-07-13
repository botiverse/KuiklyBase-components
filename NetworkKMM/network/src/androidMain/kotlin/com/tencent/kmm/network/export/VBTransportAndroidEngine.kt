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

    /**
     * Opt-in recovery for a reused HTTP/2 connection that accepts a request
     * but makes no progress towards response headers.
     *
     * Keep this disabled until the host has selected a rollout cohort. When
     * enabled, NetworkKMM watches from request-send completion to
     * `responseHeadersStart`. A reused h2 call that exceeds
     * [VBTransportReusedHttp2Recovery.responseHeadersWatchdogMillis], together
     * with another stalled call on the same physical connection, drains its
     * origin's affected client slot generation. GET/HEAD may retry once on a
     * different slot; methods with replay-unsafe bodies are never retried.
     *
     * This value is sampled at the beginning of each request. Changing it does
     * not mutate an already-running request.
     */
    @Volatile
    var reusedHttp2Recovery: VBTransportReusedHttp2Recovery =
        VBTransportReusedHttp2Recovery()
}

data class VBTransportReusedHttp2Recovery(
    /** Default-off rollout gate. */
    val enabled: Boolean = false,
    /** Independent OkHttp client/pool shards maintained for each origin. */
    val clientShardCount: Int = 5,
    /** No-response-headers interval after the request has been sent. */
    val responseHeadersWatchdogMillis: Long = 7_000L,
    /** Concurrent stalled calls required on the same reused h2 connection. */
    val minimumConcurrentStalledRequests: Int = 2,
    /**
     * Optional low-cost connection liveness layer. Zero keeps OkHttp's ping
     * interval disabled. PING does not replace the response-headers watchdog.
     */
    val pingIntervalMillis: Long = 0L,
) {
    init {
        require(clientShardCount in 1..8) { "clientShardCount must be between 1 and 8" }
        require(responseHeadersWatchdogMillis > 0L) {
            "responseHeadersWatchdogMillis must be positive"
        }
        require(minimumConcurrentStalledRequests > 0) {
            "minimumConcurrentStalledRequests must be positive"
        }
        require(pingIntervalMillis >= 0L) { "pingIntervalMillis must be non-negative" }
    }
}

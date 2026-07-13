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

data class VBTransportElapseStatistics(
    /** NetworkClient.execute enqueue -> its coroutine actually starts. */
    var clientQueueTimeMs: Double = 0.0,
    /** Request middleware + auth preparation before the first engine invocation. */
    var requestPreparationTimeMs: Double = 0.0,
    /** Android transport scope launch -> Ktor/OkHttp callStart. */
    var transportQueueTimeMs: Double = 0.0,
    /** OkHttp callStart -> application interceptor entry (Dispatcher permit granted). */
    var dispatcherQueueTimeMs: Double = 0.0,
    /**
     * DNS 解析耗时
     */
    var nameLookupTimeMs: Double = 0.0,
    /**
     * Connection establishment through connectEnd. On HTTPS this includes the
     * nested TLS phase reported separately by [sslCostTimeMs]; do not add them.
     */
    var connectTimeMs: Double = 0.0,
    /**
     * https ssl 握手耗时
     */
    var sslCostTimeMs: Double = 0.0,
    /**
     * Dispatcher start -> request headers start. This is a cumulative umbrella
     * interval that overlaps DNS/connect/TLS; do not add those child phases.
     */
    var preTransferTime: Double = 0.0,
    /**
     * Dispatcher start -> response headers start. This is a cumulative umbrella
     * interval that overlaps preparation, DNS/connect/TLS, and request send;
     * do not add those child phases.
     */
    var startTransferTimeMs: Double = 0.0,
    /** Request headers start -> request headers end. */
    var requestHeadersTimeMs: Double = 0.0,
    /** Request body start -> request body end. */
    var requestBodyTimeMs: Double = 0.0,
    /** Request headers/body end -> response headers start. */
    var responseWaitTimeMs: Double = 0.0,
    /**
     * 所有重定向过程的总耗时
     */
    var redirectTime: Double = 0.0,
    /**
     * 数据接收耗时
     */
    var recvTime: Double = 0.0,
    /**
     * 整个请求的总耗时
     */
    var totalTimeMs: Double = 0.0,
    /** Transport implementation request id used to join component logs. */
    var transportRequestId: String? = null,
    /** Negotiated protocol, for example h2 or http/1.1. */
    var protocol: String? = null,
    /** True when the acquired connection existed before this call started. */
    var reusedConnection: Boolean? = null,
    /** Number of connection attempts observed for this response. */
    var connectionAttemptCount: Int = 0,
    /** Reused HTTP/2 request-send -> response-headers watchdog fired. */
    var staleH2Detected: Boolean = false,
    /** Scheme/host/port only; never contains a path, query, or credentials. */
    var connectionOrigin: String? = null,
    /** NetworkKMM-managed Android OkHttp client/pool generation. */
    var connectionGeneration: Long? = null,
    /** Process-local diagnostic identity for the acquired OkHttp connection. */
    var connectionIdentity: String? = null,
    /** The observed generation was retired for new requests. */
    var connectionDraining: Boolean = false,
    /** This logical request made its one allowed fresh-generation retry. */
    var freshRetry: Boolean = false,
    /** `success`, `failure`, or null when no fresh retry occurred. */
    var freshRetryResult: String? = null,
    /** Duration observed when the stale-h2 watchdog fired. */
    var noResponseHeadersDurationMs: Double = 0.0,
)

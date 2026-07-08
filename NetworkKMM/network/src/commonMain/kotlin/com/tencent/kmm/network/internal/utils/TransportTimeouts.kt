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
package com.tencent.kmm.network.internal.utils

/**
 * raft.9: the connect phase gets its own budget instead of inheriting the
 * whole-request timeout.
 *
 * The ktor transports used to bind connect/request/socket timeouts to the
 * same totalTimeout (typically 30s+). On a network where one address family
 * is a black hole (IPv6 behind a proxy that only forwards IPv4, etc.) the
 * engine — HttpURLConnection tries addresses SERIALLY, no Happy Eyeballs —
 * burned the entire budget on the dead family before falling back, showing
 * up as 5/15/30s cold-connection ladders while the same request completed
 * in ~300ms once a connection existed.
 *
 * 3s, not sub-second: with serial connection attempts a connect timeout is
 * a hard verdict to abandon that address family, and a legitimate TCP
 * handshake on high-RTT cellular can take 1-3s — sub-second values would
 * fail slow-but-working paths. 3s caps the dead-family detour at one
 * ladder step (16s → ~3.3s observed shape) without breaking weak networks.
 * True zero-cost fallback (RFC 8305 parallel racing) is an engine-level
 * capability tracked separately (OkHttp fastFallback / infra RFC).
 */
internal const val TRANSPORT_CONNECT_TIMEOUT_MILLIS: Long = 3_000L

/** Connect budget for a request: never longer than the request's own total timeout. */
internal fun transportConnectTimeoutMillis(totalTimeout: Long): Long =
    if (totalTimeout in 1 until TRANSPORT_CONNECT_TIMEOUT_MILLIS) totalTimeout
    else TRANSPORT_CONNECT_TIMEOUT_MILLIS

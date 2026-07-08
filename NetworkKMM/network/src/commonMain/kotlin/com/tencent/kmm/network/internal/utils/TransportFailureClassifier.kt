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
 * raft.9: coarse failure-reason tags for transport errors, so a failed
 * request surfaces WHY it failed instead of collapsing every cause into a
 * bare message (callers were seeing "HTTP 0" for timeout, DNS, TLS and
 * truncated-body failures alike).
 *
 * Classification is by exception type name and message heuristics only —
 * no platform imports — so the same classifier serves the Android (ktor)
 * and iOS (Darwin) transports. The tag is prepended to the error message
 * and flows through the existing errorMessage field: no API change.
 */
internal fun describeTransportFailure(throwable: Throwable): String {
    val typeName = throwable::class.simpleName.orEmpty()
    val message = throwable.message?.takeIf { it.isNotBlank() } ?: throwable.toString()
    val haystack = "$typeName $message".lowercase()
    val reason = when {
        "timeout" in haystack || "timed out" in haystack -> "timeout"
        "unknownhost" in haystack || "dns" in haystack ||
            "hostname could not be found" in haystack -> "dns"
        "ssl" in haystack || "tls" in haystack || "certificate" in haystack ||
            "handshake" in haystack -> "tls"
        "unexpected end of stream" in haystack || "connection reset" in haystack ||
            "eof" in haystack || "closedchannel" in haystack ||
            "connection closed" in haystack || "broken pipe" in haystack -> "connection_lost"
        "connect" in haystack && ("refused" in haystack || "failed" in haystack ||
            "unreachable" in haystack) -> "connect"
        "cancell" in haystack -> "cancelled"
        else -> "engine"
    }
    return "[$reason] $typeName: $message"
}

/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.tencent.kmm.network.curl

import com.tencent.kmm.network.export.VBTransportMethod

/**
 * Matches only libcurl's explicit proxy/H3 incompatibility terminal. Curl 28,
 * 35, and 56 are otherwise broad timeout/TLS/receive failures, so the code
 * alone must never trigger a protocol downgrade.
 */
internal fun isCurlProxyHttp3Incompatibility(code: Int, message: String): Boolean {
    if (code != 28 && code != 35 && code != 56) return false
    val normalized = message.lowercase()
    return normalized.contains("http/3") &&
        normalized.contains("not supported") &&
        normalized.contains("proxy")
}

internal fun shouldFreshRetryCurlProxyHttp3Failure(
    method: VBTransportMethod,
    cancelled: Boolean,
    remainingTimeoutMillis: Long?
): Boolean = !cancelled && remainingTimeoutMillis != 0L &&
    (method == VBTransportMethod.GET || method == VBTransportMethod.HEAD)

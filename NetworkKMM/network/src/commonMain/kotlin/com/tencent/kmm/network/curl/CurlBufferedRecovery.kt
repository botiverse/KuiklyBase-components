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

import com.tencent.kmm.network.export.NetworkCurlBufferedResponsePolicy
import com.tencent.kmm.network.export.VBTransportMethod

internal fun shouldFreshRetryCurlBufferedStall(
    method: VBTransportMethod,
    bodyRepeatable: Boolean,
    policy: NetworkCurlBufferedResponsePolicy,
    cancelled: Boolean,
    remainingTimeoutMillis: Long?,
): Boolean = policy.freshRetryEnabled && bodyRepeatable && !cancelled &&
    remainingTimeoutMillis != 0L &&
    (method == VBTransportMethod.GET || method == VBTransportMethod.HEAD)

/** libcurl CURLE_COULDNT_RESOLVE_HOST. */
internal const val CURL_CODE_COULDNT_RESOLVE_HOST: Int = 6

/**
 * Raft task #153: retry once through the configured DoH provider only when the system resolver
 * failed to resolve the host. Nothing was sent to the server (no connection was made), so any
 * method may be retried. [provider] is the native provider id; 0 means DoH fallback is off.
 */
internal fun shouldRetryCurlWithDohFallback(
    curlCode: Int,
    provider: Int,
    cancelled: Boolean,
    remainingTimeoutMillis: Long?,
): Boolean = curlCode == CURL_CODE_COULDNT_RESOLVE_HOST && provider != 0 && !cancelled &&
    remainingTimeoutMillis != 0L

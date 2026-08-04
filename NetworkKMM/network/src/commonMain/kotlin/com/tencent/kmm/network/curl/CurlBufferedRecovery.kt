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

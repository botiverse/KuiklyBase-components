/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.tencent.kmm.network.curl

import com.tencent.kmm.network.export.NetworkCurlBufferedResponsePolicy
import com.tencent.kmm.network.export.VBTransportMethod
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CurlBufferedRecoveryTest {
    @Test
    fun onlyReplaySafeGetHeadWithBudgetCanRetry() {
        val enabled = NetworkCurlBufferedResponsePolicy()
        assertTrue(shouldFreshRetryCurlBufferedStall(VBTransportMethod.GET, true, enabled, false, null))
        assertTrue(shouldFreshRetryCurlBufferedStall(VBTransportMethod.HEAD, true, enabled, false, 1))
        assertFalse(shouldFreshRetryCurlBufferedStall(VBTransportMethod.POST, true, enabled, false, 1))
        assertFalse(shouldFreshRetryCurlBufferedStall(VBTransportMethod.GET, false, enabled, false, 1))
        assertFalse(shouldFreshRetryCurlBufferedStall(VBTransportMethod.GET, true, enabled, true, 1))
        assertFalse(shouldFreshRetryCurlBufferedStall(VBTransportMethod.GET, true, enabled, false, 0))
        assertFalse(
            shouldFreshRetryCurlBufferedStall(
                VBTransportMethod.GET,
                true,
                enabled.copy(freshRetryEnabled = false),
                false,
                1,
            )
        )
    }
}

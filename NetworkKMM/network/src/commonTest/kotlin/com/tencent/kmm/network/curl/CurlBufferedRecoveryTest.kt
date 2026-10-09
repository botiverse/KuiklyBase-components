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

    @Test
    fun dohFallbackRetriesOnlyAnUnresolvedHostWithAProviderInBudget() {
        assertTrue(shouldRetryCurlWithDohFallback(CURL_CODE_COULDNT_RESOLVE_HOST, 1, false, null))
        assertTrue(shouldRetryCurlWithDohFallback(CURL_CODE_COULDNT_RESOLVE_HOST, 2, false, 5))
        // Off, cancelled, out of time, or any other failure: the first response stands.
        assertFalse(shouldRetryCurlWithDohFallback(CURL_CODE_COULDNT_RESOLVE_HOST, 0, false, null))
        assertFalse(shouldRetryCurlWithDohFallback(CURL_CODE_COULDNT_RESOLVE_HOST, 1, true, null))
        assertFalse(shouldRetryCurlWithDohFallback(CURL_CODE_COULDNT_RESOLVE_HOST, 1, false, 0))
        assertFalse(shouldRetryCurlWithDohFallback(0, 1, false, null))
        assertFalse(shouldRetryCurlWithDohFallback(7, 1, false, null)) // couldn't connect
        assertFalse(shouldRetryCurlWithDohFallback(28, 1, false, null)) // timeout
        assertFalse(shouldRetryCurlWithDohFallback(35, 1, false, null)) // TLS
    }
}

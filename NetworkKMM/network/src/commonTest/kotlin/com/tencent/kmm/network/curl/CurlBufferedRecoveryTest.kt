/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.tencent.kmm.network.curl

import com.tencent.kmm.network.export.NetworkCurlBufferedResponsePolicy
import com.tencent.kmm.network.export.VBTransportMethod
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertTrue(shouldRetryCurlWithDohFallback(CURL_CODE_COULDNT_RESOLVE_HOST, true, false, null))
        assertTrue(shouldRetryCurlWithDohFallback(CURL_CODE_COULDNT_RESOLVE_HOST, true, false, 5))
        // Nothing left, cancelled, out of time, or any other failure: the response stands.
        assertFalse(shouldRetryCurlWithDohFallback(CURL_CODE_COULDNT_RESOLVE_HOST, false, false, null))
        assertFalse(shouldRetryCurlWithDohFallback(CURL_CODE_COULDNT_RESOLVE_HOST, true, true, null))
        assertFalse(shouldRetryCurlWithDohFallback(CURL_CODE_COULDNT_RESOLVE_HOST, true, false, 0))
        assertFalse(shouldRetryCurlWithDohFallback(0, true, false, null))
        assertFalse(shouldRetryCurlWithDohFallback(7, true, false, null)) // couldn't connect
        assertFalse(shouldRetryCurlWithDohFallback(28, true, false, null)) // timeout
        assertFalse(shouldRetryCurlWithDohFallback(35, true, false, null)) // TLS
    }

    @Test
    fun dohFallbackAttemptOrder() {
        assertEquals(listOf(1, 2), curlDohFallbackAttempts(0, listOf(1, 2)))
        assertEquals(emptyList(), curlDohFallbackAttempts(0, emptyList()))
        // A preferred provider failed: the system resolver again, then the others.
        assertEquals(listOf(0, 2), curlDohFallbackAttempts(1, listOf(1, 2)))
        assertEquals(listOf(0), curlDohFallbackAttempts(1, listOf(1)))
    }

    @Test
    fun dohPreferenceLastsTheWindowForAConfiguredProviderOnly() {
        var now = 0L
        val preference = CurlDohPreference(nowMillis = { now }, windowMillis = 60_000)
        assertEquals(0, preference.preferredProvider(listOf(1, 2)))
        preference.onDohResolved(2)
        assertEquals(2, preference.preferredProvider(listOf(1, 2)))
        assertEquals(0, preference.preferredProvider(listOf(1)), "no longer configured")
        now += 59_999
        assertEquals(2, preference.preferredProvider(listOf(1, 2)))
        now += 1
        assertEquals(0, preference.preferredProvider(listOf(1, 2)), "window over: system first again")
        preference.onDohResolved(1)
        preference.clear()
        assertEquals(0, preference.preferredProvider(listOf(1, 2)))
    }
}

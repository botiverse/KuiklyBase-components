/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.tencent.kmm.network.internal.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidStreamAttemptTimeoutBudgetTest {
    @Test
    fun ordinaryTotalCannotExpireUnlimitedStreamBeforeFirstAttempt() {
        assertEquals(
            AndroidRequestTimeoutBudget.Unlimited,
            androidAttemptTimeoutBudget(
                totalTimeoutMillis = 1,
                streamWholeTimeoutMillis = 0,
                elapsedMillis = 10_000,
                streamTimeouts = true,
            ),
        )
    }

    @Test
    fun streamRetryBudgetOnlyConsumesStreamWholeDeadline() {
        assertEquals(
            AndroidRequestTimeoutBudget.Remaining(4_000),
            androidAttemptTimeoutBudget(
                totalTimeoutMillis = 1,
                streamWholeTimeoutMillis = 10_000,
                elapsedMillis = 6_000,
                streamTimeouts = true,
            ),
        )
        assertEquals(
            AndroidRequestTimeoutBudget.Expired,
            androidAttemptTimeoutBudget(
                totalTimeoutMillis = 60_000,
                streamWholeTimeoutMillis = 10_000,
                elapsedMillis = 10_000,
                streamTimeouts = true,
            ),
        )
    }

    @Test
    fun bufferedAttemptStillUsesOrdinaryTotal() {
        assertEquals(
            AndroidRequestTimeoutBudget.Expired,
            androidAttemptTimeoutBudget(
                totalTimeoutMillis = 1,
                streamWholeTimeoutMillis = 0,
                elapsedMillis = 2,
                streamTimeouts = false,
            ),
        )
    }
}

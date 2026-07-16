/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.tencent.kmm.network.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamTimeoutPlanTest {
    @Test
    fun ktorHeaderBoundIsDocumentedCombinedCapAndZeroHeadersDisablesIt() {
        assertEquals(33_000L, streamHeadersUpperBoundMillis(3_000, 30_000))
        assertEquals(30_000L, streamHeadersUpperBoundMillis(0, 30_000))
        assertNull(streamHeadersUpperBoundMillis(3_000, 0))
    }

    @Test
    fun wholeDeadlineIsOneLogicalBudgetAcrossRetry() {
        assertEquals(10_000L, remainingStreamWholeTimeoutMillis(10_000, 0))
        assertEquals(4_000L, remainingStreamWholeTimeoutMillis(10_000, 6_000))
        assertEquals(0L, remainingStreamWholeTimeoutMillis(10_000, 11_000))
        assertNull(remainingStreamWholeTimeoutMillis(0, 11_000))
    }

    @Test
    fun responseHeaderPhaseCannotOutliveCallerWholeDeadline() {
        assertEquals(2_000L, streamPhaseTimeoutMillis(5_000L, 2_000L))
        assertEquals(5_000L, streamPhaseTimeoutMillis(5_000L, null))
        assertEquals(2_000L, streamPhaseTimeoutMillis(null, 2_000L))
        assertEquals(0L, streamPhaseTimeoutMillis(5_000L, 0L))
        assertNull(streamPhaseTimeoutMillis(null, null))
    }
}

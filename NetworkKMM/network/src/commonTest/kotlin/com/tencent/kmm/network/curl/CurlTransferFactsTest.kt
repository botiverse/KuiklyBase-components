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
package com.tencent.kmm.network.curl

import com.tencent.kmm.network.export.VBTransportElapseStatistics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CurlTransferFactsTest {
    @Test
    fun unavailableCompletionFactsPreserveEveryLegacyTimingAndExposeAbsentSchema() {
        val timing = VBTransportElapseStatistics(
            nameLookupTimeMs = 1.0,
            connectTimeMs = 2.0,
            sslCostTimeMs = 3.0,
            preTransferTime = 4.0,
            startTransferTimeMs = 5.0,
            responseWaitTimeMs = 6.0,
            totalTimeMs = 7.0,
        )

        timing.applyCurlCompletionFacts(null)

        assertEquals("unknown", timing.connectionIdentity)
        assertEquals(1.0, timing.nameLookupTimeMs)
        assertEquals(2.0, timing.connectTimeMs)
        assertEquals(3.0, timing.sslCostTimeMs)
        assertEquals(4.0, timing.preTransferTime)
        assertEquals(5.0, timing.startTransferTimeMs)
        assertEquals(6.0, timing.responseWaitTimeMs)
        assertEquals(7.0, timing.totalTimeMs)
        assertEquals("unknown", timing.curlTlsTimingState)
        assertNull(timing.curlCompletionInfoVersion)
        assertNull(timing.curlNameLookupTimingAvailable)
        assertNull(timing.curlConnectTimingAvailable)
        assertNull(timing.curlPreTransferTimingAvailable)
        assertNull(timing.curlStartTransferTimingAvailable)
        assertNull(timing.curlTotalTimingAvailable)
    }

    @Test
    fun completionFactsNamespaceConnectionIdAndExposePhaseAvailability() {
        val timing = VBTransportElapseStatistics()

        timing.applyCurlCompletionFacts(
            CurlCompletionFactsV1(
                schemaVersion = 1,
                connectionIdAvailable = true,
                nameLookupTimingAvailable = true,
                connectTimingAvailable = true,
                preTransferTimingAvailable = true,
                startTransferTimingAvailable = true,
                totalTimingAvailable = true,
                tlsTimingState = CurlTlsTimingState.REUSED_CONNECTION,
                connectionCacheId = 17,
                connectionId = 4,
                nameLookupTimeUs = 1_250,
                connectTimeUs = 2_500,
                tlsTimeUs = 99_000,
                preTransferTimeUs = 3_750,
                startTransferTimeUs = 23_500,
                totalTimeUs = 42_125,
            )
        )

        assertEquals("curl:17:4", timing.connectionIdentity)
        assertEquals(1, timing.curlCompletionInfoVersion)
        assertEquals(1.25, timing.nameLookupTimeMs)
        assertEquals(2.5, timing.connectTimeMs)
        assertEquals(0.0, timing.sslCostTimeMs)
        assertEquals(3.75, timing.preTransferTime)
        assertEquals(23.5, timing.startTransferTimeMs)
        assertEquals(23.5, timing.responseWaitTimeMs)
        assertEquals(42.125, timing.totalTimeMs)
        assertEquals(true, timing.curlNameLookupTimingAvailable)
        assertEquals(true, timing.curlConnectTimingAvailable)
        assertEquals(true, timing.curlPreTransferTimingAvailable)
        assertEquals(true, timing.curlStartTransferTimingAvailable)
        assertEquals(true, timing.curlTotalTimingAvailable)
        assertEquals("reused_connection", timing.curlTlsTimingState)
    }

    @Test
    fun unvisitedFirstByteAndTlsPhasesStayDistinctFromRealZero() {
        val timing = VBTransportElapseStatistics(
            sslCostTimeMs = 9.0,
            preTransferTime = 8.0,
            startTransferTimeMs = 7.0,
            responseWaitTimeMs = 6.0,
        )

        timing.applyCurlCompletionFacts(
            CurlCompletionFactsV1(
                schemaVersion = 1,
                connectionIdAvailable = false,
                nameLookupTimingAvailable = true,
                connectTimingAvailable = false,
                preTransferTimingAvailable = false,
                startTransferTimingAvailable = false,
                totalTimingAvailable = true,
                tlsTimingState = CurlTlsTimingState.NOT_REACHED,
                connectionCacheId = 0,
                connectionId = -1,
                nameLookupTimeUs = 500,
                connectTimeUs = 0,
                tlsTimeUs = 0,
                preTransferTimeUs = 0,
                startTransferTimeUs = 0,
                totalTimeUs = 2_000,
            )
        )

        assertEquals("unknown", timing.connectionIdentity)
        assertEquals(1, timing.curlCompletionInfoVersion)
        assertEquals(0.5, timing.nameLookupTimeMs)
        assertEquals(9.0, timing.sslCostTimeMs)
        assertEquals(8.0, timing.preTransferTime)
        assertEquals(7.0, timing.startTransferTimeMs)
        assertEquals(6.0, timing.responseWaitTimeMs)
        assertEquals(2.0, timing.totalTimeMs)
        assertEquals("not_reached", timing.curlTlsTimingState)
        assertEquals(false, timing.curlConnectTimingAvailable)
        assertEquals(false, timing.curlStartTransferTimingAvailable)
    }

    @Test
    fun nonTlsCompletionKeepsTlsAtKnownZero() {
        val timing = VBTransportElapseStatistics(sslCostTimeMs = 9.0)

        timing.applyCurlCompletionFacts(
            CurlCompletionFactsV1(
                schemaVersion = 1,
                connectionIdAvailable = true,
                nameLookupTimingAvailable = true,
                connectTimingAvailable = true,
                preTransferTimingAvailable = true,
                startTransferTimingAvailable = true,
                totalTimingAvailable = true,
                tlsTimingState = CurlTlsTimingState.NOT_APPLICABLE,
                connectionCacheId = 3,
                connectionId = 8,
                nameLookupTimeUs = 0,
                connectTimeUs = 0,
                tlsTimeUs = 123_000,
                preTransferTimeUs = 0,
                startTransferTimeUs = 0,
                totalTimeUs = 1,
            )
        )

        assertEquals(0.0, timing.sslCostTimeMs)
        assertEquals("not_applicable", timing.curlTlsTimingState)
    }

    @Test
    fun observedTlsUsesMeasuredValueIncludingRealZero() {
        val timing = VBTransportElapseStatistics(sslCostTimeMs = 9.0)

        timing.applyCurlCompletionFacts(
            CurlCompletionFactsV1(
                schemaVersion = 1,
                connectionIdAvailable = true,
                nameLookupTimingAvailable = true,
                connectTimingAvailable = true,
                preTransferTimingAvailable = true,
                startTransferTimingAvailable = true,
                totalTimingAvailable = true,
                tlsTimingState = CurlTlsTimingState.OBSERVED,
                connectionCacheId = 3,
                connectionId = 8,
                nameLookupTimeUs = 0,
                connectTimeUs = 0,
                tlsTimeUs = 0,
                preTransferTimeUs = 0,
                startTransferTimeUs = 0,
                totalTimeUs = 1,
            )
        )

        assertEquals(0.0, timing.sslCostTimeMs)
        assertEquals("observed", timing.curlTlsTimingState)
    }

    @Test
    fun invalidConnectionCoordinatesAndNegativeTimingsFailClosed() {
        val timing = VBTransportElapseStatistics(
            nameLookupTimeMs = 1.0,
            connectTimeMs = 2.0,
            totalTimeMs = 3.0,
        )

        timing.applyCurlCompletionFacts(
            CurlCompletionFactsV1(
                schemaVersion = 1,
                connectionIdAvailable = true,
                nameLookupTimingAvailable = true,
                connectTimingAvailable = true,
                preTransferTimingAvailable = false,
                startTransferTimingAvailable = false,
                totalTimingAvailable = true,
                tlsTimingState = CurlTlsTimingState.UNKNOWN,
                connectionCacheId = 0,
                connectionId = 7,
                nameLookupTimeUs = -1,
                connectTimeUs = -1,
                tlsTimeUs = -1,
                preTransferTimeUs = 0,
                startTransferTimeUs = 0,
                totalTimeUs = -1,
            )
        )

        assertEquals("unknown", timing.connectionIdentity)
        assertEquals(1, timing.curlCompletionInfoVersion)
        assertEquals(false, timing.curlNameLookupTimingAvailable)
        assertEquals(false, timing.curlConnectTimingAvailable)
        assertEquals(false, timing.curlTotalTimingAvailable)
        assertEquals(1.0, timing.nameLookupTimeMs)
        assertEquals(2.0, timing.connectTimeMs)
        assertEquals(3.0, timing.totalTimeMs)
        assertEquals("unknown", timing.curlTlsTimingState)

        timing.applyCurlCompletionFacts(
            CurlCompletionFactsV1(
                schemaVersion = 1,
                connectionIdAvailable = true,
                nameLookupTimingAvailable = false,
                connectTimingAvailable = false,
                preTransferTimingAvailable = false,
                startTransferTimingAvailable = false,
                totalTimingAvailable = false,
                tlsTimingState = CurlTlsTimingState.NOT_REACHED,
                connectionCacheId = 9,
                connectionId = -1,
                nameLookupTimeUs = 0,
                connectTimeUs = 0,
                tlsTimeUs = 0,
                preTransferTimeUs = 0,
                startTransferTimeUs = 0,
                totalTimeUs = 0,
            )
        )

        assertEquals("unknown", timing.connectionIdentity)
    }

    @Test
    fun unavailableFactsLeaveNullableObservedFlagsDistinctFromZeroElapsed() {
        val timing = VBTransportElapseStatistics()

        timing.applyCurlTransferFacts(null)

        assertNull(timing.curlFinalHeadersObserved)
        assertNull(timing.curlFirstBodyObserved)
        assertNull(timing.curlBodyProgressObserved)
        assertEquals(0.0, timing.curlFinalHeadersElapsedMs)
        assertEquals(0L, timing.curlBodyBytes)
    }

    @Test
    fun absentBodyPreservesObservedFlagsAlongsideZeroElapsedValues() {
        val timing = VBTransportElapseStatistics()

        timing.applyCurlTransferFacts(
            CurlTransferFactsV1(
                finalHeadersObserved = true,
                firstBodyObserved = false,
                bodyProgressObserved = false,
                finalHeadersElapsedMs = 17,
                firstBodyElapsedMs = 0,
                lastBodyProgressElapsedMs = 0,
                bodyBytes = 0,
            )
        )

        assertEquals(true, timing.curlFinalHeadersObserved)
        assertEquals(false, timing.curlFirstBodyObserved)
        assertEquals(false, timing.curlBodyProgressObserved)
        assertEquals(17.0, timing.curlFinalHeadersElapsedMs)
        assertEquals(0.0, timing.curlFirstBodyElapsedMs)
        assertEquals(0.0, timing.curlLastBodyProgressElapsedMs)
        assertEquals(0L, timing.curlBodyBytes)
    }
}

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

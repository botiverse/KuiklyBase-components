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

internal data class CurlTransferFactsV1(
    val finalHeadersObserved: Boolean,
    val firstBodyObserved: Boolean,
    val bodyProgressObserved: Boolean,
    val finalHeadersElapsedMs: Long,
    val firstBodyElapsedMs: Long,
    val lastBodyProgressElapsedMs: Long,
    val bodyBytes: Long,
)

internal fun VBTransportElapseStatistics.applyCurlTransferFacts(facts: CurlTransferFactsV1?) {
    if (facts == null) return
    curlFinalHeadersObserved = facts.finalHeadersObserved
    curlFirstBodyObserved = facts.firstBodyObserved
    curlBodyProgressObserved = facts.bodyProgressObserved
    curlFinalHeadersElapsedMs = facts.finalHeadersElapsedMs.toDouble()
    curlFirstBodyElapsedMs = facts.firstBodyElapsedMs.toDouble()
    curlLastBodyProgressElapsedMs = facts.lastBodyProgressElapsedMs.toDouble()
    curlBodyBytes = facts.bodyBytes
}

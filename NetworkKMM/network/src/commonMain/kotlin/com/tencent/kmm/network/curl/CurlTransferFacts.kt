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

internal enum class CurlTlsTimingState(val diagnosticValue: String) {
    UNKNOWN("unknown"),
    NOT_APPLICABLE("not_applicable"),
    OBSERVED("observed"),
    REUSED_CONNECTION("reused_connection"),
    NOT_REACHED("not_reached"),
}

internal data class CurlCompletionFactsV1(
    val schemaVersion: Int,
    val connectionIdAvailable: Boolean,
    val nameLookupTimingAvailable: Boolean,
    val connectTimingAvailable: Boolean,
    val preTransferTimingAvailable: Boolean,
    val startTransferTimingAvailable: Boolean,
    val totalTimingAvailable: Boolean,
    val tlsTimingState: CurlTlsTimingState,
    val connectionCacheId: Long,
    val connectionId: Long,
    val nameLookupTimeUs: Long,
    val connectTimeUs: Long,
    val tlsTimeUs: Long,
    val preTransferTimeUs: Long,
    val startTransferTimeUs: Long,
    val totalTimeUs: Long,
)

private const val UNKNOWN_CURL_CONNECTION_IDENTITY = "unknown"

internal fun VBTransportElapseStatistics.applyCurlCompletionFacts(facts: CurlCompletionFactsV1?) {
    // The legacy CurlResponse.elapse values are a frozen ABI. In particular,
    // an old native artifact may report a meaningful zero, so an absent V1
    // snapshot must not reinterpret or overwrite any legacy numeric field.
    if (facts == null) {
        connectionIdentity = UNKNOWN_CURL_CONNECTION_IDENTITY
        curlCompletionInfoVersion = null
        curlNameLookupTimingAvailable = null
        curlConnectTimingAvailable = null
        curlPreTransferTimingAvailable = null
        curlStartTransferTimingAvailable = null
        curlTotalTimingAvailable = null
        curlTlsTimingState = CurlTlsTimingState.UNKNOWN.diagnosticValue
        return
    }
    connectionIdentity =
        if (facts.connectionIdAvailable && facts.connectionCacheId > 0L && facts.connectionId >= 0L) {
            "curl:${facts.connectionCacheId}:${facts.connectionId}"
        } else {
            UNKNOWN_CURL_CONNECTION_IDENTITY
        }
    curlCompletionInfoVersion = facts.schemaVersion
    curlNameLookupTimingAvailable = facts.validTiming(facts.nameLookupTimingAvailable, facts.nameLookupTimeUs)
    curlConnectTimingAvailable = facts.validTiming(facts.connectTimingAvailable, facts.connectTimeUs)
    curlPreTransferTimingAvailable = facts.validTiming(facts.preTransferTimingAvailable, facts.preTransferTimeUs)
    curlStartTransferTimingAvailable = facts.validTiming(facts.startTransferTimingAvailable, facts.startTransferTimeUs)
    curlTotalTimingAvailable = facts.validTiming(facts.totalTimingAvailable, facts.totalTimeUs)
    curlTlsTimingState = facts.tlsTimingState.diagnosticValue

    if (curlNameLookupTimingAvailable == true) {
        nameLookupTimeMs = facts.nameLookupTimeUs.toMilliseconds()
    }
    if (curlConnectTimingAvailable == true) {
        connectTimeMs = facts.connectTimeUs.toMilliseconds()
    }
    when (facts.tlsTimingState) {
        CurlTlsTimingState.OBSERVED -> {
            if (facts.tlsTimeUs >= 0L) {
                sslCostTimeMs = facts.tlsTimeUs.toMilliseconds()
            }
        }
        CurlTlsTimingState.NOT_APPLICABLE,
        CurlTlsTimingState.REUSED_CONNECTION -> sslCostTimeMs = 0.0
        CurlTlsTimingState.UNKNOWN,
        CurlTlsTimingState.NOT_REACHED -> Unit
    }
    if (curlPreTransferTimingAvailable == true) {
        preTransferTime = facts.preTransferTimeUs.toMilliseconds()
    }
    if (curlStartTransferTimingAvailable == true) {
        val responseWait = facts.startTransferTimeUs.toMilliseconds()
        startTransferTimeMs = responseWait
        responseWaitTimeMs = responseWait
    }
    if (curlTotalTimingAvailable == true) {
        totalTimeMs = facts.totalTimeUs.toMilliseconds()
    }
}

private fun CurlCompletionFactsV1.validTiming(available: Boolean, microseconds: Long): Boolean =
    available && microseconds >= 0L

private fun Long.toMilliseconds(): Double = toDouble() / 1_000.0

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

internal fun VBTransportElapseStatistics.retainFirstAttemptCurlFacts(
    firstAttempt: VBTransportElapseStatistics
) {
    curlFirstAttemptFinalHeadersObserved = firstAttempt.curlFinalHeadersObserved
    curlFirstAttemptFirstBodyObserved = firstAttempt.curlFirstBodyObserved
    curlFirstAttemptBodyProgressObserved = firstAttempt.curlBodyProgressObserved
    curlFirstAttemptFinalHeadersElapsedMs = firstAttempt.curlFinalHeadersElapsedMs
    curlFirstAttemptFirstBodyElapsedMs = firstAttempt.curlFirstBodyElapsedMs
    curlFirstAttemptLastBodyProgressElapsedMs = firstAttempt.curlLastBodyProgressElapsedMs
    curlFirstAttemptBodyBytes = firstAttempt.curlBodyBytes
}

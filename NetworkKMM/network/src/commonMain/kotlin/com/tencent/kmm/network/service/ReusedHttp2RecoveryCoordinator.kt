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
package com.tencent.kmm.network.service

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * task #49 — shared quorum state machine for OHOS reused-HTTP/2 "no response
 * headers" recovery. This is the Kotlin/Native contract half of the recovery:
 * the native curl wrapper reports raw [ReusedHttp2StallFact]s (a reused-H2
 * transfer that has made no progress towards response headers past the
 * device-calibrated watchdog); this coordinator decides *when* a physical
 * connection is dead. It never retries, never closes a connection, and never
 * touches libcurl — those decisions/mechanisms belong to the routing layer and
 * the native wrapper respectively.
 *
 * Frozen contract invariants (see task #49 thread):
 * - quorum is the SIZE of the set of DISTINCT, currently-live stalled attempts
 *   on one physical connection, never a cumulative event count: a single
 *   flapping request re-reporting the same fact can never reach the threshold;
 * - the physical key is `(originId, clientGeneration, connectionId)` — a curl
 *   `CURLINFO_CONN_ID` is only an identity WITHIN one connection cache
 *   generation, so a bare `connectionId` must never be deduplicated across a
 *   generation swap;
 * - an attempt that settles (headers arrive, completes, errors, or is
 *   cancelled) is evicted immediately, so it can never contribute to a later
 *   quorum;
 * - `enabled` defaults to false: this is the `ohos_reused_h2_recovery_v0`
 *   rollout latch and stays off until device calibration proves the watchdog
 *   threshold and that `CURLINFO_CONN_ID` truthfully populates.
 *
 * All state is guarded by a single [SynchronizedObject]; `nowMillis` is injected
 * (commonMain owns no wall clock) so the churn breaker is deterministic in test.
 */
internal class ReusedHttp2RecoveryCoordinator(
    private val config: ReusedHttp2RecoveryConfig
) {
    private val lock = SynchronizedObject()

    /** Distinct live stalled attempts per physical connection. */
    private val stalledByConnection = mutableMapOf<PhysicalConnectionKey, MutableSet<Long>>()

    /**
     * Reverse index so a settled/cancelled attempt evicts in O(1) and a fact
     * that arrives under a newer key can leave its stale connection cleanly.
     */
    private val keyByAttempt = mutableMapOf<Long, PhysicalConnectionKey>()

    /** Per-origin churn breaker: suppress new deaths until this instant. */
    private val originBreakerUntil = mutableMapOf<String, Long>()

    /**
     * Feed one raw stall fact. Returns [ReusedHttp2QuorumOutcome.DeclareConnectionDead]
     * exactly on the transition where this physical connection reaches quorum;
     * otherwise [ReusedHttp2QuorumOutcome.NoAction]. Ineligible facts (recovery
     * off, not `AWAITING_HEADERS`, not negotiated H2, or not a reuse candidate)
     * are rejected fail-closed and never register an attempt.
     */
    fun onStallFact(fact: ReusedHttp2StallFact, nowMillis: Long): ReusedHttp2QuorumOutcome {
        if (!config.enabled) return ReusedHttp2QuorumOutcome.NoAction
        // v0 predicate is the conjunction reused-candidate AND actual H2 AND
        // awaiting-headers; verified here so a mis-emitted native fact cannot
        // widen recovery scope beyond the frozen contract.
        if (fact.phase != ReusedHttp2StallPhase.AWAITING_HEADERS) return ReusedHttp2QuorumOutcome.NoAction
        if (!fact.negotiatedHttp2) return ReusedHttp2QuorumOutcome.NoAction
        if (!fact.reusedConnectionCandidate) return ReusedHttp2QuorumOutcome.NoAction

        return synchronized(lock) {
            val breakerUntil = originBreakerUntil[fact.originId]
            if (breakerUntil != null) {
                if (nowMillis < breakerUntil) {
                    return@synchronized ReusedHttp2QuorumOutcome.NoAction
                }
                originBreakerUntil.remove(fact.originId)
            }

            val key = PhysicalConnectionKey(fact.originId, fact.clientGeneration, fact.connectionId)

            // If this attempt was previously counted under a different key
            // (its connection was rebound to a new generation, or curl reused
            // the integer id in a fresh cache), drop the stale membership so it
            // is only ever counted once, on its current physical connection.
            val prior = keyByAttempt[fact.transportRequestId]
            if (prior != null && prior != key) {
                stalledByConnection[prior]?.let { priorSet ->
                    priorSet.remove(fact.transportRequestId)
                    if (priorSet.isEmpty()) stalledByConnection.remove(prior)
                }
            }

            val set = stalledByConnection.getOrPut(key) { mutableSetOf() }
            set.add(fact.transportRequestId) // Set membership → duplicate facts are idempotent.
            keyByAttempt[fact.transportRequestId] = key

            if (set.size >= config.minimumConcurrentStalledRequests) {
                val stalled = set.toSet()
                // Evict the whole dead connection: the routing layer now owns
                // these attempts (drain/cancel/eligible-retry) and must not have
                // them counted again.
                for (id in stalled) {
                    keyByAttempt.remove(id)
                }
                stalledByConnection.remove(key)
                originBreakerUntil[fact.originId] = nowMillis + config.churnBreakerWindowMillis
                ReusedHttp2QuorumOutcome.DeclareConnectionDead(
                    originId = fact.originId,
                    deadClientGeneration = fact.clientGeneration,
                    connectionId = fact.connectionId,
                    stalledRequestIds = stalled
                )
            } else {
                ReusedHttp2QuorumOutcome.NoAction
            }
        }
    }

    /**
     * Report that an attempt has reached a terminal state (response headers
     * started, completed, errored, or was cancelled). Idempotent and safe for
     * an attempt that was never registered. After this returns the attempt can
     * never contribute to a quorum until it emits a fresh fact again.
     */
    fun onAttemptSettled(transportRequestId: Long) {
        synchronized(lock) {
            val key = keyByAttempt.remove(transportRequestId) ?: return
            stalledByConnection[key]?.let { set ->
                set.remove(transportRequestId)
                if (set.isEmpty()) stalledByConnection.remove(key)
            }
        }
    }

    /** Distinct live stalled attempts currently tracked on one physical connection. */
    internal fun liveStalledCount(originId: String, clientGeneration: Long, connectionId: Long): Int =
        synchronized(lock) {
            stalledByConnection[PhysicalConnectionKey(originId, clientGeneration, connectionId)]?.size ?: 0
        }

    private data class PhysicalConnectionKey(
        val originId: String,
        val clientGeneration: Long,
        val connectionId: Long
    )
}

/** The transfer phase a stall was observed in. v0 recovers only [AWAITING_HEADERS]. */
internal enum class ReusedHttp2StallPhase {
    /** Request fully sent; no response headers yet. */
    AWAITING_HEADERS,

    /** Response headers already delivered; partial body in flight (deferred). */
    MID_BODY
}

/**
 * A raw, decision-free fact from the native curl wrapper: transfer
 * [transportRequestId] on physical connection [connectionId] (within client
 * cache generation [clientGeneration]) to [originId] has stalled. The fields
 * carry the frozen v0 predicate so the coordinator can re-verify it fail-closed
 * rather than trusting the emitter.
 */
internal data class ReusedHttp2StallFact(
    val transportRequestId: Long,
    val originId: String,
    val clientGeneration: Long,
    /** curl `CURLINFO_CONN_ID`; an identity ONLY within [clientGeneration]. */
    val connectionId: Long,
    val phase: ReusedHttp2StallPhase,
    /** Actual negotiated protocol is HTTP/2 (recovery is h2-specific). */
    val negotiatedHttp2: Boolean,
    /** curl `CURLINFO_NUM_CONNECTS == 0`: this transfer opened no new connection. */
    val reusedConnectionCandidate: Boolean
)

/** Outcome of feeding one [ReusedHttp2StallFact]. */
internal sealed interface ReusedHttp2QuorumOutcome {
    /** Fact registered (or rejected) without meeting quorum. */
    object NoAction : ReusedHttp2QuorumOutcome

    /**
     * Quorum reached: this physical connection is declared dead on this
     * transition only. The routing layer should advance the origin's client
     * generation, drain/cancel the old generation, and retry the eligible
     * (GET/HEAD, replay-safe, no committed response) members of
     * [stalledRequestIds] on a fresh generation. Method eligibility, one-retry
     * bounding, and the late-old-result fence are the routing layer's contract,
     * not this coordinator's.
     */
    data class DeclareConnectionDead(
        val originId: String,
        val deadClientGeneration: Long,
        val connectionId: Long,
        val stalledRequestIds: Set<Long>
    ) : ReusedHttp2QuorumOutcome
}

/**
 * Rollout + threshold configuration, mirroring the Android
 * `VBTransportReusedHttp2Recovery` shape (task #587 / raft.25) but adapted to
 * the OHOS curl lane: no OkHttp shard/ping fields, plus a per-origin churn
 * breaker and a one-fresh-attempt bound.
 */
internal data class ReusedHttp2RecoveryConfig(
    /** `ohos_reused_h2_recovery_v0` latch; stays false until device calibration. */
    val enabled: Boolean = false,
    /** No-response-headers interval the native watchdog samples (device-calibrated). */
    val responseHeadersWatchdogMillis: Long = 7_000L,
    /** Distinct concurrent stalled attempts required on one connection (quorum). */
    val minimumConcurrentStalledRequests: Int = 2,
    /** Per-origin suppression window after a death, damping churn storms. */
    val churnBreakerWindowMillis: Long = 30_000L,
    /** Fresh recovery attempts allowed per logical request (routing layer enforces). */
    val maxFreshAttemptsPerRequest: Int = 1
) {
    init {
        require(responseHeadersWatchdogMillis > 0L) {
            "responseHeadersWatchdogMillis must be positive"
        }
        require(minimumConcurrentStalledRequests > 0) {
            "minimumConcurrentStalledRequests must be positive"
        }
        require(churnBreakerWindowMillis >= 0L) {
            "churnBreakerWindowMillis must be non-negative"
        }
        require(maxFreshAttemptsPerRequest >= 0) {
            "maxFreshAttemptsPerRequest must be non-negative"
        }
    }
}

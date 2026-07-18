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
 * task #49 — shared quorum state machine for the curl-lane (Android/iOS/OHOS)
 * reused-HTTP/2 "no response
 * headers" recovery. This is the Kotlin/Native contract half of the recovery:
 * the native curl wrapper reports raw [ReusedHttp2StallFact]s (a reused-H2
 * transfer that has made no progress towards response headers past the
 * device-calibrated watchdog); this coordinator decides *when* a physical
 * connection is dead. It never retries, never closes a connection, and never
 * touches libcurl — those decisions/mechanisms belong to the routing layer and
 * the native wrapper respectively.
 *
 * ## Attempt lifecycle (K/N authoritative)
 *
 * The routing layer owns each attempt's lifecycle and must call
 * [onAttemptStarted] when it dispatches an attempt and [onAttemptSettled] when
 * the attempt reaches any terminal state (response headers started, completed,
 * errored, or cancelled). Only facts for a currently-active attempt are
 * counted, so a native fact that races a terminal transition — delivered after
 * a settle/cancel because C→K/N callback order is not guaranteed — is fenced
 * out instead of resurrecting a terminal attempt. The active set is bounded by
 * the in-flight attempt count.
 *
 * ## Frozen contract invariants
 * - quorum is the SIZE of the set of DISTINCT, currently-live stalled attempts
 *   on one physical connection, never a cumulative event count: a single
 *   flapping request re-reporting the same fact can never reach the threshold;
 * - the physical key is `(originId, clientGeneration, connectionId)` — a curl
 *   `CURLINFO_CONN_ID` is only an identity WITHIN one connection cache
 *   generation, so a bare `connectionId` must never be deduplicated across a
 *   generation swap;
 * - an attempt only ever contributes to ONE physical connection — its latest
 *   one; a fact that rebinds it to a new key evicts the stale membership first,
 *   unconditionally, even while the origin's churn breaker is suppressing new
 *   deaths;
 * - an ineligible latest fact (outside the v0 `AWAITING_HEADERS` / negotiated
 *   H2 / reuse-candidate population) evicts any existing membership for that
 *   attempt fail-closed rather than leaving it counted;
 * - an attempt that settles/cancels is evicted immediately and fenced, so it
 *   can never contribute to a later quorum;
 * - declaring dead is once-per-generation, process-wide: the curl connection
 *   shares are process-level and cross-origin and a declaration rolls both
 *   global cohorts together, so the first declaration advances a single global
 *   retired high-watermark, and every later fact at or below it — a not-yet-
 *   quorum stream on the dead connection, a sibling connection in the same
 *   generation, or another origin still on that generation — is rejected, so no
 *   second rollover fires before the routing layer settles the affected
 *   attempts; a genuine retry re-enters on a higher generation;
 * - `enabled` defaults to false: this is the `curl_reused_h2_recovery_v0`
 *   rollout latch and stays off until device calibration proves the watchdog
 *   threshold and that `CURLINFO_CONN_ID` truthfully populates.
 *
 * All state is guarded by a single [SynchronizedObject]; `nowMillis` is injected
 * (commonMain owns no wall clock) so the churn breaker is deterministic in test.
 * No callback or external side effect runs under the lock.
 */
internal class ReusedHttp2RecoveryCoordinator(
    private val config: ReusedHttp2RecoveryConfig
) {
    private val lock = SynchronizedObject()

    /** Attempts the routing layer has dispatched and not yet settled. */
    private val activeAttempts = mutableSetOf<Long>()

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
     * Highest client generation already declared dead, process-wide. The curl
     * default and H3 connection shares are process-level and cross-origin, and a
     * declaration rolls both global cohorts together, so retirement is a single
     * global high-watermark — not per-origin. Any later fact at or below it — a
     * not-yet-quorum stream on the dead connection, a sibling connection in the
     * same generation, or another origin still on that generation — must not
     * fire a second rollover. `null` until the first declaration; a single Long,
     * so no unbounded dead-key set and no pruning.
     */
    private var maxRetiredClientGeneration: Long? = null

    /**
     * Register an attempt as active when the routing layer dispatches it.
     *
     * [transportRequestId] MUST be unique per physical attempt for as long as a
     * late native fact for it could still arrive; otherwise a recycled id lets
     * an old attempt's late fact act on a newer attempt (an ABA hazard). The
     * underlying VBTransport `requestId` is a recyclable `Int`, so the phase-#2
     * native adapter must supply a monotonic/epoch-tagged token here rather than
     * the bare id. As defence in depth a (re-)started token first drops any
     * residual membership from a prior life of the same id, but only when it is
     * not already active, so a redundant start never clobbers live membership.
     */
    fun onAttemptStarted(transportRequestId: Long) {
        synchronized(lock) {
            if (transportRequestId !in activeAttempts) {
                keyByAttempt[transportRequestId]?.let { key ->
                    evictFromConnection(transportRequestId, key)
                }
            }
            activeAttempts.add(transportRequestId)
        }
    }

    /**
     * Feed one raw stall fact. Returns [ReusedHttp2QuorumOutcome.DeclareConnectionDead]
     * exactly on the transition where this physical connection reaches quorum;
     * otherwise [ReusedHttp2QuorumOutcome.NoAction]. Facts for a non-active
     * attempt (never started, or already settled/cancelled) and ineligible
     * facts (recovery off, not `AWAITING_HEADERS`, not negotiated H2, or not a
     * reuse candidate) are rejected fail-closed; an ineligible fact still evicts
     * any stale membership the attempt held.
     */
    fun onStallFact(fact: ReusedHttp2StallFact, nowMillis: Long): ReusedHttp2QuorumOutcome {
        if (!config.enabled) return ReusedHttp2QuorumOutcome.NoAction

        return synchronized(lock) {
            // Terminal fence: a fact that races (or follows) a settle/cancel must
            // never resurrect the attempt. Only active attempts are counted.
            if (fact.transportRequestId !in activeAttempts) {
                return@synchronized ReusedHttp2QuorumOutcome.NoAction
            }

            val currentKey = PhysicalConnectionKey(fact.originId, fact.clientGeneration, fact.connectionId)
            val priorKey = keyByAttempt[fact.transportRequestId]

            // Global retired-generation fence, placed BEFORE the eligibility
            // predicate AND the rebound eviction: a fact at or below the
            // process-wide retired high-watermark — eligible OR ineligible — is
            // on a dead generation, declares nothing, and must never disturb a
            // live newer-generation membership (an ineligible stale fact must not
            // slip past into the unconditional eviction below). It only clears
            // the attempt's own membership when that membership is itself at or
            // below the watermark.
            val retired = maxRetiredClientGeneration
            if (retired != null && fact.clientGeneration <= retired) {
                if (priorKey != null && priorKey.clientGeneration <= retired) {
                    evictFromConnection(fact.transportRequestId, priorKey)
                }
                return@synchronized ReusedHttp2QuorumOutcome.NoAction
            }

            // v0 predicate is the conjunction reused-candidate AND actual H2 AND
            // awaiting-headers, re-verified here fail-closed. An attempt whose
            // LATEST fact is ineligible has left the recoverable population, so
            // it must be evicted rather than left counted on a stale key. This is
            // reached only for facts above the retired watermark, so the eviction
            // acts on a live-generation membership.
            val eligible = fact.phase == ReusedHttp2StallPhase.AWAITING_HEADERS &&
                fact.negotiatedHttp2 &&
                fact.reusedConnectionCandidate
            if (!eligible) {
                if (priorKey != null) evictFromConnection(fact.transportRequestId, priorKey)
                return@synchronized ReusedHttp2QuorumOutcome.NoAction
            }

            // If this attempt was previously counted under a different key (its
            // connection was rebound to a new generation, or curl reused the
            // integer id in a fresh cache), drop the stale membership FIRST and
            // unconditionally — before any churn-breaker suppression — so the
            // attempt is only ever counted once, on its current connection.
            if (priorKey != null && priorKey != currentKey) {
                evictFromConnection(fact.transportRequestId, priorKey)
            }

            // Churn breaker only gates registration/death on this origin; it must
            // never preserve stale membership (handled above).
            val breakerUntil = originBreakerUntil[fact.originId]
            if (breakerUntil != null) {
                if (nowMillis < breakerUntil) {
                    return@synchronized ReusedHttp2QuorumOutcome.NoAction
                }
                originBreakerUntil.remove(fact.originId)
            }

            val set = stalledByConnection.getOrPut(currentKey) { mutableSetOf() }
            set.add(fact.transportRequestId) // Set membership → duplicate facts are idempotent.
            keyByAttempt[fact.transportRequestId] = currentKey

            if (set.size >= config.minimumConcurrentStalledRequests) {
                val stalled = set.toSet()
                // Retire the whole generation once, process-wide (a declaration
                // rolls both global cohorts together, across origins). Advance
                // the global high-watermark and drop ALL stalled membership +
                // reverse index at or below it — this connection, any sibling
                // connection in the generation, and any other origin still on it
                // — so none can declare a second rollover. Active attempts are
                // left for the routing layer to settle; we never fabricate
                // completion here.
                val retiredNow = maxRetiredClientGeneration
                    ?.let { maxOf(it, fact.clientGeneration) }
                    ?: fact.clientGeneration
                maxRetiredClientGeneration = retiredNow
                val retiredKeys = stalledByConnection.keys.filter { it.clientGeneration <= retiredNow }
                for (retiredKey in retiredKeys) {
                    stalledByConnection.remove(retiredKey)?.forEach { keyByAttempt.remove(it) }
                }
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
     * started, completed, errored, or was cancelled). Removes it from the active
     * set — fencing any later native fact — and evicts any live membership.
     * Idempotent and safe for an attempt that was never registered.
     */
    fun onAttemptSettled(transportRequestId: Long) {
        synchronized(lock) {
            activeAttempts.remove(transportRequestId)
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

    /** Remove [id] from [key]'s set and the reverse index (only if still pointing there). */
    private fun evictFromConnection(id: Long, key: PhysicalConnectionKey) {
        stalledByConnection[key]?.let { set ->
            set.remove(id)
            if (set.isEmpty()) stalledByConnection.remove(key)
        }
        if (keyByAttempt[id] == key) keyByAttempt.remove(id)
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
 * the curl lane (Android/iOS/OHOS): no OkHttp shard/ping fields, plus a per-origin churn
 * breaker and a one-fresh-attempt bound.
 */
internal data class ReusedHttp2RecoveryConfig(
    /** `curl_reused_h2_recovery_v0` latch; stays false until device calibration. */
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
        // Quorum is the false-positive guard; a single-request kill defeats it,
        // so v0 mechanically forbids it (see task #49 review hardening).
        require(minimumConcurrentStalledRequests >= 2) {
            "minimumConcurrentStalledRequests must be at least 2"
        }
        require(churnBreakerWindowMillis >= 0L) {
            "churnBreakerWindowMillis must be non-negative"
        }
        require(maxFreshAttemptsPerRequest in 0..1) {
            "maxFreshAttemptsPerRequest must be 0 or 1"
        }
    }
}

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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * task #49 gate #1: deterministic proofs that quorum counts DISTINCT LIVE
 * attempts on one physical connection, that terminal/cancel evicts and fences,
 * that a rebound/ineligible latest fact never leaves stale membership, and that
 * the frozen predicate/scope/breaker invariants hold. Each test targets a
 * single invariant a wrong implementation would violate; the B1/B2/B3 tests
 * reproduce the exact false-quorum counterexamples from the PR #100 review.
 */
class ReusedHttp2RecoveryCoordinatorTest {

    private val origin = "api.example.com:443"
    private val enabled = ReusedHttp2RecoveryConfig(enabled = true)

    private fun fact(
        id: Long,
        origin: String = this.origin,
        generation: Long = 0L,
        connectionId: Long = 7L,
        phase: ReusedHttp2StallPhase = ReusedHttp2StallPhase.AWAITING_HEADERS,
        http2: Boolean = true,
        reused: Boolean = true
    ) = ReusedHttp2StallFact(
        transportRequestId = id,
        originId = origin,
        clientGeneration = generation,
        connectionId = connectionId,
        phase = phase,
        negotiatedHttp2 = http2,
        reusedConnectionCandidate = reused
    )

    private fun ReusedHttp2RecoveryCoordinator.started(vararg ids: Long) {
        for (id in ids) onAttemptStarted(id)
    }

    @Test
    fun twoDistinctLiveAttemptsOnSameConnectionReachQuorum() {
        val coordinator = ReusedHttp2RecoveryCoordinator(enabled)
        coordinator.started(1L, 2L)

        assertIs<ReusedHttp2QuorumOutcome.NoAction>(coordinator.onStallFact(fact(id = 1), nowMillis = 0))
        val outcome = coordinator.onStallFact(fact(id = 2), nowMillis = 10)

        val dead = assertIs<ReusedHttp2QuorumOutcome.DeclareConnectionDead>(outcome)
        assertEquals(setOf(1L, 2L), dead.stalledRequestIds)
        assertEquals(0L, dead.deadClientGeneration)
        assertEquals(7L, dead.connectionId)
    }

    @Test
    fun repeatedFactFromOneAttemptNeverReachesQuorum() {
        val coordinator = ReusedHttp2RecoveryCoordinator(enabled)
        coordinator.started(1L)

        repeat(5) { i ->
            assertIs<ReusedHttp2QuorumOutcome.NoAction>(
                coordinator.onStallFact(fact(id = 1), nowMillis = i.toLong())
            )
        }
        assertEquals(1, coordinator.liveStalledCount(origin, 0L, 7L))
    }

    @Test
    fun factForNeverStartedAttemptIsFenced() {
        val coordinator = ReusedHttp2RecoveryCoordinator(enabled)
        // No onAttemptStarted: a fact for an unknown attempt is rejected fail-closed.
        assertIs<ReusedHttp2QuorumOutcome.NoAction>(coordinator.onStallFact(fact(id = 1), nowMillis = 0))
        assertEquals(0, coordinator.liveStalledCount(origin, 0L, 7L))
    }

    @Test
    fun settledAttemptIsEvictedBeforeQuorum() {
        val coordinator = ReusedHttp2RecoveryCoordinator(enabled)
        coordinator.started(1L, 2L)

        coordinator.onStallFact(fact(id = 1), nowMillis = 0)
        coordinator.onAttemptSettled(transportRequestId = 1)
        val outcome = coordinator.onStallFact(fact(id = 2), nowMillis = 10)

        assertIs<ReusedHttp2QuorumOutcome.NoAction>(outcome)
        assertEquals(1, coordinator.liveStalledCount(origin, 0L, 7L))
    }

    // PR #100 review B3: settle/cancel wins before a queued native fact; the
    // late fact must not resurrect the terminal attempt into a quorum.
    @Test
    fun settleBeforeFirstFactFencesLateFact() {
        val coordinator = ReusedHttp2RecoveryCoordinator(enabled)
        coordinator.started(20L, 21L)

        coordinator.onAttemptSettled(transportRequestId = 20) // cancelled before any fact
        assertIs<ReusedHttp2QuorumOutcome.NoAction>(coordinator.onStallFact(fact(id = 20), nowMillis = 5))
        assertEquals(0, coordinator.liveStalledCount(origin, 0L, 7L))

        val outcome = coordinator.onStallFact(fact(id = 21), nowMillis = 10)
        assertIs<ReusedHttp2QuorumOutcome.NoAction>(outcome)
        assertEquals(1, coordinator.liveStalledCount(origin, 0L, 7L))
    }

    @Test
    fun settleAfterRegistrationFencesLateFact() {
        val coordinator = ReusedHttp2RecoveryCoordinator(enabled)
        coordinator.started(20L, 21L)

        coordinator.onStallFact(fact(id = 20), nowMillis = 0) // registered
        coordinator.onAttemptSettled(transportRequestId = 20) // terminal → evict + fence
        // A native fact delivered after the settle must be rejected, not re-added.
        assertIs<ReusedHttp2QuorumOutcome.NoAction>(coordinator.onStallFact(fact(id = 20), nowMillis = 5))

        val outcome = coordinator.onStallFact(fact(id = 21), nowMillis = 10)
        assertIs<ReusedHttp2QuorumOutcome.NoAction>(outcome)
        assertEquals(1, coordinator.liveStalledCount(origin, 0L, 7L))
    }

    @Test
    fun sameConnectionIdInDifferentGenerationIsNotTheSamePhysicalConnection() {
        val coordinator = ReusedHttp2RecoveryCoordinator(enabled)
        coordinator.started(1L, 2L)

        assertIs<ReusedHttp2QuorumOutcome.NoAction>(
            coordinator.onStallFact(fact(id = 1, generation = 0L, connectionId = 7L), nowMillis = 0)
        )
        assertIs<ReusedHttp2QuorumOutcome.NoAction>(
            coordinator.onStallFact(fact(id = 2, generation = 1L, connectionId = 7L), nowMillis = 10)
        )
        assertEquals(1, coordinator.liveStalledCount(origin, 0L, 7L))
        assertEquals(1, coordinator.liveStalledCount(origin, 1L, 7L))
    }

    @Test
    fun sameConnectionIdAcrossDifferentOriginsIsNotQuorum() {
        val coordinator = ReusedHttp2RecoveryCoordinator(enabled)
        coordinator.started(1L, 2L)

        assertIs<ReusedHttp2QuorumOutcome.NoAction>(
            coordinator.onStallFact(fact(id = 1, origin = "a.example.com:443"), nowMillis = 0)
        )
        assertIs<ReusedHttp2QuorumOutcome.NoAction>(
            coordinator.onStallFact(fact(id = 2, origin = "b.example.com:443"), nowMillis = 10)
        )
    }

    @Test
    fun ineligibleFactsAreRejectedAndDoNotRegister() {
        val coordinator = ReusedHttp2RecoveryCoordinator(enabled)
        coordinator.started(1L, 2L, 3L, 4L)

        coordinator.onStallFact(fact(id = 1, phase = ReusedHttp2StallPhase.MID_BODY), nowMillis = 0)
        coordinator.onStallFact(fact(id = 2, http2 = false), nowMillis = 1)
        coordinator.onStallFact(fact(id = 3, reused = false), nowMillis = 2)
        assertEquals(0, coordinator.liveStalledCount(origin, 0L, 7L))

        val outcome = coordinator.onStallFact(fact(id = 4), nowMillis = 3)
        assertIs<ReusedHttp2QuorumOutcome.NoAction>(outcome)
    }

    // PR #100 review B2: an ineligible LATEST fact for an already-registered
    // attempt must evict it fail-closed, for every ineligible transition type.
    @Test
    fun ineligibleLatestFactEvictsPreviouslyEligibleMembership() {
        val ineligibleTransitions = listOf(
            fact(id = 10, phase = ReusedHttp2StallPhase.MID_BODY),
            fact(id = 10, http2 = false),
            fact(id = 10, reused = false)
        )
        for (ineligible in ineligibleTransitions) {
            val coordinator = ReusedHttp2RecoveryCoordinator(enabled)
            coordinator.started(10L, 11L)

            coordinator.onStallFact(fact(id = 10), nowMillis = 0)
            assertEquals(1, coordinator.liveStalledCount(origin, 0L, 7L))

            assertIs<ReusedHttp2QuorumOutcome.NoAction>(coordinator.onStallFact(ineligible, nowMillis = 1))
            assertEquals(0, coordinator.liveStalledCount(origin, 0L, 7L))

            // Attempt 11 alone must not reach quorum: 10 is no longer counted.
            val outcome = coordinator.onStallFact(fact(id = 11), nowMillis = 2)
            assertIs<ReusedHttp2QuorumOutcome.NoAction>(outcome)
        }
    }

    @Test
    fun disabledCoordinatorNeverActs() {
        val coordinator = ReusedHttp2RecoveryCoordinator(ReusedHttp2RecoveryConfig(enabled = false))
        coordinator.started(1L, 2L)

        assertIs<ReusedHttp2QuorumOutcome.NoAction>(coordinator.onStallFact(fact(id = 1), nowMillis = 0))
        assertIs<ReusedHttp2QuorumOutcome.NoAction>(coordinator.onStallFact(fact(id = 2), nowMillis = 10))
        assertEquals(0, coordinator.liveStalledCount(origin, 0L, 7L))
    }

    @Test
    fun churnBreakerSuppressesRepeatDeathWithinWindowThenReopens() {
        val coordinator = ReusedHttp2RecoveryCoordinator(
            ReusedHttp2RecoveryConfig(enabled = true, churnBreakerWindowMillis = 30_000L)
        )
        coordinator.started(1L, 2L, 3L, 4L, 5L, 6L)

        coordinator.onStallFact(fact(id = 1), nowMillis = 0)
        assertIs<ReusedHttp2QuorumOutcome.DeclareConnectionDead>(
            coordinator.onStallFact(fact(id = 2), nowMillis = 100)
        )

        coordinator.onStallFact(fact(id = 3, generation = 1L, connectionId = 9L), nowMillis = 1_000)
        assertIs<ReusedHttp2QuorumOutcome.NoAction>(
            coordinator.onStallFact(fact(id = 4, generation = 1L, connectionId = 9L), nowMillis = 2_000)
        )

        coordinator.onStallFact(fact(id = 5, generation = 2L, connectionId = 11L), nowMillis = 40_000)
        assertIs<ReusedHttp2QuorumOutcome.DeclareConnectionDead>(
            coordinator.onStallFact(fact(id = 6, generation = 2L, connectionId = 11L), nowMillis = 40_100)
        )
    }

    // PR #100 review B1: an attempt that rebinds to a new key during the breaker
    // window must still leave its prior physical membership, or a later attempt
    // falsely completes a quorum on the stale connection after the window.
    @Test
    fun reboundDuringBreakerEvictsPriorPhysicalMembership() {
        val coordinator = ReusedHttp2RecoveryCoordinator(
            ReusedHttp2RecoveryConfig(enabled = true, churnBreakerWindowMillis = 100L)
        )
        coordinator.started(1L, 2L, 3L, 4L)

        coordinator.onStallFact(fact(id = 1, generation = 0L, connectionId = 7L), nowMillis = 0)
        coordinator.onStallFact(fact(id = 2, generation = 0L, connectionId = 8L), nowMillis = 1)
        assertIs<ReusedHttp2QuorumOutcome.DeclareConnectionDead>(
            coordinator.onStallFact(fact(id = 3, generation = 0L, connectionId = 8L), nowMillis = 2)
        )
        // Attempt 1 rebinds to gen1/conn9 while origin O is in the breaker window.
        coordinator.onStallFact(fact(id = 1, generation = 1L, connectionId = 9L), nowMillis = 3)
        // Its stale gen0/conn7 membership must be gone.
        assertEquals(0, coordinator.liveStalledCount(origin, 0L, 7L))

        // A fresh attempt on the now-retired gen0 is rejected outright — even
        // after the breaker window — so no stale membership can ever complete a
        // false quorum on the dead generation.
        val outcome = coordinator.onStallFact(fact(id = 4, generation = 0L, connectionId = 7L), nowMillis = 103)
        assertIs<ReusedHttp2QuorumOutcome.NoAction>(outcome)
        assertEquals(0, coordinator.liveStalledCount(origin, 0L, 7L))
    }

    @Test
    fun quorumFiresOnceThenConnectionSetIsCleared() {
        val coordinator = ReusedHttp2RecoveryCoordinator(
            ReusedHttp2RecoveryConfig(enabled = true, churnBreakerWindowMillis = 0L)
        )
        coordinator.started(1L, 2L)

        coordinator.onStallFact(fact(id = 1), nowMillis = 0)
        assertIs<ReusedHttp2QuorumOutcome.DeclareConnectionDead>(
            coordinator.onStallFact(fact(id = 2), nowMillis = 10)
        )
        assertEquals(0, coordinator.liveStalledCount(origin, 0L, 7L))
    }

    // PR #100 successor review: declaring dead is once-per-origin-generation.
    // With no breaker window, late facts for the just-declared quorum members
    // are on the retired generation and must be rejected, or the same generation
    // is declared dead a second time before routing settles them.
    @Test
    fun quorumDeclarationTerminalFencesMembersAgainstLateFacts() {
        val coordinator = ReusedHttp2RecoveryCoordinator(
            ReusedHttp2RecoveryConfig(enabled = true, churnBreakerWindowMillis = 0L)
        )
        coordinator.started(1L, 2L)

        assertIs<ReusedHttp2QuorumOutcome.NoAction>(coordinator.onStallFact(fact(id = 1), nowMillis = 0))
        assertIs<ReusedHttp2QuorumOutcome.DeclareConnectionDead>(
            coordinator.onStallFact(fact(id = 2), nowMillis = 10)
        )

        assertIs<ReusedHttp2QuorumOutcome.NoAction>(coordinator.onStallFact(fact(id = 1), nowMillis = 11))
        assertIs<ReusedHttp2QuorumOutcome.NoAction>(coordinator.onStallFact(fact(id = 2), nowMillis = 12))
        assertEquals(0, coordinator.liveStalledCount(origin, 0L, 7L))
    }

    // PR #100 review round 3: a not-yet-quorum active stream on the SAME dead
    // connection must not fire a second declaration even with no breaker window.
    @Test
    fun otherActiveStreamOnDeadConnectionCannotSecondDeclare() {
        val coordinator = ReusedHttp2RecoveryCoordinator(
            ReusedHttp2RecoveryConfig(enabled = true, churnBreakerWindowMillis = 0L)
        )
        coordinator.started(1L, 2L, 3L, 4L)

        coordinator.onStallFact(fact(id = 1, connectionId = 7L), nowMillis = 0)
        assertIs<ReusedHttp2QuorumOutcome.DeclareConnectionDead>(
            coordinator.onStallFact(fact(id = 2, connectionId = 7L), nowMillis = 10)
        )
        // Streams 3 and 4 were live on conn7 but never reached the first quorum;
        // the retired generation rejects them, so no second rollover fires.
        assertIs<ReusedHttp2QuorumOutcome.NoAction>(coordinator.onStallFact(fact(id = 3, connectionId = 7L), nowMillis = 11))
        assertIs<ReusedHttp2QuorumOutcome.NoAction>(coordinator.onStallFact(fact(id = 4, connectionId = 7L), nowMillis = 12))
        assertEquals(0, coordinator.liveStalledCount(origin, 0L, 7L))
    }

    // PR #100 review round 3: a sibling connection in the SAME retired generation
    // cannot fire a second rollover; a genuine higher-generation retry still can.
    @Test
    fun siblingConnectionInRetiredGenerationCannotSecondDeclareButNewGenerationCan() {
        val coordinator = ReusedHttp2RecoveryCoordinator(
            ReusedHttp2RecoveryConfig(enabled = true, churnBreakerWindowMillis = 0L)
        )
        coordinator.started(1L, 2L, 3L, 4L, 5L, 6L)

        coordinator.onStallFact(fact(id = 1, generation = 0L, connectionId = 7L), nowMillis = 0)
        assertIs<ReusedHttp2QuorumOutcome.DeclareConnectionDead>(
            coordinator.onStallFact(fact(id = 2, generation = 0L, connectionId = 7L), nowMillis = 10)
        )
        // conn8 is a different connection but in the retired gen0: rejected.
        assertIs<ReusedHttp2QuorumOutcome.NoAction>(
            coordinator.onStallFact(fact(id = 3, generation = 0L, connectionId = 8L), nowMillis = 11)
        )
        assertIs<ReusedHttp2QuorumOutcome.NoAction>(
            coordinator.onStallFact(fact(id = 4, generation = 0L, connectionId = 8L), nowMillis = 12)
        )

        // A genuine retry on gen1 (fresh tokens) is above the retired generation
        // and can still declare its own connection dead.
        assertIs<ReusedHttp2QuorumOutcome.NoAction>(
            coordinator.onStallFact(fact(id = 5, generation = 1L, connectionId = 9L), nowMillis = 13)
        )
        val outcome = coordinator.onStallFact(fact(id = 6, generation = 1L, connectionId = 9L), nowMillis = 14)
        val dead = assertIs<ReusedHttp2QuorumOutcome.DeclareConnectionDead>(outcome)
        assertEquals(setOf(5L, 6L), dead.stalledRequestIds)
        assertTrue(dead.deadClientGeneration == 1L)
    }

    // PR #100 review round 3 (global cohort scope): the retired generation is
    // process-wide, so a DIFFERENT origin still on the retired generation cannot
    // fire a second global rollover; a higher generation on any origin still can.
    @Test
    fun otherOriginInRetiredGenerationCannotSecondDeclareButNewGenerationCan() {
        val coordinator = ReusedHttp2RecoveryCoordinator(
            ReusedHttp2RecoveryConfig(enabled = true, churnBreakerWindowMillis = 0L)
        )
        coordinator.started(1L, 2L, 3L, 4L, 5L, 6L)

        coordinator.onStallFact(fact(id = 1, origin = "a.example.com:443", connectionId = 7L), nowMillis = 0)
        assertIs<ReusedHttp2QuorumOutcome.DeclareConnectionDead>(
            coordinator.onStallFact(fact(id = 2, origin = "a.example.com:443", connectionId = 7L), nowMillis = 10)
        )
        // Origin B still on the retired gen0 is rejected by the global watermark.
        assertIs<ReusedHttp2QuorumOutcome.NoAction>(
            coordinator.onStallFact(fact(id = 3, origin = "b.example.com:443", connectionId = 8L), nowMillis = 11)
        )
        assertIs<ReusedHttp2QuorumOutcome.NoAction>(
            coordinator.onStallFact(fact(id = 4, origin = "b.example.com:443", connectionId = 8L), nowMillis = 12)
        )
        // Origin B on gen1 (above the watermark) can still declare.
        assertIs<ReusedHttp2QuorumOutcome.NoAction>(
            coordinator.onStallFact(fact(id = 5, origin = "b.example.com:443", generation = 1L, connectionId = 9L), nowMillis = 13)
        )
        val dead = assertIs<ReusedHttp2QuorumOutcome.DeclareConnectionDead>(
            coordinator.onStallFact(fact(id = 6, origin = "b.example.com:443", generation = 1L, connectionId = 9L), nowMillis = 14)
        )
        assertEquals(setOf(5L, 6L), dead.stalledRequestIds)
    }

    // PR #100 review round 3: a stale fact at (or below) the retired generation
    // must not evict an attempt's live membership on a newer generation.
    @Test
    fun staleFactAtRetiredGenerationDoesNotEvictLiveNewerMembership() {
        val coordinator = ReusedHttp2RecoveryCoordinator(
            ReusedHttp2RecoveryConfig(enabled = true, churnBreakerWindowMillis = 0L)
        )
        coordinator.started(1L, 2L, 3L)

        coordinator.onStallFact(fact(id = 1, generation = 0L, connectionId = 7L), nowMillis = 0)
        assertIs<ReusedHttp2QuorumOutcome.DeclareConnectionDead>(
            coordinator.onStallFact(fact(id = 2, generation = 0L, connectionId = 7L), nowMillis = 10)
        )

        // Attempt 3 is live on gen1.
        coordinator.onStallFact(fact(id = 3, generation = 1L, connectionId = 9L), nowMillis = 11)
        assertEquals(1, coordinator.liveStalledCount(origin, 1L, 9L))

        // A stale gen0 late fact for attempt 3 is rejected AND must not disturb
        // its live gen1 membership.
        assertIs<ReusedHttp2QuorumOutcome.NoAction>(
            coordinator.onStallFact(fact(id = 3, generation = 0L, connectionId = 7L), nowMillis = 12)
        )
        assertEquals(1, coordinator.liveStalledCount(origin, 1L, 9L))
    }

    // PR #100 review round 3 final: a stale fact at the retired generation that
    // is ALSO ineligible must be caught by the fence BEFORE the unconditional
    // ineligible eviction, or it drops a live newer-generation membership and a
    // real quorum is missed. Covers all three ineligible transition types.
    @Test
    fun ineligibleStaleFactAtRetiredGenerationDoesNotEvictLiveNewerMembership() {
        val ineligibleStaleFacts = listOf(
            fact(id = 3, generation = 0L, connectionId = 7L, phase = ReusedHttp2StallPhase.MID_BODY),
            fact(id = 3, generation = 0L, connectionId = 7L, http2 = false),
            fact(id = 3, generation = 0L, connectionId = 7L, reused = false)
        )
        for (stale in ineligibleStaleFacts) {
            val coordinator = ReusedHttp2RecoveryCoordinator(
                ReusedHttp2RecoveryConfig(enabled = true, churnBreakerWindowMillis = 0L)
            )
            coordinator.started(1L, 2L, 3L, 4L)

            coordinator.onStallFact(fact(id = 1, generation = 0L, connectionId = 7L), nowMillis = 0)
            assertIs<ReusedHttp2QuorumOutcome.DeclareConnectionDead>(
                coordinator.onStallFact(fact(id = 2, generation = 0L, connectionId = 7L), nowMillis = 10)
            )

            // token3 live on gen1.
            coordinator.onStallFact(fact(id = 3, generation = 1L, connectionId = 9L), nowMillis = 11)
            assertEquals(1, coordinator.liveStalledCount(origin, 1L, 9L))

            // A stale gen0 fact that is also ineligible must not evict token3's
            // live gen1 membership.
            assertIs<ReusedHttp2QuorumOutcome.NoAction>(coordinator.onStallFact(stale, nowMillis = 12))
            assertEquals(1, coordinator.liveStalledCount(origin, 1L, 9L))

            // The real gen1 quorum still fires.
            val dead = assertIs<ReusedHttp2QuorumOutcome.DeclareConnectionDead>(
                coordinator.onStallFact(fact(id = 4, generation = 1L, connectionId = 9L), nowMillis = 13)
            )
            assertEquals(setOf(3L, 4L), dead.stalledRequestIds)
        }
    }

    @Test
    fun attemptReboundToNewGenerationIsNotDoubleCounted() {
        val coordinator = ReusedHttp2RecoveryCoordinator(
            ReusedHttp2RecoveryConfig(enabled = true, churnBreakerWindowMillis = 0L)
        )
        coordinator.started(1L, 2L)

        coordinator.onStallFact(fact(id = 1, generation = 0L, connectionId = 7L), nowMillis = 0)
        coordinator.onStallFact(fact(id = 1, generation = 1L, connectionId = 7L), nowMillis = 5)
        assertEquals(0, coordinator.liveStalledCount(origin, 0L, 7L))
        assertEquals(1, coordinator.liveStalledCount(origin, 1L, 7L))

        val outcome = coordinator.onStallFact(fact(id = 2, generation = 1L, connectionId = 7L), nowMillis = 10)
        val dead = assertIs<ReusedHttp2QuorumOutcome.DeclareConnectionDead>(outcome)
        assertEquals(setOf(1L, 2L), dead.stalledRequestIds)
        assertTrue(dead.deadClientGeneration == 1L)
    }

    // PR #100 review B3 ABA hardening: a redundant start for an already-active,
    // registered attempt must not evict its live membership (the residual-clear
    // is guarded on !active). The recycled-id resurrection it defends against is
    // prevented upstream by the token-uniqueness contract on onAttemptStarted.
    @Test
    fun redundantStartDoesNotClobberLiveMembership() {
        val coordinator = ReusedHttp2RecoveryCoordinator(enabled)
        coordinator.started(1L)

        coordinator.onStallFact(fact(id = 1), nowMillis = 0)
        assertEquals(1, coordinator.liveStalledCount(origin, 0L, 7L))

        coordinator.onAttemptStarted(1L)
        assertEquals(1, coordinator.liveStalledCount(origin, 0L, 7L))
    }

    @Test
    fun configRejectsQuorumBelowTwo() {
        for (invalid in listOf(0, 1)) {
            var threw = false
            try {
                ReusedHttp2RecoveryConfig(minimumConcurrentStalledRequests = invalid)
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertTrue(threw, "quorum $invalid must be rejected")
        }
    }

    // "at most one fresh attempt" — the bound must reject values above 1.
    @Test
    fun configRejectsMaxFreshAttemptsAboveOne() {
        var threw = false
        try {
            ReusedHttp2RecoveryConfig(maxFreshAttemptsPerRequest = 2)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}

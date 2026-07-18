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

        // After the window a fresh attempt on the stale connection must be alone.
        val outcome = coordinator.onStallFact(fact(id = 4, generation = 0L, connectionId = 7L), nowMillis = 103)
        assertIs<ReusedHttp2QuorumOutcome.NoAction>(outcome)
        assertEquals(1, coordinator.liveStalledCount(origin, 0L, 7L))
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

    // PR #100 successor review: declaring a connection dead is once-only. With
    // no breaker window, late facts for the just-declared quorum members must be
    // fenced (they were terminal-fenced at declaration), or the same physical
    // connection is declared dead a second time before routing settles them.
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
}

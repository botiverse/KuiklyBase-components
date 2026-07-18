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
 * attempts on one physical connection, that terminal/cancel evicts, and that
 * the frozen predicate/scope/breaker invariants hold. Each test targets a
 * single invariant a wrong implementation would violate.
 */
class ReusedHttp2RecoveryCoordinatorTest {

    private val enabled = ReusedHttp2RecoveryConfig(enabled = true)

    private fun fact(
        id: Long,
        origin: String = "api.example.com:443",
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

    @Test
    fun twoDistinctLiveAttemptsOnSameConnectionReachQuorum() {
        val coordinator = ReusedHttp2RecoveryCoordinator(enabled)

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

        // A single flapping request re-reporting must be idempotent: the live
        // set stays size 1 no matter how many facts arrive.
        repeat(5) { i ->
            assertIs<ReusedHttp2QuorumOutcome.NoAction>(
                coordinator.onStallFact(fact(id = 1), nowMillis = i.toLong())
            )
        }
        assertEquals(1, coordinator.liveStalledCount("api.example.com:443", 0L, 7L))
    }

    @Test
    fun settledAttemptIsEvictedBeforeQuorum() {
        val coordinator = ReusedHttp2RecoveryCoordinator(enabled)

        coordinator.onStallFact(fact(id = 1), nowMillis = 0)
        coordinator.onAttemptSettled(transportRequestId = 1)
        // Attempt 1 no longer counts, so attempt 2 alone must not reach quorum.
        val outcome = coordinator.onStallFact(fact(id = 2), nowMillis = 10)

        assertIs<ReusedHttp2QuorumOutcome.NoAction>(outcome)
        assertEquals(1, coordinator.liveStalledCount("api.example.com:443", 0L, 7L))
    }

    @Test
    fun cancelledAttemptIsEvictedByTerminalSignal() {
        val coordinator = ReusedHttp2RecoveryCoordinator(enabled)

        coordinator.onStallFact(fact(id = 1), nowMillis = 0)
        coordinator.onStallFact(fact(id = 2), nowMillis = 5).also {
            // both live → this would have fired; consume it and reset breaker below
            assertIs<ReusedHttp2QuorumOutcome.DeclareConnectionDead>(it)
        }
        // After the death the connection set is cleared; a late cancel for an
        // already-evicted attempt is a harmless no-op.
        coordinator.onAttemptSettled(transportRequestId = 1)
        assertEquals(0, coordinator.liveStalledCount("api.example.com:443", 0L, 7L))
    }

    @Test
    fun sameConnectionIdInDifferentGenerationIsNotTheSamePhysicalConnection() {
        val coordinator = ReusedHttp2RecoveryCoordinator(enabled)

        // CURLINFO_CONN_ID 7 means different physical connections across a
        // generation swap: must NOT be deduplicated into one quorum set.
        assertIs<ReusedHttp2QuorumOutcome.NoAction>(
            coordinator.onStallFact(fact(id = 1, generation = 0L, connectionId = 7L), nowMillis = 0)
        )
        assertIs<ReusedHttp2QuorumOutcome.NoAction>(
            coordinator.onStallFact(fact(id = 2, generation = 1L, connectionId = 7L), nowMillis = 10)
        )
        assertEquals(1, coordinator.liveStalledCount("api.example.com:443", 0L, 7L))
        assertEquals(1, coordinator.liveStalledCount("api.example.com:443", 1L, 7L))
    }

    @Test
    fun sameConnectionIdAcrossDifferentOriginsIsNotQuorum() {
        val coordinator = ReusedHttp2RecoveryCoordinator(enabled)

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

        // MID_BODY, non-H2, and non-reused facts must all be rejected AND leave
        // no residual membership that a later eligible fact could complete.
        coordinator.onStallFact(fact(id = 1, phase = ReusedHttp2StallPhase.MID_BODY), nowMillis = 0)
        coordinator.onStallFact(fact(id = 2, http2 = false), nowMillis = 1)
        coordinator.onStallFact(fact(id = 3, reused = false), nowMillis = 2)
        assertEquals(0, coordinator.liveStalledCount("api.example.com:443", 0L, 7L))

        val outcome = coordinator.onStallFact(fact(id = 4), nowMillis = 3)
        assertIs<ReusedHttp2QuorumOutcome.NoAction>(outcome)
    }

    @Test
    fun disabledCoordinatorNeverActs() {
        val coordinator = ReusedHttp2RecoveryCoordinator(ReusedHttp2RecoveryConfig(enabled = false))

        assertIs<ReusedHttp2QuorumOutcome.NoAction>(coordinator.onStallFact(fact(id = 1), nowMillis = 0))
        assertIs<ReusedHttp2QuorumOutcome.NoAction>(coordinator.onStallFact(fact(id = 2), nowMillis = 10))
        assertEquals(0, coordinator.liveStalledCount("api.example.com:443", 0L, 7L))
    }

    @Test
    fun churnBreakerSuppressesRepeatDeathWithinWindowThenReopens() {
        val coordinator = ReusedHttp2RecoveryCoordinator(
            ReusedHttp2RecoveryConfig(enabled = true, churnBreakerWindowMillis = 30_000L)
        )

        coordinator.onStallFact(fact(id = 1), nowMillis = 0)
        assertIs<ReusedHttp2QuorumOutcome.DeclareConnectionDead>(
            coordinator.onStallFact(fact(id = 2), nowMillis = 100)
        )

        // Within the 30s window a fresh quorum on the same origin is suppressed.
        coordinator.onStallFact(fact(id = 3, generation = 1L, connectionId = 9L), nowMillis = 1_000)
        assertIs<ReusedHttp2QuorumOutcome.NoAction>(
            coordinator.onStallFact(fact(id = 4, generation = 1L, connectionId = 9L), nowMillis = 2_000)
        )

        // After the window elapses recovery re-arms.
        coordinator.onStallFact(fact(id = 5, generation = 2L, connectionId = 11L), nowMillis = 40_000)
        assertIs<ReusedHttp2QuorumOutcome.DeclareConnectionDead>(
            coordinator.onStallFact(fact(id = 6, generation = 2L, connectionId = 11L), nowMillis = 40_100)
        )
    }

    @Test
    fun quorumFiresOnceThenConnectionSetIsCleared() {
        val coordinator = ReusedHttp2RecoveryCoordinator(
            // Disable the breaker to isolate the "fires once" property from suppression.
            ReusedHttp2RecoveryConfig(enabled = true, churnBreakerWindowMillis = 0L)
        )

        coordinator.onStallFact(fact(id = 1), nowMillis = 0)
        assertIs<ReusedHttp2QuorumOutcome.DeclareConnectionDead>(
            coordinator.onStallFact(fact(id = 2), nowMillis = 10)
        )
        // The dead connection's set was cleared; ids 1 and 2 are evicted.
        assertEquals(0, coordinator.liveStalledCount("api.example.com:443", 0L, 7L))
    }

    @Test
    fun attemptReboundToNewGenerationIsNotDoubleCounted() {
        val coordinator = ReusedHttp2RecoveryCoordinator(
            ReusedHttp2RecoveryConfig(enabled = true, churnBreakerWindowMillis = 0L)
        )

        // Attempt 1 first stalls on gen 0/conn 7, then is observed on gen 1/conn 7
        // (its connection was rebound). It must leave the stale set, so a second
        // distinct attempt on gen 1/conn 7 is needed for quorum there.
        coordinator.onStallFact(fact(id = 1, generation = 0L, connectionId = 7L), nowMillis = 0)
        coordinator.onStallFact(fact(id = 1, generation = 1L, connectionId = 7L), nowMillis = 5)
        assertEquals(0, coordinator.liveStalledCount("api.example.com:443", 0L, 7L))
        assertEquals(1, coordinator.liveStalledCount("api.example.com:443", 1L, 7L))

        val outcome = coordinator.onStallFact(fact(id = 2, generation = 1L, connectionId = 7L), nowMillis = 10)
        val dead = assertIs<ReusedHttp2QuorumOutcome.DeclareConnectionDead>(outcome)
        assertEquals(setOf(1L, 2L), dead.stalledRequestIds)
        assertTrue(dead.deadClientGeneration == 1L)
    }

    @Test
    fun configRejectsNonPositiveThresholds() {
        var threw = false
        try {
            ReusedHttp2RecoveryConfig(minimumConcurrentStalledRequests = 0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}

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

/**
 * task #52 Phase 2 step 1: deterministic proofs of the shared generation-swap
 * CAS. Both recovery triggers (OS network-change signal and the Phase 1 quorum
 * coordinator) funnel through [NetworkGenerationManager.retireIfCurrent], so a
 * generation retires exactly once however they interleave, and the cohort
 * rotation is a single all-or-nothing transaction: a failed transaction has
 * zero externally visible effect and is safely retryable, never a double switch
 * or a lost drain obligation.
 *
 * The controller's internal before-publish / after-publish / drain-registration
 * failure windows all surface here as `rotateToGeneration == false` with zero
 * effect — the manager cannot and need not distinguish them; that is the
 * atomicity guarantee. Proving those windows really roll back atomically is
 * phase-2 native fault-injection work.
 */
class NetworkGenerationManagerTest {

    /**
     * Records each rotate transaction as `(newGeneration, retiredGeneration)`.
     * [scripted] results (consumed in order; default `true` when exhausted) inject
     * transaction failures; a `false` call records the attempt but commits no
     * effect, mirroring the atomic controller's zero-effect rollback.
     */
    private class ScriptedCohortController(vararg scripted: Boolean) : CurlCohortController {
        private val results = scripted.toMutableList()
        val calls = mutableListOf<Pair<Long, Long>>()
        var committedRotations = 0
        override fun rotateToGeneration(newGeneration: Long, retiredGeneration: Long): Boolean {
            calls += newGeneration to retiredGeneration
            val ok = if (results.isEmpty()) true else results.removeAt(0)
            if (ok) committedRotations++
            return ok
        }
    }

    @Test
    fun retiringCurrentGenerationRotatesAtomicallyAndAdvances() {
        val controller = ScriptedCohortController()
        val manager = NetworkGenerationManager(controller)

        val result = manager.retireIfCurrent(observedGeneration = 0L)

        val advanced = assertIs<GenerationRetirementResult.Advanced>(result)
        assertEquals(0L, advanced.retiredGeneration)
        assertEquals(1L, advanced.newGeneration)
        assertEquals(1L, manager.currentGeneration())
        // One paired transaction with (newGeneration, retiredGeneration).
        assertEquals(listOf(1L to 0L), controller.calls)
        assertEquals(1, controller.committedRotations)
    }

    @Test
    fun twoTriggersObservingSameGenerationRotateExactlyOnce() {
        val controller = ScriptedCohortController()
        val manager = NetworkGenerationManager(controller)

        val first = manager.retireIfCurrent(observedGeneration = 0L)
        val second = manager.retireIfCurrent(observedGeneration = 0L)

        assertIs<GenerationRetirementResult.Advanced>(first)
        assertEquals(1L, assertIs<GenerationRetirementResult.AlreadyAdvanced>(second).currentGeneration)
        assertEquals(1L, manager.currentGeneration())
        // Exactly one transaction, not two.
        assertEquals(listOf(1L to 0L), controller.calls)
        assertEquals(1, controller.committedRotations)
    }

    @Test
    fun staleObservedGenerationIsANoOp() {
        val controller = ScriptedCohortController()
        val manager = NetworkGenerationManager(controller)

        manager.retireIfCurrent(observedGeneration = 0L) // -> gen 1
        manager.retireIfCurrent(observedGeneration = 1L) // -> gen 2
        controller.calls.clear()

        val stale = manager.retireIfCurrent(observedGeneration = 0L)

        assertEquals(2L, assertIs<GenerationRetirementResult.AlreadyAdvanced>(stale).currentGeneration)
        assertEquals(2L, manager.currentGeneration())
        assertEquals(emptyList<Pair<Long, Long>>(), controller.calls)
    }

    @Test
    fun aGenerationNewerThanCurrentDoesNotRotate() {
        val controller = ScriptedCohortController()
        val manager = NetworkGenerationManager(controller)

        // A fact/signal can never legitimately be on a future generation.
        val result = manager.retireIfCurrent(observedGeneration = 5L)

        assertEquals(0L, assertIs<GenerationRetirementResult.AlreadyAdvanced>(result).currentGeneration)
        assertEquals(0L, manager.currentGeneration())
        assertEquals(emptyList<Pair<Long, Long>>(), controller.calls)
    }

    @Test
    fun successiveRetirementsAdvanceMonotonically() {
        val controller = ScriptedCohortController()
        val manager = NetworkGenerationManager(controller)

        assertEquals(1L, (manager.retireIfCurrent(0L) as GenerationRetirementResult.Advanced).newGeneration)
        assertEquals(2L, (manager.retireIfCurrent(1L) as GenerationRetirementResult.Advanced).newGeneration)
        assertEquals(3L, (manager.retireIfCurrent(2L) as GenerationRetirementResult.Advanced).newGeneration)
        assertEquals(3L, manager.currentGeneration())
        assertEquals(listOf(1L to 0L, 2L to 1L, 3L to 2L), controller.calls)
        assertEquals(3, controller.committedRotations)
    }

    // The two triggers share one primitive: a quorum DeclareConnectionDead and a
    // network-change signal both observing the same generation rotate it once.
    @Test
    fun quorumAndNetworkChangeTriggersConvergeToOneRotation() {
        val controller = ScriptedCohortController()
        val manager = NetworkGenerationManager(controller)

        val coordinator = ReusedHttp2RecoveryCoordinator(
            ReusedHttp2RecoveryConfig(enabled = true, churnBreakerWindowMillis = 0L)
        )
        coordinator.onAttemptStarted(1L)
        coordinator.onAttemptStarted(2L)
        coordinator.onStallFact(stall(1L, generation = manager.currentGeneration()), nowMillis = 0)
        val dead = assertIs<ReusedHttp2QuorumOutcome.DeclareConnectionDead>(
            coordinator.onStallFact(stall(2L, generation = manager.currentGeneration()), nowMillis = 10)
        )

        val quorumResult = manager.retireIfCurrent(dead.deadClientGeneration)
        val signalResult = manager.retireIfCurrent(observedGeneration = 0L)

        assertIs<GenerationRetirementResult.Advanced>(quorumResult)
        assertIs<GenerationRetirementResult.AlreadyAdvanced>(signalResult)
        assertEquals(1L, manager.currentGeneration())
        assertEquals(listOf(1L to 0L), controller.calls)
        assertEquals(1, controller.committedRotations)
    }

    @Test
    fun networkChangeThenLateQuorumForSameGenerationRotatesOnce() {
        val controller = ScriptedCohortController()
        val manager = NetworkGenerationManager(controller)

        val signalResult = manager.retireIfCurrent(manager.currentGeneration())
        val lateQuorumResult = manager.retireIfCurrent(observedGeneration = 0L)

        assertIs<GenerationRetirementResult.Advanced>(signalResult)
        assertIs<GenerationRetirementResult.AlreadyAdvanced>(lateQuorumResult)
        assertEquals(1L, manager.currentGeneration())
        assertEquals(1, controller.committedRotations)
    }

    // A failed transaction has zero externally visible effect and is retryable;
    // the retry produces exactly one successful transaction.
    @Test
    fun failedRotationHasZeroEffectAndIsRetryable() {
        val controller = ScriptedCohortController(false, true)
        val manager = NetworkGenerationManager(controller)

        val failed = manager.retireIfCurrent(observedGeneration = 0L)
        // Zero effect: generation unchanged, nothing committed.
        assertEquals(0L, assertIs<GenerationRetirementResult.RotationFailed>(failed).currentGeneration)
        assertEquals(0L, manager.currentGeneration())
        assertEquals(0, controller.committedRotations)

        val retried = manager.retireIfCurrent(observedGeneration = 0L)
        val advanced = assertIs<GenerationRetirementResult.Advanced>(retried)
        assertEquals(0L, advanced.retiredGeneration)
        assertEquals(1L, advanced.newGeneration)
        assertEquals(1L, manager.currentGeneration())
        // Exactly one successful transaction across the failed + retried calls.
        assertEquals(1, controller.committedRotations)
        assertEquals(listOf(1L to 0L, 1L to 0L), controller.calls)
    }

    // Repeated signals/quorum declarations after a failed rotation must not
    // double-rotate; exactly one transaction ultimately commits.
    @Test
    fun repeatedSignalAfterFailedRotationDoesNotDoubleRotate() {
        val controller = ScriptedCohortController(false, false, true)
        val manager = NetworkGenerationManager(controller)

        assertIs<GenerationRetirementResult.RotationFailed>(manager.retireIfCurrent(0L))
        assertIs<GenerationRetirementResult.RotationFailed>(manager.retireIfCurrent(0L))
        assertIs<GenerationRetirementResult.Advanced>(manager.retireIfCurrent(0L))

        assertEquals(1L, manager.currentGeneration())
        assertEquals(1, controller.committedRotations)
    }

    @Test
    fun advancedThenStaleRetryDoesNotRotateAgain() {
        val controller = ScriptedCohortController()
        val manager = NetworkGenerationManager(controller)

        assertIs<GenerationRetirementResult.Advanced>(manager.retireIfCurrent(0L))
        // A stale retry of the already-retired generation must not rotate again.
        assertIs<GenerationRetirementResult.AlreadyAdvanced>(manager.retireIfCurrent(0L))

        assertEquals(1L, manager.currentGeneration())
        assertEquals(listOf(1L to 0L), controller.calls)
        assertEquals(1, controller.committedRotations)
    }

    // Generation overflow is fail-closed before the controller is ever invoked.
    @Test
    fun overflowFailsClosedWithoutCallingController() {
        val controller = ScriptedCohortController()
        val manager = NetworkGenerationManager(controller, initialGeneration = Long.MAX_VALUE)

        val result = manager.retireIfCurrent(observedGeneration = Long.MAX_VALUE)

        assertEquals(Long.MAX_VALUE, assertIs<GenerationRetirementResult.RotationFailed>(result).currentGeneration)
        assertEquals(Long.MAX_VALUE, manager.currentGeneration())
        assertEquals(emptyList<Pair<Long, Long>>(), controller.calls)
    }

    private fun stall(
        id: Long,
        generation: Long,
        origin: String = "api.example.com:443",
        connectionId: Long = 7L
    ) = ReusedHttp2StallFact(
        transportRequestId = id,
        originId = origin,
        clientGeneration = generation,
        connectionId = connectionId,
        phase = ReusedHttp2StallPhase.AWAITING_HEADERS,
        negotiatedHttp2 = true,
        reusedConnectionCandidate = true
    )
}

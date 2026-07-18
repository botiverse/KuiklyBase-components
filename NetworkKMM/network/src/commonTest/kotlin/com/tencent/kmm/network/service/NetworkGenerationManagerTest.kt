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
 * generation must retire exactly once however they interleave, the default/H3
 * cohorts must switch-before-drain, and a stale observed generation must be a
 * no-op.
 */
class NetworkGenerationManagerTest {

    /** Records cohort-controller calls in order as (op, generation) pairs. */
    private class RecordingCohortController : CurlCohortController {
        val calls = mutableListOf<Pair<String, Long>>()
        override fun switchToGeneration(newGeneration: Long) {
            calls += "switch" to newGeneration
        }
        override fun markGenerationForDrain(retiredGeneration: Long) {
            calls += "drain" to retiredGeneration
        }
    }

    @Test
    fun retiringCurrentGenerationAdvancesAndRollsCohorts() {
        val controller = RecordingCohortController()
        val manager = NetworkGenerationManager(controller)

        val result = manager.retireIfCurrent(observedGeneration = 0L)

        val advanced = assertIs<GenerationRetirementResult.Advanced>(result)
        assertEquals(0L, advanced.retiredGeneration)
        assertEquals(1L, advanced.newGeneration)
        assertEquals(1L, manager.currentGeneration())
        // Switch new traffic to the fresh generation BEFORE marking the old for drain.
        assertEquals(listOf("switch" to 1L, "drain" to 0L), controller.calls)
    }

    @Test
    fun twoTriggersObservingSameGenerationRotateExactlyOnce() {
        val controller = RecordingCohortController()
        val manager = NetworkGenerationManager(controller)

        val first = manager.retireIfCurrent(observedGeneration = 0L)
        val second = manager.retireIfCurrent(observedGeneration = 0L)

        assertIs<GenerationRetirementResult.Advanced>(first)
        val alreadyAdvanced = assertIs<GenerationRetirementResult.AlreadyAdvanced>(second)
        assertEquals(1L, alreadyAdvanced.currentGeneration)
        assertEquals(1L, manager.currentGeneration())
        // The cohort controller is invoked exactly once, not twice.
        assertEquals(listOf("switch" to 1L, "drain" to 0L), controller.calls)
    }

    @Test
    fun staleObservedGenerationIsANoOp() {
        val controller = RecordingCohortController()
        val manager = NetworkGenerationManager(controller)

        manager.retireIfCurrent(observedGeneration = 0L) // -> gen 1
        manager.retireIfCurrent(observedGeneration = 1L) // -> gen 2
        controller.calls.clear()

        val stale = manager.retireIfCurrent(observedGeneration = 0L)

        val alreadyAdvanced = assertIs<GenerationRetirementResult.AlreadyAdvanced>(stale)
        assertEquals(2L, alreadyAdvanced.currentGeneration)
        assertEquals(2L, manager.currentGeneration())
        assertEquals(emptyList<Pair<String, Long>>(), controller.calls)
    }

    @Test
    fun aGenerationNewerThanCurrentDoesNotRollBackwards() {
        val controller = RecordingCohortController()
        val manager = NetworkGenerationManager(controller)

        // A fact/signal can never legitimately be on a future generation; treat
        // it as already advanced rather than rotating.
        val result = manager.retireIfCurrent(observedGeneration = 5L)

        val alreadyAdvanced = assertIs<GenerationRetirementResult.AlreadyAdvanced>(result)
        assertEquals(0L, alreadyAdvanced.currentGeneration)
        assertEquals(0L, manager.currentGeneration())
        assertEquals(emptyList<Pair<String, Long>>(), controller.calls)
    }

    @Test
    fun successiveRetirementsAdvanceMonotonically() {
        val controller = RecordingCohortController()
        val manager = NetworkGenerationManager(controller)

        assertEquals(1L, (manager.retireIfCurrent(0L) as GenerationRetirementResult.Advanced).newGeneration)
        assertEquals(2L, (manager.retireIfCurrent(1L) as GenerationRetirementResult.Advanced).newGeneration)
        assertEquals(3L, (manager.retireIfCurrent(2L) as GenerationRetirementResult.Advanced).newGeneration)
        assertEquals(3L, manager.currentGeneration())
        assertEquals(
            listOf("switch" to 1L, "drain" to 0L, "switch" to 2L, "drain" to 1L, "switch" to 3L, "drain" to 2L),
            controller.calls
        )
    }

    // The two triggers share one primitive: a quorum DeclareConnectionDead and a
    // network-change signal both observing the same generation rotate it once.
    @Test
    fun quorumAndNetworkChangeTriggersConvergeToOneRotation() {
        val controller = RecordingCohortController()
        val manager = NetworkGenerationManager(controller)

        // Phase 1 coordinator declares connection dead on the current generation.
        val coordinator = ReusedHttp2RecoveryCoordinator(
            ReusedHttp2RecoveryConfig(enabled = true, churnBreakerWindowMillis = 0L)
        )
        coordinator.onAttemptStarted(1L)
        coordinator.onAttemptStarted(2L)
        coordinator.onStallFact(stall(1L, generation = manager.currentGeneration()), nowMillis = 0)
        val dead = assertIs<ReusedHttp2QuorumOutcome.DeclareConnectionDead>(
            coordinator.onStallFact(stall(2L, generation = manager.currentGeneration()), nowMillis = 10)
        )

        // Quorum trigger retires the dead generation.
        val quorumResult = manager.retireIfCurrent(dead.deadClientGeneration)
        // A network-change signal for the (now retired) same generation is a no-op.
        val signalResult = manager.retireIfCurrent(observedGeneration = 0L)

        assertIs<GenerationRetirementResult.Advanced>(quorumResult)
        assertIs<GenerationRetirementResult.AlreadyAdvanced>(signalResult)
        assertEquals(1L, manager.currentGeneration())
        assertEquals(listOf("switch" to 1L, "drain" to 0L), controller.calls)
    }

    @Test
    fun networkChangeThenLateQuorumForSameGenerationRotatesOnce() {
        val controller = RecordingCohortController()
        val manager = NetworkGenerationManager(controller)

        // Reverse order: a network-change signal rotates first...
        val signalResult = manager.retireIfCurrent(manager.currentGeneration())
        // ...then a late quorum declaration for the same (now retired) gen 0.
        val lateQuorumResult = manager.retireIfCurrent(observedGeneration = 0L)

        assertIs<GenerationRetirementResult.Advanced>(signalResult)
        assertIs<GenerationRetirementResult.AlreadyAdvanced>(lateQuorumResult)
        assertEquals(1L, manager.currentGeneration())
        assertEquals(listOf("switch" to 1L, "drain" to 0L), controller.calls)
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

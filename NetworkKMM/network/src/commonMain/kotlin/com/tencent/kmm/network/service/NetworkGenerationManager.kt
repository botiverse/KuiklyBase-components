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
 * task #52 Phase 2 — the single shared control plane for curl-lane
 * (Android/iOS/OHOS) connection-generation rotation. Both recovery triggers
 * funnel through this one primitive so a physical generation is retired exactly
 * once, no matter how the two triggers interleave:
 *
 * 1. a confirmed OS network identity change (the primary, low-false-positive
 *    layer) reads the current generation and asks to retire it;
 * 2. the no-signal fallback — [ReusedHttp2RecoveryCoordinator] declaring a
 *    connection dead — asks to retire the generation its stalled attempts were
 *    on.
 *
 * Retirement rolls the process-wide default and H3 `CURLSH` cohorts together as
 * a single all-or-nothing transaction ([CurlCohortController.rotateToGeneration]):
 * either the fresh generation's cohorts become the sole active binding AND the
 * retired generation is drain-registered, or nothing changes. There is no
 * externally observable half state, so a failed rotation can be safely retried
 * on the same generation without a double switch or a lost drain obligation.
 *
 * All generation state is guarded by one [SynchronizedObject]; the manager
 * commits its generation only after a successful ([true]) transaction, and that
 * commit is an infallible in-memory assignment.
 *
 * Scope, stated narrowly on purpose: the participants of a generation are today
 * exactly the paired default and H3 `CURLSH` cohorts. The long-lived C++ curl
 * Socket.IO session added in `raft.30` is **not** a participant — this control
 * plane neither leases nor rotates it. Phase-2 wiring must decide that
 * relationship explicitly rather than inherit it: retiring a generation must not
 * silently sever an active realtime session, and because a network change is
 * positive evidence that reconnecting will now succeed, a session must not treat
 * a rotation-driven disconnect as another failure to back off from. Until that
 * is defined, read "shared control plane" as *for the curl request cohorts*, not
 * for every consumer of the curl lane.
 */
internal class NetworkGenerationManager(
    private val cohortController: CurlCohortController,
    initialGeneration: Long = 0L
) {
    private val lock = SynchronizedObject()
    private var currentGeneration: Long = initialGeneration

    /** The generation new requests currently bind to. */
    fun currentGeneration(): Long = synchronized(lock) { currentGeneration }

    /**
     * Retire [observedGeneration] iff it is still the current one, rotating to a
     * fresh generation. This is the compare-and-swap both triggers share:
     *
     * - if [observedGeneration] is not current — already retired by the other
     *   trigger, or a duplicate/stale signal, or a generation newer than current
     *   (which can never legitimately be observed) — this is a no-op returning
     *   [GenerationRetirementResult.AlreadyAdvanced]; two triggers observing the
     *   same generation cause exactly one rotation;
     * - otherwise the shared [CurlCohortController.rotateToGeneration] transaction
     *   is attempted. On success the manager advances and returns
     *   [GenerationRetirementResult.Advanced]; on failure — the transaction had
     *   zero externally visible effect — the manager keeps its generation and
     *   returns [GenerationRetirementResult.RotationFailed] so the caller may
     *   safely retry the same generation.
     *
     * Generation overflow is fail-closed *before* the controller is invoked: at
     * `Long.MAX_VALUE` no fresh generation exists, so it returns
     * [GenerationRetirementResult.RotationFailed] rather than wrapping.
     */
    fun retireIfCurrent(observedGeneration: Long): GenerationRetirementResult = synchronized(lock) {
        if (observedGeneration != currentGeneration) {
            return@synchronized GenerationRetirementResult.AlreadyAdvanced(currentGeneration)
        }
        if (currentGeneration == Long.MAX_VALUE) {
            return@synchronized GenerationRetirementResult.RotationFailed(currentGeneration)
        }
        val newGeneration = currentGeneration + 1L
        val rotated = cohortController.rotateToGeneration(
            newGeneration = newGeneration,
            retiredGeneration = observedGeneration
        )
        if (!rotated) {
            // Zero externally visible effect: generation, active cohorts and drain
            // obligation are all unchanged; the same generation can be retried.
            return@synchronized GenerationRetirementResult.RotationFailed(currentGeneration)
        }
        // The transaction committed; only now advance (an infallible commit).
        currentGeneration = newGeneration
        GenerationRetirementResult.Advanced(
            retiredGeneration = observedGeneration,
            newGeneration = newGeneration
        )
    }
}

/**
 * Platform seam for the default/H3 `CURLSH` cohort lifecycle. The common
 * compare-and-swap in [NetworkGenerationManager] decides *when* to rotate; the
 * native implementation (phase-2) owns *how* — but as a single all-or-nothing
 * transaction, not two separable steps.
 */
internal interface CurlCohortController {
    /**
     * Atomically roll the cohorts: make [newGeneration]'s fresh default + H3
     * pair the sole active binding AND irrevocably register [retiredGeneration]
     * for drain, as one no-throw, all-or-nothing transaction.
     *
     * Returns `true` iff the whole transaction committed: at its single
     * linearization point the new pair becomes the active binding, the old pair
     * is drain-registered, and a request acquiring its `(generation, cohort
     * lease)` never observes a half state. The retired cohorts are only marked
     * for drain — torn down after every referencing easy handle / lease /
     * callback has exited, never under live references.
     *
     * Returns `false` iff nothing changed — no active-binding switch, no drain
     * registration, no orphan pair — so the same generation can be safely
     * retried. Every fallible create/prepare MUST happen before the publish
     * point; after publish only infallible in-memory commits may run. The
     * implementation must not throw across this boundary and must not report a
     * possibly-partial publish as `false`. It must also be non-reentrant (it
     * must not call back into [NetworkGenerationManager]).
     */
    fun rotateToGeneration(newGeneration: Long, retiredGeneration: Long): Boolean
}

/** Outcome of a [NetworkGenerationManager.retireIfCurrent] call. */
internal sealed interface GenerationRetirementResult {
    /** The rotation transaction committed: [retiredGeneration] rolled to [newGeneration]. */
    data class Advanced(
        val retiredGeneration: Long,
        val newGeneration: Long
    ) : GenerationRetirementResult

    /**
     * The observed generation was no longer current (already retired by the
     * other trigger or a duplicate/stale signal); no rotation happened.
     * [currentGeneration] is the live generation.
     */
    data class AlreadyAdvanced(val currentGeneration: Long) : GenerationRetirementResult

    /**
     * The observed generation was current but the rotation transaction did not
     * commit (controller returned false, or generation overflow). The transaction
     * had zero externally visible effect and [currentGeneration] is unchanged, so
     * the same generation may be safely retried.
     */
    data class RotationFailed(val currentGeneration: Long) : GenerationRetirementResult
}

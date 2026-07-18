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
 * Retirement rolls the process-wide default and H3 `CURLSH` cohorts together:
 * new requests atomically bind to a fresh generation's cohorts, and the retired
 * generation's cohorts are only marked for drain — never torn down while an
 * easy handle, native lease, or callback still references them (that lifecycle
 * is the platform [CurlCohortController]'s contract, exercised in phase-2
 * native work; here it is an injected seam so the CAS logic is deterministic in
 * common tests).
 *
 * All generation state is guarded by one [SynchronizedObject]. The cohort
 * controller is invoked under that lock so a switch and its paired drain-mark
 * are atomic with the compare-and-swap; the controller must therefore be
 * non-reentrant (it must not call back into this manager) and must not block on
 * the retiring generation's in-flight work — it only flips new-request binding
 * and marks the old generation, deferring teardown to lease/refcount drain.
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
     * Retire [observedGeneration] iff it is still the current one, advancing to
     * a fresh generation. This is the compare-and-swap both triggers share:
     *
     * - if [observedGeneration] is current, roll the cohorts (switch new traffic
     *   to the new generation, then mark the observed generation for drain) and
     *   return [GenerationRetirementResult.Advanced];
     * - otherwise the generation was already retired by the other trigger (or a
     *   duplicate signal), so this is a no-op and returns
     *   [GenerationRetirementResult.AlreadyAdvanced] — two triggers observing the
     *   same generation cause exactly one rotation.
     *
     * A generation strictly newer than the current one cannot legitimately be
     * observed (generations only advance here), so it is treated as already
     * advanced rather than rolling backwards.
     */
    fun retireIfCurrent(observedGeneration: Long): GenerationRetirementResult = synchronized(lock) {
        if (observedGeneration != currentGeneration) {
            return@synchronized GenerationRetirementResult.AlreadyAdvanced(currentGeneration)
        }
        val newGeneration = currentGeneration + 1L
        // Switch new-request binding to the fresh cohorts BEFORE marking the old
        // generation for drain, so no request can bind to a generation that is
        // already being retired.
        cohortController.switchToGeneration(newGeneration)
        cohortController.markGenerationForDrain(observedGeneration)
        currentGeneration = newGeneration
        GenerationRetirementResult.Advanced(
            retiredGeneration = observedGeneration,
            newGeneration = newGeneration
        )
    }
}

/**
 * Platform seam for the default/H3 `CURLSH` cohort lifecycle. The common CAS in
 * [NetworkGenerationManager] decides *when* to rotate; the native implementation
 * (phase-2) owns *how* — creating the fresh cohorts, atomically switching
 * new-request binding, and draining the retired cohorts only after every
 * referencing easy handle / lease / callback has exited (no teardown under
 * live references).
 */
internal interface CurlCohortController {
    /** Atomically bind new requests to [newGeneration]'s fresh default + H3 cohorts. */
    fun switchToGeneration(newGeneration: Long)

    /**
     * Mark [retiredGeneration]'s default + H3 cohorts for drain. In-flight work
     * on them continues to completion/cancel; teardown happens only once all
     * references drain. Never tears down synchronously here.
     */
    fun markGenerationForDrain(retiredGeneration: Long)
}

/** Outcome of a [NetworkGenerationManager.retireIfCurrent] call. */
internal sealed interface GenerationRetirementResult {
    /** This call retired [retiredGeneration] and rolled to [newGeneration]. */
    data class Advanced(
        val retiredGeneration: Long,
        val newGeneration: Long
    ) : GenerationRetirementResult

    /**
     * The observed generation was no longer current (already retired by the
     * other trigger or a duplicate signal); no rotation happened. [currentGeneration]
     * is the live generation.
     */
    data class AlreadyAdvanced(val currentGeneration: Long) : GenerationRetirementResult
}

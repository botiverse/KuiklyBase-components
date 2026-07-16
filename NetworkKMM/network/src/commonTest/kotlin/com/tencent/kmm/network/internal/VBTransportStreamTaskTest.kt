/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.tencent.kmm.network.internal

import com.tencent.kmm.network.export.VBTransportRequest
import com.tencent.kmm.network.export.VBTransportResponse
import com.tencent.kmm.network.export.VBTransportResultCode
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VBTransportStreamTaskTest {
    @Test
    fun downloadCancelDuringPlatformHandoffWaitsThenCancelsBeforeTerminalAndIdReuse() = runBlocking {
        val requestId = 991_009
        val task = registeredTask(requestId)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val terminals = atomic(0)
        val platformCancels = atomic(0)
        var replacementReserved = false
        task.platformPrepare = { true }
        task.platformAbortPrepared = {}
        task.platformCancel = { platformCancels.incrementAndGet() }
        task.platformRequestStream = { _, _, _, _ ->
            entered.complete(Unit)
            runBlocking { release.await() }
        }

        val requestJob = launch(Dispatchers.Default) {
            task.streamRequest(VBTransportRequest(), { _, _ -> }, {}) {
                terminals.incrementAndGet()
                replacementReserved = true
            }
        }
        entered.await()
        task.cancel()
        assertEquals(0, terminals.value)
        release.complete(Unit)
        requestJob.join()

        assertEquals(1, platformCancels.value)
        assertEquals(1, terminals.value)
        assertTrue(replacementReserved)
        assertEquals(VBTransportState.Unknown, VBTransportManager.getState(requestId))
    }

    @Test
    fun uploadCancelDuringPlatformHandoffWaitsThenCancelsBeforeTerminalAndIdReuse() = runBlocking {
        val requestId = 991_010
        val task = registeredTask(requestId)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val terminals = atomic(0)
        val platformCancels = atomic(0)
        var replacementReserved = false
        task.platformPrepare = { true }
        task.platformAbortPrepared = {}
        task.platformCancel = { platformCancels.incrementAndGet() }
        task.platformRequestUpload = { _, _, _, _ ->
            entered.complete(Unit)
            runBlocking { release.await() }
        }

        val requestJob = launch(Dispatchers.Default) {
            task.uploadStreamRequest(VBTransportRequest(), null, {}, handler = {
                terminals.incrementAndGet()
                replacementReserved = true
            })
        }
        entered.await()
        task.cancel()
        assertEquals(0, terminals.value)
        release.complete(Unit)
        requestJob.join()

        assertEquals(1, platformCancels.value)
        assertEquals(1, terminals.value)
        assertTrue(replacementReserved)
        assertEquals(VBTransportState.Unknown, VBTransportManager.getState(requestId))
    }

    @Test
    fun cancelBeforePlatformEntryPublishesOneCancelledTerminalAndAbortsReservation() {
        val task = registeredTask(991_001)
        val terminals = mutableListOf<Int>()
        var aborts = 0
        var platformEntries = 0
        var cancels = 0
        task.platformPrepare = {
            task.cancel()
            true
        }
        task.platformAbortPrepared = { aborts++ }
        task.platformCancel = { cancels++ }
        task.platformRequestStream = { _, _, _, _ -> platformEntries++ }

        task.streamRequest(VBTransportRequest(), { _, _ -> }, {}) { response ->
            terminals += response.errorCode
        }

        assertEquals(listOf(VBTransportResultCode.CODE_CANCELED), terminals)
        assertEquals(1, aborts)
        assertEquals(1, cancels)
        assertEquals(0, platformEntries)
        assertEquals(VBTransportState.Unknown, VBTransportManager.getState(task.requestId))
    }

    @Test
    fun cancelAfterPrepareBeforeGateAbortsUnusedPlatformReservation() {
        val task = registeredTask(991_004)
        val terminals = mutableListOf<Int>()
        var aborts = 0
        var platformEntries = 0
        task.platformPrepare = { true }
        task.platformCancel = {}
        task.platformAbortPrepared = { aborts++ }
        task.afterRunningPreparedForTest = { task.cancel() }
        task.platformRequestStream = { _, _, _, _ -> platformEntries++ }

        task.streamRequest(VBTransportRequest(), { _, _ -> }, {}) { response ->
            terminals += response.errorCode
        }

        assertEquals(listOf(VBTransportResultCode.CODE_CANCELED), terminals)
        assertEquals(1, aborts)
        assertEquals(0, platformEntries)
        assertEquals(VBTransportState.Unknown, VBTransportManager.getState(task.requestId))
    }

    @Test
    fun cancelAfterGateInstallAbortsBeforeTerminalAllowsSameIdReplacement() {
        val requestId = 991_005
        val reservation = CancellationAwareRegistry<Int, String>()
        val task = registeredTask(requestId)
        val events = mutableListOf<String>()
        task.platformPrepare = { reservation.begin(it) }
        task.platformAbortPrepared = {
            reservation.remove(it)
            events += "abort"
        }
        task.platformCancel = { events += "platform-cancel" }
        task.afterStreamGateInstalledForTest = { task.cancel() }
        task.platformRequestStream = { _, _, _, _ -> events += "platform-entry" }

        task.streamRequest(VBTransportRequest(), { _, _ -> }, {}) {
            events += "terminal:${it.errorCode}"
            check(reservation.begin(requestId))
            events += "replacement-reserved"
            reservation.remove(requestId)
        }

        assertEquals(
            listOf(
                "abort",
                "terminal:${VBTransportResultCode.CODE_CANCELED}",
                "replacement-reserved",
            ),
            events,
        )
        assertEquals(VBTransportState.Unknown, VBTransportManager.getState(requestId))
    }

    @Test
    fun doubleCancelCannotPublishTerminalWhilePlatformAbortIsBlocked() = runBlocking {
        val requestId = 991_006
        val reservation = CancellationAwareRegistry<Int, String>()
        val task = registeredTask(requestId)
        val abortEntered = CompletableDeferred<Unit>()
        val releaseAbort = CompletableDeferred<Unit>()
        val gateInstalled = CompletableDeferred<Unit>()
        val terminals = atomic(0)
        val platformCancels = atomic(0)
        val platformEntries = atomic(0)
        task.platformPrepare = { reservation.begin(it) }
        task.platformAbortPrepared = {
            abortEntered.complete(Unit)
            runBlocking { releaseAbort.await() }
            reservation.remove(it)
        }
        task.platformCancel = { platformCancels.incrementAndGet() }
        task.afterStreamGateInstalledForTest = {
            gateInstalled.complete(Unit)
            task.cancel()
        }
        task.platformRequestStream = { _, _, _, _ -> platformEntries.incrementAndGet() }

        val requestJob = launch(Dispatchers.Default) {
            task.streamRequest(VBTransportRequest(), { _, _ -> }, {}) {
                terminals.incrementAndGet()
                check(reservation.begin(requestId))
                reservation.remove(requestId)
            }
        }
        gateInstalled.await()
        abortEntered.await()
        val secondCancel = launch(Dispatchers.Default) { task.cancel() }
        delay(25)

        assertEquals(0, terminals.value)
        assertEquals(0, platformCancels.value)
        releaseAbort.complete(Unit)
        requestJob.join()
        secondCancel.join()

        assertEquals(1, terminals.value)
        assertEquals(0, platformCancels.value)
        assertEquals(0, platformEntries.value)
        assertEquals(VBTransportState.Unknown, VBTransportManager.getState(requestId))
    }

    @Test
    fun abortThrowBeforeRemoveStillContainsFailureAndNeverEntersPlatform() {
        val requestId = 991_007
        val reservation = CancellationAwareRegistry<Int, String>()
        val task = registeredTask(requestId)
        val terminals = atomic(0)
        val platformEntries = atomic(0)
        var abortCalls = 0
        var replacementReserved = false
        task.platformPrepare = { reservation.begin(it) }
        task.platformAbortPrepared = {
            abortCalls++
            if (abortCalls == 1) error("abort before remove")
            reservation.remove(it)
        }
        task.platformCancel = {}
        task.afterStreamGateInstalledForTest = { task.cancel() }
        task.platformRequestStream = { _, _, _, _ -> platformEntries.incrementAndGet() }

        task.streamRequest(VBTransportRequest(), { _, _ -> }, {}) {
            terminals.incrementAndGet()
            replacementReserved = reservation.begin(requestId)
            reservation.remove(requestId)
        }

        assertEquals(1, terminals.value)
        assertEquals(2, abortCalls)
        assertTrue(replacementReserved)
        assertEquals(0, platformEntries.value)
        assertEquals(VBTransportState.Unknown, VBTransportManager.getState(task.requestId))
    }

    @Test
    fun abortRemoveThenThrowStillAllowsSameIdReplacementAtTerminal() {
        val requestId = 991_008
        val reservation = CancellationAwareRegistry<Int, String>()
        val task = registeredTask(requestId)
        var replacementReserved = false
        task.platformPrepare = { reservation.begin(it) }
        task.platformAbortPrepared = {
            if (reservation.remove(it) != null) error("abort after remove")
        }
        task.platformCancel = {}
        task.afterStreamGateInstalledForTest = { task.cancel() }
        task.platformRequestStream = { _, _, _, _ -> error("platform must not start") }

        task.streamRequest(VBTransportRequest(), { _, _ -> }, {}) {
            replacementReserved = reservation.begin(requestId)
            reservation.remove(requestId)
        }

        assertEquals(true, replacementReserved)
        assertEquals(VBTransportState.Unknown, VBTransportManager.getState(requestId))
    }

    @Test
    fun repeatedAbortFailurePublishesOneTerminalAndNeverEntersPlatform() {
        val task = registeredTask(991_011)
        var abortCalls = 0
        var terminals = 0
        var platformEntries = 0
        task.platformPrepare = { true }
        task.platformAbortPrepared = {
            abortCalls++
            error("reservation removal unavailable")
        }
        task.platformCancel = {}
        task.afterStreamGateInstalledForTest = { task.cancel() }
        task.platformRequestStream = { _, _, _, _ -> platformEntries++ }

        task.streamRequest(VBTransportRequest(), { _, _ -> }, {}) { terminals++ }

        assertEquals(2, abortCalls)
        assertEquals(1, terminals)
        assertEquals(0, platformEntries)
        assertEquals(VBTransportState.Unknown, VBTransportManager.getState(task.requestId))
    }

    @Test
    fun midstreamCancelWinsOnceAndSuppressesLateChunkAndSuccess() {
        val task = registeredTask(991_002)
        var start: ((Int, Map<String, List<String>>) -> Unit)? = null
        var chunk: ((ByteArray) -> Unit)? = null
        var complete: ((VBTransportResponse) -> Unit)? = null
        val events = mutableListOf<String>()
        var cancels = 0
        task.platformPrepare = { true }
        task.platformCancel = { cancels++ }
        task.platformRequestStream = { _, onStart, onChunk, onComplete ->
            start = onStart
            chunk = onChunk
            complete = onComplete
        }

        task.streamRequest(VBTransportRequest(), { status, _ -> events += "start:$status" }, {
            events += "chunk:${it.size}"
        }) { response -> events += "terminal:${response.errorCode}" }
        start!!(200, emptyMap())
        chunk!!(byteArrayOf(1))
        task.cancel()
        chunk!!(byteArrayOf(2))
        complete!!(VBTransportResponse())

        assertEquals(
            listOf("start:200", "chunk:1", "terminal:${VBTransportResultCode.CODE_CANCELED}"),
            events,
        )
        assertEquals(1, cancels)
        assertEquals(VBTransportState.Unknown, VBTransportManager.getState(task.requestId))
    }

    @Test
    fun throwingStartClosesNetworkFailureBeforeTransportCancelAndCleansRegistry() {
        val task = registeredTask(991_003)
        val events = mutableListOf<String>()
        task.platformPrepare = { true }
        task.platformCancel = { events += "cancel" }
        task.platformRequestStream = { _, onStart, onChunk, _ ->
            onStart(200, emptyMap())
            onChunk(byteArrayOf(1))
        }

        task.streamRequest(VBTransportRequest(), { _, _ -> error("consumer start failed") }, {
            events += "chunk"
        }) { response -> events += "terminal:${response.errorCode}" }

        assertEquals(
            // The gate is logically closed before cancel; delivery waits for
            // the currently admitted throwing callback to unwind.
            listOf("cancel", "terminal:${VBTransportResultCode.CODE_NETWORK_ERROR}"),
            events,
        )
        assertEquals(VBTransportState.Unknown, VBTransportManager.getState(task.requestId))
    }

    @Test
    fun cancelVersusSuccessHasExactlyOneTerminalWinner() = runBlocking {
        repeat(100) { iteration ->
            val task = registeredTask(992_000 + iteration)
            var complete: ((VBTransportResponse) -> Unit)? = null
            val terminals = atomic(0)
            val ready = CompletableDeferred<Unit>()
            task.platformPrepare = { true }
            task.platformCancel = {}
            task.platformRequestStream = { _, _, _, onComplete ->
                complete = onComplete
                ready.complete(Unit)
            }
            task.streamRequest(VBTransportRequest(), { _, _ -> }, {}) { terminals.incrementAndGet() }
            ready.await()

            val cancelJob = launch(Dispatchers.Default) { task.cancel() }
            val successJob = launch(Dispatchers.Default) { complete!!(VBTransportResponse()) }
            cancelJob.join()
            successJob.join()

            assertEquals(1, terminals.value, "iteration=$iteration")
            assertEquals(VBTransportState.Unknown, VBTransportManager.getState(task.requestId))
        }
    }

    private fun registeredTask(requestId: Int): VBTransportTask {
        check(VBTransportManager.onTaskPrepared(requestId))
        return VBTransportTask(requestId, true, "stream-task-test", VBTransportManager).also {
            VBTransportManager.onTaskBegin(it)
        }
    }
}

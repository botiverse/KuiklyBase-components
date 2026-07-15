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
import kotlin.test.Test
import kotlin.test.assertEquals

class VBTransportStreamTaskTest {
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
        task.platformRequestStream = { _, onStart, onChunk, onComplete ->
            onStart(200, emptyMap())
            onChunk(byteArrayOf(1))
            onComplete(VBTransportResponse())
        }

        task.streamRequest(VBTransportRequest(), { _, _ -> error("consumer start failed") }, {
            events += "chunk"
        }) { response -> events += "terminal:${response.errorCode}" }

        assertEquals(
            listOf("terminal:${VBTransportResultCode.CODE_NETWORK_ERROR}", "cancel"),
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

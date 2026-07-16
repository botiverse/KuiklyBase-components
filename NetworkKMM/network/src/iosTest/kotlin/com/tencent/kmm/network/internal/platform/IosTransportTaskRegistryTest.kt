/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.tencent.kmm.network.internal.platform

import com.tencent.kmm.network.export.VBTransportPostRequest
import com.tencent.kmm.network.export.VBTransportMethod
import com.tencent.kmm.network.export.VBTransportRequest
import com.tencent.kmm.network.export.VBTransportResultCode
import com.tencent.kmm.network.service.VBTransportService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosTransportTaskRegistryTest {
    @Test
    fun callbackReleaseAllowsSameIdPrepareBeforeJobCompletionHook() = runBlocking {
        val registry = IosTransportTaskRegistry()
        val job = launch(start = CoroutineStart.LAZY) { kotlinx.coroutines.awaitCancellation() }
        assertTrue(registry.prepare(25))
        assertTrue(registry.register(25, job))

        assertTrue(registry.release(25, job))
        assertTrue(registry.prepare(25))

        registry.abort(25)
        job.cancel()
        job.join()
    }

    @Test
    fun uninitializedPostAbortsPreparedOwnerBeforeSynchronousFailure() {
        val request = VBTransportPostRequest().apply { requestId = 29 }
        var callbacks = 0
        val transport = IOSTransportImpl()
        assertTrue(transport.prepareRequest(request.requestId))

        transport.post(request) { callbacks++ }

        assertEquals(1, callbacks)
        assertTrue(transport.prepareRequest(request.requestId))
        transport.abortPreparedRequest(request.requestId)
    }

    @Test
    fun abortAfterPreRegisterCancelRemovesCancelledReservation() {
        val registry = IosTransportTaskRegistry()
        assertTrue(registry.prepare(26))
        registry.cancel(26)
        registry.abort(26)
        assertTrue(registry.prepare(26))
        registry.abort(26)
    }

    @Test
    fun cancelBeforeRegisterConsumesOldOwnerWithoutExecutionAndAllowsImmediateReuse() = runBlocking {
        val registry = IosTransportTaskRegistry()
        val requestId = 27
        var executions = 0
        val oldJob = launch(start = CoroutineStart.LAZY) { executions++ }

        assertTrue(registry.prepare(requestId))
        registry.cancel(requestId)
        assertTrue(registry.register(requestId, oldJob))
        assertTrue(registry.prepare(requestId))
        delay(10)

        assertEquals(0, executions)
        registry.abort(requestId)
    }

    @Test
    fun publicStreamWholeDeadlineIncludesBlockedCommonToPlatformHandoff() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val terminal = CompletableDeferred<com.tencent.kmm.network.export.VBTransportResponse>()
        var starts = 0
        var chunks = 0
        IosTransportTestHooks.beforeStreamTransportCoroutineStart = {
            entered.complete(Unit)
            release.await()
        }
        try {
            val request = VBTransportRequest().apply {
                method = VBTransportMethod.GET
                url = "https://127.0.0.1/never-opened"
                useCurl = false
                streamWholeTimeoutMillis = 25L
                streamResponseHeadersTimeoutMillis = 0L
            }

            VBTransportService.streamRequest(
                request,
                onResponseStart = { _, _ -> starts++ },
                onChunk = { chunks++ },
            ) { terminal.complete(it) }
            entered.await()
            delay(50L)
            release.complete(Unit)

            val response = withTimeout(2_000L) { terminal.await() }
            assertEquals(VBTransportResultCode.CODE_FORCE_TIMEOUT, response.errorCode)
            assertEquals(0, starts)
            assertEquals(0, chunks)
            val transport = IOSTransportImpl()
            assertTrue(transport.prepareRequest(request.requestId))
            transport.abortPreparedRequest(request.requestId)
        } finally {
            release.complete(Unit)
            IosTransportTestHooks.reset()
        }
    }
}

/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.tencent.kmm.network.internal.platform

import com.tencent.kmm.network.export.VBTransportPostRequest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidPreparedTransportTaskRegistryTest {
    @Test
    fun uninitializedPostAbortsPreparedOwnerBeforeSynchronousFailure() {
        val request = VBTransportPostRequest().apply { requestId = 29 }
        var callbacks = 0
        assertTrue(AndroidTransportImpl.prepareRequest(request.requestId))

        AndroidTransportImpl.post(request) { callbacks++ }

        assertEquals(1, callbacks)
        assertTrue(AndroidTransportImpl.prepareRequest(request.requestId))
        AndroidTransportImpl.abortPreparedRequest(request.requestId)
    }

    @Test
    fun abortAfterPreRegisterCancelRemovesCancelledReservation() {
        val registry = AndroidPreparedTransportTaskRegistry()
        assertTrue(registry.prepare(26))
        registry.cancel(26)
        registry.abort(26)
        assertTrue(registry.prepare(26))
        registry.abort(26)
    }

    @Test
    fun cancelBeforeRegisterConsumesOldOwnerWithoutExecutionAndAllowsImmediateReuse() = runBlocking {
        val registry = AndroidPreparedTransportTaskRegistry()
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
    fun cancelAfterRegisterRemovesOnlyCapturedJobAndAllowsImmediateReuse() = runBlocking {
        val registry = AndroidPreparedTransportTaskRegistry()
        val requestId = 28
        val oldJob = launch(start = CoroutineStart.LAZY) { kotlinx.coroutines.awaitCancellation() }

        assertTrue(registry.prepare(requestId))
        assertTrue(registry.register(requestId, oldJob))
        registry.cancel(requestId)
        assertTrue(registry.prepare(requestId))

        registry.abort(requestId)
        oldJob.join()
        assertTrue(oldJob.isCancelled)
    }
}

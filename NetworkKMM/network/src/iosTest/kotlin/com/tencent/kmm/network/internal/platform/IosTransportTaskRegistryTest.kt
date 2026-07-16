/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.tencent.kmm.network.internal.platform

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosTransportTaskRegistryTest {
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
}

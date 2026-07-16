/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.tencent.kmm.network.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class VBTransportManagerTest {
    @Test
    fun activeIdCollisionRejectsReplacementWithoutOrphaningOwner() {
        val requestId = 987_654_321
        val old = VBTransportTask(
            requestId,
            useCurl = true,
            logTag = "old",
            taskManager = VBTransportManager
        )
        val replacement = VBTransportTask(
            requestId,
            useCurl = true,
            logTag = "replacement",
            taskManager = VBTransportManager
        )
        assertEquals(true, VBTransportManager.onTaskPrepared(requestId))
        VBTransportManager.onTaskBegin(old)
        assertEquals(false, VBTransportManager.onTaskPrepared(requestId))
        replacement.cancel()

        assertSame(old, VBTransportManager.getTask(requestId))
        assertEquals(VBTransportState.Canceled, replacement.getState())
        VBTransportManager.onTaskFinish(replacement)
        assertSame(old, VBTransportManager.getTask(requestId))
        VBTransportManager.onTaskFinish(old)
        assertEquals(VBTransportState.Unknown, VBTransportManager.getState(requestId))
    }

    @Test
    fun throwingPlatformCancelCannotLeaveCanceledTaskResident() {
        val requestId = 987_654_322
        val task = VBTransportTask(
            requestId,
            useCurl = true,
            logTag = "throwing-platform-cancel",
            taskManager = VBTransportManager
        ).apply {
            setState(VBTransportState.Running)
            platformCancel = { error("platform cancel failed") }
        }
        VBTransportManager.onTaskPrepared(requestId)
        VBTransportManager.onTaskBegin(task)

        VBTransportManager.cancel(requestId)
        assertEquals(VBTransportState.Unknown, VBTransportManager.getState(requestId))

        val replacement = VBTransportTask(
            requestId,
            useCurl = true,
            logTag = "replacement-after-throw",
            taskManager = VBTransportManager
        )
        VBTransportManager.onTaskPrepared(requestId)
        VBTransportManager.onTaskBegin(replacement)
        assertSame(replacement, VBTransportManager.getTask(requestId))
        VBTransportManager.onTaskFinish(replacement)
    }
}

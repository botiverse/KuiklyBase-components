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
    fun lateOldTerminalCannotRemoveReplacementWithSameId() {
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
        VBTransportManager.onTaskPrepared(requestId)
        VBTransportManager.onTaskBegin(old)
        VBTransportManager.onTaskPrepared(requestId)
        VBTransportManager.onTaskBegin(replacement)

        VBTransportManager.onTaskFinish(old)

        assertSame(replacement, VBTransportManager.getTask(requestId))
        VBTransportManager.onTaskFinish(replacement)
        assertEquals(VBTransportState.Unknown, VBTransportManager.getState(requestId))
    }
}

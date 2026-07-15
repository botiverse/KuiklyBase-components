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

class StreamCallbackGateTest {
    @Test
    fun startFailureCancelsSuppressesChunksAndBecomesTerminalFailure() {
        val events = mutableListOf<String>()
        val gate = StreamCallbackGate(
            onStart = { _, _ -> error("disk open failed") },
            onChunk = { events += "chunk" },
            onComplete = { events += "complete:$it" },
            failureCompletion = { "failure:${it.message}" },
            cancelTransport = { events += "cancel" },
            onCallbackFailure = { events += "error:${it.message}" }
        )

        gate.responseStart(200, emptyMap())
        gate.chunk(byteArrayOf(1))
        gate.complete("success")
        gate.complete("duplicate")

        assertEquals(
            listOf("error:disk open failed", "cancel", "complete:failure:disk open failed"),
            events
        )
    }

    @Test
    fun chunkFailureCancelsAndTerminalIsExactlyOnce() {
        val events = mutableListOf<String>()
        val gate = StreamCallbackGate(
            onStart = { status, _ -> events += "start:$status" },
            onChunk = { error("write failed") },
            onComplete = { events += "complete:$it" },
            failureCompletion = { "failure:${it.message}" },
            cancelTransport = { events += "cancel" }
        )

        gate.responseStart(206, emptyMap())
        gate.chunk(byteArrayOf(1, 2))
        gate.chunk(byteArrayOf(3))
        gate.complete("success")
        gate.complete("duplicate")

        assertEquals(listOf("start:206", "cancel", "complete:failure:write failed"), events)
    }

    @Test
    fun duplicateStartAndPreStartChunkAreSuppressed() {
        val events = mutableListOf<String>()
        val gate = StreamCallbackGate(
            onStart = { status, _ -> events += "start:$status" },
            onChunk = { events += "chunk:${it.size}" },
            onComplete = { events += "complete:$it" },
            failureCompletion = { "failure" },
            cancelTransport = {}
        )

        gate.chunk(byteArrayOf(0))
        gate.responseStart(200, emptyMap())
        gate.responseStart(201, emptyMap())
        gate.chunk(byteArrayOf(1, 2, 3))
        gate.complete("ok")

        assertEquals(listOf("start:200", "chunk:3", "complete:ok"), events)
    }
}

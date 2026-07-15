/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.tencent.kmm.network.internal

import com.tencent.kmm.network.export.IVBPBRequestIdGenerator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class RequestIdOwnerRegistryTest {
    @AfterTest
    fun reset() {
        VBPBRequestIdGenerator.requestIdGenerator = null
    }

    @Test
    fun constantIdReplacementGetsDistinctOwnerAndCannotCancelIncumbent() {
        VBPBRequestIdGenerator.requestIdGenerator = object : IVBPBRequestIdGenerator {
            override fun getRequestId(): Int = 909
        }
        val registry = RequestIdOwnerRegistry()
        val incumbent = Any()
        val replacement = Any()

        val incumbentId = registry.reserve(incumbent)
        val replacementId = registry.reserve(replacement)

        assertNotEquals(incumbentId, replacementId)
        assertTrue(registry.isOwner(incumbentId, incumbent))
        assertFalse(registry.isOwner(incumbentId, replacement))
        registry.release(replacementId, replacement)
        assertTrue(registry.isOwner(incumbentId, incumbent))
        registry.release(incumbentId, incumbent)
    }

    @Test
    fun cancelAndOwnerReleaseAreOneAtomicBoundary() = runBlocking {
        VBPBRequestIdGenerator.requestIdGenerator = object : IVBPBRequestIdGenerator {
            override fun getRequestId(): Int = 910
        }
        val registry = RequestIdOwnerRegistry()
        val incumbent = Any()
        val replacement = Any()
        val incumbentId = registry.reserve(incumbent)
        val cancelEntered = CompletableDeferred<Unit>()
        val releaseCancel = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val cancelJob = launch(Dispatchers.Default) {
            registry.cancelIfOwner(incumbentId, incumbent) {
                events += "cancel"
                cancelEntered.complete(Unit)
                runBlocking { releaseCancel.await() }
            }
        }
        cancelEntered.await()
        val replacementId = async(Dispatchers.Default) {
            registry.release(incumbentId, incumbent)
            registry.reserve(replacement).also { events += "replacement" }
        }
        delay(25)
        assertFalse(replacementId.isCompleted)

        releaseCancel.complete(Unit)
        cancelJob.join()
        assertEquals(incumbentId, replacementId.await())
        assertEquals(listOf("cancel", "replacement"), events)
        registry.release(incumbentId, replacement)
    }
}

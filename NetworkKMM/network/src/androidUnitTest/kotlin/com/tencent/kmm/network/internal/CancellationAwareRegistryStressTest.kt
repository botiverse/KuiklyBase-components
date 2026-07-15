/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.tencent.kmm.network.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class CancellationAwareRegistryStressTest {
    @Test
    fun cancelNeverTouchesHandleAfterOwnerDeletion() {
        val violations = AtomicInteger(0)
        repeat(1_000) { requestId ->
            val registry = CancellationAwareRegistry<Int, AtomicBoolean>()
            val deleted = AtomicBoolean(false)
            registry.publish(requestId, deleted)
            val ready = CountDownLatch(2)
            val go = CountDownLatch(1)
            val cancelThread = thread(start = true) {
                ready.countDown()
                go.await()
                registry.cancelOrRemember(requestId, removePublished = false) { handle ->
                    if (handle.get()) violations.incrementAndGet()
                }
            }
            val releaseThread = thread(start = true) {
                ready.countDown()
                go.await()
                registry.removeIfSame(requestId, deleted) { handle -> handle.set(true) }
            }
            ready.await()
            go.countDown()
            cancelThread.join()
            releaseThread.join()
        }
        assertEquals(0, violations.get())
    }
}

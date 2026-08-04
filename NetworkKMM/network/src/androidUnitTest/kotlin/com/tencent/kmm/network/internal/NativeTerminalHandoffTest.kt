/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.tencent.kmm.network.internal

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ObsoleteCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class NativeTerminalHandoffTest {
    @Test
    fun sameIdIsReusableBeforeAnyBusinessCallbackStarts() = runBlocking {
        val dispatcher = newFixedThreadPoolContext(2, "native-terminal-test")
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val registry = CancellationAwareRegistry<Int, String>()
            val allowCallbacks = CompletableDeferred<Unit>()
            val handoff = NativeTerminalHandoff(registry) { block ->
                scope.transportLaunch {
                    allowCallbacks.await()
                    block()
                }
            }
            val firstCompleted = CompletableDeferred<Unit>()
            val secondCompleted = CompletableDeferred<Unit>()
            val cleanupOrder = CopyOnWriteArrayList<String>()

            assertTrue(registry.begin(7))
            assertTrue(registry.publish(7, "first"))
            handoff.detachCleanupAndDispatch(
                key = 7,
                value = "first",
                cleanup = { cleanupOrder += "first" }
            ) {
                assertEquals(listOf("first", "second"), cleanupOrder)
                firstCompleted.complete(Unit)
            }
            // Both workers are paused before business callback entry. Removal
            // is synchronous, so the exact same logical ID can publish before
            // the first callback is allowed to start.
            assertTrue(registry.begin(7))
            assertTrue(registry.publish(7, "second"))
            handoff.detachCleanupAndDispatch(
                key = 7,
                value = "second",
                cleanup = { cleanupOrder += "second" }
            ) {
                secondCompleted.complete(Unit)
            }
            allowCallbacks.complete(Unit)

            withTimeout(2_000) { firstCompleted.await() }
            withTimeout(2_000) { secondCompleted.await() }
            assertEquals(listOf("first", "second"), cleanupOrder)
        } finally {
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun synchronouslyBlockedFirstTerminalDoesNotDelaySecondWorker() = runBlocking {
        val dispatcher = newFixedThreadPoolContext(2, "native-terminal-blocking-test")
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val releaseFirst = CountDownLatch(1)
        try {
            val registry = CancellationAwareRegistry<Int, String>()
            val handoff = NativeTerminalHandoff(registry) { block -> scope.transportLaunch { block() } }
            val firstStarted = CountDownLatch(1)
            val secondCompleted = CountDownLatch(1)
            val firstThread = AtomicReference<String>()
            val secondThread = AtomicReference<String>()

            assertTrue(registry.begin(1))
            assertTrue(registry.publish(1, "blocking"))
            handoff.detachCleanupAndDispatch(1, "blocking", cleanup = {}) {
                firstThread.set(Thread.currentThread().name)
                firstStarted.countDown()
                assertTrue(
                    releaseFirst.await(5, TimeUnit.SECONDS),
                    "test must release the synchronously blocked first worker"
                )
            }
            assertTrue(
                firstStarted.await(5, TimeUnit.SECONDS),
                "first callback must occupy one terminal worker"
            )

            assertTrue(registry.begin(2))
            assertTrue(registry.publish(2, "following"))
            handoff.detachCleanupAndDispatch(2, "following", cleanup = {}) {
                secondThread.set(Thread.currentThread().name)
                secondCompleted.countDown()
            }
            assertTrue(
                secondCompleted.await(5, TimeUnit.SECONDS),
                "second callback must complete while the first worker is synchronously blocked"
            )
            assertNotEquals(firstThread.get(), secondThread.get())
            assertTrue(firstThread.get().contains("native-terminal-blocking-test"))
            assertTrue(secondThread.get().contains("native-terminal-blocking-test"))
        } finally {
            releaseFirst.countDown()
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun throwingTerminalDoesNotCancelFollowingTerminal() = runBlocking {
        val dispatcher = newFixedThreadPoolContext(2, "native-terminal-throw-test")
        val thrown = CompletableDeferred<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, throwable ->
                thrown.complete(throwable)
            }
        )
        try {
            val registry = CancellationAwareRegistry<Int, String>()
            val handoff = NativeTerminalHandoff(registry) { block -> scope.transportLaunch { block() } }
            val followingCompleted = CompletableDeferred<Unit>()

            assertTrue(registry.begin(1))
            assertTrue(registry.publish(1, "throwing"))
            handoff.detachCleanupAndDispatch(1, "throwing", cleanup = {}) {
                error("consumer terminal failed")
            }

            assertTrue(registry.begin(2))
            assertTrue(registry.publish(2, "following"))
            handoff.detachCleanupAndDispatch(2, "following", cleanup = {}) {
                followingCompleted.complete(Unit)
            }
            assertEquals(
                "consumer terminal failed",
                withTimeout(2_000) { thrown.await() }.message
            )
            withTimeout(2_000) { followingCompleted.await() }
        } finally {
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun cleanupFailureIsReportedWithoutSuppressingTerminal() = runBlocking {
        val dispatcher = newFixedThreadPoolContext(1, "native-cleanup-throw-test")
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val registry = CancellationAwareRegistry<Int, String>()
            val handoff = NativeTerminalHandoff(registry) { block -> scope.transportLaunch { block() } }
            val cleanupFailure = CompletableDeferred<Throwable>()
            val terminalCompleted = CompletableDeferred<Unit>()

            assertTrue(registry.begin(3))
            assertTrue(registry.publish(3, "cleanup-throwing"))
            handoff.detachCleanupAndDispatch(
                key = 3,
                value = "cleanup-throwing",
                cleanup = { error("native cleanup failed") },
                onCleanupFailure = { cleanupFailure.complete(it) }
            ) {
                terminalCompleted.complete(Unit)
            }

            assertEquals(
                "native cleanup failed",
                withTimeout(2_000) { cleanupFailure.await() }.message
            )
            withTimeout(2_000) { terminalCompleted.await() }
            assertTrue(registry.begin(3), "cleanup failure must not retain the old logical ID")
        } finally {
            scope.cancel()
            dispatcher.close()
        }
    }
}

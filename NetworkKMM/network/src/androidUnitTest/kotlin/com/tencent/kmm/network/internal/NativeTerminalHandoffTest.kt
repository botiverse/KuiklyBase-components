/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.tencent.kmm.network.internal

import java.util.concurrent.CopyOnWriteArrayList
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
import kotlin.test.assertTrue

@OptIn(ObsoleteCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class NativeTerminalHandoffTest {
    @Test
    fun blockedFirstTerminalDoesNotDelaySecondAndKeyIsReusableBeforeHandoff() = runBlocking {
        val dispatcher = newFixedThreadPoolContext(2, "native-terminal-test")
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val registry = CancellationAwareRegistry<Int, String>()
            val handoff = NativeTerminalHandoff(registry) { block -> scope.transportLaunch { block() } }
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val secondCompleted = CompletableDeferred<Unit>()
            val cleanupOrder = CopyOnWriteArrayList<String>()

            assertTrue(registry.begin(7))
            assertTrue(registry.publish(7, "first"))
            handoff.detachCleanupAndDispatch(
                key = 7,
                value = "first",
                cleanup = { cleanupOrder += "first" }
            ) {
                assertEquals(listOf("first"), cleanupOrder)
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
            withTimeout(2_000) { firstStarted.await() }

            // Removal is synchronous and happens before the first business
            // callback starts, so the exact same logical ID can publish now.
            assertTrue(registry.begin(7))
            assertTrue(registry.publish(7, "second"))
            handoff.detachCleanupAndDispatch(
                key = 7,
                value = "second",
                cleanup = { cleanupOrder += "second" }
            ) {
                secondCompleted.complete(Unit)
            }

            // The second worker reaches its callback while the first remains
            // deliberately blocked. A native owner using this handoff returns
            // before either business callback completes.
            withTimeout(2_000) { secondCompleted.await() }
            assertEquals(listOf("first", "second"), cleanupOrder)
            releaseFirst.complete(Unit)
        } finally {
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
}

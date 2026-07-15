/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tencent.kmm.network.internal.platform

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

internal fun interface AndroidTransportDeadlineHandle {
    fun cancel(): Boolean
}

internal fun interface AndroidTransportDeadlineScheduler {
    fun schedule(delayMillis: Long, block: () -> Unit): AndroidTransportDeadlineHandle
}

internal object AndroidTransportWallClockDeadlineScheduler : AndroidTransportDeadlineScheduler {
    private val executor =
        ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "NetworkKMM-hard-deadline").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            executeExistingDelayedTasksAfterShutdownPolicy = false
            continueExistingPeriodicTasksAfterShutdownPolicy = false
        }

    override fun schedule(delayMillis: Long, block: () -> Unit): AndroidTransportDeadlineHandle {
        val future = executor.schedule(block, delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        return AndroidTransportDeadlineHandle { future.cancel(false) }
    }
}

internal enum class AndroidTransportCancellationResult {
    Requested,
    AlreadyComplete,
    Missing
}

internal data class AndroidTransportDeadlineDiagnostics(
    val configuredTimeoutMillis: Long,
    val deadlineElapsedMillis: Long,
    val transportCallbackDelayMillis: Long,
    val cancellationResult: AndroidTransportCancellationResult
)

/**
 * Races the transport callback against an independent wall-clock deadline.
 *
 * The scheduler is deliberately not a coroutine on Dispatchers.IO: a blocked
 * or starved transport dispatcher must not be able to postpone a 30s deadline
 * into a multi-minute callback. The deadline winner cancels the transport job
 * but never waits for that cancellation to complete before releasing the
 * caller. Any eventual transport callback is suppressed and diagnosed.
 */
internal class AndroidTransportHardDeadline(
    private val configuredTimeoutMillis: Long,
    private val elapsedMillis: () -> Long,
    private val scheduler: AndroidTransportDeadlineScheduler = AndroidTransportWallClockDeadlineScheduler,
    private val cancelTransport: () -> AndroidTransportCancellationResult,
    private val onDeadline: (AndroidTransportDeadlineDiagnostics) -> Unit,
    private val onLateTransportCallback: (transportCallbackDelayMillis: Long) -> Unit = {}
) {
    private val state = AtomicReference(State.Running)
    private val deadlineElapsedMillis = AtomicLong(-1L)
    private val cancellationResult =
        AtomicReference(AndroidTransportCancellationResult.Missing)
    private val handle = AtomicReference<AndroidTransportDeadlineHandle?>(null)

    fun start() {
        if (configuredTimeoutMillis <= 0L) return
        val remainingMillis =
            (configuredTimeoutMillis - elapsedMillis()).coerceAtLeast(0L)
        val scheduled =
            scheduler.schedule(remainingMillis) {
                val elapsedAtDeadline = elapsedMillis()
                deadlineElapsedMillis.set(elapsedAtDeadline)
                if (!state.compareAndSet(State.Running, State.Deadline)) return@schedule
                val cancellation = cancelTransport()
                cancellationResult.set(cancellation)
                onDeadline(
                    AndroidTransportDeadlineDiagnostics(
                        configuredTimeoutMillis = configuredTimeoutMillis,
                        deadlineElapsedMillis = elapsedAtDeadline,
                        transportCallbackDelayMillis = 0L,
                        cancellationResult = cancellation
                    )
                )
            }
        handle.set(scheduled)
        if (state.get() != State.Running) {
            scheduled.cancel()
        }
    }

    fun tryDeliverTransportCallback(): Boolean {
        if (state.compareAndSet(State.Running, State.Completed)) {
            handle.get()?.cancel()
            return true
        }
        if (state.get() == State.Deadline) {
            val deadlineElapsed = deadlineElapsedMillis.get().coerceAtLeast(0L)
            onLateTransportCallback((elapsedMillis() - deadlineElapsed).coerceAtLeast(0L))
        }
        return false
    }

    fun transportJobCompleted(cause: Throwable?) {
        if (cause is CancellationException) {
            state.compareAndSet(State.Running, State.CallerCancelled)
        }
        if (state.get() != State.Deadline) {
            handle.get()?.cancel()
        }
    }

    fun deadlineWon(): Boolean = state.get() == State.Deadline

    fun deadlineElapsedMillis(): Long = deadlineElapsedMillis.get().coerceAtLeast(0L)

    fun cancellationResult(): AndroidTransportCancellationResult = cancellationResult.get()

    private enum class State {
        Running,
        Completed,
        Deadline,
        CallerCancelled
    }
}

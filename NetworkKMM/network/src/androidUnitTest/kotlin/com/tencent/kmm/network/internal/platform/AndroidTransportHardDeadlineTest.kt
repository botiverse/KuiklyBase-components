package com.tencent.kmm.network.internal.platform

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import com.tencent.kmm.network.internal.VBTransportManager
import com.tencent.kmm.network.internal.VBTransportState
import com.tencent.kmm.network.internal.VBTransportTask
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidTransportHardDeadlineTest {
    @Test
    fun deadlineCancelsTransportAndSuppressesLateCallback() {
        var elapsedMillis = 0L
        val scheduler = FakeDeadlineScheduler()
        val transportJob = Job()
        var cancellationCount = 0
        var diagnostics: AndroidTransportDeadlineDiagnostics? = null
        var lateCallbackDelayMillis: Long? = null
        val deadline =
            AndroidTransportHardDeadline(
                configuredTimeoutMillis = 30_000L,
                elapsedMillis = { elapsedMillis },
                scheduler = scheduler,
                cancelTransport = {
                    cancellationCount += 1
                    transportJob.cancel(CancellationException("deadline"))
                    AndroidTransportCancellationResult.Requested
                },
                onDeadline = { diagnostics = it },
                onLateTransportCallback = { lateCallbackDelayMillis = it }
            )

        deadline.start()
        assertEquals(30_000L, scheduler.delayMillis)

        elapsedMillis = 30_007L
        scheduler.fire()

        assertEquals(1, cancellationCount)
        assertTrue(transportJob.isCancelled)
        assertEquals(
            AndroidTransportDeadlineDiagnostics(
                configuredTimeoutMillis = 30_000L,
                deadlineElapsedMillis = 30_007L,
                transportCallbackDelayMillis = 0L,
                cancellationResult = AndroidTransportCancellationResult.Requested
            ),
            diagnostics
        )
        elapsedMillis = 30_500L
        assertFalse(deadline.tryDeliverTransportCallback())
        assertEquals(493L, lateCallbackDelayMillis)
    }

    @Test
    fun completionBeforeDeadlineCancelsWatchdogWithoutCancellingTransport() {
        val scheduler = FakeDeadlineScheduler()
        var cancellationCount = 0
        var deadlineCount = 0
        val deadline =
            AndroidTransportHardDeadline(
                configuredTimeoutMillis = 30_000L,
                elapsedMillis = { 12L },
                scheduler = scheduler,
                cancelTransport = {
                    cancellationCount += 1
                    AndroidTransportCancellationResult.Requested
                },
                onDeadline = { deadlineCount += 1 }
            )

        deadline.start()
        assertTrue(deadline.tryDeliverTransportCallback())
        assertTrue(scheduler.cancelled)
        scheduler.fire()

        assertEquals(0, cancellationCount)
        assertEquals(0, deadlineCount)
    }

    @Test
    fun callerCancellationSuppressesDeadlineAndLateTransportCallback() {
        val scheduler = FakeDeadlineScheduler()
        var deadlineCount = 0
        val deadline =
            AndroidTransportHardDeadline(
                configuredTimeoutMillis = 30_000L,
                elapsedMillis = { 1L },
                scheduler = scheduler,
                cancelTransport = { AndroidTransportCancellationResult.Requested },
                onDeadline = { deadlineCount += 1 }
            )

        deadline.start()
        deadline.transportJobCompleted(CancellationException("caller cancelled"))
        scheduler.fire()

        assertEquals(0, deadlineCount)
        assertFalse(deadline.tryDeliverTransportCallback())
    }

    @Test
    fun deadlineAndCompletionRaceHasExactlyOneWinner() {
        repeat(500) {
            val scheduler = FakeDeadlineScheduler()
            val deadlineCount = AtomicInteger(0)
            val delivered = AtomicBoolean(false)
            val start = CountDownLatch(1)
            val deadline =
                AndroidTransportHardDeadline(
                    configuredTimeoutMillis = 30_000L,
                    elapsedMillis = { 30_000L },
                    scheduler = scheduler,
                    cancelTransport = { AndroidTransportCancellationResult.Requested },
                    onDeadline = { deadlineCount.incrementAndGet() }
                )
            deadline.start()
            val timeoutThread = Thread {
                start.await()
                scheduler.fire()
            }
            val completionThread = Thread {
                start.await()
                delivered.set(deadline.tryDeliverTransportCallback())
            }
            timeoutThread.start()
            completionThread.start()
            start.countDown()
            timeoutThread.join()
            completionThread.join()

            assertEquals(1, deadlineCount.get() + if (delivered.get()) 1 else 0)
        }
    }

    @Test
    fun timedOutRequestDoesNotPoisonNextRequestController() {
        val firstScheduler = FakeDeadlineScheduler()
        val secondScheduler = FakeDeadlineScheduler()
        var firstDeadlineCount = 0
        val first =
            AndroidTransportHardDeadline(
                configuredTimeoutMillis = 30_000L,
                elapsedMillis = { 30_000L },
                scheduler = firstScheduler,
                cancelTransport = { AndroidTransportCancellationResult.Requested },
                onDeadline = { firstDeadlineCount += 1 }
            )
        val second =
            AndroidTransportHardDeadline(
                configuredTimeoutMillis = 30_000L,
                elapsedMillis = { 5L },
                scheduler = secondScheduler,
                cancelTransport = { AndroidTransportCancellationResult.Requested },
                onDeadline = { error("next request must not time out") }
            )

        first.start()
        firstScheduler.fire()
        second.start()

        assertEquals(1, firstDeadlineCount)
        assertTrue(second.tryDeliverTransportCallback())
    }

    @Test
    fun transportTaskDeadlineAndCompletionRaceHasOneTerminalWinner() {
        repeat(500) { index ->
            val task =
                VBTransportTask(
                    requestId = index + 1,
                    useCurl = false,
                    logTag = "deadline-race",
                    taskManager = VBTransportManager
                )
            task.setState(VBTransportState.Running)
            val winners = AtomicInteger(0)
            val start = CountDownLatch(1)
            val first = Thread {
                start.await()
                if (task.trySetDone()) winners.incrementAndGet()
            }
            val second = Thread {
                start.await()
                if (task.trySetDone()) winners.incrementAndGet()
            }
            first.start()
            second.start()
            start.countDown()
            first.join()
            second.join()

            assertEquals(1, winners.get())
            assertEquals(VBTransportState.Done, task.getState())
        }
    }

}

private class FakeDeadlineScheduler : AndroidTransportDeadlineScheduler {
    private var block: (() -> Unit)? = null
    private val cancellation = AtomicBoolean(false)
    var delayMillis: Long? = null
        private set
    val cancelled: Boolean
        get() = cancellation.get()

    override fun schedule(delayMillis: Long, block: () -> Unit): AndroidTransportDeadlineHandle {
        this.delayMillis = delayMillis
        this.block = block
        return AndroidTransportDeadlineHandle {
            cancellation.set(true)
            true
        }
    }

    fun fire() {
        if (!cancellation.get()) block?.invoke()
    }
}

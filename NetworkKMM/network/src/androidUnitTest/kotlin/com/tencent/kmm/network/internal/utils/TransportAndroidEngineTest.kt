package com.tencent.kmm.network.internal.utils

import com.tencent.kmm.network.export.VBTransportAndroidEngine
import com.tencent.kmm.network.export.VBTransportReusedHttp2Recovery
import com.tencent.kmm.network.export.VBTransportMethod
import io.ktor.client.engine.android.AndroidClientEngine
import io.ktor.client.engine.okhttp.OkHttpEngine
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TransportAndroidEngineTest {
    @Test
    fun okHttpEngineIsTheDefault() {
        assertTrue(VBTransportAndroidEngine.okHttpEnabled)
    }

    @Test
    fun transportDefaultsEnableFastFallback() {
        val client = OkHttpClient.Builder().applyTransportOkHttpDefaults().build()
        assertTrue(client.fastFallback)
        assertEquals(1, client.interceptors.size)
    }

    @Test
    fun reusedHttp2RecoveryIsDefaultOffAndValidatesDurations() {
        val defaults = VBTransportReusedHttp2Recovery()
        assertTrue(!defaults.enabled)
        assertEquals(7_000L, defaults.responseHeadersWatchdogMillis)
        assertEquals(0L, defaults.pingIntervalMillis)
    }

    @Test
    fun transportDefaultsRaisePerHostConcurrencyWithoutTouchingGlobalCap() {
        // task #587: OkHttp's default maxRequestsPerHost=5 throttled the ~10-wide
        // foreground Thread h2 burst (task #586 tracing measured 212–752ms of
        // Dispatcher permit wait on the 6th–10th calls). Lift the per-host cap to
        // 16 while leaving the global maxRequests at OkHttp's default 64, so h1
        // degradation / multi-host traffic cannot expand total concurrency.
        val client = OkHttpClient.Builder().applyTransportOkHttpDefaults().build()
        assertEquals(16, client.dispatcher.maxRequestsPerHost)
        assertEquals(64, client.dispatcher.maxRequests)
    }

    @Test
    fun transportTracerSeparatesCoroutineAndDispatcherQueueTime() {
        var now = 1_000_000L
        AndroidTransportPhaseTracer.nanoTime = { now }
        try {
            AndroidTransportPhaseTracer.scheduled(requestId = 42)
            now += 2_000_000L
            AndroidTransportPhaseTracer.transportCoroutineStarted(requestId = 42)
            now += 3_000_000L
            AndroidTransportPhaseTracer.callStarted(requestId = 42)
            now += 5_000_000L
            AndroidTransportPhaseTracer.dispatcherStarted(requestId = 42)
            now += 7_000_000L
            AndroidTransportPhaseTracer.responseBodyRead(requestId = 42)

            val timing = AndroidTransportPhaseTracer.complete(requestId = 42)

            assertEquals(2.0, timing.transportQueueTimeMs)
            assertEquals(5.0, timing.dispatcherQueueTimeMs)
            assertEquals(17.0, timing.totalTimeMs)
            assertEquals("42", timing.transportRequestId)
        } finally {
            AndroidTransportPhaseTracer.resetForTests()
        }
    }

    @Test
    fun okHttpToggleSelectsOkHttpEngine() {
        buildTransportHttpClient(okHttpEnabled = true).use { client ->
            assertIs<OkHttpEngine>(client.engine)
        }
    }

    @Test
    fun killSwitchFallsBackToLegacyAndroidEngine() {
        buildTransportHttpClient(okHttpEnabled = false).use { client ->
            assertIs<AndroidClientEngine>(client.engine)
        }
    }

    @Test
    fun concurrentDrainOfOneOriginGenerationRollsOverExactlyOnce() {
        val clientsCreated = AtomicInteger()
        val manager = AndroidTransportClientGenerationManager {
            clientsCreated.incrementAndGet()
            buildTransportHttpClient(okHttpEnabled = true, recovery = it)
        }
        val recovery = VBTransportReusedHttp2Recovery(enabled = true)
        val leases = List(10) {
            manager.acquire("https://api.example.test/v1/messages?request=$it", recovery)
        }
        val originalGeneration = leases.first().generation
        assertTrue(leases.all { it.generation == originalGeneration })

        val start = CountDownLatch(1)
        val done = CountDownLatch(leases.size)
        val results = Collections.synchronizedList(mutableListOf<AndroidTransportGenerationRollover>())
        val executor = Executors.newFixedThreadPool(leases.size)
        try {
            leases.forEach { lease ->
                executor.execute {
                    start.await()
                    results += lease.drainGeneration()
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))

            assertEquals(1, results.count { it.initiated })
            assertEquals(1, results.map { it.generation }.distinct().size)
            assertEquals(2, clientsCreated.get())

            manager.acquire("https://api.example.test/v1/next", recovery).use { fresh ->
                assertEquals(results.first().generation, fresh.generation)
                assertTrue(fresh.generation != originalGeneration)
            }
        } finally {
            leases.forEach(AndroidTransportClientLease::close)
            executor.shutdownNow()
        }
    }

    @Test
    fun originsRollOverIndependently() {
        val manager = AndroidTransportClientGenerationManager()
        val recovery = VBTransportReusedHttp2Recovery(enabled = true)
        manager.acquire("https://one.example.test/a", recovery).use { one ->
            manager.acquire("https://two.example.test/a", recovery).use { two ->
                val twoGeneration = two.generation
                val rolled = one.drainGeneration()
                assertTrue(rolled.initiated)
                manager.acquire("https://two.example.test/b", recovery).use { twoAgain ->
                    assertEquals(twoGeneration, twoAgain.generation)
                }
            }
        }
    }

    @Test
    fun getAndHeadCanClaimOnlyOneSequentialFreshRetry() {
        listOf(VBTransportMethod.GET, VBTransportMethod.HEAD).forEach { method ->
            val state = AndroidReusedH2RetryState(method)
            assertTrue(state.claimRetry(watchdogTriggered = true, hasBudget = true))
            assertTrue(state.attempted)
            assertTrue(!state.claimRetry(watchdogTriggered = true, hasBudget = true))
        }
    }

    @Test
    fun replayUnsafeMethodsNeverClaimFreshRetry() {
        listOf(
            VBTransportMethod.POST,
            VBTransportMethod.PUT,
            VBTransportMethod.PATCH,
            VBTransportMethod.DELETE,
            VBTransportMethod.OPTIONS,
        ).forEach { method ->
            val state = AndroidReusedH2RetryState(method)
            assertTrue(!state.claimRetry(watchdogTriggered = true, hasBudget = true))
            assertTrue(!state.attempted)
        }
    }

    @Test
    fun freshRetryRequiresWatchdogAndRemainingTotalBudget() {
        val state = AndroidReusedH2RetryState(VBTransportMethod.GET)
        assertTrue(!state.claimRetry(watchdogTriggered = false, hasBudget = true))
        assertTrue(!state.claimRetry(watchdogTriggered = true, hasBudget = false))
        assertTrue(!state.attempted)
    }
}

package com.tencent.kmm.network.internal.utils

import com.tencent.kmm.network.export.VBTransportAndroidEngine
import com.tencent.kmm.network.export.VBTransportReusedHttp2Recovery
import com.tencent.kmm.network.export.VBTransportMethod
import com.tencent.kmm.network.internal.platform.AndroidRequestTimeoutBudget
import com.tencent.kmm.network.internal.platform.androidRequestTimeoutBudget
import com.tencent.kmm.network.internal.platform.requireAndroidRequestTimeoutBudget
import io.ktor.client.engine.android.AndroidClientEngine
import io.ktor.client.engine.okhttp.OkHttpEngine
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertEquals(5, defaults.clientShardCount)
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
        val manager = AndroidTransportClientGenerationManager(
            clientFactory = {
                clientsCreated.incrementAndGet()
                buildTransportHttpClient(okHttpEnabled = true, recovery = it)
            },
        )
        val recovery = VBTransportReusedHttp2Recovery(enabled = true, clientShardCount = 1)
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
        val recovery = VBTransportReusedHttp2Recovery(enabled = true, clientShardCount = 1)
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
    fun getAndHeadBodiesAreNeverAutomaticallyReplayed() {
        listOf(VBTransportMethod.GET, VBTransportMethod.HEAD).forEach { method ->
            val bufferedBody = AndroidReusedH2RetryState(method, hasReplayUnsafeBody = true)
            assertTrue(!bufferedBody.claimRetry(watchdogTriggered = true, hasBudget = true))
            assertTrue(!bufferedBody.attempted)
        }
    }

    @Test
    fun freshRetryRequiresWatchdogAndRemainingTotalBudget() {
        val state = AndroidReusedH2RetryState(VBTransportMethod.GET)
        assertTrue(!state.claimRetry(watchdogTriggered = false, hasBudget = true))
        assertTrue(!state.claimRetry(watchdogTriggered = true, hasBudget = false))
        assertTrue(!state.attempted)
    }

    @Test
    fun requestsRoundRobinAcrossFiveIndependentClientPools() {
        val clientsCreated = AtomicInteger()
        val manager = AndroidTransportClientGenerationManager(
            clientFactory = {
                clientsCreated.incrementAndGet()
                buildTransportHttpClient(okHttpEnabled = true, recovery = it)
            },
        )
        val recovery = VBTransportReusedHttp2Recovery(enabled = true, clientShardCount = 5)
        val leases = List(10) {
            manager.acquire("https://api.example.test/v1/messages?request=$it", recovery)
        }
        try {
            assertEquals(listOf(0, 1, 2, 3, 4, 0, 1, 2, 3, 4), leases.map { it.shard })
            assertEquals(5, clientsCreated.get())
            assertEquals(5, leases.map { it.generation }.distinct().size)
        } finally {
            leases.forEach(AndroidTransportClientLease::close)
        }
    }

    @Test
    fun oneStaleShardRollsOverWithoutChangingTheOtherFour() {
        val manager = AndroidTransportClientGenerationManager()
        val recovery = VBTransportReusedHttp2Recovery(enabled = true, clientShardCount = 5)
        val before = List(5) { manager.acquire("https://api.example.test/v1/$it", recovery) }
        try {
            val beforeByShard = before.associate { it.shard to it.generation }
            val stale = before.first { it.shard == 2 }
            val rollover = stale.drainGeneration()
            assertTrue(rollover.initiated)

            val after = List(5) { manager.acquire("https://api.example.test/v2/$it", recovery) }
            try {
                val afterByShard = after.associate { it.shard to it.generation }
                assertTrue(afterByShard.getValue(2) != beforeByShard.getValue(2))
                listOf(0, 1, 3, 4).forEach { shard ->
                    assertEquals(beforeByShard.getValue(shard), afterByShard.getValue(shard))
                }
            } finally {
                after.forEach(AndroidTransportClientLease::close)
            }
        } finally {
            before.forEach(AndroidTransportClientLease::close)
        }
    }

    @Test
    fun freshRetryAvoidsTheShardThatTriggeredTheWatchdog() {
        val manager = AndroidTransportClientGenerationManager()
        val recovery = VBTransportReusedHttp2Recovery(enabled = true, clientShardCount = 5)
        manager.acquire("https://api.example.test/v1/old", recovery).use { old ->
            old.drainGeneration()
            manager.acquire(
                "https://api.example.test/v1/fresh",
                recovery,
                avoidShard = old.shard,
            ).use { fresh ->
                assertTrue(fresh.shard != old.shard)
            }
        }
    }

    @Test
    fun repeatedMultiShardFailuresCannotCreateClientsWithoutBound() {
        var now = 1_000L
        val clientsCreated = AtomicInteger()
        val manager = AndroidTransportClientGenerationManager(
            clientFactory = {
                clientsCreated.incrementAndGet()
                buildTransportHttpClient(okHttpEnabled = true, recovery = it)
            },
            nowMillis = { now },
        )
        val recovery = VBTransportReusedHttp2Recovery(enabled = true, clientShardCount = 5)
        val firstWave = List(5) { manager.acquire("https://api.example.test/first/$it", recovery) }
        try {
            firstWave.forEach { lease ->
                val result = lease.drainGeneration()
                assertTrue(result.initiated)
                assertTrue(!result.rateLimited)
            }
            assertEquals(10, clientsCreated.get())

            val secondWave = List(5) { manager.acquire("https://api.example.test/second/$it", recovery) }
            try {
                secondWave.forEach { lease ->
                    val result = lease.drainGeneration()
                    assertTrue(!result.initiated)
                    assertTrue(result.rateLimited)
                    assertTrue(!result.observedGenerationDraining)
                }
                assertEquals(10, clientsCreated.get())

                now += 30_000L
                val afterCooldown = secondWave.first().drainGeneration()
                assertTrue(afterCooldown.initiated)
                assertEquals(11, clientsCreated.get())
            } finally {
                secondWave.forEach(AndroidTransportClientLease::close)
            }
        } finally {
            firstWave.forEach(AndroidTransportClientLease::close)
        }
    }

    @Test
    fun rateLimitedStaleShardIsQuarantinedWhileHealthySlotsExist() {
        var now = 1_000L
        val manager = AndroidTransportClientGenerationManager(nowMillis = { now })
        val recovery = VBTransportReusedHttp2Recovery(enabled = true, clientShardCount = 5)

        fun acquireShardZero(): AndroidTransportClientLease {
            repeat(10) { index ->
                val lease = manager.acquire("https://api.example.test/seek/$index", recovery)
                if (lease.shard == 0) return lease
                lease.close()
            }
            error("round-robin never selected shard zero")
        }

        repeat(5) {
            acquireShardZero().use { lease ->
                assertTrue(lease.drainGeneration().initiated)
            }
        }
        acquireShardZero().use { lease ->
            val limited = lease.drainGeneration()
            assertTrue(limited.rateLimited)
            assertTrue(!limited.observedGenerationDraining)
        }

        val subsequent = List(8) {
            manager.acquire("https://api.example.test/healthy/$it", recovery)
        }
        try {
            assertTrue(subsequent.none { it.shard == 0 })
            assertEquals(setOf(1, 2, 3, 4), subsequent.map { it.shard }.toSet())
        } finally {
            subsequent.forEach(AndroidTransportClientLease::close)
        }

        now += 30_000L
        val afterCooldown = List(5) {
            manager.acquire("https://api.example.test/probe/$it", recovery)
        }
        try {
            assertTrue(afterCooldown.any { it.shard == 0 })
        } finally {
            afterCooldown.forEach(AndroidTransportClientLease::close)
        }
    }

    @Test
    fun stalledConnectionRegistryRequiresTwoCallsOnTheSamePhysicalConnection() {
        val registry = AndroidStalledConnectionRegistry()
        val triggered = mutableListOf<String>()
        registry.register("connection-a", "request-1") { triggered += "one:$it" }
        registry.matureAndTriggerIfAtLeast("connection-a", "request-1", minimum = 2)
        assertTrue(triggered.isEmpty())

        registry.register("connection-b", "request-other") { triggered += "other:$it" }
        registry.matureAndTriggerIfAtLeast("connection-b", "request-other", minimum = 2)
        assertTrue(triggered.isEmpty())

        // The key is the physical OkHttp Connection identity, not origin. Two
        // coalesced-origin calls on the same connection therefore share count.
        registry.register("connection-a", "request-2-other-origin") { triggered += "two:$it" }
        registry.matureAndTriggerIfAtLeast(
            "connection-a",
            "request-2-other-origin",
            minimum = 2,
        )
        assertEquals(setOf("one:2", "two:2"), triggered.toSet())
    }

    @Test
    fun stalledConnectionRegistryRemovalPreventsCountLeakAndFalseTrigger() {
        val registry = AndroidStalledConnectionRegistry()
        var triggerCount = 0
        registry.register("connection-a", "request-1") { triggerCount += 1 }
        registry.register("connection-a", "request-2") { triggerCount += 1 }
        assertEquals(2, registry.count("connection-a"))

        registry.matureAndTriggerIfAtLeast("connection-a", "request-1", minimum = 2)
        registry.unregister("connection-a", "request-1")
        assertEquals(1, registry.count("connection-a"))
        registry.matureAndTriggerIfAtLeast("connection-a", "request-2", minimum = 2)
        assertEquals(0, triggerCount)

        registry.unregister("connection-a", "request-2")
        assertEquals(0, registry.count("connection-a"))
    }

    @Test
    fun totalTimeoutBudgetDistinguishesUnlimitedRemainingAndExpired() {
        assertEquals(
            AndroidRequestTimeoutBudget.Unlimited,
            androidRequestTimeoutBudget(totalTimeoutMillis = 0L, elapsedMillis = 99_000L),
        )
        assertEquals(
            AndroidRequestTimeoutBudget.Remaining(1L),
            androidRequestTimeoutBudget(totalTimeoutMillis = 1L, elapsedMillis = 0L),
        )
        assertEquals(
            AndroidRequestTimeoutBudget.Expired,
            androidRequestTimeoutBudget(totalTimeoutMillis = 1L, elapsedMillis = 1L),
        )
        assertEquals(
            AndroidRequestTimeoutBudget.Expired,
            androidRequestTimeoutBudget(totalTimeoutMillis = 1L, elapsedMillis = 2L),
        )

        var acquisitions = 0
        assertFailsWith<java.net.SocketTimeoutException> {
            requireAndroidRequestTimeoutBudget(AndroidRequestTimeoutBudget.Expired)
            acquisitions += 1
        }
        assertEquals(0, acquisitions)
        requireAndroidRequestTimeoutBudget(AndroidRequestTimeoutBudget.Remaining(1L))
        acquisitions += 1
        assertEquals(1, acquisitions)
    }

    @Test
    fun oldConfigurationCannotRollBackCurrentEpochAndDisableDrainsPools() {
        val manager = AndroidTransportClientGenerationManager()
        val oldConfig = VBTransportReusedHttp2Recovery(
            enabled = true,
            clientShardCount = 1,
            responseHeadersWatchdogMillis = 7_000L,
        )
        val newConfig = oldConfig.copy(responseHeadersWatchdogMillis = 9_000L)
        val oldLease = manager.acquire(
            url = "https://api.example.test/old",
            recovery = oldConfig,
            epoch = 1L,
        )
        assertEquals(1, manager.openGenerationCountForTests())

        manager.activateConfiguration(epoch = 2L)
        assertEquals(1, manager.openGenerationCountForTests())
        assertFailsWith<IllegalStateException> {
            manager.acquire(
                url = "https://api.example.test/stale-retry",
                recovery = oldConfig,
                epoch = 1L,
            )
        }

        val newLease = manager.acquire(
            url = "https://api.example.test/new",
            recovery = newConfig,
            epoch = 2L,
        )
        assertEquals(2, manager.openGenerationCountForTests())
        val staleDrain = oldLease.drainGeneration()
        assertTrue(!staleDrain.initiated)
        assertTrue(staleDrain.observedGenerationDraining)

        oldLease.close()
        assertEquals(1, manager.openGenerationCountForTests())

        // An enabled -> disabled or OkHttp -> legacy switch publishes another
        // epoch. The active recovery client drains immediately when idle.
        newLease.close()
        manager.activateConfiguration(epoch = 3L)
        assertEquals(0, manager.openGenerationCountForTests())
    }

    @Test
    fun publicDisablePublishesNewEpochAndClosesIdleRecoveryPools() {
        val originalEngine = VBTransportAndroidEngine.okHttpEnabled
        val originalRecovery = VBTransportAndroidEngine.reusedHttp2Recovery
        try {
            VBTransportAndroidEngine.okHttpEnabled = true
            VBTransportAndroidEngine.reusedHttp2Recovery =
                VBTransportReusedHttp2Recovery(enabled = true, clientShardCount = 1)
            val enabledSnapshot = AndroidTransportClientProvider.snapshot()
            val request = com.tencent.kmm.network.export.VBTransportGetRequest().apply {
                url = "https://api.example.test/config-lifecycle"
            }
            AndroidTransportClientProvider.acquire(request, enabledSnapshot).use { }
            assertEquals(1, AndroidTransportClientProvider.openGenerationCountForTests())

            VBTransportAndroidEngine.reusedHttp2Recovery = originalRecovery.copy(enabled = false)
            assertTrue(!AndroidTransportClientProvider.isCurrent(enabledSnapshot))
            assertEquals(0, AndroidTransportClientProvider.openGenerationCountForTests())
            assertFailsWith<IllegalStateException> {
                AndroidTransportClientProvider.acquire(request, enabledSnapshot)
            }
        } finally {
            VBTransportAndroidEngine.reusedHttp2Recovery = originalRecovery
            VBTransportAndroidEngine.okHttpEnabled = originalEngine
        }
    }
}

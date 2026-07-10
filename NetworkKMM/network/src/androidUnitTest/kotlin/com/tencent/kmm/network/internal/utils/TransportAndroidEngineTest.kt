package com.tencent.kmm.network.internal.utils

import com.tencent.kmm.network.export.VBTransportAndroidEngine
import io.ktor.client.engine.android.AndroidClientEngine
import io.ktor.client.engine.okhttp.OkHttpEngine
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
}

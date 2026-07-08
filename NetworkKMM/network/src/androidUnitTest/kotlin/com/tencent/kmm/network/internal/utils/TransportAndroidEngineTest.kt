package com.tencent.kmm.network.internal.utils

import com.tencent.kmm.network.export.VBTransportAndroidEngine
import io.ktor.client.engine.android.AndroidClientEngine
import io.ktor.client.engine.okhttp.OkHttpEngine
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertIs
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

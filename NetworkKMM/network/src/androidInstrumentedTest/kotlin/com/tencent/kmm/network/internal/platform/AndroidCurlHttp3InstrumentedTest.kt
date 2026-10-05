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

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.kmm.network.export.*
import com.tencent.kmm.network.service.NetworkClient
import com.tencent.kmm.network.service.NetworkClientConfig
import com.tencent.kmm.network.service.NetworkEngineSelection
import com.tencent.kmm.network.service.NetworkTransportEngine
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Common setup only: no inherited tests, mocked transport, TLS bypass or native replacement. */
abstract class AndroidCurlHttp3Fixture {
    private lateinit var publicCa: File

    @Before
    fun setUpHttp3() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        publicCa = File(instrumentation.targetContext.cacheDir, "networkkmm-public-runtime-ca.pem")
        instrumentation.context.assets.open("networkkmm-cacert.pem").use { input ->
            publicCa.outputStream().use { input.copyTo(it) }
        }
        assertTrue("committed curl must load", AndroidCurlJniBridge.isAvailable)
        assertTrue("committed curl must advertise HTTP/3", AndroidCurlJniBridge.supportsHttp3)
    }

    @After
    fun tearDownHttp3() {
        AndroidCurlEngineProvider.testBridge = null
        VBTransportCurl.clear()
        if (::publicCa.isInitialized) publicCa.delete()
    }

    protected suspend fun execute(url: String, http3Enabled: Boolean = true): NetworkResponse {
        val status = VBTransportCurl.configure(NetworkCurlRuntimeConfiguration(
            trustStore = NetworkCurlTrustStore(publicCa.absolutePath, networkCurlSha256Hex(publicCa.readBytes())),
            proxy = NetworkCurlProxyConfiguration.direct(),
            http3Enabled = http3Enabled
        ))
        assertTrue(status.detail, status.configured)
        val observed = AtomicReference<AndroidCurlNativeRequest?>()
        // Observe the effective JNI request; always delegate to the real committed native library.
        AndroidCurlEngineProvider.testBridge = object : AndroidCurlNativeBridge by AndroidCurlJniBridge {
            override suspend fun execute(request: AndroidCurlNativeRequest): com.tencent.kmm.network.curl.CurlNativeResponse {
                observed.set(request)
                return AndroidCurlJniBridge.execute(request)
            }
        }
        val response = try {
            NetworkClient(NetworkClientConfig(engineSelector = {
                NetworkEngineSelection(requestedEngine = NetworkTransportEngine.CURL)
            })).execute(NetworkRequest(url = url, policy = NetworkRequestPolicy(timeoutMillis = 30_000)))
        } finally {
            AndroidCurlEngineProvider.testBridge = null
        }
        val native = requireNotNull(observed.get()) { "request must execute through the Android curl JNI bridge" }
        assertEquals("effective H3 intent", http3Enabled, native.http3Enabled)
        assertEquals("hostname/SNI input must remain unchanged", url, native.url)
        assertEquals("direct connection", "", native.proxyUrl)
        val actual = when (response.timing.protocol) {
            "h3" -> NetworkHttpProtocol.HTTP_3
            "h2" -> NetworkHttpProtocol.HTTP_2
            "http/1.1", "http/1.x" -> NetworkHttpProtocol.HTTP_1_1
            else -> NetworkHttpProtocol.UNKNOWN
        }
        if (response.isSuccess) assertEquals("public protocol must match native completion", actual, response.protocol)
        Log.i("NetworkKmmH3Contract", "uid=${android.os.Process.myUid()} url=$url h3=$http3Enabled " +
            "protocol=${response.protocol} nativeProtocol=${response.timing.protocol} status=${response.statusCode} " +
            "success=${response.isSuccess} error=${response.error?.kind}")
        return response
    }

    protected companion object {
        const val H3_HOST = "cloudflare-quic.com"
        const val H3_URL = "https://cloudflare-quic.com/"
    }
}

/** Ordinary system DNS: CURL_HTTP_VERSION_3 permits fallback; the positive is a separate CI gate. */
@RunWith(AndroidJUnit4::class)
class AndroidCurlHttp3NegotiationInstrumentedTest : AndroidCurlHttp3Fixture() {
    @Test
    fun enabledDualStackRequestReportsNegotiatedProtocol() = runBlocking {
        val response = execute(H3_URL)
        assertTrue("enabled request failed: ${response.error}", response.isSuccess)
        assertEquals(200, response.statusCode)
        assertTrue("unexpected negotiated protocol ${response.protocol}", response.protocol in setOf(
            NetworkHttpProtocol.HTTP_3, NetworkHttpProtocol.HTTP_2, NetworkHttpProtocol.HTTP_1_1
        ))
    }

    @Test
    fun enabledRequestFallsBackAtHttp2OnlyOrigin() = runBlocking {
        val response = execute("https://github.com/robots.txt")
        assertTrue("H2 fallback failed: ${response.error}", response.isSuccess)
        assertEquals(200, response.statusCode)
        assertEquals(NetworkHttpProtocol.HTTP_2, response.protocol)
    }

    @Test
    fun totalConnectionFailureRemainsFailure() = runBlocking {
        val response = execute("https://127.0.0.1:1/")
        assertFalse(response.isSuccess)
        assertEquals(NetworkErrorKind.CONNECT, response.error?.kind)
        assertEquals(NetworkHttpProtocol.UNKNOWN, response.protocol)
    }
}

/** Mandatory separate job supplies a real A-only DNS answer for the original HTTPS hostname. */
@RunWith(AndroidJUnit4::class)
class AndroidCurlHttp3PositiveInstrumentedTest : AndroidCurlHttp3Fixture() {
    @Test
    fun actualHttp3ConnectionDoesNotLeakIntoDefaultPool() = runBlocking {
        val expected = requireNotNull(InstrumentationRegistry.getArguments().getString("h3ExpectedIpv4")) {
            "controlled IPv4 DNS fixture is required; never skip the H3 positive"
        }
        val addresses = InetAddress.getAllByName(H3_HOST).toList()
        assertTrue("A-only DNS fixture was not applied: $addresses", addresses.isNotEmpty() &&
            addresses.all { it is Inet4Address && it.hostAddress == expected })
        Log.i("NetworkKmmH3Contract", "controlledDns host=$H3_HOST addresses=$addresses expected=$expected")
        val h3 = execute(H3_URL)
        assertTrue("mandatory App H3 failed: ${h3.error}", h3.isSuccess)
        assertEquals(200, h3.statusCode)
        assertEquals(NetworkHttpProtocol.HTTP_3, h3.protocol)

        // Same verified H3 origin, then disable H3: default pool must stay H2.
        val defaultH2 = execute(H3_URL, http3Enabled = false)
        assertTrue("default H2 failed: ${defaultH2.error}", defaultH2.isSuccess)
        assertEquals(200, defaultH2.statusCode)
        assertEquals(NetworkHttpProtocol.HTTP_2, defaultH2.protocol)
    }
}

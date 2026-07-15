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

import com.tencent.kmm.network.export.VBTransportAndroidEngine
import com.tencent.kmm.network.export.VBTransportMethod
import com.tencent.kmm.network.export.VBTransportRequest
import com.tencent.kmm.network.export.VBTransportResponse
import com.tencent.kmm.network.export.VBTransportReusedHttp2Recovery
import com.tencent.kmm.network.internal.utils.AndroidTransportClientProvider
import com.tencent.kmm.network.internal.utils.AndroidTransportPhaseTracer
import com.tencent.kmm.network.internal.utils.applyTransportOkHttpDefaults
import com.tencent.kmm.network.service.VBTransportService
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import okhttp3.Protocol
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidTransportHardDeadlineIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var releaseTransportStart: CountDownLatch
    private var originalOkHttpEnabled: Boolean = true
    private lateinit var originalRecovery: VBTransportReusedHttp2Recovery

    @BeforeTest
    fun setUp() {
        originalOkHttpEnabled = VBTransportAndroidEngine.okHttpEnabled
        originalRecovery = VBTransportAndroidEngine.reusedHttp2Recovery
        releaseTransportStart = CountDownLatch(1)
        AndroidTransportTestHooks.reset()
        AndroidTransportPhaseTracer.resetForTests()

        server = MockWebServer().apply {
            protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/stall" -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    "/warm", "/ok" -> MockResponse().setBody("ok")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }

        AndroidTransportClientProvider.setClientFactoryForTests { recovery ->
            HttpClient(OkHttp) {
                install(HttpTimeout)
                engine {
                    config {
                        applyTransportOkHttpDefaults(recovery.pingIntervalMillis)
                        protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
                    }
                }
            }
        }
        VBTransportAndroidEngine.okHttpEnabled = true
        VBTransportAndroidEngine.reusedHttp2Recovery = VBTransportReusedHttp2Recovery(
            enabled = true,
            clientShardCount = 1,
            responseHeadersWatchdogMillis = 10_000L,
            minimumConcurrentStalledRequests = 2,
        )
    }

    @AfterTest
    fun tearDown() {
        releaseTransportStart.countDown()
        AndroidTransportTestHooks.reset()
        VBTransportAndroidEngine.reusedHttp2Recovery = originalRecovery
        VBTransportAndroidEngine.okHttpEnabled = originalOkHttpEnabled
        AndroidTransportClientProvider.setClientFactoryForTests(null)
        server.shutdown()
        AndroidTransportPhaseTracer.resetForTests()
    }

    @Test
    fun publicServiceDeadlineStartsBeforeBlockedTransportCoroutine() {
        AndroidTransportTestHooks.beforeTransportCoroutineStart = {
            releaseTransportStart.await(5, TimeUnit.SECONDS)
        }
        val request = request("/never-started", timeoutMillis = 100L)
        val startedAt = System.nanoTime()

        val timedOut = execute(request, waitMillis = 2_000L)
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue(elapsedMillis in 50L..1_000L, "caller-time deadline elapsed=$elapsedMillis ms")
        assertTrue(timedOut.errorMessage.contains("timeout", ignoreCase = true))
        assertTrue(
            AndroidTransportPhaseTracer.activeCallCountForTests() == 0,
            "transport must still be blocked before an OkHttp call starts",
        )
    }

    @Test
    fun reusedHttp2StreamIsCanceledAndSameGenerationConnectionRemainsUsable() {
        val warmRequest = request("/warm", timeoutMillis = 1_000L)
        val warmResponse = execute(warmRequest)
        val warmRecorded = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals(0, warmResponse.errorCode)
        assertEquals(0, warmRecorded.sequenceNumber)
        assertTrue(warmRequest.transportElapseStatistics.protocol.orEmpty().contains("h2"))
        assertEventually("warm request resources did not converge") {
            AndroidTransportPhaseTracer.activeCallCountForTests() == 0 &&
                AndroidTransportClientProvider.activeLeaseCountForTests() == 0
        }

        val stalledRequest = request("/stall", timeoutMillis = 100L)
        val startedAt = System.nanoTime()
        val timeoutResponse = execute(stalledRequest, waitMillis = 2_000L)
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        val stalledRecorded = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

        assertTrue(elapsedMillis in 50L..1_000L, "reused-H2 deadline elapsed=$elapsedMillis ms")
        assertTrue(timeoutResponse.errorMessage.contains("timeout", ignoreCase = true))
        assertEquals(1, stalledRecorded.sequenceNumber, "stall must use the warmed H2 connection")
        assertTrue(stalledRequest.transportElapseStatistics.reusedConnection == true)
        assertEventually("deadline did not cancel the OkHttp call and release its lease") {
            AndroidTransportPhaseTracer.activeCallCountForTests() == 0 &&
                AndroidTransportClientProvider.activeLeaseCountForTests() == 0
        }

        val nextRequest = request("/ok", timeoutMillis = 1_000L)
        val nextResponse = execute(nextRequest)
        val nextRecorded = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

        assertEquals(0, nextResponse.errorCode)
        assertEquals("ok", (nextResponse.data as ByteArray).decodeToString())
        assertEquals(2, nextRecorded.sequenceNumber, "next request must reuse the same H2 connection")
        assertEquals(
            stalledRequest.transportElapseStatistics.connectionGeneration,
            nextRequest.transportElapseStatistics.connectionGeneration,
        )
        assertEquals(
            stalledRequest.transportElapseStatistics.connectionIdentity,
            nextRequest.transportElapseStatistics.connectionIdentity,
        )
        assertEventually("next request resources did not converge") {
            AndroidTransportPhaseTracer.activeCallCountForTests() == 0 &&
                AndroidTransportClientProvider.activeLeaseCountForTests() == 0
        }
    }

    private fun execute(request: VBTransportRequest, waitMillis: Long = 2_000L): VBTransportResponse {
        val response = AtomicReference<VBTransportResponse?>()
        val latch = CountDownLatch(1)
        VBTransportService.sendRequest(request) {
            response.set(it)
            latch.countDown()
        }
        assertTrue(latch.await(waitMillis, TimeUnit.MILLISECONDS), "request callback did not arrive")
        return assertNotNull(response.get())
    }

    private fun request(path: String, timeoutMillis: Long): VBTransportRequest =
        VBTransportRequest().apply {
            method = VBTransportMethod.GET
            url = server.url(path).toString()
            totalTimeout = timeoutMillis
        }

    private fun assertEventually(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition(), message)
    }
}

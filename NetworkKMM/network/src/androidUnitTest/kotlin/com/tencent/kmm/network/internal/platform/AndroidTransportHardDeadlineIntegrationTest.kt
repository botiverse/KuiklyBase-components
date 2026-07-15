package com.tencent.kmm.network.internal.platform

import com.sun.net.httpserver.HttpServer
import com.tencent.kmm.network.export.VBTransportAndroidEngine
import com.tencent.kmm.network.export.VBTransportMethod
import com.tencent.kmm.network.export.VBTransportRequest
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidTransportHardDeadlineIntegrationTest {
    private lateinit var server: HttpServer
    private lateinit var releaseStall: CountDownLatch
    private var originalOkHttpEnabled: Boolean = true

    @BeforeTest
    fun setUp() {
        originalOkHttpEnabled = VBTransportAndroidEngine.okHttpEnabled
        VBTransportAndroidEngine.okHttpEnabled = true
        releaseStall = CountDownLatch(1)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool()
            createContext("/stall") { exchange ->
                releaseStall.await(5, TimeUnit.SECONDS)
                runCatching {
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.close()
                }
            }
            createContext("/ok") { exchange ->
                val body = "ok".encodeToByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
    }

    @AfterTest
    fun tearDown() {
        releaseStall.countDown()
        server.stop(0)
        (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        VBTransportAndroidEngine.okHttpEnabled = originalOkHttpEnabled
    }

    @Test
    fun noResponseHeadersHitsWallClockDeadlineAndNextRequestCompletes() {
        val timeoutResponse = AtomicReference<com.tencent.kmm.network.export.VBTransportResponse?>()
        val timeoutLatch = CountDownLatch(1)
        val startedAt = System.nanoTime()
        AndroidTransportImpl.request(
            request("/stall", timeoutMillis = 100L),
        ) { response ->
            timeoutResponse.set(response)
            timeoutLatch.countDown()
        }

        assertTrue(timeoutLatch.await(2, TimeUnit.SECONDS))
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        val timedOut = assertNotNull(timeoutResponse.get())
        assertTrue(elapsedMillis in 50L..1_000L, "deadline elapsed=$elapsedMillis ms")
        assertTrue(timedOut.errorMessage.contains("timeout", ignoreCase = true))

        val nextResponse = AtomicReference<com.tencent.kmm.network.export.VBTransportResponse?>()
        val nextLatch = CountDownLatch(1)
        AndroidTransportImpl.request(
            request("/ok", timeoutMillis = 1_000L),
        ) { response ->
            nextResponse.set(response)
            nextLatch.countDown()
        }

        assertTrue(nextLatch.await(2, TimeUnit.SECONDS))
        val completed = assertNotNull(nextResponse.get())
        assertEquals(0, completed.errorCode)
        assertEquals("ok", (completed.data as ByteArray).decodeToString())
    }

    private fun request(path: String, timeoutMillis: Long): VBTransportRequest =
        VBTransportRequest().apply {
            requestId = nextRequestId.incrementAndGet()
            method = VBTransportMethod.GET
            url = "http://127.0.0.1:${server.address.port}$path"
            totalTimeout = timeoutMillis
        }

    private companion object {
        val nextRequestId = AtomicInteger(900_000)
    }
}

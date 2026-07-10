package com.tencent.kmm.network.internal.utils

import com.sun.net.httpserver.HttpServer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidTransportPhaseTracerIntegrationTest {
    @Test
    fun dispatcherWaitIsMeasuredAndInternalHeaderIsNotSent() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val receivedTraceHeaders = Collections.synchronizedList(mutableListOf<String?>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool()
            createContext("/phase") { exchange ->
                receivedTraceHeaders += exchange.requestHeaders.getFirst(NETWORK_KMM_TRACE_HEADER)
                if (firstEntered.count > 0L) {
                    firstEntered.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                }
                exchange.sendResponseHeaders(200, 2)
                exchange.responseBody.use { it.write("ok".encodeToByteArray()) }
            }
            start()
        }
        val dispatcher = Dispatcher().apply {
            maxRequests = 2
            maxRequestsPerHost = 1
        }
        val client = OkHttpClient.Builder()
            .applyTransportOkHttpDefaults()
            .dispatcher(dispatcher)
            .build()
        val completed = CountDownLatch(2)
        val timings = Collections.synchronizedMap(mutableMapOf<Int, com.tencent.kmm.network.export.VBTransportElapseStatistics>())
        val url = "http://127.0.0.1:${server.address.port}/phase"

        try {
            enqueueTraced(client, url, requestId = 1, completed = completed, timings = timings)
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            enqueueTraced(client, url, requestId = 2, completed = completed, timings = timings)
            Thread.sleep(100)
            releaseFirst.countDown()

            assertTrue(completed.await(5, TimeUnit.SECONDS))
            val first = timings.getValue(1)
            val second = timings.getValue(2)
            assertTrue(second.dispatcherQueueTimeMs >= 50.0, "second=${second.dispatcherQueueTimeMs}")
            assertTrue(second.dispatcherQueueTimeMs > first.dispatcherQueueTimeMs)
            assertEquals("http/1.1", first.protocol)
            assertEquals(2, receivedTraceHeaders.size)
            receivedTraceHeaders.forEach(::assertNull)
        } finally {
            releaseFirst.countDown()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            server.stop(0)
            AndroidTransportPhaseTracer.resetForTests()
        }
    }

    private fun enqueueTraced(
        client: OkHttpClient,
        url: String,
        requestId: Int,
        completed: CountDownLatch,
        timings: MutableMap<Int, com.tencent.kmm.network.export.VBTransportElapseStatistics>
    ) {
        AndroidTransportPhaseTracer.scheduled(requestId)
        AndroidTransportPhaseTracer.transportCoroutineStarted(requestId)
        client.newCall(
            Request.Builder()
                .url(url)
                .header(NETWORK_KMM_TRACE_HEADER, requestId.toString())
                .build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                timings[requestId] = AndroidTransportPhaseTracer.complete(requestId)
                completed.countDown()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { it.body?.string() }
                timings[requestId] = AndroidTransportPhaseTracer.complete(requestId)
                completed.countDown()
            }
        })
    }
}

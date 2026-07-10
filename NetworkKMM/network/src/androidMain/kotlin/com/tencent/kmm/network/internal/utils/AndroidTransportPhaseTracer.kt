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
package com.tencent.kmm.network.internal.utils

import com.tencent.kmm.network.export.VBTransportElapseStatistics
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal const val NETWORK_KMM_TRACE_HEADER = "X-NetworkKMM-Trace-Id"

internal object AndroidTransportPhaseTracer {
    private val traces = ConcurrentHashMap<Int, Trace>()
    internal var nanoTime: () -> Long = System::nanoTime

    fun scheduled(requestId: Int) {
        traces[requestId] = Trace(requestId = requestId, scheduledNanos = nanoTime())
    }

    fun transportCoroutineStarted(requestId: Int) {
        update(requestId) { transportCoroutineStartedNanos = nanoTime() }
    }

    internal fun callStarted(requestId: Int) {
        update(requestId) { callStartNanos = nanoTime() }
    }

    fun dispatcherStarted(requestId: Int) {
        update(requestId) { dispatcherStartedNanos = nanoTime() }
    }

    fun responseBodyRead(requestId: Int) {
        update(requestId) { bodyReadEndNanos = nanoTime() }
    }

    fun cancel(requestId: Int) {
        traces.remove(requestId)
    }

    internal fun resetForTests() {
        traces.clear()
        nanoTime = System::nanoTime
    }

    fun complete(requestId: Int): VBTransportElapseStatistics {
        val trace = traces.remove(requestId)
            ?: return VBTransportElapseStatistics(transportRequestId = requestId.toString())
        return synchronized(trace) { trace.snapshot() }
    }

    fun eventListenerFactory(): EventListener.Factory = EventListener.Factory { call ->
        val requestId = call.request().header(NETWORK_KMM_TRACE_HEADER)?.toIntOrNull()
        if (requestId == null) EventListener.NONE else TraceEventListener(requestId)
    }

    private inline fun update(requestId: Int, block: Trace.() -> Unit) {
        traces[requestId]?.let { trace -> synchronized(trace) { trace.block() } }
    }

    private class TraceEventListener(private val requestId: Int) : EventListener() {
        private inline fun update(block: Trace.() -> Unit) {
            this@AndroidTransportPhaseTracer.update(requestId, block)
        }

        override fun callStart(call: Call) = callStarted(requestId)

        override fun dnsStart(call: Call, domainName: String) = update {
            dnsStartNanos = nanoTime()
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = update {
            dnsTimeNanos += elapsedSince(dnsStartNanos)
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) = update {
            connectStartNanos = nanoTime()
            connectAttempts += 1
        }

        override fun secureConnectStart(call: Call) = update {
            secureConnectStartNanos = nanoTime()
        }

        override fun secureConnectEnd(call: Call, handshake: Handshake?) = update {
            sslTimeNanos += elapsedSince(secureConnectStartNanos)
        }

        override fun connectEnd(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: okhttp3.Protocol?
        ) = update {
            connectTimeNanos += elapsedSince(connectStartNanos)
            protocolName = protocol?.toString() ?: protocolName
        }

        override fun connectFailed(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: okhttp3.Protocol?,
            ioe: IOException
        ) = update {
            connectTimeNanos += elapsedSince(connectStartNanos)
            protocolName = protocol?.toString() ?: protocolName
        }

        override fun connectionAcquired(call: Call, connection: Connection) = update {
            protocolName = connection.protocol().toString()
            reusedConnection = connectAttempts == 0
        }

        override fun requestHeadersStart(call: Call) = update {
            requestHeadersStartNanos = nanoTime()
        }

        override fun requestHeadersEnd(call: Call, request: Request) = update {
            requestHeadersEndNanos = nanoTime()
            requestHeadersTimeNanos += elapsedBetween(requestHeadersStartNanos, requestHeadersEndNanos)
        }

        override fun requestBodyStart(call: Call) = update {
            requestBodyStartNanos = nanoTime()
        }

        override fun requestBodyEnd(call: Call, byteCount: Long) = update {
            requestBodyEndNanos = nanoTime()
            requestBodyTimeNanos += elapsedBetween(requestBodyStartNanos, requestBodyEndNanos)
        }

        override fun responseHeadersStart(call: Call) = update {
            responseHeadersStartNanos = nanoTime()
            val requestEnd = requestBodyEndNanos.takeIf { it > 0L } ?: requestHeadersEndNanos
            responseWaitTimeNanos += elapsedBetween(requestEnd, responseHeadersStartNanos)
        }

        override fun responseHeadersEnd(call: Call, response: Response) = update {
            responseHeadersEndNanos = nanoTime()
            protocolName = response.protocol.toString()
        }

        override fun responseBodyEnd(call: Call, byteCount: Long) = update {
            responseBodyEndNanos = nanoTime()
        }

        override fun callEnd(call: Call) = update { callEndNanos = nanoTime() }

        override fun callFailed(call: Call, ioe: IOException) = update { callEndNanos = nanoTime() }
    }

    private class Trace(
        private val requestId: Int,
        private val scheduledNanos: Long
    ) {
        var transportCoroutineStartedNanos = 0L
        var callStartNanos = 0L
        var dispatcherStartedNanos = 0L
        var dnsStartNanos = 0L
        var dnsTimeNanos = 0L
        var connectStartNanos = 0L
        var connectTimeNanos = 0L
        var secureConnectStartNanos = 0L
        var sslTimeNanos = 0L
        var requestHeadersStartNanos = 0L
        var requestHeadersEndNanos = 0L
        var requestHeadersTimeNanos = 0L
        var requestBodyStartNanos = 0L
        var requestBodyEndNanos = 0L
        var requestBodyTimeNanos = 0L
        var responseHeadersStartNanos = 0L
        var responseHeadersEndNanos = 0L
        var responseWaitTimeNanos = 0L
        var responseBodyEndNanos = 0L
        var bodyReadEndNanos = 0L
        var callEndNanos = 0L
        var connectAttempts = 0
        var protocolName: String? = null
        var reusedConnection: Boolean? = null

        fun elapsedSince(start: Long): Long =
            if (start == 0L) 0L else (nanoTime() - start).coerceAtLeast(0L)

        fun elapsedBetween(start: Long, end: Long): Long =
            if (start == 0L || end == 0L) 0L else (end - start).coerceAtLeast(0L)

        fun snapshot(): VBTransportElapseStatistics {
            val end = sequenceOf(bodyReadEndNanos, responseBodyEndNanos, callEndNanos)
                .firstOrNull { it > 0L } ?: nanoTime()
            val transferStart = responseHeadersEndNanos.takeIf { it > 0L } ?: responseHeadersStartNanos
            return VBTransportElapseStatistics(
                transportQueueTimeMs = millis(elapsedBetween(scheduledNanos, transportCoroutineStartedNanos)),
                dispatcherQueueTimeMs = millis(elapsedBetween(callStartNanos, dispatcherStartedNanos)),
                nameLookupTimeMs = millis(dnsTimeNanos),
                connectTimeMs = millis(connectTimeNanos),
                sslCostTimeMs = millis(sslTimeNanos),
                preTransferTime = millis(elapsedBetween(dispatcherStartedNanos, requestHeadersStartNanos)),
                startTransferTimeMs = millis(elapsedBetween(dispatcherStartedNanos, responseHeadersStartNanos)),
                requestHeadersTimeMs = millis(requestHeadersTimeNanos),
                requestBodyTimeMs = millis(requestBodyTimeNanos),
                responseWaitTimeMs = millis(responseWaitTimeNanos),
                recvTime = millis(elapsedBetween(transferStart, end)),
                totalTimeMs = millis(elapsedBetween(scheduledNanos, end)),
                transportRequestId = requestId.toString(),
                protocol = protocolName,
                reusedConnection = reusedConnection,
                connectionAttemptCount = connectAttempts
            )
        }
    }

    private fun millis(nanos: Long): Double =
        nanos.toDouble() / TimeUnit.MILLISECONDS.toNanos(1).toDouble()
}

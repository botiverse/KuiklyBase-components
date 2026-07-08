package com.tencent.kmm.network.internal.utils

import kotlin.test.Test
import kotlin.test.assertTrue

class TransportFailureClassifierTest {
    private class FakeTimeoutException : Exception("Request timeout has expired [url=https://x, request_timeout=60000 ms]")
    private class FakeUnknownHostException : Exception("Unable to resolve host \"cdn.example.com\": No address associated with hostname")
    private class FakeSslException : Exception("SSL handshake aborted: ssl=0x7b8: I/O error during system call")
    private class FakeEofException : Exception("unexpected end of stream on Connection{cdn.example.com:443}")
    private class FakeConnectException : Exception("Failed to connect to cdn.example.com/1.2.3.4:443")
    private class WeirdEngineException : Exception("something exotic happened")

    @Test
    fun timeoutFailuresAreTaggedTimeout() {
        assertTrue(describeTransportFailure(FakeTimeoutException()).startsWith("[timeout]"))
    }

    @Test
    fun dnsFailuresAreTaggedDns() {
        assertTrue(describeTransportFailure(FakeUnknownHostException()).startsWith("[dns]"))
    }

    @Test
    fun tlsFailuresAreTaggedTls() {
        assertTrue(describeTransportFailure(FakeSslException()).startsWith("[tls]"))
    }

    @Test
    fun truncatedStreamFailuresAreTaggedConnectionLost() {
        assertTrue(describeTransportFailure(FakeEofException()).startsWith("[connection_lost]"))
    }

    @Test
    fun connectFailuresAreTaggedConnect() {
        assertTrue(describeTransportFailure(FakeConnectException()).startsWith("[connect]"))
    }

    @Test
    fun unknownFailuresFallBackToEngineTagWithTypeAndMessage() {
        val described = describeTransportFailure(WeirdEngineException())
        assertTrue(described.startsWith("[engine]"))
        assertTrue("WeirdEngineException" in described)
        assertTrue("something exotic happened" in described)
    }
}

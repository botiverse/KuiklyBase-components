package com.tencent.kmm.network.curl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** raft.46 (Hands 79db9fe6): a response-headers stall on a dead reused HTTP/2 connection. */
class CurlHeadersStallTest {
    @Test
    fun aResponseHeadersStallIsReplayedLikeABodyStall() {
        val headers = CurlNativeResponse(code = 28, errorMsg = "buffered response headers timeout after 7012ms")
        assertTrue(headers.isBufferedResponseHeadersTimeout())
        assertTrue(headers.isBufferedBodyIdleTimeout(), "the existing GET/HEAD fresh-retry path handles it")

        val body = CurlNativeResponse(code = 28, errorMsg = "buffered body idle timeout after 7001ms")
        assertFalse(body.isBufferedResponseHeadersTimeout(), "a body stall does not retire the pooled engine")
        assertTrue(body.isBufferedBodyIdleTimeout())

        val plainTimeout = CurlNativeResponse(code = 28, errorMsg = "Operation timed out after 30000 milliseconds")
        assertFalse(plainTimeout.isBufferedResponseHeadersTimeout())
        assertFalse(plainTimeout.isBufferedBodyIdleTimeout())
    }

    @Test
    fun theFirstStallRetiresThePooledEngineAndTheOthersOnTheSameConnectionDoNot() {
        var now = 0L
        val retirement = CurlPooledEngineRetirement(nowMillis = { now }, intervalMillis = 10_000)
        val retired = mutableListOf<Boolean>()

        // Several requests on the same dead connection stall together.
        assertTrue(retirement.retireIfDue(http3Enabled = false) { retired += it })
        assertFalse(retirement.retireIfDue(http3Enabled = false) { retired += it })
        now += 9_999
        assertFalse(retirement.retireIfDue(http3Enabled = false) { retired += it })
        // The HTTP/3 engine is a separate pool.
        assertTrue(retirement.retireIfDue(http3Enabled = true) { retired += it })
        now += 1
        assertTrue(retirement.retireIfDue(http3Enabled = false) { retired += it })

        assertEquals(listOf(false, true, false), retired)
    }
}

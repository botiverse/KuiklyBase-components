package com.tencent.kmm.network.internal.utils

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals

class ByteReadChannelWrapperTest {
    private fun bytes(count: Int): ByteArray = ByteArray(count) { (it % 251).toByte() }

    @Test
    fun readAvailableReturnsExactBodyWhenLengthMatches() = runBlocking {
        val body = bytes(64)
        val read = ByteReadChannelWrapper(ByteReadChannel(body)).readAvailable(64L)
        assertContentEquals(body, read)
    }

    @Test
    fun readAvailableReturnsShortBodyInsteadOfHangingWhenStreamEndsEarly() = runBlocking {
        // raft.9 regression pin: EOF before Content-Length bytes used to spin
        // forever (readAvailable -1 was treated as progress) until the request
        // timeout killed the call.
        val body = bytes(10)
        val read = ByteReadChannelWrapper(ByteReadChannel(body)).readAvailable(64L)
        assertContentEquals(body, read)
    }

    @Test
    fun readAvailableDrainsBytesDeliveredBeyondDeclaredLength() = runBlocking {
        // e.g. an engine that transparently decompressed the body while the
        // header still carries the compressed length: return everything the
        // stream delivered instead of truncating at the declared size.
        val body = bytes(20_000)
        val read = ByteReadChannelWrapper(ByteReadChannel(body)).readAvailable(64L)
        assertContentEquals(body, read)
    }

    @Test
    fun readAvailableHandlesZeroDeclaredLength() = runBlocking {
        val body = bytes(5)
        val read = ByteReadChannelWrapper(ByteReadChannel(body)).readAvailable(0L)
        assertContentEquals(body, read)
    }

    @Test
    fun readAvailableReturnsEmptyForEmptyBody() = runBlocking {
        val read = ByteReadChannelWrapper(ByteReadChannel(ByteArray(0))).readAvailable(0L)
        assertContentEquals(ByteArray(0), read)
    }
}

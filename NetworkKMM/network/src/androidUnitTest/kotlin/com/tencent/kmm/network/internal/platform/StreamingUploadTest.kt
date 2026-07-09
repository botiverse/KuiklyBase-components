package com.tencent.kmm.network.internal.platform

import com.tencent.kmm.network.export.NetworkBody
import com.tencent.kmm.network.export.NetworkByteStream
import com.tencent.kmm.network.export.NetworkByteStreamSink
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkRequestPolicy
import com.tencent.kmm.network.export.VBTransportMethod
import com.tencent.kmm.network.export.VBTransportRequest
import com.tencent.kmm.network.export.VBTransportResponse
import com.tencent.kmm.network.service.NetworkClient
import com.tencent.kmm.network.service.NetworkClientConfig
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * issue #8 slice 1: the streamed request body must arrive complete at a real
 * server through the real Android transport, with chunked framing when the
 * length is unknown and a true Content-Length when it is known. The server is
 * a minimal in-test HTTP/1.1 endpoint that records the raw request.
 */
class StreamingUploadTest {

    private lateinit var server: ServerSocket
    private var port = 0
    @Volatile private var receivedHeaders: String = ""
    @Volatile private var receivedBody: ByteArray = ByteArray(0)
    private var acceptor: Thread? = null

    @BeforeTest
    fun startServer() {
        server = ServerSocket(0)
        port = server.localPort
        acceptor = thread(isDaemon = true) {
            try {
                while (!server.isClosed) {
                    val socket = server.accept()
                    socket.use { s ->
                        val input = s.getInputStream()
                        val headerBuffer = ByteArrayOutputStream()
                        var last4 = 0
                        while (true) {
                            val b = input.read()
                            if (b == -1) break
                            headerBuffer.write(b)
                            last4 = ((last4 shl 8) or b) and 0xFFFFFFFF.toInt()
                            if (last4 == 0x0D0A0D0A) break
                        }
                        val headers = headerBuffer.toString(Charsets.ISO_8859_1.name())
                        receivedHeaders = headers
                        val body = ByteArrayOutputStream()
                        val contentLength = headers.lineSequence()
                            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                            ?.substringAfter(':')?.trim()?.toIntOrNull()
                        if (contentLength != null) {
                            var remaining = contentLength
                            val buf = ByteArray(8192)
                            while (remaining > 0) {
                                val n = input.read(buf, 0, minOf(buf.size, remaining))
                                if (n == -1) break
                                body.write(buf, 0, n)
                                remaining -= n
                            }
                        } else {
                            // chunked: decode until the zero-size chunk
                            while (true) {
                                val sizeLine = StringBuilder()
                                while (true) {
                                    val b = input.read()
                                    if (b == -1 || b == 0x0A) break
                                    if (b != 0x0D) sizeLine.append(b.toChar())
                                }
                                val size = sizeLine.toString().trim().toIntOrNull(16) ?: break
                                if (size == 0) break
                                var remaining = size
                                val buf = ByteArray(8192)
                                while (remaining > 0) {
                                    val n = input.read(buf, 0, minOf(buf.size, remaining))
                                    if (n == -1) break
                                    body.write(buf, 0, n)
                                    remaining -= n
                                }
                                input.read(); input.read() // trailing CRLF
                            }
                        }
                        receivedBody = body.toByteArray()
                        val reply = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                        s.getOutputStream().write(reply.toByteArray(Charsets.ISO_8859_1))
                        s.getOutputStream().flush()
                    }
                }
            } catch (_: Throwable) {
                // server closed
            }
        }
    }

    @AfterTest
    fun stopServer() {
        server.close()
    }

    private fun chunkedPayload(chunks: List<ByteArray>, contentLength: Long?): NetworkBody.Stream =
        NetworkBody.Stream(
            stream = NetworkByteStream.fromChunks(contentLength = contentLength) { sink ->
                chunks.forEach { sink.write(it) }
            },
            contentType = "application/octet-stream"
        )

    private fun payloadChunks(count: Int, size: Int): List<ByteArray> =
        (0 until count).map { i -> ByteArray(size) { j -> ((i * 31 + j) % 251).toByte() } }

    @Test
    fun streamedBodyWithKnownLengthArrivesCompleteWithContentLength() = runBlocking {
        val chunks = payloadChunks(count = 5, size = 4096)
        val total = chunks.sumOf { it.size }.toLong()
        val client = NetworkClient(NetworkClientConfig(defaultPolicy = NetworkRequestPolicy(timeoutMillis = 10_000)))
        val response = client.execute(
            NetworkRequest(
                method = VBTransportMethod.POST,
                url = "http://127.0.0.1:$port/upload",
                body = chunkedPayload(chunks, contentLength = total)
            )
        )
        assertEquals(200, response.statusCode)
        assertTrue(
            receivedHeaders.lineSequence().any { it.equals("Content-Length: $total", ignoreCase = true) },
            "known length must go out as a real Content-Length header:\n$receivedHeaders"
        )
        val expected = ByteArray(total.toInt())
        var off = 0
        chunks.forEach { it.copyInto(expected, off); off += it.size }
        assertContentEquals(expected, receivedBody)
    }

    @Test
    fun streamedBodyWithUnknownLengthArrivesCompleteAsChunked() = runBlocking {
        val chunks = payloadChunks(count = 7, size = 3000)
        val client = NetworkClient(NetworkClientConfig(defaultPolicy = NetworkRequestPolicy(timeoutMillis = 10_000)))
        val response = client.execute(
            NetworkRequest(
                method = VBTransportMethod.POST,
                url = "http://127.0.0.1:$port/upload",
                body = chunkedPayload(chunks, contentLength = null)
            )
        )
        assertEquals(200, response.statusCode)
        assertTrue(
            receivedHeaders.lineSequence().any {
                it.replace(" ", "").equals("Transfer-Encoding:chunked", ignoreCase = true)
            },
            "unknown length must go out chunked:\n$receivedHeaders"
        )
        val expected = ByteArray(chunks.sumOf { it.size })
        var off = 0
        chunks.forEach { it.copyInto(expected, off); off += it.size }
        assertContentEquals(expected, receivedBody)
    }

    @Test
    fun interfaceDefaultBuffersAndDelegatesToRequest() = runBlocking {
        // The buffered fallback (used by platforms without streaming) must
        // deliver the identical byte sequence through request().
        var captured: ByteArray? = null
        val service = object : IVBTransportService {
            override fun sendBytesRequest(kmmBytesRequest: com.tencent.kmm.network.export.VBTransportBytesRequest, kmmBytesResponseCallback: (com.tencent.kmm.network.export.VBTransportBytesResponse) -> Unit) = error("unused")
            override fun sendStringRequest(kmmStringRequest: com.tencent.kmm.network.export.VBTransportStringRequest, kmmStringResponseCallback: (com.tencent.kmm.network.export.VBTransportStringResponse) -> Unit) = error("unused")
            override fun post(kmmPostRequest: com.tencent.kmm.network.export.VBTransportPostRequest, kmmPostResponseCallback: (com.tencent.kmm.network.export.VBTransportPostResponse) -> Unit) = error("unused")
            override fun get(kmmGetRequest: com.tencent.kmm.network.export.VBTransportGetRequest, kmmGetResponseCallback: (com.tencent.kmm.network.export.VBTransportGetResponse) -> Unit) = error("unused")
            override fun request(kmmRequest: VBTransportRequest, kmmResponseCallback: (VBTransportResponse) -> Unit) {
                captured = kmmRequest.data as? ByteArray
                kmmResponseCallback(VBTransportResponse().apply { errorCode = 200 })
            }
            override fun cancel(requestId: Int) = Unit
        }
        val chunks = payloadChunks(count = 3, size = 100)
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        service.requestUploadStream(
            kmmRequest = VBTransportRequest(),
            contentLength = null,
            writeBody = { sink: NetworkByteStreamSink -> chunks.forEach { sink.write(it) } }
        ) { done.complete(Unit) }
        done.await()
        val expected = ByteArray(chunks.sumOf { it.size })
        var off = 0
        chunks.forEach { it.copyInto(expected, off); off += it.size }
        assertContentEquals(expected, captured)
    }

    @Test
    fun interfaceDefaultReportsWriteBodyFailureInsteadOfHanging() = runBlocking {
        // CC-希乐's #48 finding: a throwing writeBody must reach the callback
        // as a classified error — never a swallowed exception + eternal hang
        // (uploadStream schedules no force-timeout by design).
        val service = object : IVBTransportService {
            override fun sendBytesRequest(kmmBytesRequest: com.tencent.kmm.network.export.VBTransportBytesRequest, kmmBytesResponseCallback: (com.tencent.kmm.network.export.VBTransportBytesResponse) -> Unit) = error("unused")
            override fun sendStringRequest(kmmStringRequest: com.tencent.kmm.network.export.VBTransportStringRequest, kmmStringResponseCallback: (com.tencent.kmm.network.export.VBTransportStringResponse) -> Unit) = error("unused")
            override fun post(kmmPostRequest: com.tencent.kmm.network.export.VBTransportPostRequest, kmmPostResponseCallback: (com.tencent.kmm.network.export.VBTransportPostResponse) -> Unit) = error("unused")
            override fun get(kmmGetRequest: com.tencent.kmm.network.export.VBTransportGetRequest, kmmGetResponseCallback: (com.tencent.kmm.network.export.VBTransportGetResponse) -> Unit) = error("unused")
            override fun request(kmmRequest: VBTransportRequest, kmmResponseCallback: (VBTransportResponse) -> Unit) = error("must not reach request() on writeBody failure")
            override fun cancel(requestId: Int) = Unit
        }
        val done = kotlinx.coroutines.CompletableDeferred<VBTransportResponse>()
        service.requestUploadStream(
            kmmRequest = VBTransportRequest(),
            contentLength = null,
            writeBody = { _: NetworkByteStreamSink -> throw IllegalStateException("source stream broke mid-write") }
        ) { done.complete(it) }
        val response = done.await()
        assertEquals(com.tencent.kmm.network.export.VBTransportResultCode.CODE_NETWORK_ERROR, response.errorCode)
        assertTrue(
            response.errorMessage.contains("source stream broke mid-write"),
            "classified error must carry the cause: ${response.errorMessage}"
        )
    }
}

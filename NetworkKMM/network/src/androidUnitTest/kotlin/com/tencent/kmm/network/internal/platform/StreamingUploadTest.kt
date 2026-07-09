package com.tencent.kmm.network.internal.platform

import com.tencent.kmm.network.export.NetworkBody
import com.tencent.kmm.network.export.NetworkMultipartPart
import com.tencent.kmm.network.export.VBTransportMultipartBodyBuilder
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

    // ---- issue #8 slice 2: multipart streaming ----

    private fun expectedMultipartBytes(boundary: String, fileBytes: ByteArray): ByteArray =
        VBTransportMultipartBodyBuilder(boundary)
            .addFormField("purpose", "slice2")
            .addFile(
                name = "file",
                fileName = "payload.bin",
                bytes = fileBytes,
                contentType = "application/octet-stream"
            )
            .build()
            .data

    private fun multipartWithStreamingFile(
        boundary: String,
        fileBytes: ByteArray,
        declaredLength: Long?
    ): NetworkBody.Multipart =
        NetworkBody.Multipart(
            boundary = boundary,
            parts = listOf(
                NetworkMultipartPart(name = "purpose", body = NetworkBody.Text("slice2", contentType = "text/plain; charset=utf-8")),
                NetworkMultipartPart(
                    name = "file",
                    fileName = "payload.bin",
                    body = NetworkBody.Stream(
                        stream = NetworkByteStream.fromChunks(contentLength = declaredLength) { sink ->
                            fileBytes.toList().chunked(3072).forEach { chunk ->
                                sink.write(chunk.toByteArray())
                            }
                        },
                        contentType = "application/octet-stream"
                    )
                )
            )
        )

    @Test
    fun streamedMultipartWithKnownLengthsMatchesBufferedBuilderBytesExactly() = runBlocking {
        // The strongest slice-2 contract: a server cannot tell a streamed
        // multipart from the legacy buffered one — wire bytes are identical.
        val boundary = "slice2-boundary-A"
        val fileBytes = ByteArray(40_000) { (it % 251).toByte() }
        // Text part uses the builder's addFormField (no explicit content type)
        // in the reference; align both sides on the same part shapes instead:
        val expected = run {
            val builder = VBTransportMultipartBodyBuilder(boundary)
            builder.addPart(name = "purpose", bytes = "slice2".encodeToByteArray(), contentType = "text/plain; charset=utf-8")
            builder.addPart(name = "file", bytes = fileBytes, fileName = "payload.bin", contentType = "application/octet-stream")
            builder.build().data
        }
        val client = NetworkClient(NetworkClientConfig(defaultPolicy = NetworkRequestPolicy(timeoutMillis = 10_000)))
        val response = client.execute(
            NetworkRequest(
                method = VBTransportMethod.POST,
                url = "http://127.0.0.1:$port/upload",
                body = multipartWithStreamingFile(boundary, fileBytes, declaredLength = fileBytes.size.toLong())
            )
        )
        assertEquals(200, response.statusCode)
        assertTrue(
            receivedHeaders.lineSequence().any { it.equals("Content-Length: ${expected.size}", ignoreCase = true) },
            "all-known-length multipart must send exact Content-Length:\n$receivedHeaders"
        )
        assertContentEquals(expected, receivedBody)
    }

    @Test
    fun streamedMultipartWithUnknownPartLengthGoesChunkedWithIdenticalBytes() = runBlocking {
        val boundary = "slice2-boundary-B"
        val fileBytes = ByteArray(25_000) { ((it * 7) % 251).toByte() }
        val expected = run {
            val builder = VBTransportMultipartBodyBuilder(boundary)
            builder.addPart(name = "purpose", bytes = "slice2".encodeToByteArray(), contentType = "text/plain; charset=utf-8")
            builder.addPart(name = "file", bytes = fileBytes, fileName = "payload.bin", contentType = "application/octet-stream")
            builder.build().data
        }
        val client = NetworkClient(NetworkClientConfig(defaultPolicy = NetworkRequestPolicy(timeoutMillis = 10_000)))
        val response = client.execute(
            NetworkRequest(
                method = VBTransportMethod.POST,
                url = "http://127.0.0.1:$port/upload",
                body = multipartWithStreamingFile(boundary, fileBytes, declaredLength = null)
            )
        )
        assertEquals(200, response.statusCode)
        assertTrue(
            receivedHeaders.lineSequence().any {
                it.replace(" ", "").equals("Transfer-Encoding:chunked", ignoreCase = true)
            },
            "unknown part length must go out chunked:\n$receivedHeaders"
        )
        assertContentEquals(expected, receivedBody)
    }

    @Test
    fun multipartScalarPartEncodeFailureFallsBackToClassifiedError() = runBlocking {
        // CC-希乐's #53 finding: a scalar part that fails to encode must not
        // throw out of the streaming plan builder — the body falls back to the
        // buffered path, whose toBytes error becomes a classified
        // NetworkResponse.error exactly like a streamless multipart's would.
        val client = NetworkClient(NetworkClientConfig(defaultPolicy = NetworkRequestPolicy(timeoutMillis = 10_000)))
        val response = client.execute(
            NetworkRequest(
                method = VBTransportMethod.POST,
                url = "http://127.0.0.1:$port/upload",
                body = NetworkBody.Multipart(
                    boundary = "slice2-boundary-D",
                    parts = listOf(
                        NetworkMultipartPart(
                            name = "file",
                            fileName = "payload.bin",
                            body = NetworkBody.Stream(
                                stream = NetworkByteStream.fromChunks(contentLength = 3L) { sink ->
                                    sink.write(byteArrayOf(1, 2, 3))
                                },
                                contentType = "application/octet-stream"
                            )
                        ),
                        // The one scalar body that can fail to encode: a nested
                        // multipart holding a FileRef with neither readAllBlock
                        // nor openStreamBlock.
                        NetworkMultipartPart(
                            name = "meta",
                            body = NetworkBody.Multipart(
                                boundary = "slice2-inner",
                                parts = listOf(
                                    NetworkMultipartPart(name = "broken", body = NetworkBody.FileRef(path = "/nowhere.bin"))
                                )
                            )
                        )
                    )
                )
            )
        )
        assertEquals(null, response.statusCode)
        val error = response.error
        assertTrue(error != null, "encode failure must surface as a classified error, not a throw")
        assertTrue(
            error.message.orEmpty().contains("readAllBlock or openStreamBlock"),
            "classified error must carry the encode-failure cause: $error"
        )
    }

    @Test
    fun allScalarMultipartKeepsBufferedPathBytes() = runBlocking {
        // No streaming part -> legacy buffered path; bytes stay the
        // raft.8-validated builder output.
        val boundary = "slice2-boundary-C"
        val expected = run {
            val builder = VBTransportMultipartBodyBuilder(boundary)
            builder.addPart(name = "alpha", bytes = "one".encodeToByteArray(), contentType = "text/plain; charset=utf-8")
            builder.addPart(name = "beta", bytes = "two".encodeToByteArray(), contentType = "text/plain; charset=utf-8")
            builder.build().data
        }
        val client = NetworkClient(NetworkClientConfig(defaultPolicy = NetworkRequestPolicy(timeoutMillis = 10_000)))
        val response = client.execute(
            NetworkRequest(
                method = VBTransportMethod.POST,
                url = "http://127.0.0.1:$port/upload",
                body = NetworkBody.Multipart(
                    boundary = boundary,
                    parts = listOf(
                        NetworkMultipartPart(name = "alpha", body = NetworkBody.Text("one", contentType = "text/plain; charset=utf-8")),
                        NetworkMultipartPart(name = "beta", body = NetworkBody.Text("two", contentType = "text/plain; charset=utf-8"))
                    )
                )
            )
        )
        assertEquals(200, response.statusCode)
        assertContentEquals(expected, receivedBody)
    }
}

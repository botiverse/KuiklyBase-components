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
package com.tencent.kmm.network.darwin

import com.tencent.kmm.network.export.NetworkBody
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkRequestPolicy
import com.tencent.kmm.network.export.VBTransportMethod
import com.tencent.kmm.network.service.NetworkClient
import com.tencent.kmm.network.service.NetworkClientConfig
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behavior-contract tests for the REAL ktor Darwin (NSURLSession) engine —
 * the same iosMain transport code iOS ships — run natively on a macOS CI
 * runner against tests/wrapper/test_server.py.
 *
 * Why this lane exists (raft.9 lesson): Android had unit tests + device
 * verification and OHOS had host-native wrapper tests, but Darwin engine
 * semantics shipped completely unexercised. Every test here is bounded by
 * [withTimeout], so an engine-level hang (e.g. a body read that never sees
 * EOF) fails red instead of wedging CI.
 *
 * The server base URL comes from DARWIN_TEST_BASE_URL; tests no-op when it
 * is unset so the target stays buildable outside the CI harness.
 */
class DarwinEngineBehaviorTest {

    @OptIn(ExperimentalForeignApi::class)
    private val baseUrl: String? = getenv("DARWIN_TEST_BASE_URL")?.toKString()?.takeIf { it.isNotBlank() }

    private val client by lazy {
        NetworkClient(NetworkClientConfig(defaultPolicy = NetworkRequestPolicy(timeoutMillis = 10_000)))
    }

    private fun run(block: suspend (String) -> Unit) {
        val base = baseUrl ?: return
        runBlocking { withTimeout(15_000) { block(base) } }
    }

    private fun get(url: String) = NetworkRequest(method = VBTransportMethod.GET, url = url)

    @Test
    fun ciHarnessSuppliesTheServerUrl() {
        // Guards against a vacuous green: on CI (GitHub sets CI=true) the
        // base URL must have reached the test process, otherwise every other
        // test here silently no-ops.
        @OptIn(ExperimentalForeignApi::class)
        val onCi = getenv("CI")?.toKString() == "true"
        if (onCi) {
            assertTrue(baseUrl != null, "DARWIN_TEST_BASE_URL must be set on CI")
        }
    }

    @Test
    fun exactLengthBodyCompletesAndDeliversContent() = run { base ->
        val response = client.execute(get("$base/ok"))
        assertEquals(200, response.statusCode)
        assertTrue((response.body.bytes?.size ?: 0) > 0, "body must be non-empty")
    }

    @Test
    fun gzipBodyWithCompressedContentLengthDoesNotHangTheBodyRead() = run { base ->
        // The raft.9 read path treats Content-Length as a hint and drains to
        // EOF on over-delivery. NSURLSession transparently decompresses gzip,
        // so delivered bytes != header length — exactly the branch that never
        // ran on Darwin before this lane. A hang here times out red.
        val response = client.execute(get("$base/gzip"))
        assertEquals(200, response.statusCode)
        assertTrue((response.body.bytes?.size ?: 0) > 0, "decoded gzip body must be non-empty")
    }

    @Test
    fun authStatusPassesThroughInsteadOfHanging() = run { base ->
        // Login-shaped case: a non-2xx auth response must complete with its
        // real status (the iOS "stuck at Signing in..." class of symptom is a
        // request that never completes at all).
        val response = client.execute(get("$base/auth401"))
        assertEquals(401, response.statusCode)
    }

    @Test
    fun postBodyRoundTripsAndCompletes() = run { base ->
        val payload = """{"email":"darwin-lane@test.invalid","password":"x"}"""
        val response = client.execute(
            NetworkRequest(
                method = VBTransportMethod.POST,
                url = "$base/echo",
                body = NetworkBody.Text(payload, contentType = "application/json")
            )
        )
        assertEquals(200, response.statusCode)
        // test_server.py POST echoes the received byte count.
        assertTrue(
            response.body.text().orEmpty().contains("\"echoLen\":${payload.encodeToByteArray().size}"),
            "server must have received the full POST body: ${response.body.text()}"
        )
    }

    @Test
    fun streamedUploadArrivesCompleteThroughTheDarwinEngine() = run { base ->
        // issue #8 slice 1: the request body is produced chunk-by-chunk through
        // the streaming path (never buffered whole); the server's echoLen
        // proves every byte arrived through the real NSURLSession engine.
        val chunks = (0 until 6).map { i -> ByteArray(2048) { j -> ((i * 17 + j) % 251).toByte() } }
        val total = chunks.sumOf { it.size }.toLong()
        val response = client.execute(
            NetworkRequest(
                method = VBTransportMethod.POST,
                url = "$base/echo",
                body = com.tencent.kmm.network.export.NetworkBody.Stream(
                    stream = com.tencent.kmm.network.export.NetworkByteStream.fromChunks(contentLength = total) { sink ->
                        chunks.forEach { sink.write(it) }
                    },
                    contentType = "application/octet-stream"
                )
            )
        )
        assertEquals(200, response.statusCode)
        assertTrue(
            response.body.text().orEmpty().contains("\"echoLen\":$total"),
            "server must have received all $total streamed bytes: ${response.body.text()}"
        )
    }

    @Test
    fun streamedMultipartUploadDeliversFullBodyThroughTheRealEngine() = run { base ->
        // issue #8 slice 2 on the real NSURLSession engine: a multipart with a
        // streaming file part must arrive complete (server echoes byte count).
        val boundary = "darwin-slice2"
        val fileBytes = ByteArray(30_000) { (it % 251).toByte() }
        val expectedLength = com.tencent.kmm.network.export.VBTransportMultipartBodyBuilder(boundary)
            .addPart(name = "purpose", bytes = "slice2".encodeToByteArray(), contentType = "text/plain; charset=utf-8")
            .addPart(name = "file", bytes = fileBytes, fileName = "payload.bin", contentType = "application/octet-stream")
            .build().data.size
        val response = client.execute(
            NetworkRequest(
                method = VBTransportMethod.POST,
                url = "$base/echo",
                body = com.tencent.kmm.network.export.NetworkBody.Multipart(
                    boundary = boundary,
                    parts = listOf(
                        com.tencent.kmm.network.export.NetworkMultipartPart(
                            name = "purpose",
                            body = NetworkBody.Text("slice2", contentType = "text/plain; charset=utf-8")
                        ),
                        com.tencent.kmm.network.export.NetworkMultipartPart(
                            name = "file",
                            fileName = "payload.bin",
                            body = NetworkBody.Stream(
                                stream = com.tencent.kmm.network.export.NetworkByteStream.fromChunks(
                                    contentLength = fileBytes.size.toLong()
                                ) { sink ->
                                    fileBytes.toList().chunked(4096).forEach { sink.write(it.toByteArray()) }
                                },
                                contentType = "application/octet-stream"
                            )
                        )
                    )
                )
            )
        )
        assertEquals(200, response.statusCode)
        assertTrue(
            response.body.text().orEmpty().contains("\"echoLen\":$expectedLength"),
            "server must receive the full multipart body: ${response.body.text()}"
        )
    }

    @Test
    fun serverErrorBodyIsReadableAndCompletes() = run { base ->
        val response = client.execute(get("$base/boom500"))
        assertEquals(500, response.statusCode)
    }

    @Test
    fun redirectIsFollowedToCompletion() = run { base ->
        val response = client.execute(get("$base/redirect"))
        assertEquals(200, response.statusCode)
    }
}

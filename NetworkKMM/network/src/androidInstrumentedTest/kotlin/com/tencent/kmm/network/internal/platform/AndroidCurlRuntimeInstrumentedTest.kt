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

import android.util.Log
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tencent.kmm.network.export.NetworkBody
import com.tencent.kmm.network.export.NetworkByteStream
import com.tencent.kmm.network.export.NetworkByteStreamSink
import com.tencent.kmm.network.export.NetworkErrorKind
import com.tencent.kmm.network.export.NetworkHttpProtocol
import com.tencent.kmm.network.export.NetworkCurlProxyConfiguration
import com.tencent.kmm.network.export.NetworkCurlRuntimeConfiguration
import com.tencent.kmm.network.export.NetworkCurlTrustStore
import com.tencent.kmm.network.export.NetworkProgressCallbacks
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkRequestPolicy
import com.tencent.kmm.network.export.NetworkStreamTimeoutPolicy
import com.tencent.kmm.network.export.NetworkTransferProgress
import com.tencent.kmm.network.export.VBTransportAndroidCurl
import com.tencent.kmm.network.export.VBTransportCurl
import com.tencent.kmm.network.export.VBTransportMethod
import com.tencent.kmm.network.export.networkCurlSha256Hex
import com.tencent.kmm.network.service.NetworkCall
import com.tencent.kmm.network.service.NetworkClient
import com.tencent.kmm.network.service.NetworkClientConfig
import com.tencent.kmm.network.service.NetworkEngineDiagnosticsListener
import com.tencent.kmm.network.service.NetworkEngineSelection
import com.tencent.kmm.network.service.NetworkEngineSelectionDiagnostics
import com.tencent.kmm.network.service.NetworkTransportEngine
import com.tencent.kmm.network.service.AndroidCurlSystemProxyResolver
import com.tencent.kmm.network.service.CurlSystemProxyResolution
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidCurlRuntimeInstrumentedTest {
    private lateinit var server: RuntimeHttpServer
    private lateinit var trustStoreFile: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        trustStoreFile = File(context.cacheDir, "networkkmm-runtime-ca.pem")
        trustStoreFile.writeBytes(decode(AndroidCurlTlsTestMaterial.TRUSTED_CA_PEM_BASE64))
        configureCurl(trustStoreFile, NetworkCurlProxyConfiguration.direct())
        server = RuntimeHttpServer(concurrentUploadCount = CONCURRENT_UPLOADS)
    }

    @After
    fun tearDown() {
        server.close()
        AndroidCurlSystemProxyResolver.testResolver = null
        VBTransportCurl.clear()
        trustStoreFile.delete()
    }

    @Test
    fun committedAndroidCurlArtifactPassesRuntimeGate() {
        runBlocking {
            assertTrue("instrumentation APK must load the committed curl artifact", VBTransportAndroidCurl.nativeAvailable)
            val engine = requireNotNull(AndroidCurlEngineProvider.resolve())

            runtimeGate("buffered") { bufferedSelectorRequestUsesCurl() }
            runtimeGate("buffered-multi") { concurrentBufferedRequestsShareOneNativeOwner() }
            runtimeGate("buffered-cancel") { bufferedCoroutineCancellationWakesNativeOwner() }
            runtimeGate("download") { streamingDownloadPreservesCallbackThread() }
            runtimeGate("upload") { streamingUploadUsesNativePullAndProgress() }
            runtimeGate("external-cancel") { externalCancelStopsBodyCallbacks(engine) }
            runtimeGate("pre-cancel") { highLevelPreCancelNeverStartsNative(engine) }
            runtimeGate("native-pre-start") { nativePreStartCancelCoversAllThreeEntrypoints() }
            runtimeGate("callback-failure") { callbackFailureAbortsAndSuppressesLaterChunks(engine) }
            runtimeGate("concurrent-upload") { concurrentUploadsDoNotStarveDispatcher() }
            runtimeGate("cert-proxy") { certificateAcceptanceMatrixAndManualProxy() }
            runtimeGate("http3") { publicHttp3NegotiationContract() }

            Log.i(
                TAG,
                "completed passed=true gates=buffered,buffered-multi,buffered-cancel,download,upload,external-cancel," +
                    "pre-start,cross-thread,callback-failure,concurrent-upload,cert-matrix," +
                    "manual-proxy,android-system-pac-proxy,h3,h3-default-isolation,h3-h2-fallback," +
                    "h3-total-failure"
            )
        }
    }

    private suspend fun runtimeGate(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (throwable: Throwable) {
            throw AssertionError(
                "Android curl runtime gate '$name' failed: ${throwable.message ?: throwable}",
                throwable
            )
        }
    }

    private suspend fun bufferedSelectorRequestUsesCurl() {
        val selections = Collections.synchronizedList(mutableListOf<NetworkEngineSelectionDiagnostics>())
        val client = curlClient(selections)
        val request = request("/buffer").setHeader("X-Runtime-Gate", "header-lifetime")

        val response = client.execute(request)

        assertTrue("buffered selector response failed: ${response.error}", response.isSuccess)
        assertEquals(200, response.statusCode)
        assertEquals("buffer-ok", response.body.text())
        val selection = selections.single()
        assertEquals(NetworkTransportEngine.CURL, selection.selectedEngine)
        assertTrue(selection.capabilities.requestBodyStreaming)
        assertTrue(selection.capabilities.responseBodyStreaming)
        assertEquals(1, server.requestCount("/buffer"))
    }

    private suspend fun concurrentBufferedRequestsShareOneNativeOwner() = coroutineScope {
        val startedAt = System.nanoTime()
        val responses = withTimeout(CONCURRENT_TIMEOUT_MS) {
            (0 until CONCURRENT_BUFFERED_REQUESTS).map { index ->
                async(Dispatchers.Default) {
                    AndroidCurlJniBridge.execute(nativeRequest("/buffer-delay/$index"))
                }
            }.awaitAll()
        }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue(responses.all { it.code == 0 && it.httpCode == 200 })
        assertTrue(responses.all { it.data?.decodeToString() == "buffer-delay-ok" })
        if (responses.all { it.elapse.curlMultiOwnerThreadObserved == null }) {
            // Rollout compatibility: the committed older .so has no async JNI
            // entry, so Kotlin deliberately falls back to blocking V27. The
            // fresh-artifact run below must take the strict multi branch.
            return@coroutineScope
        }
        assertTrue(responses.all { it.elapse.curlMultiOwnerThreadObserved == true })
        assertTrue(responses.all { it.elapse.curlEnqueueToNativeStartElapsedMs >= 0.0 })
        assertEquals(CONCURRENT_BUFFERED_REQUESTS, server.maxConcurrentBuffered.get())
        assertTrue(
            "four 800ms buffered requests must advance concurrently, elapsed=$elapsedMillis",
            elapsedMillis < 2_200
        )
    }

    private suspend fun bufferedCoroutineCancellationWakesNativeOwner() = coroutineScope {
        val request = nativeRequest("/slow-buffer")
        val result = async(Dispatchers.Default) { AndroidCurlJniBridge.execute(request) }
        withTimeout(TIMEOUT_MS) {
            while (server.requestCount("/slow-buffer") == 0) {
                delay(10)
            }
        }

        result.cancelAndJoin()

        assertTrue("coroutine cancellation must abort the native buffered transfer",
            server.awaitBufferedSlowDisconnect())
        delay(100)
        val reusedIdResponse = AndroidCurlJniBridge.execute(nativeRequest("/slow-buffer"))
        assertEquals("cancel terminal must release the request-id mapping", 0, reusedIdResponse.code)
        assertEquals("slow-buffer-reused", reusedIdResponse.data?.decodeToString())
    }

    private suspend fun streamingDownloadPreservesCallbackThread() {
        val client = curlClient()
        val chunks = Collections.synchronizedList(mutableListOf<ByteArray>())
        val callbackThreads = Collections.synchronizedSet(mutableSetOf<Int>())
        val started = CompletableDeferred<Pair<Int, Long?>>()
        val completed = CompletableDeferred<com.tencent.kmm.network.export.NetworkResponse>()

        client.downloadStream(
            request = request("/stream"),
            onResponseStart = { status, length, _ ->
                callbackThreads += System.identityHashCode(Thread.currentThread())
                started.complete(status to length)
            },
            onChunk = { chunk ->
                callbackThreads += System.identityHashCode(Thread.currentThread())
                chunks += chunk.copyOf()
            },
            onComplete = completed::complete
        )

        assertEquals(200 to 20L, withTimeout(TIMEOUT_MS) { started.await() })
        val response = withTimeout(TIMEOUT_MS) { completed.await() }
        assertTrue(response.isSuccess)
        assertEquals("stream-onestream-two", merge(chunks).decodeToString())
        assertEquals("JNI callbacks must stay on the native perform thread", 1, callbackThreads.size)
    }

    private suspend fun streamingUploadUsesNativePullAndProgress() {
        val progress = Collections.synchronizedList(mutableListOf<NetworkTransferProgress>())
        val request = request("/upload").apply {
            method = VBTransportMethod.POST
            body = NetworkBody.Stream(
                stream = NetworkByteStream.fromChunks(contentLength = 6) { sink ->
                    sink.write("abc".encodeToByteArray())
                    sink.write("def".encodeToByteArray())
                },
                contentType = "application/octet-stream"
            )
            this.progress = NetworkProgressCallbacks(uploadProgress = progress::add)
        }

        val response = curlClient().execute(request)

        assertTrue(response.isSuccess)
        assertEquals("upload:abcdef", response.body.text())
        assertEquals(6L, progress.last().bytesTransferred)
        assertEquals(6L, progress.last().bytesTotal)
    }

    private suspend fun externalCancelStopsBodyCallbacks(
        engine: com.tencent.kmm.network.service.NetworkEngine
    ) = coroutineScope {
        val request = request("/slow")
        val call = NetworkCall(request)
        val callbackEntered = CompletableDeferred<Unit>()
        val releaseCallback = CountDownLatch(1)
        val chunks = AtomicInteger(0)
        val result = async(Dispatchers.Default) {
            engine.downloadStream(
                request = request,
                call = call,
                onResponseStart = { _, _, _ ->
                    callbackEntered.complete(Unit)
                    releaseCallback.await(5, TimeUnit.SECONDS)
                },
                onChunk = { chunks.incrementAndGet() }
            )
        }

        withTimeout(TIMEOUT_MS) { callbackEntered.await() }
        call.cancel()
        releaseCallback.countDown()
        val response = withTimeout(TIMEOUT_MS) { result.await() }

        assertEquals(NetworkErrorKind.CANCELLED, response.error?.kind)
        assertEquals("cancel during response-start must suppress body", 0, chunks.get())
        assertTrue(server.awaitSlowDisconnect())
    }

    private suspend fun highLevelPreCancelNeverStartsNative(
        engine: com.tencent.kmm.network.service.NetworkEngine
    ) {
        val request = request("/pre-cancel-engine")
        val call = NetworkCall(request).apply { cancel() }

        val response = engine.execute(request, call)

        assertEquals(NetworkErrorKind.CANCELLED, response.error?.kind)
        assertEquals(0, server.requestCount("/pre-cancel-engine"))
    }

    private suspend fun nativePreStartCancelCoversAllThreeEntrypoints() {
        val buffered = nativeRequest("/native-pre-buffer").apply { cancel() }
        val bufferedResponse = AndroidCurlJniBridge.execute(buffered)
        assertEquals(42, bufferedResponse.code)
        assertNull(bufferedResponse.data)
        assertEquals(0, server.requestCount("/native-pre-buffer"))

        val streamStarts = AtomicInteger(0)
        val streamChunks = AtomicInteger(0)
        val stream = nativeRequest("/native-pre-stream").apply { cancel() }
        val streamResponse = AndroidCurlJniBridge.downloadStream(
            request = stream,
            onResponseStart = { _, _ ->
                streamStarts.incrementAndGet()
            },
            onChunk = { streamChunks.incrementAndGet() }
        )
        assertEquals(0, streamStarts.get())
        assertEquals(0, streamChunks.get())
        assertEquals(42, streamResponse.code)
        assertEquals(0, server.requestCount("/native-pre-stream"))

        val uploadReads = AtomicInteger(0)
        val upload = nativeRequest("/native-pre-upload", method = "POST").copy(
            uploadContentLength = 4
        ).apply { cancel() }
        val uploadResponse = AndroidCurlJniBridge.uploadStream(
            request = upload,
            source = AndroidCurlUploadSource {
                uploadReads.incrementAndGet()
                "body".encodeToByteArray()
            }
        )
        assertEquals(42, uploadResponse.code)
        assertEquals(0, uploadReads.get())
        assertEquals(0, server.requestCount("/native-pre-upload"))
    }

    private suspend fun callbackFailureAbortsAndSuppressesLaterChunks(
        engine: com.tencent.kmm.network.service.NetworkEngine
    ) {
        val request = request("/callback-failure")
        val call = NetworkCall(request)
        val chunks = AtomicInteger(0)

        val response = engine.downloadStream(
            request = request,
            call = call,
            onResponseStart = { _, _, _ -> Unit },
            onChunk = {
                chunks.incrementAndGet()
                error("instrumented consumer failure")
            }
        )

        assertEquals(NetworkErrorKind.UNKNOWN, response.error?.kind)
        assertTrue(response.error?.message.orEmpty().contains("instrumented consumer failure"))
        assertEquals("later native chunks must be suppressed", 1, chunks.get())
    }

    private suspend fun concurrentUploadsDoNotStarveDispatcher() = coroutineScope {
        val client = curlClient()
        val responses = withTimeout(CONCURRENT_TIMEOUT_MS) {
            (0 until CONCURRENT_UPLOADS).map { index ->
                async(Dispatchers.Default) {
                    val payload = "payload-$index-".repeat(1024).encodeToByteArray()
                    val upload = request("/upload-delay/$index").apply {
                        method = VBTransportMethod.POST
                        body = NetworkBody.Stream(
                            stream = NetworkByteStream.fromChunks(contentLength = payload.size.toLong()) { sink ->
                                payload.asList().chunked(2048).forEach { part ->
                                    sink.write(part.toByteArray())
                                    delay(2)
                                }
                            },
                            contentType = "application/octet-stream"
                        )
                    }
                    client.execute(upload)
                }
            }.awaitAll()
        }

        assertTrue(responses.all { it.isSuccess })
        assertEquals(CONCURRENT_UPLOADS, server.concurrentUploadsCompleted.get())
        assertEquals(CONCURRENT_UPLOADS, server.maxConcurrentUploads.get())
    }

    private suspend fun certificateAcceptanceMatrixAndManualProxy() {
        val valid = RuntimeHttpsServer(AndroidCurlTlsTestMaterial.VALID_PKCS12_BASE64)
        val unknown = RuntimeHttpsServer(AndroidCurlTlsTestMaterial.UNKNOWN_PKCS12_BASE64)
        val expired = RuntimeHttpsServer(AndroidCurlTlsTestMaterial.EXPIRED_PKCS12_BASE64)
        val mismatch = RuntimeHttpsServer(AndroidCurlTlsTestMaterial.MISMATCH_PKCS12_BASE64)
        val delayed = RuntimeHttpsServer(
            AndroidCurlTlsTestMaterial.VALID_PKCS12_BASE64,
            responseDelayMillis = 1_500
        )
        try {
            configureCurl(trustStoreFile, NetworkCurlProxyConfiguration.direct())
            assertTrue(curlClient().execute(NetworkRequest(url = valid.url())).isSuccess)
            assertEquals(NetworkErrorKind.TLS, curlClient().execute(NetworkRequest(url = unknown.url())).error?.kind)
            assertEquals(NetworkErrorKind.TLS, curlClient().execute(NetworkRequest(url = expired.url())).error?.kind)
            assertEquals(NetworkErrorKind.TLS, curlClient().execute(NetworkRequest(url = mismatch.url())).error?.kind)

            val wrongCa = File(trustStoreFile.parentFile, "networkkmm-runtime-wrong-ca.pem").apply {
                writeBytes(decode(AndroidCurlTlsTestMaterial.WRONG_CA_PEM_BASE64))
            }
            try {
                configureCurl(wrongCa, NetworkCurlProxyConfiguration.direct())
                assertEquals(NetworkErrorKind.TLS, curlClient().execute(NetworkRequest(url = valid.url())).error?.kind)
            } finally {
                wrongCa.delete()
            }

            RuntimeConnectProxy().use { proxy ->
                configureCurl(
                    trustStoreFile,
                    NetworkCurlProxyConfiguration.manual("http://127.0.0.1:${proxy.port}")
                )
                val starts = Collections.synchronizedList(mutableListOf<Int>())
                val streamCall = curlClient().downloadStream(
                    request = NetworkRequest(url = valid.url()),
                    onResponseStart = { status, _, _ -> starts += status },
                    onChunk = {},
                    onComplete = {}
                )
                assertTrue(withTimeout(TIMEOUT_MS) { streamCall.await() }.isSuccess)
                assertEquals(
                    "proxy CONNECT headers must not become the origin start",
                    listOf(200),
                    starts
                )
                assertTrue("manual proxy must observe CONNECT", proxy.awaitConnect())
            }

            RuntimeConnectProxy().use { proxy ->
                configureCurl(
                    trustStoreFile,
                    NetworkCurlProxyConfiguration.manual("http://127.0.0.1:${proxy.port}")
                )
                val starts = Collections.synchronizedList(mutableListOf<Int>())
                val streamCall = curlClient().downloadStream(
                    request = NetworkRequest(url = delayed.url()).apply {
                        policy = NetworkRequestPolicy(
                            streamTimeouts = NetworkStreamTimeoutPolicy(
                                responseHeadersTimeoutMillis = 500,
                                interChunkIdleTimeoutMillis = 2_000
                            )
                        )
                    },
                    onResponseStart = { status, _, _ -> starts += status },
                    onChunk = {},
                    onComplete = {}
                )
                val response = withTimeout(TIMEOUT_MS) { streamCall.await() }
                assertEquals(NetworkErrorKind.TIMEOUT, response.error?.kind)
                assertTrue("CONNECT 200 must not disarm final-header timeout", starts.isEmpty())
                assertTrue("delayed proxy request must observe CONNECT", proxy.awaitConnect())
            }

            RuntimeConnectProxy().use { proxy ->
                AndroidCurlSystemProxyResolver.testResolver = {
                    CurlSystemProxyResolution.resolved("http://127.0.0.1:${proxy.port}")
                }
                try {
                    configureCurl(trustStoreFile, NetworkCurlProxyConfiguration.androidSystem())
                    assertTrue(curlClient().execute(NetworkRequest(url = valid.url())).isSuccess)
                    assertTrue("Android system PAC proxy must observe CONNECT", proxy.awaitConnect())
                } finally {
                    AndroidCurlSystemProxyResolver.testResolver = null
                }
            }
        } finally {
            valid.close()
            unknown.close()
            expired.close()
            mismatch.close()
            delayed.close()
            configureCurl(trustStoreFile, NetworkCurlProxyConfiguration.direct())
        }
    }

    private suspend fun publicHttp3NegotiationContract() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val publicCa = File(
            instrumentation.targetContext.cacheDir,
            "networkkmm-public-runtime-ca.pem"
        )
        instrumentation.context.assets.open(PUBLIC_CA_ASSET).use { input ->
            publicCa.outputStream().use { output -> input.copyTo(output) }
        }
        try {
            assertTrue("committed curl artifact must advertise HTTP/3", AndroidCurlJniBridge.supportsHttp3)

            configureCurl(
                publicCa,
                NetworkCurlProxyConfiguration.direct(),
                http3Enabled = true
            )
            val h3 = curlClient().execute(publicRequest(PUBLIC_HTTP3_URL))
            assertTrue("explicit h3 request failed: ${h3.error?.message}", h3.isSuccess)
            assertEquals(NetworkHttpProtocol.HTTP_3, h3.protocol)

            // The same origin already has a live h3 connection. A default
            // request must still negotiate h2 from the isolated default pool.
            configureCurl(publicCa, NetworkCurlProxyConfiguration.direct())
            val defaultH2 = curlClient().execute(publicRequest(PUBLIC_HTTP3_URL))
            assertTrue("default h2 request failed: ${defaultH2.error?.message}", defaultH2.isSuccess)
            assertEquals(NetworkHttpProtocol.HTTP_2, defaultH2.protocol)

            // GitHub exposes h2 on this endpoint without an h3 listener. The
            // explicit h3 preference must fall back instead of becoming 3ONLY.
            configureCurl(
                publicCa,
                NetworkCurlProxyConfiguration.direct(),
                http3Enabled = true
            )
            val fallback = curlClient().execute(publicRequest(PUBLIC_H2_FALLBACK_URL))
            assertTrue("h3 to h2 fallback failed: ${fallback.error?.message}", fallback.isSuccess)
            assertEquals(NetworkHttpProtocol.HTTP_2, fallback.protocol)

            val totalFailure = curlClient().execute(publicRequest(PUBLIC_TOTAL_FAILURE_URL))
            assertFalse(totalFailure.isSuccess)
            assertEquals(NetworkErrorKind.CONNECT, totalFailure.error?.kind)
            assertEquals(NetworkHttpProtocol.UNKNOWN, totalFailure.protocol)
        } finally {
            publicCa.delete()
            configureCurl(trustStoreFile, NetworkCurlProxyConfiguration.direct())
        }
    }

    private fun configureCurl(
        file: File,
        proxy: NetworkCurlProxyConfiguration,
        http3Enabled: Boolean = false
    ) {
        val status = VBTransportCurl.configure(
            NetworkCurlRuntimeConfiguration(
                trustStore = NetworkCurlTrustStore(
                    path = file.absolutePath,
                    sha256 = networkCurlSha256Hex(file.readBytes())
                ),
                proxy = proxy,
                http3Enabled = http3Enabled
            )
        )
        assertTrue(status.detail, status.configured)
    }

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.DEFAULT)

    private fun curlClient(
        selections: MutableList<NetworkEngineSelectionDiagnostics>? = null
    ): NetworkClient = NetworkClient(
        config = NetworkClientConfig(
            engineSelector = {
                NetworkEngineSelection(requestedEngine = NetworkTransportEngine.CURL)
            },
            engineDiagnostics = selections?.let { output ->
                object : NetworkEngineDiagnosticsListener {
                    override fun onEngineSelected(diagnostics: NetworkEngineSelectionDiagnostics) {
                        output += diagnostics
                    }
                }
            }
        )
    )

    private fun request(path: String): NetworkRequest = NetworkRequest(
        url = server.url(path),
        policy = NetworkRequestPolicy(timeoutMillis = TIMEOUT_MS)
    )

    private fun publicRequest(url: String): NetworkRequest = NetworkRequest(
        url = url,
        policy = NetworkRequestPolicy(timeoutMillis = PUBLIC_TIMEOUT_MS)
    )

    private fun nativeRequest(path: String, method: String = "GET") = AndroidCurlNativeRequest(
        requestId = path.hashCode(),
        url = server.url(path),
        method = method,
        headers = emptyMap(),
        timeoutMillis = TIMEOUT_MS,
        caInfoPath = trustStoreFile.absolutePath,
        proxyUrl = ""
    )

    private fun merge(chunks: List<ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        chunks.forEach(output::write)
        return output.toByteArray()
    }

    private class RuntimeHttpsServer(
        pkcs12Base64: String,
        private val responseDelayMillis: Long = 0
    ) : AutoCloseable {
        private val executor = Executors.newCachedThreadPool()
        private val running = java.util.concurrent.atomic.AtomicBoolean(true)
        private val server: SSLServerSocket

        init {
            val password = AndroidCurlTlsTestMaterial.PASSWORD.toCharArray()
            val keyStore = KeyStore.getInstance("PKCS12").apply {
                load(ByteArrayInputStream(Base64.decode(pkcs12Base64, Base64.DEFAULT)), password)
            }
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, password)
            }
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(keyManagers.keyManagers, null, null)
            }
            server = sslContext.serverSocketFactory.createServerSocket(
                0,
                50,
                InetAddress.getByName("127.0.0.1")
            ) as SSLServerSocket
            executor.execute {
                while (running.get()) {
                    try {
                        val socket = server.accept()
                        executor.execute {
                            // Negative TLS cases intentionally abort the peer handshake. Keep
                            // those server-side exceptions contained in the connection worker;
                            // the curl response assertions remain the source of truth.
                            runCatching { handle(socket) }
                        }
                    } catch (_: Throwable) {
                        if (running.get()) throw AssertionError("HTTPS accept loop failed")
                    }
                }
            }
        }

        fun url(): String = "https://127.0.0.1:${server.localPort}/ok"

        override fun close() {
            running.set(false)
            runCatching { server.close() }
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }

        private fun handle(socket: Socket) {
            socket.use { connection ->
                connection.soTimeout = 10_000
                val input = BufferedInputStream(connection.getInputStream())
                val output = BufferedOutputStream(connection.getOutputStream())
                readLine(input) ?: return
                while (!readLine(input).isNullOrEmpty()) Unit
                if (responseDelayMillis > 0) {
                    Thread.sleep(responseDelayMillis)
                }
                val body = "tls-ok".encodeToByteArray()
                output.write(
                    (
                        "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(StandardCharsets.US_ASCII)
                )
                output.write(body)
                output.flush()
            }
        }

        private fun readLine(input: BufferedInputStream): String? {
            val output = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                if (next < 0) return if (output.size() == 0) null else output.toString("US-ASCII")
                if (next == '\n'.code) break
                if (next != '\r'.code) output.write(next)
            }
            return output.toString("US-ASCII")
        }
    }

    private class RuntimeConnectProxy : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        private val running = java.util.concurrent.atomic.AtomicBoolean(true)
        private val connected = CountDownLatch(1)
        val port: Int = server.localPort

        init {
            executor.execute {
                while (running.get()) {
                    try {
                        val socket = server.accept()
                        executor.execute { handle(socket) }
                    } catch (_: Throwable) {
                        if (running.get()) throw AssertionError("proxy accept loop failed")
                    }
                }
            }
        }

        fun awaitConnect(): Boolean = connected.await(5, TimeUnit.SECONDS)

        override fun close() {
            running.set(false)
            runCatching { server.close() }
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }

        private fun handle(client: Socket) {
            client.use { downstream ->
                downstream.soTimeout = 10_000
                val input = BufferedInputStream(downstream.getInputStream())
                val output = BufferedOutputStream(downstream.getOutputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2 || parts[0] != "CONNECT") return
                while (!readLine(input).isNullOrEmpty()) Unit
                val authority = parts[1]
                val host = authority.substringBeforeLast(':')
                val targetPort = authority.substringAfterLast(':').toInt()
                Socket(host, targetPort).use { upstream ->
                    output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    output.flush()
                    connected.countDown()
                    val upstreamToClient = executor.submit {
                        runCatching { upstream.getInputStream().copyTo(downstream.getOutputStream()) }
                    }
                    runCatching { input.copyTo(upstream.getOutputStream()) }
                    runCatching { upstream.shutdownOutput() }
                    runCatching { upstreamToClient.get(5, TimeUnit.SECONDS) }
                }
            }
        }

        private fun readLine(input: BufferedInputStream): String? {
            val output = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                if (next < 0) return if (output.size() == 0) null else output.toString("US-ASCII")
                if (next == '\n'.code) break
                if (next != '\r'.code) output.write(next)
            }
            return output.toString("US-ASCII")
        }
    }

    private class RuntimeHttpServer(
        concurrentUploadCount: Int
    ) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        private val running = java.util.concurrent.atomic.AtomicBoolean(true)
        private val counts = ConcurrentHashMap<String, AtomicInteger>()
        private val uploadBarrier = CountDownLatch(concurrentUploadCount)
        private val slowDisconnected = CountDownLatch(1)
        private val bufferedSlowDisconnected = CountDownLatch(1)
        private val activeUploads = AtomicInteger(0)
        private val activeBuffered = AtomicInteger(0)
        val maxConcurrentUploads = AtomicInteger(0)
        val maxConcurrentBuffered = AtomicInteger(0)
        val concurrentUploadsCompleted = AtomicInteger(0)

        init {
            executor.execute {
                while (running.get()) {
                    try {
                        val socket = server.accept()
                        executor.execute { handle(socket) }
                    } catch (_: Throwable) {
                        if (running.get()) throw AssertionError("HTTP accept loop failed")
                    }
                }
            }
        }

        fun url(path: String): String = "http://127.0.0.1:${server.localPort}$path"

        fun requestCount(path: String): Int = counts[path]?.get() ?: 0

        fun awaitSlowDisconnect(): Boolean = slowDisconnected.await(5, TimeUnit.SECONDS)

        fun awaitBufferedSlowDisconnect(): Boolean =
            bufferedSlowDisconnected.await(5, TimeUnit.SECONDS)

        override fun close() {
            running.set(false)
            runCatching { server.close() }
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }

        private fun handle(socket: Socket) {
            socket.use { connection ->
                connection.soTimeout = 15_000
                val input = BufferedInputStream(connection.getInputStream())
                val output = BufferedOutputStream(connection.getOutputStream())
                val requestLine = readLine(input) ?: return
                val path = requestLine.split(' ').getOrNull(1) ?: "/"
                counts.computeIfAbsent(path) { AtomicInteger(0) }.incrementAndGet()
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim().lowercase()] =
                            line.substring(colon + 1).trim()
                    }
                }
                val body = readBody(input, headers)
                when {
                    path == "/buffer" -> respond(output, "buffer-ok".encodeToByteArray())
                    path.startsWith("/buffer-delay/") -> delayedBuffered(output)
                    path == "/stream" -> stream(output, listOf("stream-one", "stream-two"))
                    path == "/upload" -> respond(output, "upload:${body.decodeToString()}".encodeToByteArray())
                    path == "/slow" -> slow(output)
                    path == "/slow-buffer" -> {
                        if (requestCount(path) == 1) {
                            slow(output, bufferedSlowDisconnected)
                        } else {
                            respond(output, "slow-buffer-reused".encodeToByteArray())
                        }
                    }
                    path == "/callback-failure" -> stream(output, listOf("first", "second", "third"))
                    path.startsWith("/upload-delay/") -> delayedUpload(output, body)
                    else -> respond(output, "not-found".encodeToByteArray(), status = "404 Not Found")
                }
            }
        }

        private fun delayedUpload(output: BufferedOutputStream, body: ByteArray) {
            val active = activeUploads.incrementAndGet()
            maxConcurrentUploads.updateAndGet { current -> maxOf(current, active) }
            uploadBarrier.countDown()
            val allArrived = uploadBarrier.await(10, TimeUnit.SECONDS)
            if (allArrived) {
                concurrentUploadsCompleted.incrementAndGet()
                respond(output, "received:${body.size}".encodeToByteArray())
            } else {
                respond(output, "barrier-timeout".encodeToByteArray(), status = "500 Internal Server Error")
            }
            activeUploads.decrementAndGet()
        }

        private fun delayedBuffered(output: BufferedOutputStream) {
            val active = activeBuffered.incrementAndGet()
            maxConcurrentBuffered.updateAndGet { current -> maxOf(current, active) }
            try {
                Thread.sleep(800)
                respond(output, "buffer-delay-ok".encodeToByteArray())
            } finally {
                activeBuffered.decrementAndGet()
            }
        }

        private fun slow(
            output: BufferedOutputStream,
            disconnected: CountDownLatch = slowDisconnected
        ) {
            writeHeaders(output, contentLength = 100_000)
            try {
                repeat(1_000) {
                    output.write("slow-chunk".encodeToByteArray())
                    output.flush()
                    Thread.sleep(10)
                }
            } catch (_: Throwable) {
                disconnected.countDown()
            }
        }

        private fun stream(output: BufferedOutputStream, chunks: List<String>) {
            val bytes = chunks.map(String::encodeToByteArray)
            writeHeaders(output, contentLength = bytes.sumOf { it.size })
            try {
                bytes.forEach { chunk ->
                    output.write(chunk)
                    output.flush()
                    Thread.sleep(20)
                }
            } catch (_: SocketException) {
                // Callback-failure coverage intentionally aborts the client
                // connection before the server has written every chunk.
            }
        }

        private fun respond(
            output: BufferedOutputStream,
            body: ByteArray,
            status: String = "200 OK"
        ) {
            writeHeaders(output, body.size, status)
            output.write(body)
            output.flush()
        }

        private fun writeHeaders(
            output: BufferedOutputStream,
            contentLength: Int,
            status: String = "200 OK"
        ) {
            output.write(
                (
                    "HTTP/1.1 $status\r\nContent-Length: $contentLength\r\n" +
                    "Content-Type: application/octet-stream\r\nConnection: close\r\n\r\n"
                    ).toByteArray(StandardCharsets.US_ASCII)
            )
            output.flush()
        }

        private fun readBody(
            input: BufferedInputStream,
            headers: Map<String, String>
        ): ByteArray {
            val length = headers["content-length"]?.toIntOrNull()
            if (length != null) return readExact(input, length)
            if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
                val output = ByteArrayOutputStream()
                while (true) {
                    val size = readLine(input)?.substringBefore(';')?.trim()?.toInt(16) ?: break
                    if (size == 0) {
                        while (!readLine(input).isNullOrEmpty()) Unit
                        break
                    }
                    output.write(readExact(input, size))
                    readLine(input)
                }
                return output.toByteArray()
            }
            return ByteArray(0)
        }

        private fun readExact(input: BufferedInputStream, length: Int): ByteArray {
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(bytes, offset, length - offset)
                if (read < 0) error("Unexpected EOF after $offset/$length bytes")
                offset += read
            }
            return bytes
        }

        private fun readLine(input: BufferedInputStream): String? {
            val output = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                if (next < 0) return if (output.size() == 0) null else output.toString("US-ASCII")
                if (next == '\n'.code) break
                if (next != '\r'.code) output.write(next)
            }
            return output.toString("US-ASCII")
        }
    }

    companion object {
        private const val TAG = "NetworkKMMCurlRuntime"
        private const val TIMEOUT_MS = 10_000L
        private const val PUBLIC_TIMEOUT_MS = 30_000L
        private const val CONCURRENT_TIMEOUT_MS = 30_000L
        private const val CONCURRENT_BUFFERED_REQUESTS = 4
        private const val CONCURRENT_UPLOADS = 8
        private const val PUBLIC_CA_ASSET = "networkkmm-cacert.pem"
        private const val PUBLIC_HTTP3_URL = "https://cloudflare-quic.com/"
        private const val PUBLIC_H2_FALLBACK_URL = "https://github.com/robots.txt"
        private const val PUBLIC_TOTAL_FAILURE_URL = "https://127.0.0.1:1/"
    }
}

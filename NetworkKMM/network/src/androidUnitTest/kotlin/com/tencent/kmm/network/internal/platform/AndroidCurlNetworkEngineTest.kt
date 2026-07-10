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

import com.tencent.kmm.network.curl.CurlNativeResponse
import com.tencent.kmm.network.export.NetworkBody
import com.tencent.kmm.network.export.NetworkByteStream
import com.tencent.kmm.network.export.NetworkEngineCapabilities
import com.tencent.kmm.network.export.NetworkErrorKind
import com.tencent.kmm.network.export.NetworkProgressCallbacks
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkTransferProgress
import com.tencent.kmm.network.export.NetworkCurlProxyConfiguration
import com.tencent.kmm.network.export.NetworkCurlRuntimeConfiguration
import com.tencent.kmm.network.export.NetworkCurlTrustStore
import com.tencent.kmm.network.export.NetworkCurlConfigurationFailureReason
import com.tencent.kmm.network.export.VBTransportElapseStatistics
import com.tencent.kmm.network.export.VBTransportCurl
import com.tencent.kmm.network.export.VBTransportMethod
import com.tencent.kmm.network.export.VBTransportResultCode
import com.tencent.kmm.network.export.networkCurlSha256Hex
import com.tencent.kmm.network.service.NetworkCall
import com.tencent.kmm.network.service.NetworkEngineSelection
import com.tencent.kmm.network.service.NetworkTransportEngine
import com.tencent.kmm.network.service.AndroidCurlSystemProxyResolver
import com.tencent.kmm.network.service.CurlSystemProxyResolution
import com.tencent.kmm.network.service.NetworkEngineUnavailableReason
import com.tencent.kmm.network.service.resolveAndroidCurlSystemProxy
import com.tencent.kmm.network.service.resolveNetworkEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidCurlNetworkEngineTest {
    private lateinit var trustStoreFile: File

    @BeforeTest
    fun configureCurlRuntime() {
        trustStoreFile = File.createTempFile("networkkmm-test-ca", ".pem").apply {
            writeText("unit-test-ca")
        }
        val bytes = trustStoreFile.readBytes()
        VBTransportCurl.configure(
            NetworkCurlRuntimeConfiguration(
                trustStore = NetworkCurlTrustStore(
                    path = trustStoreFile.absolutePath,
                    sha256 = networkCurlSha256Hex(bytes)
                ),
                proxy = NetworkCurlProxyConfiguration.direct()
            )
        )
    }

    @AfterTest
    fun resetBridge() {
        AndroidCurlEngineProvider.testBridge = null
        AndroidCurlSystemProxyResolver.testResolver = null
        VBTransportCurl.clear()
        trustStoreFile.delete()
    }

    @Test
    fun bufferedRequestMapsBodyHeadersTimingAndCaPath() = runBlocking {
        val bridge = FakeBridge().apply {
            executeResponse = CurlNativeResponse(
                code = 0,
                httpCode = 201,
                headers = "HTTP/1.1 201 Created\r\nContent-Length: 2\r\nX-Test: yes\r\n",
                data = "ok".encodeToByteArray(),
                elapse = VBTransportElapseStatistics(totalTimeMs = 12.5)
            )
        }
        val progress = mutableListOf<NetworkTransferProgress>()
        val engine = AndroidCurlNetworkEngine(bridge)
        val request = NetworkRequest(
            method = VBTransportMethod.POST,
            url = "https://example.test",
            path = "/v1/resource",
            body = NetworkBody.Json("{\"ok\":true}"),
            progress = NetworkProgressCallbacks(downloadProgress = progress::add)
        ).apply {
            addQuery("q", "a b")
            setHeader("X-Request", "value")
        }

        val response = engine.execute(request, NetworkCall(request))

        assertEquals(201, response.statusCode)
        assertEquals("ok", response.body.text())
        assertEquals(listOf("yes"), response.headers["X-Test"])
        assertEquals(12.5, response.timing.totalTimeMs)
        assertNull(response.error)
        assertEquals(listOf(NetworkTransferProgress(2, 2)), progress)
        val nativeRequest = assertNotNull(bridge.lastRequest)
        assertEquals("https://example.test/v1/resource?q=a%20b", nativeRequest.url)
        assertEquals("POST", nativeRequest.method)
        assertEquals("application/json", nativeRequest.headers["Content-Type"])
        assertEquals("value", nativeRequest.headers["X-Request"])
        assertContentEquals("{\"ok\":true}".encodeToByteArray(), nativeRequest.body)
        assertEquals(trustStoreFile.absolutePath, nativeRequest.caInfoPath)
        assertEquals("", nativeRequest.proxyUrl)
    }

    @Test
    fun manualProxyIsLatchedIntoNativeRequest() = runBlocking {
        VBTransportCurl.configure(
            NetworkCurlRuntimeConfiguration(
                trustStore = NetworkCurlTrustStore(
                    path = trustStoreFile.absolutePath,
                    sha256 = networkCurlSha256Hex(trustStoreFile.readBytes())
                ),
                proxy = NetworkCurlProxyConfiguration.manual("http://127.0.0.1:8888")
            )
        )
        val bridge = FakeBridge()
        val request = NetworkRequest(url = "https://example.test")

        AndroidCurlNetworkEngine(bridge).execute(request, NetworkCall(request))

        assertEquals("http://127.0.0.1:8888", bridge.lastRequest?.proxyUrl)
    }

    @Test
    fun androidSystemProxyIsResolvedPerRequestAndLatchedIntoNativeRequest() = runBlocking {
        VBTransportCurl.configure(
            NetworkCurlRuntimeConfiguration(
                trustStore = NetworkCurlTrustStore(
                    path = trustStoreFile.absolutePath,
                    sha256 = networkCurlSha256Hex(trustStoreFile.readBytes())
                ),
                proxy = NetworkCurlProxyConfiguration.androidSystem()
            )
        )
        val resolvedUrls = mutableListOf<String>()
        AndroidCurlSystemProxyResolver.testResolver = { url ->
            resolvedUrls += url
            CurlSystemProxyResolution.resolved("http://localhost:3128")
        }
        val bridge = FakeBridge()
        val request = NetworkRequest(url = "https://example.test", path = "/through-pac")

        AndroidCurlNetworkEngine(bridge).execute(request, NetworkCall(request))

        assertEquals(listOf("https://example.test/through-pac"), resolvedUrls)
        assertEquals("http://localhost:3128", bridge.lastRequest?.proxyUrl)
        assertTrue(AndroidCurlNetworkEngine(bridge).capabilities.pacProxy.rolloutEligible)
    }

    @Test
    fun androidPacProxyUsesSystemLocalForwarder() {
        val resolution = resolveAndroidCurlSystemProxy(
            url = "https://example.test/path",
            proxySelector = null,
            isPacSelector = true,
            property = { key ->
                mapOf(
                    "https.proxyHost" to "localhost",
                    "https.proxyPort" to "4321"
                )[key]
            }
        )

        assertTrue(resolution.available)
        assertEquals("http://localhost:4321", resolution.proxyUrl)
    }

    @Test
    fun androidPacProxyFailsClosedUntilLocalForwarderIsReady() {
        val resolution = resolveAndroidCurlSystemProxy(
            url = "https://example.test/path",
            proxySelector = null,
            isPacSelector = true,
            property = { key ->
                mapOf(
                    "https.proxyHost" to "localhost",
                    "https.proxyPort" to "-1"
                )[key]
            }
        )

        assertFalse(resolution.available)
        assertEquals(NetworkEngineUnavailableReason.PROXY_SYSTEM_UNAVAILABLE, resolution.reason)
    }

    @Test
    fun unavailableAndroidSystemProxyFallsBackToKtorDuringSelection() {
        VBTransportCurl.configure(
            NetworkCurlRuntimeConfiguration(
                trustStore = NetworkCurlTrustStore(
                    path = trustStoreFile.absolutePath,
                    sha256 = networkCurlSha256Hex(trustStoreFile.readBytes())
                ),
                proxy = NetworkCurlProxyConfiguration.androidSystem()
            )
        )
        AndroidCurlSystemProxyResolver.testResolver = {
            CurlSystemProxyResolution.unavailable(
                NetworkEngineUnavailableReason.PROXY_SYSTEM_UNAVAILABLE,
                "PAC localhost proxy is not ready"
            )
        }
        val bridge = FakeBridge()
        val curl = AndroidCurlNetworkEngine(bridge)
        val ktor = object : com.tencent.kmm.network.service.NetworkEngine {
            override suspend fun execute(request: NetworkRequest, call: NetworkCall) = error("unused")
        }
        val request = NetworkRequest(url = "https://example.test")

        val resolved = resolveNetworkEngine(
            selection = NetworkEngineSelection(requestedEngine = NetworkTransportEngine.CURL),
            platformDefault = NetworkTransportEngine.KTOR,
            resolver = { engine -> if (engine == NetworkTransportEngine.KTOR) ktor else curl },
            request = request
        )

        assertSame(ktor, resolved.engine)
        assertEquals(NetworkEngineUnavailableReason.PROXY_SYSTEM_UNAVAILABLE, resolved.diagnostics.unavailableReason)
        assertEquals("PAC localhost proxy is not ready", resolved.diagnostics.unavailableDetail)
        assertNull(bridge.lastRequest)
    }

    @Test
    fun androidStaticSystemProxyUsesSelectorPerRequest() {
        val selector = object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> = if (uri.host == "intranet.test") {
                listOf(Proxy.NO_PROXY)
            } else {
                listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.test", 8080)))
            }

            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
        }

        val direct = resolveAndroidCurlSystemProxy(
            url = "https://intranet.test",
            proxySelector = selector,
            isPacSelector = false
        )
        val proxied = resolveAndroidCurlSystemProxy(
            url = "https://external.test",
            proxySelector = selector,
            isPacSelector = false
        )

        assertEquals("", direct.proxyUrl)
        assertEquals("http://proxy.test:8080", proxied.proxyUrl)
    }

    @Test
    fun androidSystemProxyDoesNotCollapseOrderedChoicesToFirstEntry() {
        val selector = object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> = listOf(
                Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("primary.test", 8080)),
                Proxy.NO_PROXY
            )

            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
        }

        val resolution = resolveAndroidCurlSystemProxy(
            url = "https://external.test",
            proxySelector = selector,
            isPacSelector = false
        )

        assertFalse(resolution.available)
        assertEquals(NetworkEngineUnavailableReason.PROXY_SYSTEM_UNAVAILABLE, resolution.reason)
    }

    @Test
    fun trustStoreHashMismatchFailsClosedBeforeNativeStart() = runBlocking {
        val status = VBTransportCurl.configure(
            NetworkCurlRuntimeConfiguration(
                trustStore = NetworkCurlTrustStore(
                    path = trustStoreFile.absolutePath,
                    sha256 = "0".repeat(64)
                ),
                proxy = NetworkCurlProxyConfiguration.direct()
            )
        )
        val bridge = FakeBridge()
        val request = NetworkRequest(url = "https://example.test")

        val response = AndroidCurlNetworkEngine(bridge).execute(request, NetworkCall(request))

        assertFalse(status.configured)
        assertEquals(NetworkCurlConfigurationFailureReason.TRUST_STORE_HASH_MISMATCH, status.failureReason)
        assertEquals(NetworkErrorKind.TLS, response.error?.kind)
        assertNull(bridge.lastRequest)
    }

    @Test
    fun invalidManualProxyFailsClosedAsConnectBeforeNativeStart() = runBlocking {
        val status = VBTransportCurl.configure(
            NetworkCurlRuntimeConfiguration(
                trustStore = NetworkCurlTrustStore(
                    path = trustStoreFile.absolutePath,
                    sha256 = networkCurlSha256Hex(trustStoreFile.readBytes())
                ),
                proxy = NetworkCurlProxyConfiguration.manual("ftp://127.0.0.1:8888")
            )
        )
        val bridge = FakeBridge()
        val request = NetworkRequest(url = "https://example.test")

        val response = AndroidCurlNetworkEngine(bridge).execute(request, NetworkCall(request))

        assertFalse(status.configured)
        assertEquals(NetworkCurlConfigurationFailureReason.PROXY_URL_INVALID, status.failureReason)
        assertEquals(NetworkErrorKind.CONNECT, response.error?.kind)
        assertNull(bridge.lastRequest)
    }

    @Test
    fun responseTaxonomyPreservesHttpAndCurlFailures() = runBlocking {
        val bridge = FakeBridge()
        val engine = AndroidCurlNetworkEngine(bridge)
        val request = NetworkRequest(url = "https://example.test")

        bridge.executeResponse = CurlNativeResponse(code = 0, httpCode = 401)
        assertEquals(NetworkErrorKind.AUTH, engine.execute(request, NetworkCall(request)).error?.kind)

        bridge.executeResponse = CurlNativeResponse(code = 0, httpCode = 503)
        assertEquals(NetworkErrorKind.HTTP_STATUS, engine.execute(request, NetworkCall(request)).error?.kind)

        val curlFailures = listOf(
            6 to NetworkErrorKind.DNS,
            7 to NetworkErrorKind.CONNECT,
            28 to NetworkErrorKind.TIMEOUT,
            35 to NetworkErrorKind.TLS,
            42 to NetworkErrorKind.CANCELLED
        )
        curlFailures.forEach { (code, expected) ->
            bridge.executeResponse = CurlNativeResponse(code = code, errorMsg = "native failure")
            assertEquals(expected, engine.execute(request, NetworkCall(request)).error?.kind)
        }
    }

    @Test
    fun downloadStreamDeliversHeadersChunksAndProgress() = runBlocking {
        val bridge = FakeBridge().apply {
            streamStatus = 206
            streamHeaders = "HTTP/1.1 206 Partial Content\r\nContent-Length: 6\r\n"
            streamChunks = listOf("abc".encodeToByteArray(), "def".encodeToByteArray())
            streamResponse = CurlNativeResponse(
                code = 0,
                httpCode = 206,
                headers = streamHeaders,
                elapse = VBTransportElapseStatistics(totalTimeMs = 7.0)
            )
        }
        val progress = mutableListOf<NetworkTransferProgress>()
        val request = NetworkRequest(
            url = "https://example.test/file",
            progress = NetworkProgressCallbacks(downloadProgress = progress::add)
        )
        val starts = mutableListOf<Triple<Int, Long?, Map<String, List<String>>>>()
        val chunks = mutableListOf<ByteArray>()

        val response = AndroidCurlNetworkEngine(bridge).downloadStream(
            request = request,
            call = NetworkCall(request),
            onResponseStart = { status, length, headers -> starts += Triple(status, length, headers) },
            onChunk = chunks::add
        )

        assertEquals(206, starts.single().first)
        assertEquals(6L, starts.single().second)
        assertEquals(2, chunks.size)
        assertContentEquals("abcdef".encodeToByteArray(), chunks.reduce(ByteArray::plus))
        assertEquals(
            listOf(NetworkTransferProgress(3, 6), NetworkTransferProgress(6, 6)),
            progress
        )
        assertNull(response.body.bytes)
        assertEquals(7.0, response.timing.totalTimeMs)
    }

    @Test
    fun uploadStreamPullsBoundedChunksAndReportsProgress() = runBlocking {
        val bridge = FakeBridge().apply {
            uploadReadSize = 2
            uploadResponse = CurlNativeResponse(code = 0, httpCode = 200, data = "done".encodeToByteArray())
        }
        val progress = mutableListOf<NetworkTransferProgress>()
        val request = NetworkRequest(
            method = VBTransportMethod.PUT,
            url = "https://example.test/upload",
            body = NetworkBody.Stream(
                stream = NetworkByteStream.fromChunks(contentLength = 6) { sink ->
                    sink.write("abc".encodeToByteArray())
                    sink.write("def".encodeToByteArray())
                },
                contentType = "application/octet-stream"
            ),
            progress = NetworkProgressCallbacks(uploadProgress = progress::add)
        )

        val response = AndroidCurlNetworkEngine(bridge).execute(request, NetworkCall(request))

        assertEquals("done", response.body.text())
        assertContentEquals("abcdef".encodeToByteArray(), bridge.uploadedBytes)
        assertTrue(bridge.uploadChunkSizes.all { it in 1..2 })
        assertEquals(6L, bridge.lastRequest?.uploadContentLength)
        assertEquals("application/octet-stream", bridge.lastRequest?.headers?.get("Content-Type"))
        assertEquals(
            listOf(
                NetworkTransferProgress(2, 6),
                NetworkTransferProgress(3, 6),
                NetworkTransferProgress(5, 6),
                NetworkTransferProgress(6, 6)
            ),
            progress
        )
    }

    @Test
    fun streamingGetFailsInsteadOfChangingTheWireMethod() = runBlocking {
        val bridge = FakeBridge()
        val request = NetworkRequest(
            method = VBTransportMethod.GET,
            url = "https://example.test/upload",
            body = NetworkBody.Stream(
                NetworkByteStream.fromChunks { sink -> sink.write("body".encodeToByteArray()) }
            )
        )

        val response = AndroidCurlNetworkEngine(bridge).execute(request, NetworkCall(request))

        assertEquals(NetworkErrorKind.UNKNOWN, response.error?.kind)
        assertEquals(
            "streaming request body is not supported for GET " +
                "(CURLOPT_UPLOAD would rewrite the verb); use POST/PUT/PATCH/DELETE/OPTIONS",
            response.error?.message
        )
        assertEquals(VBTransportResultCode.CODE_NETWORK_ERROR, response.error?.rawCode)
        assertNull(bridge.lastRequest)
    }

    @Test
    fun cancellingCallCancelsNativeRequestAndMapsTerminalResponse() = runBlocking {
        val bridge = BlockingBridge()
        val request = NetworkRequest(url = "https://example.test/slow")
        val call = NetworkCall(request)
        val engine = AndroidCurlNetworkEngine(bridge)
        val result = CompletableDeferred<NetworkErrorKind?>()

        val job = launch {
            result.complete(engine.execute(request, call).error?.kind)
        }
        val requestId = bridge.started.await()
        call.cancel()

        assertEquals(requestId, bridge.cancelledRequestId.await())
        assertEquals(NetworkErrorKind.CANCELLED, result.await())
        assertTrue(assertNotNull(bridge.lastRequest).cancellationSignal.isCancelled())
        job.join()
    }

    @Test
    fun alreadyCancelledCallNeverStartsNativeRequest() = runBlocking {
        val bridge = FakeBridge()
        val request = NetworkRequest(url = "https://example.test/cancelled")
        val call = NetworkCall(request).apply { cancel() }

        val response = AndroidCurlNetworkEngine(bridge).execute(request, call)

        assertEquals(NetworkErrorKind.CANCELLED, response.error?.kind)
        assertNull(bridge.lastRequest)
        assertEquals(1, bridge.cancelledIds.size)
    }

    @Test
    fun resolverOnlyRegistersAvailableCurlAndUsesDelegateCapabilities() {
        val unavailable = FakeBridge(isAvailable = false)
        AndroidCurlEngineProvider.testBridge = unavailable
        assertNull(resolvePlatformNetworkEngine(NetworkTransportEngine.CURL))

        val available = FakeBridge(isAvailable = true)
        AndroidCurlEngineProvider.testBridge = available
        val curl = assertNotNull(resolvePlatformNetworkEngine(NetworkTransportEngine.CURL))
        assertTrue(curl.capabilities.requestBodyStreaming)
        assertTrue(curl.capabilities.responseBodyStreaming)

        val ktorCapabilities = NetworkEngineCapabilities(responseBodyStreaming = false)
        val ktor = object : com.tencent.kmm.network.service.NetworkEngine {
            override val capabilities = ktorCapabilities

            override suspend fun execute(request: NetworkRequest, call: NetworkCall) = error("unused")
        }
        val resolvedCurl = resolveNetworkEngine(
            selection = NetworkEngineSelection(requestedEngine = NetworkTransportEngine.CURL),
            platformDefault = NetworkTransportEngine.KTOR,
            resolver = { engine -> if (engine == NetworkTransportEngine.KTOR) ktor else curl }
        )
        val resolvedKtor = resolveNetworkEngine(
            selection = NetworkEngineSelection(forcePlatformDefault = true),
            platformDefault = NetworkTransportEngine.KTOR,
            resolver = { engine -> if (engine == NetworkTransportEngine.KTOR) ktor else curl }
        )

        assertSame(curl, resolvedCurl.engine)
        assertTrue(resolvedCurl.diagnostics.capabilities.responseBodyStreaming)
        assertFalse(resolvedKtor.diagnostics.capabilities.responseBodyStreaming)
    }

    @Test
    fun jniCallbackLatchesUserFailureAndSignalsNativeCancellation() {
        val signal = AndroidCurlCancellationSignal()
        val callback = AndroidCurlJniCallback(
            onResponseStartBlock = null,
            onChunkBlock = { error("consumer rejected chunk") },
            uploadSource = null,
            cancellationSignal = signal,
            onCompleteBlock = {}
        )

        callback.onChunk("chunk".encodeToByteArray())

        assertTrue(callback.isCancelled())
        assertEquals(
            "Android curl callback failed: consumer rejected chunk",
            callback.failureMessage()
        )
    }

    private open class FakeBridge(
        override val isAvailable: Boolean = true
    ) : AndroidCurlNativeBridge {
        var executeResponse = CurlNativeResponse(code = 0, httpCode = 200)
        var streamResponse = CurlNativeResponse(code = 0, httpCode = 200)
        var uploadResponse = CurlNativeResponse(code = 0, httpCode = 200)
        var streamStatus = 200L
        var streamHeaders = "HTTP/1.1 200 OK\r\n"
        var streamChunks: List<ByteArray> = emptyList()
        var uploadReadSize = 4
        var lastRequest: AndroidCurlNativeRequest? = null
        var uploadedBytes = ByteArray(0)
        val uploadChunkSizes = mutableListOf<Int>()
        val cancelledIds = mutableListOf<Int>()

        override suspend fun execute(request: AndroidCurlNativeRequest): CurlNativeResponse {
            lastRequest = request
            return executeResponse
        }

        override suspend fun downloadStream(
            request: AndroidCurlNativeRequest,
            onResponseStart: (Long, String) -> Unit,
            onChunk: (ByteArray) -> Unit
        ): CurlNativeResponse {
            lastRequest = request
            onResponseStart(streamStatus, streamHeaders)
            streamChunks.forEach(onChunk)
            return streamResponse
        }

        override suspend fun uploadStream(
            request: AndroidCurlNativeRequest,
            source: AndroidCurlUploadSource
        ): CurlNativeResponse {
            lastRequest = request
            val chunks = mutableListOf<ByteArray>()
            while (true) {
                val chunk = source.read(uploadReadSize) ?: error("Upload source aborted")
                if (chunk.isEmpty()) break
                uploadChunkSizes += chunk.size
                chunks += chunk
            }
            uploadedBytes = chunks.fold(ByteArray(0), ByteArray::plus)
            return uploadResponse
        }

        override fun cancel(requestId: Int) {
            cancelledIds += requestId
        }
    }

    private class BlockingBridge : FakeBridge() {
        val started = CompletableDeferred<Int>()
        val cancelledRequestId = CompletableDeferred<Int>()
        private val response = CompletableDeferred<CurlNativeResponse>()

        override suspend fun execute(request: AndroidCurlNativeRequest): CurlNativeResponse {
            lastRequest = request
            started.complete(request.requestId)
            return response.await()
        }

        override fun cancel(requestId: Int) {
            cancelledRequestId.complete(requestId)
            response.complete(CurlNativeResponse(code = 42, errorMsg = "cancelled by caller"))
        }
    }
}

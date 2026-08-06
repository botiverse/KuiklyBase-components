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
import com.tencent.kmm.network.export.NetworkHttpProtocol
import com.tencent.kmm.network.export.NetworkProgressCallbacks
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkRequestPolicy
import com.tencent.kmm.network.export.NetworkRetryPolicy
import com.tencent.kmm.network.export.NetworkStreamTimeoutPolicy
import com.tencent.kmm.network.export.NetworkTransferProgress
import com.tencent.kmm.network.export.NetworkCurlProxyConfiguration
import com.tencent.kmm.network.export.NetworkCurlRuntimeConfiguration
import com.tencent.kmm.network.export.NetworkCurlTrustStore
import com.tencent.kmm.network.export.NetworkCurlConfigurationFailureReason
import com.tencent.kmm.network.export.NetworkCurlBufferedResponsePolicy
import com.tencent.kmm.network.export.VBTransportElapseStatistics
import com.tencent.kmm.network.export.VBTransportCurl
import com.tencent.kmm.network.export.VBTransportMethod
import com.tencent.kmm.network.export.VBTransportResultCode
import com.tencent.kmm.network.export.networkCurlSha256Hex
import com.tencent.kmm.network.service.NetworkCall
import com.tencent.kmm.network.service.NetworkClient
import com.tencent.kmm.network.service.NetworkClientConfig
import com.tencent.kmm.network.service.NetworkRequestMiddleware
import com.tencent.kmm.network.service.NetworkEngineSelection
import com.tencent.kmm.network.service.NetworkTransportEngine
import com.tencent.kmm.network.service.AndroidCurlSystemProxyResolver
import com.tencent.kmm.network.service.CurlSystemProxyResolution
import com.tencent.kmm.network.service.NetworkEngineUnavailableReason
import com.tencent.kmm.network.service.preparedCurlHttp3Requested
import com.tencent.kmm.network.service.resolveAndroidCurlSystemProxy
import com.tencent.kmm.network.service.resolveNetworkEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        assertEquals(7_000L, nativeRequest.bufferedBodyIdleTimeoutMillis)
        assertEquals(16L * 1024L * 1024L, nativeRequest.maxBufferedResponseBytes)
    }

    @Test
    fun bufferedBodyStallRetriesGetOnceOnFreshRequestWithinBudget() = runBlocking {
        val bridge = FakeBridge().apply {
            executeResponses += CurlNativeResponse(
                code = 28,
                errorMsg = "buffered body idle timeout after 7000ms",
                elapse = VBTransportElapseStatistics(
                    curlFinalHeadersObserved = true,
                    curlFirstBodyObserved = true,
                    curlBodyProgressObserved = true,
                    curlFinalHeadersElapsedMs = 5.0,
                    curlFirstBodyElapsedMs = 8.0,
                    curlLastBodyProgressElapsedMs = 9.0,
                    curlBodyBytes = 3,
                )
            )
            executeResponses += CurlNativeResponse(code = 0, httpCode = 200, data = "ok".encodeToByteArray())
        }
        val request = NetworkRequest(
            method = VBTransportMethod.GET,
            url = "https://example.test",
            policy = NetworkRequestPolicy(timeoutMillis = 20_000)
        )

        val response = AndroidCurlNetworkEngine(bridge).execute(request, NetworkCall(request))

        assertEquals("ok", response.body.text())
        assertEquals(2, bridge.executeRequests.size)
        assertTrue(bridge.executeRequests[0].requestId != bridge.executeRequests[1].requestId)
        assertTrue(bridge.executeRequests[1].timeoutMillis in 1..20_000)
        assertTrue(response.timing.curlBodyStallDetected)
        assertTrue(response.timing.freshRetry)
        assertEquals("success", response.timing.freshRetryResult)
        assertEquals(true, response.timing.curlFirstAttemptFinalHeadersObserved)
        assertEquals(true, response.timing.curlFirstAttemptFirstBodyObserved)
        assertEquals(9.0, response.timing.curlFirstAttemptLastBodyProgressElapsedMs)
        assertEquals(3L, response.timing.curlFirstAttemptBodyBytes)
    }

    @Test
    fun bufferedBodyStallNeverReplaysWriteAndPolicyCanDisableGetRetry() = runBlocking {
        val stall = CurlNativeResponse(
            code = 28,
            errorMsg = "buffered body idle timeout after 7000ms"
        )
        val writeBridge = FakeBridge().apply { executeResponses += stall }
        val post = NetworkRequest(
            method = VBTransportMethod.POST,
            url = "https://example.test",
            body = NetworkBody.Text("write")
        )

        val writeResponse = AndroidCurlNetworkEngine(writeBridge).execute(post, NetworkCall(post))

        assertEquals(1, writeBridge.executeRequests.size)
        assertTrue(writeResponse.timing.curlBodyStallDetected)
        assertFalse(writeResponse.timing.freshRetry)

        val disabledBridge = FakeBridge().apply { executeResponses += stall.copy() }
        val disabledGet = NetworkRequest(
            method = VBTransportMethod.GET,
            url = "https://example.test",
            policy = NetworkRequestPolicy(
                curlBufferedResponse = NetworkCurlBufferedResponsePolicy(freshRetryEnabled = false)
            )
        )
        AndroidCurlNetworkEngine(disabledBridge).execute(disabledGet, NetworkCall(disabledGet))
        assertEquals(1, disabledBridge.executeRequests.size)

        val uploadBridge = FakeBridge().apply { uploadResponse = stall.copy() }
        val upload = NetworkRequest(
            method = VBTransportMethod.POST,
            url = "https://example.test",
            body = NetworkBody.Stream(
                stream = NetworkByteStream.fromChunks(contentLength = 3) { sink ->
                    sink.write("abc".encodeToByteArray())
                }
            )
        )
        val uploadResponse = AndroidCurlNetworkEngine(uploadBridge).execute(upload, NetworkCall(upload))
        assertEquals(1, uploadBridge.uploadRequests)
        assertFalse(uploadResponse.timing.freshRetry)
    }

    @Test
    fun freshRetryIsBoundedAndSuppressedByCancelOrExhaustedTotalBudget() = runBlocking {
        fun stall() = CurlNativeResponse(
            code = 28,
            errorMsg = "buffered body idle timeout after 7000ms"
        )
        val twiceStalled = FakeBridge().apply {
            executeResponses += stall()
            executeResponses += stall()
            executeResponses += CurlNativeResponse(code = 0, httpCode = 200)
        }
        val retryingGet = NetworkRequest(
            method = VBTransportMethod.GET,
            url = "https://example.test",
            policy = NetworkRequestPolicy(
                retry = NetworkRetryPolicy(maxRetries = 1),
                timeoutMillis = 20_000,
            )
        )
        val client = NetworkClient(engine = AndroidCurlNetworkEngine(twiceStalled))

        val failed = client.execute(retryingGet)

        assertEquals(2, twiceStalled.executeRequests.size)
        assertTrue(failed.timing.freshRetry)
        assertEquals("failure", failed.timing.freshRetryResult)

        lateinit var cancelledCall: NetworkCall
        val cancelledBridge = FakeBridge().apply {
            executeResponses += stall()
            onExecute = { _, attempt -> if (attempt == 1) cancelledCall.cancel() }
        }
        cancelledCall = NetworkCall(retryingGet)
        AndroidCurlNetworkEngine(cancelledBridge).execute(retryingGet, cancelledCall)
        assertEquals(1, cancelledBridge.executeRequests.size)

        val exhaustedBridge = FakeBridge().apply {
            executeResponses += stall()
            executeDelayMillis = 5
        }
        val exhausted = retryingGet.copyMutable().apply {
            policy = NetworkRequestPolicy(timeoutMillis = 1)
        }
        AndroidCurlNetworkEngine(exhaustedBridge).execute(exhausted, NetworkCall(exhausted))
        assertEquals(1, exhaustedBridge.executeRequests.size)
    }

    @Test
    fun freshRetryResultDescribesTransportTerminalNotHttpSuccess() = runBlocking {
        val bridge = FakeBridge().apply {
            executeResponses += CurlNativeResponse(
                code = 28,
                errorMsg = "buffered body idle timeout after 7000ms"
            )
            executeResponses += CurlNativeResponse(code = 0, httpCode = 503)
        }
        val request = NetworkRequest(method = VBTransportMethod.GET, url = "https://example.test")

        val response = AndroidCurlNetworkEngine(bridge).execute(request, NetworkCall(request))

        assertEquals(503, response.statusCode)
        assertEquals(NetworkErrorKind.HTTP_STATUS, response.error?.kind)
        assertEquals("success", response.timing.freshRetryResult)
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
    fun explicitProxyHttp3FailureRetriesGetOnceOnFreshH2AndLatchesEnvironment() = runBlocking {
        configureManualProxyHttp3()
        VBTransportCurl.updateNetworkEnvironment("android-network-1|vpn=true|proxy=127.0.0.1:8888")
        val bridge = FakeBridge(supportsHttp3 = true).apply {
            executeResponse = CurlNativeResponse(
                code = 35,
                errorMsg = "HTTP/3 is not supported over an HTTP proxy"
            )
            freshExecuteResponse = CurlNativeResponse(
                code = 0,
                httpCode = 200,
                data = "ok".encodeToByteArray()
            )
        }
        val request = NetworkRequest(
            method = VBTransportMethod.GET,
            url = "https://example.test/runtime-models",
            policy = NetworkRequestPolicy(timeoutMillis = 20_000)
        )

        val response = AndroidCurlNetworkEngine(bridge).execute(request, NetworkCall(request))

        assertEquals("ok", response.body.text())
        assertEquals(1, bridge.executeRequests.size)
        assertEquals(1, bridge.freshExecuteRequests.size)
        assertTrue(bridge.executeRequests.single().http3Enabled)
        assertFalse(bridge.freshExecuteRequests.single().http3Enabled)
        assertTrue(bridge.freshExecuteRequests.single().timeoutMillis in 1..20_000)
        assertTrue(response.timing.freshRetry)
        assertEquals("proxy_h3_to_h2_success", response.timing.freshRetryResult)
        assertEquals(true, preparedCurlHttp3Requested(request))

        bridge.executeResponse = CurlNativeResponse(code = 0, httpCode = 200)
        val next = NetworkRequest(url = "https://example.test/agents")
        AndroidCurlNetworkEngine(bridge).execute(next, NetworkCall(next))
        assertFalse(bridge.executeRequests.last().http3Enabled)
        assertEquals(true, preparedCurlHttp3Requested(next))
    }

    @Test
    fun explicitProxyHttp3FailureAlsoRetriesHeadButNeverReplaysPost() = runBlocking {
        configureManualProxyHttp3()
        val message = "HTTP/3 is not supported over an HTTP proxy"
        val headBridge = FakeBridge(supportsHttp3 = true).apply {
            executeResponse = CurlNativeResponse(code = 56, errorMsg = message)
            freshExecuteResponse = CurlNativeResponse(code = 0, httpCode = 204)
        }
        val head = NetworkRequest(method = VBTransportMethod.HEAD, url = "https://example.test/health")
        assertEquals(204, AndroidCurlNetworkEngine(headBridge).execute(head, NetworkCall(head)).statusCode)
        assertEquals(1, headBridge.freshExecuteRequests.size)

        VBTransportCurl.updateNetworkEnvironment("post-environment")
        val postBridge = FakeBridge(supportsHttp3 = true).apply {
            executeResponse = CurlNativeResponse(code = 35, errorMsg = message)
        }
        val post = NetworkRequest(
            method = VBTransportMethod.POST,
            url = "https://example.test/agents/start",
            body = NetworkBody.Json("{}")
        )
        val failed = AndroidCurlNetworkEngine(postBridge).execute(post, NetworkCall(post))

        assertNull(failed.statusCode)
        assertEquals(1, postBridge.executeRequests.size)
        assertEquals(0, postBridge.freshExecuteRequests.size)
        assertFalse(failed.timing.freshRetry)

        postBridge.executeResponse = CurlNativeResponse(code = 0, httpCode = 200)
        val afterPost = NetworkRequest(url = "https://example.test/runtime-models")
        AndroidCurlNetworkEngine(postBridge).execute(afterPost, NetworkCall(afterPost))
        assertFalse(postBridge.executeRequests.last().http3Enabled)
    }

    @Test
    fun broadCurlErrorsAndHttpStatusesDoNotDowngradeOrRetry() = runBlocking {
        configureManualProxyHttp3()
        val bridge = FakeBridge(supportsHttp3 = true).apply {
            executeResponse = CurlNativeResponse(code = 35, errorMsg = "TLS certificate verify failed")
        }
        val tls = NetworkRequest(url = "https://example.test/tls")
        AndroidCurlNetworkEngine(bridge).execute(tls, NetworkCall(tls))
        assertEquals(0, bridge.freshExecuteRequests.size)

        bridge.executeResponse = CurlNativeResponse(code = 0, httpCode = 407)
        val status = NetworkRequest(url = "https://example.test/proxy-auth")
        val statusResponse = AndroidCurlNetworkEngine(bridge).execute(status, NetworkCall(status))
        assertEquals(407, statusResponse.statusCode)
        assertEquals(0, bridge.freshExecuteRequests.size)
        assertTrue(bridge.executeRequests.all { it.http3Enabled })
    }

    @Test
    fun proxyHttp3RetryIsBoundedAndEnvironmentChangeClearsLatch() = runBlocking {
        configureManualProxyHttp3()
        VBTransportCurl.updateNetworkEnvironment("network-a")
        val message = "HTTP/3 is not supported over an HTTP proxy"
        val bridge = FakeBridge(supportsHttp3 = true).apply {
            executeResponse = CurlNativeResponse(code = 35, errorMsg = message)
            freshExecuteResponse = CurlNativeResponse(code = 56, errorMsg = message)
        }
        val first = NetworkRequest(url = "https://example.test/first")

        val failed = AndroidCurlNetworkEngine(bridge).execute(first, NetworkCall(first))

        assertEquals(1, bridge.executeRequests.size)
        assertEquals(1, bridge.freshExecuteRequests.size)
        assertEquals("proxy_h3_to_h2_failure", failed.timing.freshRetryResult)

        VBTransportCurl.updateNetworkEnvironment("network-b")
        bridge.executeResponse = CurlNativeResponse(code = 0, httpCode = 200)
        val next = NetworkRequest(url = "https://example.test/next")
        AndroidCurlNetworkEngine(bridge).execute(next, NetworkCall(next))
        assertTrue(bridge.executeRequests.last().http3Enabled)
    }

    @Test
    fun proxyHttp3RetryRespectsCancellationAndRemainingDeadline() = runBlocking {
        configureManualProxyHttp3()
        val message = "HTTP/3 is not supported over an HTTP proxy"
        lateinit var cancelledCall: NetworkCall
        val cancelledBridge = FakeBridge(supportsHttp3 = true).apply {
            executeResponse = CurlNativeResponse(code = 35, errorMsg = message)
            onExecute = { _, _ -> cancelledCall.cancel() }
        }
        val cancelled = NetworkRequest(url = "https://example.test/cancelled")
        cancelledCall = NetworkCall(cancelled)
        AndroidCurlNetworkEngine(cancelledBridge).execute(cancelled, cancelledCall)
        assertEquals(0, cancelledBridge.freshExecuteRequests.size)

        VBTransportCurl.updateNetworkEnvironment("deadline-environment")
        val exhaustedBridge = FakeBridge(supportsHttp3 = true).apply {
            executeResponse = CurlNativeResponse(code = 35, errorMsg = message)
            executeDelayMillis = 5
        }
        val exhausted = NetworkRequest(
            url = "https://example.test/exhausted",
            policy = NetworkRequestPolicy(timeoutMillis = 1)
        )
        AndroidCurlNetworkEngine(exhaustedBridge).execute(exhausted, NetworkCall(exhausted))
        assertEquals(0, exhaustedBridge.freshExecuteRequests.size)
    }

    @Test
    fun staleProxyFailureCannotLatchAReplacementEnvironment() = runBlocking {
        configureManualProxyHttp3()
        VBTransportCurl.updateNetworkEnvironment("network-old")
        val bridge = FakeBridge(supportsHttp3 = true).apply {
            executeResponse = CurlNativeResponse(
                code = 35,
                errorMsg = "HTTP/3 is not supported over an HTTP proxy"
            )
            onExecute = { _, _ -> VBTransportCurl.updateNetworkEnvironment("network-new") }
        }
        val stale = NetworkRequest(url = "https://example.test/stale")

        AndroidCurlNetworkEngine(bridge).execute(stale, NetworkCall(stale))

        assertEquals(0, bridge.freshExecuteRequests.size)
        bridge.onExecute = null
        bridge.executeResponse = CurlNativeResponse(code = 0, httpCode = 200)
        val next = NetworkRequest(url = "https://example.test/new")
        AndroidCurlNetworkEngine(bridge).execute(next, NetworkCall(next))
        assertTrue(bridge.executeRequests.last().http3Enabled)
    }

    @Test
    fun http3OptInRequiresNativeFeatureAndReachesTheRequest() = runBlocking {
        VBTransportCurl.configure(
            NetworkCurlRuntimeConfiguration(
                trustStore = NetworkCurlTrustStore(
                    path = trustStoreFile.absolutePath,
                    sha256 = networkCurlSha256Hex(trustStoreFile.readBytes())
                ),
                proxy = NetworkCurlProxyConfiguration.direct(),
                http3Enabled = true
            )
        )
        val bridge = FakeBridge(supportsHttp3 = true).apply {
            executeResponse = CurlNativeResponse(
                code = 0,
                httpCode = 200,
                elapse = VBTransportElapseStatistics(protocol = "h3")
            )
        }
        val request = NetworkRequest(url = "https://example.test")
        val engine = AndroidCurlNetworkEngine(bridge)

        val response = engine.execute(request, NetworkCall(request))

        assertTrue(assertNotNull(bridge.lastRequest).http3Enabled)
        assertTrue(engine.capabilities.http3.rolloutEligible)
        assertEquals(NetworkHttpProtocol.HTTP_3, response.protocol)
    }

    @Test
    fun perRequestHttp3DisableOverridesGlobalEnable() = runBlocking {
        VBTransportCurl.configure(
            NetworkCurlRuntimeConfiguration(
                trustStore = NetworkCurlTrustStore(
                    path = trustStoreFile.absolutePath,
                    sha256 = networkCurlSha256Hex(trustStoreFile.readBytes())
                ),
                proxy = NetworkCurlProxyConfiguration.direct(),
                http3Enabled = true
            )
        )
        val bridge = FakeBridge(supportsHttp3 = true)
        val request = NetworkRequest(url = "https://example.test")
            .setCurlHttp3Enabled(false)

        AndroidCurlNetworkEngine(bridge).execute(request, NetworkCall(request))

        assertFalse(assertNotNull(bridge.lastRequest).http3Enabled)
        assertEquals(false, preparedCurlHttp3Requested(request))
    }

    @Test
    fun http3OptInFailsClosedWhenArtifactLacksFeature() = runBlocking {
        VBTransportCurl.configure(
            NetworkCurlRuntimeConfiguration(
                trustStore = NetworkCurlTrustStore(
                    path = trustStoreFile.absolutePath,
                    sha256 = networkCurlSha256Hex(trustStoreFile.readBytes())
                ),
                proxy = NetworkCurlProxyConfiguration.direct(),
                http3Enabled = true
            )
        )
        val bridge = FakeBridge(supportsHttp3 = false)
        val request = NetworkRequest(url = "https://example.test")
        val engine = AndroidCurlNetworkEngine(bridge)

        val response = engine.execute(request, NetworkCall(request))

        assertNull(bridge.lastRequest)
        assertFalse(engine.capabilities.http3.rolloutEligible)
        assertTrue(response.error?.message?.contains("CURL_VERSION_HTTP3") == true)
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
            progress = NetworkProgressCallbacks(downloadProgress = progress::add),
            policy = NetworkRequestPolicy(
                streamTimeouts = NetworkStreamTimeoutPolicy(
                    connectTimeoutMillis = 101,
                    responseHeadersTimeoutMillis = 202,
                    interChunkIdleTimeoutMillis = 303,
                    wholeTransferTimeoutMillis = 404
                )
            )
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
        assertEquals(101L, bridge.lastRequest?.streamConnectTimeoutMillis)
        assertEquals(202L, bridge.lastRequest?.streamResponseHeadersTimeoutMillis)
        assertEquals(303L, bridge.lastRequest?.streamIdleTimeoutMillis)
        assertEquals(404L, bridge.lastRequest?.streamWholeTimeoutMillis)
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
    fun throwingUploadBodyCancelStillClosesPullAndCancelsNativeExactlyOnce() = runBlocking {
        val bridge = BlockingUploadBridge()
        var originalBodyCancels = 0
        var replacementBodyCancels = 0
        val callbacks = mutableListOf<com.tencent.kmm.network.export.NetworkResponse>()
        val parent = SupervisorJob()
        val request = NetworkRequest(
            method = VBTransportMethod.PUT,
            url = "https://example.test/upload-cancel",
            body = NetworkBody.Stream(
                NetworkByteStream.fromChunks(cancelBlock = { originalBodyCancels++ }) {}
            )
        )
        val client = NetworkClient(
            config = NetworkClientConfig(
                requestMiddlewares = listOf(
                    object : NetworkRequestMiddleware {
                        override suspend fun prepare(request: NetworkRequest): NetworkRequest = request.apply {
                            body = NetworkBody.Stream(
                                NetworkByteStream.fromChunks(cancelBlock = {
                                    replacementBodyCancels++
                                    error("replacement cancel failed")
                                }) {
                                    bridge.writerStarted.complete(Unit)
                                    awaitCancellation()
                                }
                            )
                        }
                    }
                )
            ),
            engine = AndroidCurlNetworkEngine(bridge),
            scope = CoroutineScope(Dispatchers.Default + parent)
        )

        val call = client.execute(request, callbacks::add)
        bridge.started.await()
        bridge.writerStarted.await()
        parent.cancel()

        val terminal = withTimeout(2_000) { call.await() }
        withTimeout(2_000) { bridge.pullClosed.await() }
        assertEquals(NetworkErrorKind.CANCELLED, terminal.error?.kind)
        assertEquals(1, callbacks.size)
        assertEquals(1, originalBodyCancels)
        assertEquals(1, replacementBodyCancels)
        assertEquals(1, bridge.cancelledIds.size)
        assertTrue(assertNotNull(bridge.lastRequest).cancellationSignal.isCancelled())
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
    fun publicNativeStatusSeparatesLinkAndHttp3Capability() {
        AndroidCurlEngineProvider.testBridge = FakeBridge(
            isAvailable = false,
            supportsHttp3 = true
        )
        assertFalse(VBTransportCurl.nativeStatus.linked)
        assertFalse(VBTransportCurl.nativeStatus.http3FeatureAvailable)

        AndroidCurlEngineProvider.testBridge = FakeBridge(
            isAvailable = true,
            supportsHttp3 = false
        )
        assertTrue(VBTransportCurl.nativeStatus.linked)
        assertFalse(VBTransportCurl.nativeStatus.http3FeatureAvailable)

        AndroidCurlEngineProvider.testBridge = FakeBridge(
            isAvailable = true,
            supportsHttp3 = true
        )
        assertTrue(VBTransportCurl.nativeStatus.linked)
        assertTrue(VBTransportCurl.nativeStatus.http3FeatureAvailable)
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

    @Test
    fun jniCallbackPublishesTerminalOnlyAfterApplyingCompletionAndTransferFacts() {
        var terminalInvoked = false
        val callback = AndroidCurlJniCallback(
            onResponseStartBlock = null,
            onChunkBlock = null,
            uploadSource = null,
            cancellationSignal = AndroidCurlCancellationSignal(),
            onCompleteBlock = { response ->
                terminalInvoked = true
                val timing = response.elapse
                assertEquals(true, timing.curlFinalHeadersObserved)
                assertEquals(false, timing.curlFirstBodyObserved)
                assertEquals(false, timing.curlBodyProgressObserved)
                assertEquals(9.0, timing.curlFinalHeadersElapsedMs)
                assertEquals(0.0, timing.curlFirstBodyElapsedMs)
                assertEquals(0L, timing.curlBodyBytes)
                assertEquals(4.0, timing.curlEnqueueToNativeStartElapsedMs)
                assertEquals(true, timing.curlMultiOwnerThreadObserved)
                assertEquals(1, timing.curlCompletionInfoVersion)
                assertEquals("curl:17:4", timing.connectionIdentity)
                assertEquals(1.25, timing.nameLookupTimeMs)
                assertEquals(2.5, timing.connectTimeMs)
                assertEquals(0.0, timing.sslCostTimeMs)
                assertEquals(3.75, timing.preTransferTime)
                assertEquals(23.5, timing.startTransferTimeMs)
                assertEquals(23.5, timing.responseWaitTimeMs)
                assertEquals(42.125, timing.totalTimeMs)
                assertEquals(true, timing.curlNameLookupTimingAvailable)
                assertEquals(true, timing.curlConnectTimingAvailable)
                assertEquals(true, timing.curlPreTransferTimingAvailable)
                assertEquals(true, timing.curlStartTransferTimingAvailable)
                assertEquals(true, timing.curlTotalTimingAvailable)
                assertEquals("reused_connection", timing.curlTlsTimingState)
            }
        )
        callback.onCompletionFacts(
            connectionIdAvailable = true,
            nameLookupTimingAvailable = true,
            connectTimingAvailable = true,
            preTransferTimingAvailable = true,
            startTransferTimingAvailable = true,
            totalTimingAvailable = true,
            tlsTimingState = 3,
            connectionCacheId = 17,
            connectionId = 4,
            nameLookupTimeUs = 1_250,
            connectTimeUs = 2_500,
            tlsTimeUs = 99_000,
            preTransferTimeUs = 3_750,
            startTransferTimeUs = 23_500,
            totalTimeUs = 42_125,
        )
        callback.onTransferFacts(
            finalHeadersObserved = true,
            firstBodyObserved = false,
            bodyProgressObserved = false,
            finalHeadersElapsedMs = 9,
            firstBodyElapsedMs = 0,
            lastBodyProgressElapsedMs = 0,
            bodyBytes = 0,
        )
        callback.onMultiFacts(
            enqueueToNativeStartElapsedMs = 4,
            ownerThreadObserved = true,
        )
        assertFalse(terminalInvoked)
        callback.onComplete(
            code = 28,
            httpCode = 200,
            errorMessage = "buffered body idle timeout",
            headers = "HTTP/1.1 200 OK\r\n",
            redirectUrl = "",
            data = null,
            protocol = "h2",
            nameLookupTimeMs = 1.0,
            connectTimeMs = 2.0,
            sslCostTimeMs = 3.0,
            preTransferTimeMs = 4.0,
            startTransferTimeMs = 5.0,
            redirectTimeMs = 0.0,
            receiveTimeMs = 0.0,
            totalTimeMs = 510.0,
        )
        assertTrue(terminalInvoked)
    }

    @Test
    fun jniCallbackWithoutCompletionFactsPreservesLegacyTimingAndMarksSchemaAbsent() {
        var terminalInvoked = false
        val callback = AndroidCurlJniCallback(
            onResponseStartBlock = null,
            onChunkBlock = null,
            uploadSource = null,
            cancellationSignal = AndroidCurlCancellationSignal(),
            onCompleteBlock = { response ->
                terminalInvoked = true
                val timing = response.elapse
                assertEquals(1.0, timing.nameLookupTimeMs)
                assertEquals(2.0, timing.connectTimeMs)
                assertEquals(3.0, timing.sslCostTimeMs)
                assertEquals(4.0, timing.preTransferTime)
                assertEquals(5.0, timing.startTransferTimeMs)
                assertEquals(0.0, timing.responseWaitTimeMs)
                assertEquals(510.0, timing.totalTimeMs)
                assertEquals("unknown", timing.connectionIdentity)
                assertNull(timing.curlCompletionInfoVersion)
                assertNull(timing.curlNameLookupTimingAvailable)
                assertNull(timing.curlConnectTimingAvailable)
                assertNull(timing.curlPreTransferTimingAvailable)
                assertNull(timing.curlStartTransferTimingAvailable)
                assertNull(timing.curlTotalTimingAvailable)
                assertEquals("unknown", timing.curlTlsTimingState)
            }
        )

        callback.onComplete(
            code = 0,
            httpCode = 200,
            errorMessage = "",
            headers = "HTTP/1.1 200 OK\r\n",
            redirectUrl = "",
            data = "ok".encodeToByteArray(),
            protocol = "h2",
            nameLookupTimeMs = 1.0,
            connectTimeMs = 2.0,
            sslCostTimeMs = 3.0,
            preTransferTimeMs = 4.0,
            startTransferTimeMs = 5.0,
            redirectTimeMs = 0.0,
            receiveTimeMs = 500.0,
            totalTimeMs = 510.0,
        )

        assertTrue(terminalInvoked)
    }

    private open class FakeBridge(
        override val isAvailable: Boolean = true,
        override val supportsHttp3: Boolean = false
    ) : AndroidCurlNativeBridge {
        var executeResponse = CurlNativeResponse(code = 0, httpCode = 200)
        var freshExecuteResponse = CurlNativeResponse(code = 0, httpCode = 200)
        val executeResponses = mutableListOf<CurlNativeResponse>()
        val executeRequests = mutableListOf<AndroidCurlNativeRequest>()
        val freshExecuteRequests = mutableListOf<AndroidCurlNativeRequest>()
        var streamResponse = CurlNativeResponse(code = 0, httpCode = 200)
        var uploadResponse = CurlNativeResponse(code = 0, httpCode = 200)
        var streamStatus = 200L
        var streamHeaders = "HTTP/1.1 200 OK\r\n"
        var streamChunks: List<ByteArray> = emptyList()
        var uploadReadSize = 4
        var lastRequest: AndroidCurlNativeRequest? = null
        var uploadedBytes = ByteArray(0)
        val uploadChunkSizes = mutableListOf<Int>()
        var uploadRequests = 0
        val cancelledIds = mutableListOf<Int>()
        var executeDelayMillis: Long = 0
        var onExecute: ((AndroidCurlNativeRequest, Int) -> Unit)? = null

        override suspend fun execute(request: AndroidCurlNativeRequest): CurlNativeResponse {
            lastRequest = request
            executeRequests += request
            onExecute?.invoke(request, executeRequests.size)
            if (executeDelayMillis > 0) delay(executeDelayMillis)
            return if (executeResponses.isEmpty()) executeResponse else executeResponses.removeAt(0)
        }

        override suspend fun executeFresh(request: AndroidCurlNativeRequest): CurlNativeResponse {
            lastRequest = request
            freshExecuteRequests += request
            return freshExecuteResponse
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
            uploadRequests += 1
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

    private fun configureManualProxyHttp3() {
        VBTransportCurl.configure(
            NetworkCurlRuntimeConfiguration(
                trustStore = NetworkCurlTrustStore(
                    path = trustStoreFile.absolutePath,
                    sha256 = networkCurlSha256Hex(trustStoreFile.readBytes())
                ),
                proxy = NetworkCurlProxyConfiguration.manual("http://127.0.0.1:8888"),
                http3Enabled = true
            )
        )
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

    private class BlockingUploadBridge : FakeBridge() {
        val started = CompletableDeferred<Unit>()
        val writerStarted = CompletableDeferred<Unit>()
        val pullClosed = CompletableDeferred<Unit>()

        override suspend fun uploadStream(
            request: AndroidCurlNativeRequest,
            source: AndroidCurlUploadSource
        ): CurlNativeResponse {
            lastRequest = request
            started.complete(Unit)
            if (source.read(4) == null) {
                pullClosed.complete(Unit)
            }
            return CurlNativeResponse(code = 42, errorMsg = "cancelled by caller")
        }
    }
}

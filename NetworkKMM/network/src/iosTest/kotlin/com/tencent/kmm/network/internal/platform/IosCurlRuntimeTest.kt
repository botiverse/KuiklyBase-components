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

import com.tencent.kmm.network.export.NetworkBody
import com.tencent.kmm.network.export.NetworkByteStream
import com.tencent.kmm.network.export.NetworkCurlProxyConfiguration
import com.tencent.kmm.network.export.NetworkCurlRuntimeConfiguration
import com.tencent.kmm.network.export.NetworkCurlTrustStore
import com.tencent.kmm.network.export.NetworkErrorKind
import com.tencent.kmm.network.export.NetworkHttpProtocol
import com.tencent.kmm.network.export.NetworkProgressCallbacks
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkTransferProgress
import com.tencent.kmm.network.export.VBTransportCurl
import com.tencent.kmm.network.export.VBTransportMethod
import com.tencent.kmm.network.service.NetworkCall
import com.tencent.kmm.network.service.NetworkTransportEngine
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.getenv
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IosCurlRuntimeTest {
    @AfterTest
    fun clearCurlRuntime() {
        VBTransportCurl.clear()
    }

    @Test
    fun productionEnginePerformsExplicitCaHttpsRequest() = runBlocking {
        val url = runtimeEnvironment("NETWORKKMM_IOS_CURL_RUNTIME_URL") ?: return@runBlocking
        val caPath = runtimeCaPath() ?: return@runBlocking
        val caSha256 = runtimeCaSha256() ?: return@runBlocking
        configureRuntime(caPath, caSha256)
        try {
            val engine = assertNotNull(resolvePlatformNetworkEngine(NetworkTransportEngine.CURL))
            val request = NetworkRequest(url = url).apply {
                setHeader("Accept", "text/plain")
            }
            val response = engine.execute(request, NetworkCall(request))

            assertTrue(response.statusCode in 200..299)
            assertTrue(response.body.bytes?.isNotEmpty() == true)
            assertNull(response.error)
        } finally {
            VBTransportCurl.clear()
        }
    }

    @Test
    fun productionBridgeMatchesOptionalApiArtifactContract() = runBlocking {
        val expectation = runtimeEnvironment("NETWORKKMM_IOS_CURL_OPTIONAL_API_EXPECTATION")
            ?: return@runBlocking
        val url = runtimeEnvironment("NETWORKKMM_IOS_CURL_RUNTIME_URL") ?: return@runBlocking
        val caPath = runtimeCaPath() ?: return@runBlocking
        val result = IosCurlCInteropBridge.executeWithOptionalApiDiagnostics(
            runtimeRequest(900_001, url, caPath)
        )

        assertEquals(0, result.response.code, result.response.errorMsg)
        assertTrue(result.response.httpCode in 200..299)
        assertTrue(result.response.data?.isNotEmpty() == true)
        when (expectation) {
            "unavailable" -> assertEquals(
                IosCurlOptionalApiDiagnostics(
                    bufferedBodyIdleTimeoutSetterAvailable = false,
                    maxBufferedResponseBytesSetterAvailable = false,
                    transferFactsAvailable = false
                ),
                result.optionalApi,
                "old artifact must remain request-capable without additive symbols"
            )
            "available" -> {
                assertEquals(
                    IosCurlOptionalApiDiagnostics(
                        bufferedBodyIdleTimeoutSetterAvailable = true,
                        maxBufferedResponseBytesSetterAvailable = true,
                        transferFactsAvailable = true
                    ),
                    result.optionalApi,
                    "fresh artifact symbols must survive final executable linking"
                )
                assertEquals(true, result.response.elapse.curlFinalHeadersObserved)
                assertEquals(true, result.response.elapse.curlFirstBodyObserved)
                assertEquals(true, result.response.elapse.curlBodyProgressObserved)
                assertTrue(result.response.elapse.curlBodyBytes > 0)

                val capped = IosCurlCInteropBridge.executeWithOptionalApiDiagnostics(
                    runtimeRequest(
                        requestId = 900_004,
                        url = url,
                        caPath = caPath,
                        maxBufferedResponseBytes = 1
                    )
                )
                assertTrue(capped.optionalApi.maxBufferedResponseBytesSetterAvailable)
                assertEquals(63, capped.response.code, capped.response.errorMsg)
                assertEquals(0, capped.response.data?.size ?: 0)
            }
            else -> error("Unknown iOS curl optional API expectation: $expectation")
        }
    }

    @Test
    fun productionEngineNegotiatesHttp3AndPreservesFallbackContract() = runBlocking {
        val h3Url = runtimeEnvironment("NETWORKKMM_IOS_CURL_RUNTIME_HTTP3_URL") ?: return@runBlocking
        val fallbackUrl = runtimeEnvironment("NETWORKKMM_IOS_CURL_RUNTIME_H2_FALLBACK_URL")
            ?: return@runBlocking
        val totalFailureUrl = runtimeEnvironment("NETWORKKMM_IOS_CURL_RUNTIME_TOTAL_FAILURE_URL")
            ?: return@runBlocking
        val caPath = runtimeEnvironment("NETWORKKMM_IOS_CURL_RUNTIME_PUBLIC_CA_PATH") ?: return@runBlocking
        val caSha = runtimeEnvironment("NETWORKKMM_IOS_CURL_RUNTIME_PUBLIC_CA_SHA256") ?: return@runBlocking
        assertTrue(IosCurlCInteropBridge.supportsHttp3)
        val resolveEntries = buildMap {
            runtimeEnvironment("NETWORKKMM_IOS_CURL_RUNTIME_HTTP3_RESOLVE")?.let { put(h3Url, it) }
            runtimeEnvironment("NETWORKKMM_IOS_CURL_RUNTIME_H2_FALLBACK_RESOLVE")?.let {
                put(fallbackUrl, it)
            }
        }
        val engine = IosCurlNetworkEngine(
            IosCurlResolveOverrideBridge(IosCurlCInteropBridge, resolveEntries)
        )

        configureRuntime(caPath, caSha, http3Enabled = true)
        val h3Attempt = executeExternal(engine, h3Url)
        val h3 = h3Attempt.response
        val h3Diagnostic = h3.runtimeDiagnostic("h3", h3Url, h3Attempt.attempts)
        assertTrue(h3.isSuccess, h3Diagnostic)
        assertEquals(NetworkHttpProtocol.HTTP_3, h3.protocol, h3Diagnostic)

        // The h3 connection above remains pooled. Default traffic must use
        // the separate h2/h1 pool instead of silently reusing that connection.
        configureRuntime(caPath, caSha)
        val defaultAttempt = executeExternal(engine, h3Url)
        val defaultH2 = defaultAttempt.response
        val defaultDiagnostic = defaultH2.runtimeDiagnostic("default", h3Url, defaultAttempt.attempts)
        assertTrue(defaultH2.isSuccess, defaultDiagnostic)
        assertEquals(NetworkHttpProtocol.HTTP_2, defaultH2.protocol, defaultDiagnostic)

        configureRuntime(caPath, caSha, http3Enabled = true)
        val fallbackAttempt = executeExternal(engine, fallbackUrl)
        val fallback = fallbackAttempt.response
        val fallbackDiagnostic = fallback.runtimeDiagnostic("fallback", fallbackUrl, fallbackAttempt.attempts)
        assertTrue(fallback.isSuccess, fallbackDiagnostic)
        assertEquals(NetworkHttpProtocol.HTTP_2, fallback.protocol, fallbackDiagnostic)

        val totalFailure = execute(engine, totalFailureUrl)
        val failureDiagnostic = totalFailure.runtimeDiagnostic("total-failure", totalFailureUrl)
        assertFalse(totalFailure.isSuccess, failureDiagnostic)
        assertEquals(NetworkErrorKind.CONNECT, totalFailure.error?.kind, failureDiagnostic)
        assertEquals(NetworkHttpProtocol.UNKNOWN, totalFailure.protocol, failureDiagnostic)
    }

    @Test
    fun productionEngineEnforcesCertificateAcceptanceMatrix() = runBlocking {
        val trustedUrl = runtimeEnvironment("NETWORKKMM_IOS_CURL_RUNTIME_URL") ?: return@runBlocking
        val unknownUrl = runtimeEnvironment("NETWORKKMM_IOS_CURL_RUNTIME_UNKNOWN_CA_URL") ?: return@runBlocking
        val expiredUrl = runtimeEnvironment("NETWORKKMM_IOS_CURL_RUNTIME_EXPIRED_URL") ?: return@runBlocking
        val mismatchUrl = runtimeEnvironment("NETWORKKMM_IOS_CURL_RUNTIME_MISMATCH_URL") ?: return@runBlocking
        val trustedCaPath = runtimeCaPath() ?: return@runBlocking
        val trustedCaSha = runtimeCaSha256() ?: return@runBlocking
        val wrongCaPath = runtimeEnvironment("NETWORKKMM_IOS_CURL_RUNTIME_WRONG_CA_PATH") ?: return@runBlocking
        val wrongCaSha = runtimeEnvironment("NETWORKKMM_IOS_CURL_RUNTIME_WRONG_CA_SHA256") ?: return@runBlocking

        configureRuntime(trustedCaPath, trustedCaSha)
        val engine = assertNotNull(resolvePlatformNetworkEngine(NetworkTransportEngine.CURL))
        assertTrue(execute(engine, trustedUrl).isSuccess)
        assertEquals(NetworkErrorKind.TLS, execute(engine, unknownUrl).error?.kind)
        assertEquals(NetworkErrorKind.TLS, execute(engine, expiredUrl).error?.kind)
        assertEquals(NetworkErrorKind.TLS, execute(engine, mismatchUrl).error?.kind)

        configureRuntime(wrongCaPath, wrongCaSha)
        assertEquals(NetworkErrorKind.TLS, execute(engine, trustedUrl).error?.kind)
        VBTransportCurl.clear()
    }

    @Test
    fun productionEngineUsesFixedManualProxy() = runBlocking {
        val url = runtimeEnvironment("NETWORKKMM_IOS_CURL_RUNTIME_URL") ?: return@runBlocking
        val caPath = runtimeCaPath() ?: return@runBlocking
        val caSha = runtimeCaSha256() ?: return@runBlocking
        val proxyUrl = runtimeEnvironment("NETWORKKMM_IOS_CURL_RUNTIME_PROXY_URL") ?: return@runBlocking
        configureRuntime(caPath, caSha, NetworkCurlProxyConfiguration.manual(proxyUrl))
        try {
            val engine = assertNotNull(resolvePlatformNetworkEngine(NetworkTransportEngine.CURL))
            assertTrue(execute(engine, url).isSuccess)
        } finally {
            VBTransportCurl.clear()
        }
    }

    @Test
    fun productionBridgeStreamsHttpsResponseAndSuppressesCallbacksAfterFailure() = runBlocking {
        val url = runtimeEnvironment("NETWORKKMM_IOS_CURL_RUNTIME_URL") ?: return@runBlocking
        val caPath = runtimeCaPath() ?: return@runBlocking
        val caSha = runtimeCaSha256() ?: return@runBlocking
        configureRuntime(caPath, caSha)
        val chunks = mutableListOf<ByteArray>()
        var responseStarts = 0

        val response = IosCurlCInteropBridge.downloadStream(
            request = runtimeRequest(900_002, url, caPath),
            onResponseStart = { code, headers ->
                responseStarts += 1
                assertTrue(code in 200..299)
                assertTrue(headers.isNotEmpty())
            },
            onChunk = chunks::add
        )

        assertEquals(0, response.code, response.errorMsg)
        assertEquals(1, responseStarts)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.sumOf(ByteArray::size) > 0)

        var chunksAfterFailure = 0
        val failed = IosCurlCInteropBridge.downloadStream(
            request = runtimeRequest(900_003, url, caPath),
            onResponseStart = { _, _ -> error("consumer rejected response") },
            onChunk = { chunksAfterFailure += 1 }
        )

        assertEquals(-1, failed.code)
        assertTrue(failed.errorMsg.contains("consumer rejected response"))
        assertEquals(0, chunksAfterFailure)
    }

    @Test
    fun productionEngineStreamsUploadsBeyondPerformPoolWidth() = runBlocking {
        val url = runtimeEnvironment("NETWORKKMM_IOS_CURL_RUNTIME_UPLOAD_URL") ?: return@runBlocking
        val caPath = runtimeCaPath() ?: return@runBlocking
        val caSha = runtimeCaSha256() ?: return@runBlocking
        configureRuntime(caPath, caSha)
        val payload = "NetworkKMM iOS curl streaming upload".encodeToByteArray()

        val single = upload(url = url, caPath = caPath, payload = payload)
        assertTrue(single.statusCode in 200..299)
        assertContentEquals(payload, single.body.bytes)
        assertNull(single.error)

        // The production perform pool has four threads. Eight high-level uploads
        // prove producers run independently and still make progress past that width.
        val responses = coroutineScope {
            (0 until 8).map { index ->
                async {
                    upload(
                        url = url,
                        caPath = caPath,
                        payload = "upload-$index".encodeToByteArray()
                    )
                }
            }.awaitAll()
        }
        assertTrue(responses.all { it.statusCode in 200..299 && it.error == null })
    }

    @Test
    fun productionBridgeHonorsPreStartAndExternalCancellation() = runBlocking {
        val url = runtimeEnvironment("NETWORKKMM_IOS_CURL_RUNTIME_CANCEL_URL") ?: return@runBlocking
        val caPath = runtimeCaPath() ?: return@runBlocking
        val caSha = runtimeCaSha256() ?: return@runBlocking
        configureRuntime(caPath, caSha)

        val preStart = runtimeRequest(900_200, url, caPath).also(IosCurlNativeRequest::cancel)
        val preStartResponse = IosCurlCInteropBridge.execute(preStart)
        assertEquals(42, preStartResponse.code, preStartResponse.errorMsg)

        val request = runtimeRequest(900_201, url, caPath)
        var responseCode: Int? = null
        val job = launch {
            responseCode = IosCurlCInteropBridge.execute(request).code
        }
        delay(250)
        request.cancel()
        IosCurlCInteropBridge.cancel(request.requestId)
        withTimeout(10_000) { job.join() }

        assertEquals(42, responseCode)
    }

    private suspend fun upload(
        url: String,
        caPath: String,
        payload: ByteArray
    ): NetworkResponse {
        val progress = mutableListOf<NetworkTransferProgress>()
        val request = NetworkRequest(
            method = VBTransportMethod.POST,
            url = url,
            body = NetworkBody.Stream(
                stream = NetworkByteStream.fromChunks(payload.size.toLong()) { sink ->
                    val midpoint = payload.size / 2
                    sink.write(payload.copyOfRange(0, midpoint))
                    sink.write(payload.copyOfRange(midpoint, payload.size))
                },
                contentType = "application/octet-stream"
            ),
            progress = NetworkProgressCallbacks(uploadProgress = progress::add)
        )
        return IosCurlNetworkEngine(IosCurlCInteropBridge)
            .execute(request, NetworkCall(request))
            .also {
                assertEquals(payload.size.toLong(), progress.lastOrNull()?.bytesTransferred)
            }
    }

    private fun runtimeRequest(
        requestId: Int,
        url: String,
        caPath: String,
        method: String = "GET",
        headers: Map<String, String> = mapOf("Accept" to "text/plain"),
        uploadContentLength: Long? = null,
        maxBufferedResponseBytes: Long = 0
    ) = IosCurlNativeRequest(
        requestId = requestId,
        url = url,
        method = method,
        headers = headers,
        timeoutMillis = 30_000,
        maxBufferedResponseBytes = maxBufferedResponseBytes,
        uploadContentLength = uploadContentLength,
        caInfoPath = caPath,
        proxyUrl = ""
    )

    private fun runtimeCaPath(): String? =
        runtimeEnvironment("NETWORKKMM_IOS_CURL_RUNTIME_CA_PATH")

    private fun runtimeCaSha256(): String? =
        runtimeEnvironment("NETWORKKMM_IOS_CURL_RUNTIME_CA_SHA256")

    private fun configureRuntime(
        caPath: String,
        caSha256: String,
        proxy: NetworkCurlProxyConfiguration = NetworkCurlProxyConfiguration.direct(),
        http3Enabled: Boolean = false
    ) {
        val status = VBTransportCurl.configure(
            NetworkCurlRuntimeConfiguration(
                trustStore = NetworkCurlTrustStore(caPath, caSha256),
                proxy = proxy,
                http3Enabled = http3Enabled
            )
        )
        assertTrue(status.configured, status.detail)
    }

    private suspend fun execute(
        engine: com.tencent.kmm.network.service.NetworkEngine,
        url: String
    ): NetworkResponse {
        val request = NetworkRequest(url = url)
        return engine.execute(request, NetworkCall(request))
    }

    private suspend fun executeExternal(
        engine: com.tencent.kmm.network.service.NetworkEngine,
        url: String
    ): RuntimeAttempt {
        for (attempt in 1..6) {
            val response = execute(engine, url)
            if (response.error?.kind != NetworkErrorKind.DNS || attempt == 6) {
                return RuntimeAttempt(response, attempt)
            }
            // The hosted iOS simulator can launch before its external resolver
            // is ready. Retry only DNS readiness; all transport/protocol results
            // leave this loop immediately and remain strict assertions above.
            delay(1_000)
        }
        error("unreachable")
    }

    private fun NetworkResponse.runtimeDiagnostic(
        label: String,
        url: String,
        attempts: Int = 1
    ): String =
        "$label url=$url attempts=$attempts status=$statusCode protocol=$protocol " +
            "errorKind=${error?.kind} error=${error?.message}"

    private fun runtimeEnvironment(name: String): String? =
        getenv(name)?.toKString()?.takeIf(String::isNotBlank)

    private data class RuntimeAttempt(
        val response: NetworkResponse,
        val attempts: Int
    )

    private class IosCurlResolveOverrideBridge(
        private val delegate: IosCurlNativeBridge,
        private val entriesByUrl: Map<String, String>
    ) : IosCurlNativeBridge {
        override val isAvailable: Boolean
            get() = delegate.isAvailable
        override val supportsHttp3: Boolean
            get() = delegate.supportsHttp3

        override suspend fun execute(request: IosCurlNativeRequest) =
            delegate.execute(request.withResolveEntry())

        override suspend fun downloadStream(
            request: IosCurlNativeRequest,
            onResponseStart: (Long, String) -> Unit,
            onChunk: (ByteArray) -> Unit
        ) = delegate.downloadStream(request.withResolveEntry(), onResponseStart, onChunk)

        override suspend fun uploadStream(
            request: IosCurlNativeRequest,
            source: IosCurlUploadSource
        ) = delegate.uploadStream(request.withResolveEntry(), source)

        override fun cancel(requestId: Int) {
            delegate.cancel(requestId)
        }

        private fun IosCurlNativeRequest.withResolveEntry(): IosCurlNativeRequest =
            copy(resolveEntry = entriesByUrl[url])
    }
}

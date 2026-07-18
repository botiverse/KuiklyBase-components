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
package com.tencent.kmm.network.service

import com.tencent.kmm.network.export.NetworkEngineCapabilities
import com.tencent.kmm.network.export.NetworkHttpProtocol
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkResponseBody
import com.tencent.kmm.network.export.VBTransportElapseStatistics
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkEngineRoutingTest {
    @Test
    fun externalConfigMapsOnceAndFailsClosed() {
        val curl = NetworkEngineSelection.fromExternalConfig(
            engine = " CURL ",
            curlEnabled = true
        )
        assertEquals(NetworkTransportEngine.CURL, curl.requestedEngine)
        assertTrue(NetworkTransportEngine.CURL in curl.allowedEngines)

        val disabled = NetworkEngineSelection.fromExternalConfig(
            engine = "curl",
            curlEnabled = false
        )
        assertEquals(NetworkTransportEngine.CURL, disabled.requestedEngine)
        assertFalse(NetworkTransportEngine.CURL in disabled.allowedEngines)

        val invalid = NetworkEngineSelection.fromExternalConfig(
            engine = "cronet",
            curlEnabled = true
        )
        assertNull(invalid.requestedEngine)
        assertFalse(invalid.externalValueValid)
    }

    @Test
    fun selectionUsesRequestedEngineAndFallsBackSafely() {
        val ktor = FakeEngine("ktor")
        val curl = FakeEngine("curl")
        val resolver: (NetworkTransportEngine) -> NetworkEngine? = {
            when (it) {
                NetworkTransportEngine.KTOR -> ktor
                NetworkTransportEngine.CURL -> curl
            }
        }

        val requested = resolveNetworkEngine(
            selection = NetworkEngineSelection(
                requestedEngine = NetworkTransportEngine.CURL,
                hostSelectionTag = "config-generation-42"
            ),
            platformDefault = NetworkTransportEngine.KTOR,
            resolver = resolver
        )
        assertEquals(NetworkTransportEngine.CURL, requested.diagnostics.selectedEngine)
        assertEquals(NetworkEngineSelectionReason.REQUESTED, requested.diagnostics.reason)
        assertEquals("config-generation-42", requested.diagnostics.hostSelectionTag)

        val disallowed = resolveNetworkEngine(
            selection = NetworkEngineSelection(
                requestedEngine = NetworkTransportEngine.CURL,
                allowedEngines = setOf(NetworkTransportEngine.KTOR)
            ),
            platformDefault = NetworkTransportEngine.KTOR,
            resolver = resolver
        )
        assertEquals(NetworkTransportEngine.KTOR, disallowed.diagnostics.selectedEngine)
        assertEquals(NetworkEngineSelectionReason.DISALLOWED, disallowed.diagnostics.reason)

        val unavailable = resolveNetworkEngine(
            selection = NetworkEngineSelection(requestedEngine = NetworkTransportEngine.CURL),
            platformDefault = NetworkTransportEngine.KTOR,
            resolver = { engine -> if (engine == NetworkTransportEngine.KTOR) ktor else null }
        )
        assertEquals(NetworkTransportEngine.KTOR, unavailable.diagnostics.selectedEngine)
        assertEquals(NetworkEngineSelectionReason.UNAVAILABLE, unavailable.diagnostics.reason)

        val invalid = resolveNetworkEngine(
            selection = NetworkEngineSelection.fromExternalConfig(
                engine = "unknown",
                curlEnabled = true
            ),
            platformDefault = NetworkTransportEngine.KTOR,
            resolver = resolver
        )
        assertEquals(NetworkTransportEngine.KTOR, invalid.diagnostics.selectedEngine)
        assertEquals(NetworkEngineSelectionReason.INVALID_EXTERNAL_VALUE, invalid.diagnostics.reason)
    }

    @Test
    fun selectorCanGrayThenRollbackWithoutRebuildingClient() = runBlocking {
        val ktor = FakeEngine("ktor")
        val curl = FakeEngine(
            "curl",
            timing = VBTransportElapseStatistics(
                dispatcherQueueTimeMs = 17.0,
                connectTimeMs = 29.0,
                sslCostTimeMs = 11.0,
                totalTimeMs = 42.0,
                protocol = "h2",
                connectionAttemptCount = 2
            )
        )
        val selected = mutableListOf<NetworkEngineSelectionDiagnostics>()
        val completed = mutableListOf<NetworkEngineExecutionDiagnostics>()
        var rollback = false
        val router = RoutingNetworkEngine(
            selector = {
                NetworkEngineSelection(
                    requestedEngine = NetworkTransportEngine.CURL,
                    forcePlatformDefault = rollback
                )
            },
            diagnosticsListener = object : NetworkEngineDiagnosticsListener {
                override fun onEngineSelected(diagnostics: NetworkEngineSelectionDiagnostics) {
                    selected += diagnostics
                }

                override fun onEngineCompleted(diagnostics: NetworkEngineExecutionDiagnostics) {
                    completed += diagnostics
                }
            },
            platformDefault = NetworkTransportEngine.KTOR,
            resolver = { engine -> if (engine == NetworkTransportEngine.KTOR) ktor else curl }
        )
        val request = NetworkRequest().apply { url = "https://example.test" }
        val firstCall = NetworkCall(request)

        router.execute(request, firstCall)
        rollback = true
        // A retry/second attempt on the same call must not switch engines.
        router.execute(request, firstCall)
        router.execute(request, NetworkCall(request))

        assertEquals(listOf("curl", "curl"), curl.executed)
        assertEquals(listOf("ktor"), ktor.executed)
        assertEquals(
            listOf(NetworkTransportEngine.CURL, NetworkTransportEngine.KTOR),
            selected.map { it.selectedEngine }
        )
        assertEquals(NetworkEngineSelectionReason.REMOTE_ROLLBACK, selected.last().reason)
        assertEquals(42.0, completed.first().timing.totalTimeMs)
        assertEquals(17.0, completed.first().timing.dispatcherQueueTimeMs)
        assertEquals(29.0, completed.first().timing.connectTimeMs)
        assertEquals(11.0, completed.first().timing.sslCostTimeMs)
        assertEquals("h2", completed.first().timing.protocol)
        assertEquals(2, completed.first().timing.connectionAttemptCount)
        assertEquals(3, completed.size)
    }

    @Test
    fun completionDiagnosticsSeparateHttp3RequestFromH2Negotiation() = runBlocking {
        val completed = mutableListOf<NetworkEngineExecutionDiagnostics>()
        val curl = FakeEngine(
            name = "curl",
            protocol = NetworkHttpProtocol.HTTP_2
        )
        val router = RoutingNetworkEngine(
            selector = { NetworkEngineSelection(requestedEngine = NetworkTransportEngine.CURL) },
            diagnosticsListener = object : NetworkEngineDiagnosticsListener {
                override fun onEngineCompleted(diagnostics: NetworkEngineExecutionDiagnostics) {
                    completed += diagnostics
                }
            },
            platformDefault = NetworkTransportEngine.KTOR,
            resolver = { curl }
        )
        val verifiedDefault = CurlPlatformDefaultTrust(
            available = true,
            detail = "test compiled default"
        )
        val h3Requested = NetworkRequest(url = "https://example.test/h3")
            .setCurlHttp3Enabled(true)
        val ordinaryH2 = NetworkRequest(url = "https://example.test/h2")
            .setCurlHttp3Enabled(false)
        assertTrue(prepareCurlRuntime(h3Requested, verifiedDefault, nativeHttp3Supported = true).available)

        router.execute(h3Requested, NetworkCall(h3Requested))
        // Explicit false remains observable even when the selected platform
        // engine never invokes curl runtime preparation (Android/iOS off).
        router.execute(ordinaryH2, NetworkCall(ordinaryH2))

        assertEquals(listOf(true, false), completed.map { it.http3Requested })
        assertEquals(
            listOf(NetworkHttpProtocol.HTTP_2, NetworkHttpProtocol.HTTP_2),
            completed.map { it.negotiatedProtocol }
        )
    }

    @Test
    fun stableRolloutBucketsCohortsAndKeepsRollbackImmediate() {
        val half = NetworkEngineRolloutConfig(
            curlBasisPoints = 5_000,
            curlEnabled = true,
            salt = "release-42"
        )
        val first = half.selectionFor("account-123")
        val repeated = half.selectionFor("account-123")

        assertEquals(first.rollout?.bucket, repeated.rollout?.bucket)
        assertEquals(first.requestedEngine, repeated.requestedEngine)
        assertTrue(requireNotNull(first.rollout).bucket in 0..9_999)

        assertNull(
            NetworkEngineRolloutConfig(
                curlBasisPoints = 0,
                curlEnabled = true
            ).selectionFor("any").requestedEngine
        )
        assertEquals(
            NetworkTransportEngine.CURL,
            NetworkEngineRolloutConfig(
                curlBasisPoints = 10_000,
                curlEnabled = true
            ).selectionFor("any").requestedEngine
        )
        assertTrue(
            NetworkEngineRolloutConfig(
                curlBasisPoints = 10_000,
                curlEnabled = true,
                forcePlatformDefault = true
            ).selectionFor("any").forcePlatformDefault
        )
    }

    @Test
    fun ineligibleRequestedEngineFallsBackWithSpecificReason() {
        val ktor = FakeEngine("ktor")
        val curl = FakeEngine(
            name = "curl",
            availability = NetworkEngineAvailability.unavailable(
                NetworkEngineUnavailableReason.PROXY_PAC_UNSUPPORTED,
                "PAC unresolved"
            )
        )
        val request = NetworkRequest(url = "https://example.test")

        val resolved = resolveNetworkEngine(
            selection = NetworkEngineSelection(requestedEngine = NetworkTransportEngine.CURL),
            platformDefault = NetworkTransportEngine.KTOR,
            resolver = { engine -> if (engine == NetworkTransportEngine.KTOR) ktor else curl },
            request = request
        )

        assertEquals(NetworkTransportEngine.KTOR, resolved.diagnostics.selectedEngine)
        assertEquals(NetworkEngineSelectionReason.INELIGIBLE, resolved.diagnostics.reason)
        assertEquals(
            NetworkEngineUnavailableReason.PROXY_PAC_UNSUPPORTED,
            resolved.diagnostics.unavailableReason
        )
        assertEquals("PAC unresolved", resolved.diagnostics.unavailableDetail)
    }

    @Test
    fun streamingUsesSelectedEngineAndReportsActualCapabilities() = runBlocking {
        val ktor = FakeEngine("ktor")
        val curl = FakeEngine(
            name = "curl",
            capabilities = NetworkEngineCapabilities(
                requestBodyStreaming = true,
                responseBodyStreaming = true,
                multipartStreaming = true
            )
        )
        var diagnostic: NetworkEngineSelectionDiagnostics? = null
        val router = RoutingNetworkEngine(
            selector = { NetworkEngineSelection(requestedEngine = NetworkTransportEngine.CURL) },
            diagnosticsListener = object : NetworkEngineDiagnosticsListener {
                override fun onEngineSelected(diagnostics: NetworkEngineSelectionDiagnostics) {
                    diagnostic = diagnostics
                }
            },
            platformDefault = NetworkTransportEngine.KTOR,
            resolver = { engine -> if (engine == NetworkTransportEngine.KTOR) ktor else curl }
        )
        val request = NetworkRequest().apply { url = "https://example.test/stream" }
        var startStatus = 0
        val chunks = mutableListOf<ByteArray>()

        router.downloadStream(
            request = request,
            call = NetworkCall(request),
            onResponseStart = { status, _, _ -> startStatus = status },
            onChunk = { chunks += it }
        )

        assertEquals(200, startStatus)
        assertContentEquals("curl".encodeToByteArray(), chunks.single())
        assertEquals(listOf("curl-stream"), curl.executed)
        assertTrue(requireNotNull(diagnostic).capabilities.responseBodyStreaming)

        val defaultCapabilities = resolveNetworkEngine(
            selection = NetworkEngineSelection(forcePlatformDefault = true),
            platformDefault = NetworkTransportEngine.KTOR,
            resolver = { engine -> if (engine == NetworkTransportEngine.KTOR) ktor else curl }
        ).diagnostics.capabilities
        assertFalse(defaultCapabilities.responseBodyStreaming)
    }

    @Test
    fun selectorFailureFallsBackToPlatformDefault() = runBlocking {
        val ktor = FakeEngine("ktor")
        var diagnostic: NetworkEngineSelectionDiagnostics? = null
        val router = RoutingNetworkEngine(
            selector = { error("remote config unavailable") },
            diagnosticsListener = object : NetworkEngineDiagnosticsListener {
                override fun onEngineSelected(diagnostics: NetworkEngineSelectionDiagnostics) {
                    diagnostic = diagnostics
                }
            },
            platformDefault = NetworkTransportEngine.KTOR,
            resolver = { ktor }
        )
        val request = NetworkRequest().apply { url = "https://example.test" }

        router.execute(request, NetworkCall(request))

        assertEquals(NetworkEngineSelectionReason.SELECTOR_ERROR, diagnostic?.reason)
        assertEquals(listOf("ktor"), ktor.executed)
    }

    private class FakeEngine(
        private val name: String,
        override val capabilities: NetworkEngineCapabilities = NetworkEngineCapabilities(),
        private val timing: VBTransportElapseStatistics = VBTransportElapseStatistics(),
        private val availability: NetworkEngineAvailability = NetworkEngineAvailability.Available,
        private val protocol: NetworkHttpProtocol = NetworkHttpProtocol.UNKNOWN
    ) : NetworkEngine {
        val executed = mutableListOf<String>()

        override fun availability(request: NetworkRequest): NetworkEngineAvailability = availability

        override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse {
            executed += name
            return response(request)
        }

        override suspend fun downloadStream(
            request: NetworkRequest,
            call: NetworkCall,
            onResponseStart: (statusCode: Int, contentLength: Long?, headers: Map<String, List<String>>) -> Unit,
            onChunk: (ByteArray) -> Unit
        ): NetworkResponse {
            executed += "$name-stream"
            val bytes = name.encodeToByteArray()
            onResponseStart(200, bytes.size.toLong(), mapOf("Content-Length" to listOf(bytes.size.toString())))
            onChunk(bytes)
            return response(request)
        }

        private fun response(request: NetworkRequest): NetworkResponse = NetworkResponse(
            request = request,
            statusCode = 200,
            headers = emptyMap(),
            body = NetworkResponseBody(),
            timing = timing,
            protocol = protocol
        )
    }
}

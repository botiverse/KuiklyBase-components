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

import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkResponseBody
import com.tencent.kmm.network.export.NetworkUserAgent
import com.tencent.kmm.network.export.VBTransportMethod
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Public-contract coverage for the default `User-Agent`, asserted on the headers
 * the engine actually receives.
 *
 * Two boundaries were missed by narrower checks and are pinned here:
 *
 *  - `downloadStream()` prepares requests on its own path, so covering
 *    `execute()` alone left streaming downloads with no User-Agent at all.
 *  - Injecting before the interceptor chain let an interceptor add its own
 *    `user-agent` afterwards, so the engine received two header keys that
 *    differed only in case.
 *
 * Counting call sites in the source could not have caught either: the first is
 * about which paths exist, the second purely about ordering.
 */
class NetworkClientUserAgentContractTest {

    /** Captures exactly what the client hands to the transport. */
    private class CapturingEngine : NetworkEngine {
        var executeHeaders: Map<String, String>? = null
        var streamHeaders: Map<String, String>? = null

        override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse {
            executeHeaders = request.headers.toMap()
            return ok(request)
        }

        override suspend fun downloadStream(
            request: NetworkRequest,
            call: NetworkCall,
            onResponseStart: (Int, Long?, Map<String, List<String>>) -> Unit,
            onChunk: (ByteArray) -> Unit
        ): NetworkResponse {
            streamHeaders = request.headers.toMap()
            onResponseStart(200, 0L, emptyMap())
            return ok(request)
        }

        private fun ok(request: NetworkRequest) = NetworkResponse(
            request = request,
            statusCode = 200,
            headers = emptyMap(),
            body = NetworkResponseBody()
        )
    }

    private fun userAgentKeys(headers: Map<String, String>): List<String> =
        headers.keys.filter { it.equals("User-Agent", ignoreCase = true) }

    private fun request(): NetworkRequest =
        NetworkRequest(method = VBTransportMethod.GET, url = "https://example.invalid/probe")

    @AfterTest
    fun reset() {
        NetworkUserAgent.appIdentity = null
    }

    @Test
    fun ordinaryRequestReachesTheEngineWithExactlyOneUserAgent() = runBlocking {
        val engine = CapturingEngine()
        val client = NetworkClient(engine = engine, config = NetworkClientConfig())

        client.execute(request())

        val headers = requireNotNull(engine.executeHeaders)
        assertEquals(1, userAgentKeys(headers).size, "headers=$headers")
    }

    @Test
    fun streamingDownloadAlsoReachesTheEngineWithAUserAgent() = runBlocking {
        // The gap that shipped in the first version: this path prepares
        // requests separately and carried no User-Agent at all.
        val engine = CapturingEngine()
        val client = NetworkClient(engine = engine, config = NetworkClientConfig())

        val finished = CompletableDeferred<NetworkResponse>()
        client.downloadStream(
            request = request(),
            onResponseStart = { _, _, _ -> },
            onChunk = {},
            onComplete = { finished.complete(it) }
        )
        finished.await()

        val headers = requireNotNull(engine.streamHeaders)
        assertEquals(1, userAgentKeys(headers).size, "headers=$headers")
    }

    @Test
    fun anInterceptorSettingLowercaseUserAgentStillWinsAndDoesNotDuplicate() = runBlocking {
        // The second boundary: injecting before the chain produced a canonical
        // default alongside the interceptor's lowercase key.
        val engine = CapturingEngine()
        val interceptor = object : NetworkInterceptor {
            override suspend fun intercept(chain: NetworkInterceptorChain): NetworkResponse {
                val request = chain.request
                request.headers["user-agent"] = "interceptor/1.0"
                return chain.proceed(request)
            }
        }
        val client = NetworkClient(
            engine = engine,
            config = NetworkClientConfig(interceptors = listOf(interceptor))
        )

        client.execute(request())

        val headers = requireNotNull(engine.executeHeaders)
        val keys = userAgentKeys(headers)
        assertEquals(1, keys.size, "expected a single User-Agent, headers=$headers")
        assertEquals("interceptor/1.0", headers[keys.single()])
    }

    @Test
    fun anExplicitCallerValueSurvivesToTheEngine() = runBlocking {
        val engine = CapturingEngine()
        val client = NetworkClient(engine = engine, config = NetworkClientConfig())
        val outgoing = request().setHeader("User-Agent", "caller/2.0")

        client.execute(outgoing)

        val headers = requireNotNull(engine.executeHeaders)
        assertEquals(1, userAgentKeys(headers).size, "headers=$headers")
        assertEquals("caller/2.0", headers["User-Agent"])
    }

    @Test
    fun theDefaultCarriesTheLibraryVersion() = runBlocking {
        val engine = CapturingEngine()
        val client = NetworkClient(engine = engine, config = NetworkClientConfig())

        client.execute(request())

        val headers = requireNotNull(engine.executeHeaders)
        val value = headers.getValue(userAgentKeys(headers).single())
        assertTrue(
            value.contains("NetworkKMM/${NetworkUserAgent.LIBRARY_VERSION}"),
            "value=$value"
        )
    }
}

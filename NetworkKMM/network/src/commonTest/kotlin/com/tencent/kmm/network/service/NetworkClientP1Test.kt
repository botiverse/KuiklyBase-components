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

import com.tencent.kmm.network.export.NetworkErrorKind
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkResponseBody
import com.tencent.kmm.network.export.VBTransportResultCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkClientP1Test {
    @Test
    fun interceptorsRunInDeclaredOrder() = runBlocking {
        val events = mutableListOf<String>()
        val client = NetworkClient(
            config = NetworkClientConfig(
                interceptors = listOf(
                    object : NetworkInterceptor {
                        override suspend fun intercept(chain: NetworkInterceptorChain): NetworkResponse {
                            events.add("first-before")
                            val response = chain.proceed(chain.request.apply { setHeader("X-First", "1") })
                            events.add("first-after")
                            return response
                        }
                    },
                    object : NetworkInterceptor {
                        override suspend fun intercept(chain: NetworkInterceptorChain): NetworkResponse {
                            events.add("second-before")
                            val response = chain.proceed(chain.request.apply { setHeader("X-Second", "2") })
                            events.add("second-after")
                            return response
                        }
                    }
                )
            ),
            engine = object : NetworkEngine {
                override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse {
                    events.add("engine:${request.headers["X-First"]}:${request.headers["X-Second"]}")
                    return NetworkResponse(
                        request = request,
                        statusCode = 200,
                        headers = emptyMap(),
                        body = NetworkResponseBody(bytes = byteArrayOf(1))
                    )
                }
            }
        )

        client.execute(NetworkRequest())

        assertEquals(
            listOf("first-before", "second-before", "engine:1:2", "second-after", "first-after"),
            events
        )
    }

    @Test
    fun errorClassifierMapsStableTaxonomy() {
        assertEquals(
            NetworkErrorKind.CANCELLED,
            classifyNetworkErrorKind(VBTransportResultCode.CODE_CANCELED, "", null)
        )
        assertEquals(
            NetworkErrorKind.TIMEOUT,
            classifyNetworkErrorKind(VBTransportResultCode.CODE_FORCE_TIMEOUT, "", null)
        )
        assertEquals(
            NetworkErrorKind.DNS,
            classifyNetworkErrorKind(-1, "Could not resolve host", null)
        )
        assertEquals(
            NetworkErrorKind.TLS,
            classifyNetworkErrorKind(-1, "SSL certificate verify failed", null)
        )
        assertEquals(
            NetworkErrorKind.CONNECT,
            classifyNetworkErrorKind(-1, "Connection refused", null)
        )
        assertEquals(
            NetworkErrorKind.AUTH,
            classifyNetworkErrorKind(401, "Unauthorized", 401)
        )
        assertEquals(
            NetworkErrorKind.HTTP_STATUS,
            classifyNetworkErrorKind(503, "Service unavailable", 503)
        )
    }

    @Test
    fun cancelHandlerRegisteredAfterCancellationRunsImmediately() {
        val request = NetworkRequest()
        val call = NetworkCall(request)
        var invocations = 0

        call.cancel()
        call.addCancelHandler { invocations++ }

        assertEquals(1, invocations)
    }

    @Test
    fun cancelContinuesAfterOneHandlerThrows() {
        val request = NetworkRequest()
        val call = NetworkCall(request)
        val events = mutableListOf<String>()
        call.addCancelHandler {
            events += "first"
            error("cancel callback failed")
        }
        call.addCancelHandler { events += "second" }

        call.cancel()
        call.cancel()

        assertEquals(listOf("first", "second"), events)
    }
}

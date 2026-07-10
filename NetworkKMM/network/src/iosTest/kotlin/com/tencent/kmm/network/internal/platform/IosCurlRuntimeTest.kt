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
import com.tencent.kmm.network.export.NetworkProgressCallbacks
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkTransferProgress
import com.tencent.kmm.network.export.VBTransportIosCurl
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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IosCurlRuntimeTest {
    @Test
    fun productionEnginePerformsExplicitCaHttpsRequest() = runBlocking {
        val url = runtimeEnvironment("NETWORKKMM_IOS_CURL_RUNTIME_URL") ?: return@runBlocking
        val caPath = runtimeCaPath() ?: return@runBlocking
        VBTransportIosCurl.caInfoPath = caPath
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
            VBTransportIosCurl.caInfoPath = null
        }
    }

    @Test
    fun productionBridgeStreamsHttpsResponseAndSuppressesCallbacksAfterFailure() = runBlocking {
        val url = runtimeEnvironment("NETWORKKMM_IOS_CURL_RUNTIME_URL") ?: return@runBlocking
        val caPath = runtimeCaPath() ?: return@runBlocking
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
        return IosCurlNetworkEngine(IosCurlCInteropBridge) { caPath }
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
        uploadContentLength: Long? = null
    ) = IosCurlNativeRequest(
        requestId = requestId,
        url = url,
        method = method,
        headers = headers,
        timeoutMillis = 30_000,
        uploadContentLength = uploadContentLength,
        caInfoPath = caPath
    )

    private fun runtimeCaPath(): String? =
        runtimeEnvironment("NETWORKKMM_IOS_CURL_RUNTIME_CA_PATH")

    private fun runtimeEnvironment(name: String): String? =
        getenv(name)?.toKString()?.takeIf(String::isNotBlank)
}

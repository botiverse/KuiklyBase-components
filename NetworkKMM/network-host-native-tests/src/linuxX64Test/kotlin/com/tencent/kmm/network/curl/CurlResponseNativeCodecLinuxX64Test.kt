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
package com.tencent.kmm.network.curl

import com.tencent.qqlive.kmm.native.libcurl.CurlResponse
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.memcpy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CurlResponseNativeCodecLinuxX64Test {
    @Test
    fun decodesFullCurlResponseStructRoundTrip() = memScoped {
        val errorText = "TLS failed"
        val headerText = "HTTP/1.1 401 Unauthorized\r\nContent-Type: text/plain\r\n"
        val redirectText = "https://example.com/login"
        val error = errorText.cstr.getPointer(this)
        val headers = headerText.cstr.getPointer(this)
        val redirectUrl = redirectText.cstr.getPointer(this)
        val body = "unauthorized".encodeToByteArray()
        val bodyPtr = allocArray<ByteVar>(body.size)
        body.usePinned { pinned ->
            memcpy(bodyPtr, pinned.addressOf(0), body.size.convert())
        }

        val response = alloc<CurlResponse> {
            code = 0
            httpCode = 401
            errorMsg = error
            errorMsgLen = errorText.length
            this.headers = headers
            headerLen = headerText.length
            this.redirectUrl = redirectUrl
            data = bodyPtr
            dataLen = body.size
            elapse.nameLookupTimeMs = 1.0
            elapse.connectTimeMs = 2.0
            elapse.sslCostTimeMs = 3.0
            elapse.preTransferTime = 4.0
            elapse.startTransferTimeMs = 5.0
            elapse.redirectTime = 6.0
            elapse.recvTime = 7.0
            elapse.totalTimeMs = 8.0
        }

        val decoded = response.toCurlNativeResponseForHostTest()

        assertEquals(0, decoded.code)
        assertEquals(401, decoded.httpCode)
        assertEquals(errorText, decoded.errorMsg)
        assertEquals(headerText, decoded.headers)
        assertEquals(redirectText, decoded.redirectUrl)
        assertContentEquals(body, decoded.data)
        assertEquals(1.0, decoded.elapse.nameLookupTimeMs)
        assertEquals(8.0, decoded.elapse.totalTimeMs)
    }

    @Test
    fun decodesNullEmptyPointersWithoutReadingCString() = memScoped {
        val response = alloc<CurlResponse> {
            code = 0
            httpCode = 204
            errorMsg = null
            errorMsgLen = 0
            headers = null
            headerLen = 0
            redirectUrl = null
            data = null
            dataLen = 0
        }

        val decoded = response.toCurlNativeResponseForHostTest()

        assertEquals(0, decoded.code)
        assertEquals(204, decoded.httpCode)
        assertEquals("", decoded.errorMsg)
        assertEquals("", decoded.headers)
        assertEquals("", decoded.redirectUrl)
        assertNull(decoded.data)
    }
}

private data class HostCurlNativeResponse(
    val code: Int,
    val httpCode: Int,
    val errorMsg: String,
    val headers: String,
    val data: ByteArray?,
    val redirectUrl: String,
    val elapse: HostCurlElapseStats
)

private data class HostCurlElapseStats(
    val nameLookupTimeMs: Double = 0.0,
    val connectTimeMs: Double = 0.0,
    val sslCostTimeMs: Double = 0.0,
    val preTransferTime: Double = 0.0,
    val startTransferTimeMs: Double = 0.0,
    val redirectTime: Double = 0.0,
    val recvTime: Double = 0.0,
    val totalTimeMs: Double = 0.0
)

private fun CurlResponse.toCurlNativeResponseForHostTest(): HostCurlNativeResponse {
    return HostCurlNativeResponse(
        code = code,
        httpCode = httpCode.toInt(),
        errorMsg = errorMsg.toKStringOrEmpty(errorMsgLen),
        headers = headers.toKStringOrEmpty(headerLen),
        redirectUrl = redirectUrl?.toKString().orEmpty(),
        data = data.readBytesOrNull(dataLen),
        elapse = HostCurlElapseStats(
            nameLookupTimeMs = elapse.nameLookupTimeMs,
            connectTimeMs = elapse.connectTimeMs,
            sslCostTimeMs = elapse.sslCostTimeMs,
            preTransferTime = elapse.preTransferTime,
            startTransferTimeMs = elapse.startTransferTimeMs,
            redirectTime = elapse.redirectTime,
            recvTime = elapse.recvTime,
            totalTimeMs = elapse.totalTimeMs
        )
    )
}

private fun CPointer<ByteVar>?.toKStringOrEmpty(length: Int): String {
    return if (this == null || length <= 0) "" else toKString()
}

private fun CPointer<ByteVar>?.readBytesOrNull(length: Int): ByteArray? {
    return this?.let { if (length > 0) it.readBytes(length) else ByteArray(0) }
}

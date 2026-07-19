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
package com.tencent.kmm.network.export

import kotlin.random.Random
import kotlin.time.TimeMark

enum class VBTransportMethod {
    GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS
}

enum class VBTransportContentType(private val description: String) {
    JSON("application/json"),
    BYTE("application/octet-stream");

    override fun toString(): String = description
}

class VBTransportMultipartBody(
    val boundary: String,
    val contentType: String,
    val data: ByteArray
)

class VBTransportMultipartBodyBuilder(
    private val boundary: String = defaultMultipartBoundary()
) {
    private val chunks = mutableListOf<ByteArray>()

    fun addFormField(
        name: String,
        value: String
    ): VBTransportMultipartBodyBuilder {
        return addPart(
            name = name,
            bytes = value.encodeToByteArray()
        )
    }

    fun addFile(
        name: String,
        fileName: String,
        bytes: ByteArray,
        contentType: String = VBTransportContentType.BYTE.toString()
    ): VBTransportMultipartBodyBuilder {
        return addPart(
            name = name,
            bytes = bytes,
            fileName = fileName,
            contentType = contentType
        )
    }

    fun addPart(
        name: String,
        bytes: ByteArray,
        fileName: String? = null,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap()
    ): VBTransportMultipartBodyBuilder {
        chunks.add(
            NetworkMultipartFraming.partPrologue(
                boundary = boundary,
                name = name,
                fileName = fileName,
                contentType = contentType,
                headers = headers
            )
        )
        chunks.add(bytes)
        chunks.add(NetworkMultipartFraming.partEpilogue())
        return this
    }

    fun build(): VBTransportMultipartBody {
        val body = mergeChunks(chunks + NetworkMultipartFraming.terminator(boundary))
        return VBTransportMultipartBody(
            boundary = boundary,
            contentType = "multipart/form-data; boundary=$boundary",
            data = body
        )
    }

    private fun appendString(value: String) {
        chunks.add(value.encodeToByteArray())
    }


    private fun mergeChunks(chunks: List<ByteArray>): ByteArray {
        var totalSize = 0
        chunks.forEach { totalSize += it.size }
        val result = ByteArray(totalSize)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(result, destinationOffset = offset)
            offset += chunk.size
        }
        return result
    }
}

private fun defaultMultipartBoundary(): String =
    "KuiklyNetworkBoundary${Random.nextInt(0, Int.MAX_VALUE)}"

open class VBTransportBaseRequest {
    var requestId: Int = 0
    var header = mutableMapOf<String, String>()
    var logTag: String = ""
    var url: String = ""
    var method: VBTransportMethod = VBTransportMethod.GET
    var quicForceQuic = false
    var totalTimeout: Long = 0L
    // 底层是否使用 libcurl 进行请求
    var useCurl: Boolean = true
    internal var transportElapseStatistics: VBTransportElapseStatistics = VBTransportElapseStatistics()
    /**
     * The public service-entry timestamp for the whole-request timeout.
     *
     * Android owns a hard deadline in the platform transport, but the budget
     * begins before any common-to-platform handoff. Keeping the exact mark on
     * the request prevents dispatcher delay from silently extending it.
     */
    internal var serviceRequestStartMark: TimeMark? = null

    /** Internal curl inputs prepared and latched by the selected engine. */
    internal var curlCaInfoPath: String? = null
    internal var curlProxyUrl: String? = null
    internal var curlHttp3Enabled: Boolean = false
    internal var streamConnectTimeoutMillis: Long = 3_000L
    internal var streamResponseHeadersTimeoutMillis: Long = 30_000L
    internal var streamIdleTimeoutMillis: Long = 60_000L
    internal var streamWholeTimeoutMillis: Long = 0L
    internal var curlBufferedBodyIdleTimeoutMillis: Long = 7_000L
    internal var curlMaxBufferedResponseBytes: Long = 16L * 1024L * 1024L

    internal open fun bodyData(): Any? = null
}

class VBTransportStringRequest : VBTransportBaseRequest() {
    init {
        method = VBTransportMethod.GET
    }
}

class VBTransportBytesRequest : VBTransportBaseRequest() {
    var data: ByteArray = byteArrayOf()

    init {
        method = VBTransportMethod.POST
    }

    internal override fun bodyData(): Any = data
}

class VBTransportPostRequest : VBTransportBaseRequest() {
    lateinit var data: Any

    init {
        method = VBTransportMethod.POST
    }

    fun isDataInitialize(): Boolean = this::data.isInitialized

    internal override fun bodyData(): Any? = if (isDataInitialize()) data else null
}

class VBTransportGetRequest : VBTransportBaseRequest() {
    init {
        method = VBTransportMethod.GET
    }
}

class VBTransportRequest : VBTransportBaseRequest() {
    var data: Any? = null

    internal override fun bodyData(): Any? = data
}

fun VBTransportRequest.setMultipartBody(body: VBTransportMultipartBody) {
    header["Content-Type"] = body.contentType
    data = body.data
}

/**
 * issue #8 slice 2: ONE source of truth for the multipart wire format, shared
 * by the buffered [VBTransportMultipartBodyBuilder] and the streaming
 * multipart writer — the bytes on the wire are identical whichever path a
 * request takes, so servers cannot tell the difference.
 */
internal object NetworkMultipartFraming {

    fun partPrologue(
        boundary: String,
        name: String,
        fileName: String?,
        contentType: String?,
        headers: Map<String, String>
    ): ByteArray {
        val out = StringBuilder()
        out.append("--").append(boundary).append("\r\n")
        out.append("Content-Disposition: form-data; name=\"")
        out.append(sanitizeHeaderValue(name)).append("\"")
        fileName?.let {
            out.append("; filename=\"").append(sanitizeHeaderValue(it)).append("\"")
        }
        out.append("\r\n")
        val partHeaders = headers.toMutableMap()
        contentType?.let {
            if (!partHeaders.keys.any { key -> key.equals("Content-Type", ignoreCase = true) }) {
                partHeaders["Content-Type"] = it
            }
        }
        partHeaders.forEach { (headerName, headerValue) ->
            out.append(sanitizeHeaderName(headerName))
                .append(": ")
                .append(sanitizeHeaderValue(headerValue))
                .append("\r\n")
        }
        out.append("\r\n")
        return out.toString().encodeToByteArray()
    }

    fun partEpilogue(): ByteArray = "\r\n".encodeToByteArray()

    fun terminator(boundary: String): ByteArray = "--$boundary--\r\n".encodeToByteArray()

    private fun sanitizeHeaderValue(value: String): String =
        value.replace("\"", "%22")
            .replace("\r", "")
            .replace("\n", "")

    private fun sanitizeHeaderName(value: String): String =
        value.replace(":", "")
            .replace("\r", "")
            .replace("\n", "")
}

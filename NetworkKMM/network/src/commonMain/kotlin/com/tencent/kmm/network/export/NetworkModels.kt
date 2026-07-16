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

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.math.min
import kotlin.random.Random

data class NetworkQueryParameter(
    val name: String,
    val value: String
)

data class NetworkTransferProgress(
    val bytesTransferred: Long,
    val bytesTotal: Long? = null
) {
    val hasKnownTotal: Boolean
        get() = bytesTotal != null && bytesTotal >= 0
}

class NetworkProgressCallbacks(
    val uploadProgress: ((NetworkTransferProgress) -> Unit)? = null,
    val downloadProgress: ((NetworkTransferProgress) -> Unit)? = null
)

interface NetworkByteStreamSink {
    suspend fun write(bytes: ByteArray)
}

class NetworkByteStream(
    val contentLength: Long? = null,
    private val readAllBlock: suspend () -> ByteArray,
    private val cancelBlock: (() -> Unit)? = null,
    private val attemptCancelBlock: (() -> Unit)? = null,
    private val readChunksBlock: (suspend (NetworkByteStreamSink) -> Unit)? = null
) {
    private val cancelArmed = atomic(true)
    suspend fun readAll(): ByteArray = readAllBlock()

    suspend fun readChunks(sink: NetworkByteStreamSink) {
        val chunkReader = readChunksBlock
        if (chunkReader != null) {
            chunkReader(sink)
        } else {
            sink.write(readAllBlock())
        }
    }

    fun cancel() {
        if (cancelArmed.compareAndSet(expect = true, update = false)) {
            cancelBlock?.invoke()
        }
    }

    internal fun cancelAttempt() {
        (attemptCancelBlock ?: cancelBlock)?.invoke()
    }

    companion object {
        fun fromChunks(
            contentLength: Long? = null,
            cancelBlock: (() -> Unit)? = null,
            attemptCancelBlock: (() -> Unit)? = null,
            readChunksBlock: suspend (NetworkByteStreamSink) -> Unit
        ): NetworkByteStream {
            return NetworkByteStream(
                contentLength = contentLength,
                readAllBlock = {
                    val chunks = mutableListOf<ByteArray>()
                    readChunksBlock(object : NetworkByteStreamSink {
                        override suspend fun write(bytes: ByteArray) {
                            chunks.add(bytes)
                        }
                    })
                    mergeByteArrays(chunks)
                },
                cancelBlock = cancelBlock,
                attemptCancelBlock = attemptCancelBlock,
                readChunksBlock = readChunksBlock
            )
        }
    }
}

sealed class NetworkBody {
    open val contentType: String? = null
    open val contentLength: Long? = null
    open val repeatable: Boolean = true

    object Empty : NetworkBody()

    class Bytes(
        val bytes: ByteArray,
        override val contentType: String = VBTransportContentType.BYTE.toString()
    ) : NetworkBody() {
        override val contentLength: Long = bytes.size.toLong()
    }

    class Text(
        val text: String,
        override val contentType: String = "text/plain; charset=utf-8"
    ) : NetworkBody() {
        override val contentLength: Long = text.encodeToByteArray().size.toLong()
    }

    class Json(
        val json: String
    ) : NetworkBody() {
        override val contentType: String = VBTransportContentType.JSON.toString()
        override val contentLength: Long = json.encodeToByteArray().size.toLong()
    }

    class Form(
        val fields: List<Pair<String, String>>
    ) : NetworkBody() {
        override val contentType: String = "application/x-www-form-urlencoded; charset=utf-8"
    }

    class Multipart(
        val parts: List<NetworkMultipartPart>,
        val boundary: String = defaultNetworkMultipartBoundary()
    ) : NetworkBody() {
        override val contentType: String = "multipart/form-data; boundary=$boundary"
        override val repeatable: Boolean = parts.all { it.body.repeatable }
    }

    class Stream(
        val stream: NetworkByteStream,
        override val contentType: String? = null
    ) : NetworkBody() {
        override val contentLength: Long? = stream.contentLength
        override val repeatable: Boolean = false
    }

    class FileRef(
        val path: String,
        override val contentType: String? = VBTransportContentType.BYTE.toString(),
        override val contentLength: Long? = null,
        private val readAllBlock: (suspend (path: String) -> ByteArray)? = null,
        private val cancelBlock: (() -> Unit)? = null,
        private val openStreamBlock: (suspend (path: String) -> NetworkByteStream)? = null
    ) : NetworkBody() {
        private val cancelArmed = atomic(true)
        override val repeatable: Boolean = readAllBlock != null || openStreamBlock != null

        suspend fun readAll(): ByteArray? = readAllBlock?.invoke(path)

        suspend fun openStream(): NetworkByteStream? = openStreamBlock?.invoke(path)

        fun cancel() {
            if (cancelArmed.compareAndSet(expect = true, update = false)) {
                cancelBlock?.invoke()
            }
        }
    }
}

class NetworkMultipartPart(
    val name: String,
    val body: NetworkBody,
    val fileName: String? = null,
    val headers: Map<String, String> = emptyMap()
)

class NetworkRequest(
    var method: VBTransportMethod = VBTransportMethod.GET,
    var url: String = "",
    var path: String = "",
    val query: MutableList<NetworkQueryParameter> = mutableListOf(),
    val headers: MutableMap<String, String> = mutableMapOf(),
    var body: NetworkBody = NetworkBody.Empty,
    val metadata: MutableMap<String, String> = mutableMapOf(),
    var policy: NetworkRequestPolicy = NetworkRequestPolicy(),
    var progress: NetworkProgressCallbacks = NetworkProgressCallbacks()
) {
    fun addQuery(name: String, value: String): NetworkRequest {
        query.add(NetworkQueryParameter(name, value))
        return this
    }

    fun setHeader(name: String, value: String): NetworkRequest {
        headers[name] = value
        return this
    }

    fun resolvedUrl(): String {
        val base = when {
            url.isNotBlank() && path.isNotBlank() -> appendPath(url, path)
            url.isNotBlank() -> url
            else -> path
        }
        if (query.isEmpty()) {
            return base
        }
        val separator = if (base.contains("?")) "&" else "?"
        return base + separator + query.joinToString("&") {
            "${encodeUrlComponent(it.name)}=${encodeUrlComponent(it.value)}"
        }
    }

    fun copyMutable(): NetworkRequest {
        return NetworkRequest(
            method = method,
            url = url,
            path = path,
            query = query.toMutableList(),
            headers = headers.toMutableMap(),
            body = body,
            metadata = metadata.toMutableMap(),
            policy = policy.copyMutable(),
            progress = progress
        )
    }
}

class NetworkResponseBody(
    val bytes: ByteArray? = null,
    val stream: NetworkByteStream? = null
) {
    fun text(): String? = bytes?.decodeToString()

    fun <T> decodeBytes(decoder: (ByteArray) -> T): T? = bytes?.let(decoder)

    fun <T> decodeText(decoder: (String) -> T): T? = text()?.let(decoder)

    fun cancelStream() {
        stream?.cancel()
    }
}

class NetworkResponse(
    val request: NetworkRequest,
    val statusCode: Int?,
    val headers: Map<String, List<String>>,
    val body: NetworkResponseBody,
    val error: NetworkError? = null,
    val rawResponse: VBTransportBaseResponse? = null,
    val timing: VBTransportElapseStatistics = VBTransportElapseStatistics(),
    /** Actual negotiated wire protocol when the selected engine can report it. */
    val protocol: NetworkHttpProtocol = NetworkHttpProtocol.UNKNOWN
) {
    val isSuccess: Boolean = error == null && (statusCode == null || statusCode < 400)
}

enum class NetworkHttpProtocol {
    UNKNOWN,
    HTTP_1_0,
    HTTP_1_1,
    HTTP_2,
    HTTP_3
}

enum class NetworkErrorKind {
    CANCELLED,
    TIMEOUT,
    DNS,
    CONNECT,
    TLS,
    HTTP_STATUS,
    DECODE,
    AUTH,
    UNKNOWN
}

class NetworkError(
    val kind: NetworkErrorKind,
    val message: String,
    val statusCode: Int? = null,
    val cause: Throwable? = null,
    val rawCode: Int? = null
)

enum class NetworkEngineFeatureReason {
    AVAILABLE,
    NOT_DECLARED,
    CONFIGURATION_REQUIRED,
    PLATFORM_RESOLVER_REQUIRED,
    PAC_UNSUPPORTED,
    NOT_IMPLEMENTED,
    NATIVE_BACKEND_NOT_COMPILED,
    RUNTIME_UNAVAILABLE
}

data class NetworkEngineFeatureStatus(
    val compiledIn: Boolean,
    val runtimeAvailable: Boolean,
    val rolloutEligible: Boolean,
    val reason: NetworkEngineFeatureReason,
    val detail: String? = null
) {
    companion object {
        fun available(detail: String? = null): NetworkEngineFeatureStatus =
            NetworkEngineFeatureStatus(
                compiledIn = true,
                runtimeAvailable = true,
                rolloutEligible = true,
                reason = NetworkEngineFeatureReason.AVAILABLE,
                detail = detail
            )

        fun unavailable(
            reason: NetworkEngineFeatureReason = NetworkEngineFeatureReason.NOT_DECLARED,
            compiledIn: Boolean = false,
            runtimeAvailable: Boolean = false,
            detail: String? = null
        ): NetworkEngineFeatureStatus = NetworkEngineFeatureStatus(
            compiledIn = compiledIn,
            runtimeAvailable = runtimeAvailable,
            rolloutEligible = false,
            reason = reason,
            detail = detail
        )
    }
}

data class NetworkEngineCapabilities(
    val requestBodyStreaming: Boolean = false,
    val responseBodyStreaming: Boolean = false,
    val multipartStreaming: Boolean = false,
    val uploadProgress: Boolean = false,
    val downloadProgress: Boolean = false,
    val appOwnedTrustStore: NetworkEngineFeatureStatus = NetworkEngineFeatureStatus.unavailable(),
    val manualProxy: NetworkEngineFeatureStatus = NetworkEngineFeatureStatus.unavailable(),
    val pacProxy: NetworkEngineFeatureStatus = NetworkEngineFeatureStatus.unavailable(),
    val httpDns: NetworkEngineFeatureStatus = NetworkEngineFeatureStatus.unavailable(),
    val http3: NetworkEngineFeatureStatus = NetworkEngineFeatureStatus.unavailable()
)

enum class NetworkPriority {
    LOW,
    NORMAL,
    HIGH
}

enum class NetworkDispatcher {
    IO,
    DEFAULT
}

data class NetworkBackoffPolicy(
    val initialDelayMillis: Long = 300,
    val maxDelayMillis: Long = 5_000,
    val multiplier: Double = 2.0,
    val jitterRatio: Double = 0.0
) {
    fun delayForAttempt(attempt: Int): Long {
        var delay = initialDelayMillis.toDouble()
        repeat(attempt) {
            delay *= multiplier
        }
        val capped = min(delay, maxDelayMillis.toDouble())
        if (jitterRatio <= 0.0) {
            return capped.toLong()
        }
        val jitter = capped * jitterRatio
        return (capped - jitter + Random.nextDouble() * jitter * 2).toLong()
    }
}

data class NetworkRetryPolicy(
    val maxRetries: Int = 0,
    val retryStatusCodes: Set<Int> = setOf(408, 429, 500, 502, 503, 504),
    val retryErrorKinds: Set<NetworkErrorKind> = setOf(
        NetworkErrorKind.TIMEOUT,
        NetworkErrorKind.DNS,
        NetworkErrorKind.CONNECT,
        NetworkErrorKind.TLS,
        NetworkErrorKind.UNKNOWN
    ),
    val backoff: NetworkBackoffPolicy = NetworkBackoffPolicy()
) {
    fun shouldRetry(response: NetworkResponse): Boolean {
        val status = response.statusCode
        if (status != null && status in retryStatusCodes) {
            return true
        }
        val errorKind = response.error?.kind
        return errorKind != null && errorKind in retryErrorKinds
    }
}

data class NetworkRequestPolicy(
    val timeoutMillis: Long = 0,
    val retry: NetworkRetryPolicy = NetworkRetryPolicy(),
    val priority: NetworkPriority = NetworkPriority.NORMAL,
    val dispatcher: NetworkDispatcher = NetworkDispatcher.IO,
    /**
     * Streaming responses are not bounded by [timeoutMillis] as a whole by
     * default: a healthy large download may legitimately outlive it. These
     * phase deadlines preserve fast failure without killing active progress.
     */
    val streamTimeouts: NetworkStreamTimeoutPolicy = NetworkStreamTimeoutPolicy()
) {
    fun copyMutable(): NetworkRequestPolicy {
        return NetworkRequestPolicy(
            timeoutMillis = timeoutMillis,
            retry = retry,
            priority = priority,
            dispatcher = dispatcher,
            streamTimeouts = streamTimeouts
        )
    }
}

data class NetworkStreamTimeoutPolicy(
    /** DNS, socket, proxy tunnel and TLS establishment budget. */
    val connectTimeoutMillis: Long = 3_000,
    /**
     * Final origin response-header budget. Curl starts this phase after each
     * connection/proxy/TLS pre-transfer point. Ktor transports enforce the
     * conservative combined upper bound `connect + responseHeaders` because
     * their public engine API does not expose a portable connection-ready event.
     */
    val responseHeadersTimeoutMillis: Long = 30_000,
    val interChunkIdleTimeoutMillis: Long = 60_000,
    /** Zero disables a whole-transfer deadline. */
    val wholeTransferTimeoutMillis: Long = 0
)

internal class NetworkBodyBytes(
    val bytes: ByteArray?,
    val contentType: String?,
    val error: NetworkError? = null
)

internal suspend fun NetworkBody.toBytes(
    progress: ((NetworkTransferProgress) -> Unit)? = null
): NetworkBodyBytes {
    return when (this) {
        NetworkBody.Empty -> NetworkBodyBytes(null, null)
        is NetworkBody.Bytes -> bytes.toBodyBytes(contentType, progress)
        is NetworkBody.Text -> text.encodeToByteArray().toBodyBytes(contentType, progress)
        is NetworkBody.Json -> json.encodeToByteArray().toBodyBytes(contentType, progress)
        is NetworkBody.Form -> NetworkBodyBytes(
            fields.joinToString("&") {
                "${encodeUrlComponent(it.first)}=${encodeUrlComponent(it.second)}"
            }.encodeToByteArray().also { bytes ->
                progress?.invoke(NetworkTransferProgress(bytes.size.toLong(), bytes.size.toLong()))
            },
            contentType
        )
        is NetworkBody.Multipart -> multipartBodyBytes(this, progress)
        is NetworkBody.Stream -> stream.readAllWithProgress(progress).toBodyBytes(contentType)
        is NetworkBody.FileRef -> {
            val bytes = readAll() ?: openStream()?.readAllWithProgress(progress)
            if (bytes == null) {
                NetworkBodyBytes(
                    null,
                    contentType,
                    NetworkError(
                        kind = NetworkErrorKind.UNKNOWN,
                        message = "FileRef body requires a readAllBlock or openStreamBlock on this engine."
                    )
                )
            } else {
                bytes.toBodyBytes(contentType, progress)
            }
        }
    }
}

internal fun NetworkBody.cancel() {
    when (this) {
        is NetworkBody.Stream -> runCatching { stream.cancel() }
        is NetworkBody.FileRef -> runCatching { this.cancel() }
        is NetworkBody.Multipart -> parts.forEach { part ->
            runCatching { part.body.cancel() }
        }
        else -> Unit
    }
}

private suspend fun multipartBodyBytes(
    body: NetworkBody.Multipart,
    progress: ((NetworkTransferProgress) -> Unit)? = null
): NetworkBodyBytes {
    val builder = VBTransportMultipartBodyBuilder(body.boundary)
    body.parts.forEach { part ->
        val bodyBytes = part.body.toBytes(progress)
        bodyBytes.error?.let {
            return NetworkBodyBytes(null, body.contentType, it)
        }
        builder.addPart(
            name = part.name,
            bytes = bodyBytes.bytes ?: ByteArray(0),
            fileName = part.fileName,
            contentType = bodyBytes.contentType,
            headers = part.headers
        )
    }
    val multipartBody = builder.build()
    return NetworkBodyBytes(multipartBody.data, multipartBody.contentType)
}

private fun ByteArray.toBodyBytes(
    contentType: String?,
    progress: ((NetworkTransferProgress) -> Unit)? = null
): NetworkBodyBytes {
    progress?.invoke(NetworkTransferProgress(size.toLong(), size.toLong()))
    return NetworkBodyBytes(this, contentType)
}

private suspend fun NetworkByteStream.readAllWithProgress(
    progress: ((NetworkTransferProgress) -> Unit)? = null
): ByteArray {
    val chunks = mutableListOf<ByteArray>()
    var transferred = 0L
    readChunks(object : NetworkByteStreamSink {
        override suspend fun write(bytes: ByteArray) {
            chunks.add(bytes)
            transferred += bytes.size
            progress?.invoke(NetworkTransferProgress(transferred, contentLength))
        }
    })
    return mergeByteArrays(chunks)
}

private fun mergeByteArrays(chunks: List<ByteArray>): ByteArray {
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

private fun appendPath(url: String, path: String): String {
    val trimmedUrl = url.trimEnd('/')
    val trimmedPath = path.trimStart('/')
    return "$trimmedUrl/$trimmedPath"
}

private fun defaultNetworkMultipartBoundary(): String =
    "KuiklyNetworkBoundary${Random.nextInt(0, Int.MAX_VALUE)}"

private fun encodeUrlComponent(value: String): String {
    val bytes = value.encodeToByteArray()
    val builder = StringBuilder()
    bytes.forEach { byte ->
        val intValue = byte.toInt() and 0xff
        val char = intValue.toChar()
        if (char in 'A'..'Z' ||
            char in 'a'..'z' ||
            char in '0'..'9' ||
            char == '-' ||
            char == '_' ||
            char == '.' ||
            char == '~'
        ) {
            builder.append(char)
        } else {
            builder.append('%')
            builder.append(intValue.toString(16).uppercase().padStart(2, '0'))
        }
    }
    return builder.toString()
}

/**
 * issue #8 slice 2: a Multipart body containing at least one Stream/FileRef
 * part streams out as a composite — framing bytes from
 * [NetworkMultipartFraming] (identical to the buffered builder's wire format)
 * interleaved with part bodies pulled chunk-by-chunk. Small scalar parts
 * (Bytes/Text/Json/Form) are encoded up front; only Stream/FileRef parts are
 * pulled lazily. Returns null when no part streams — all-scalar multiparts
 * keep the battle-tested buffered path (raft.8 multer compatibility).
 *
 * The composite's contentLength is exact when every streaming part declares
 * one (true Content-Length upload); any unknown part length makes the whole
 * body chunked.
 */
internal suspend fun NetworkBody.Multipart.streamingUploadStreamOrNull(): NetworkByteStream? {
    val hasStreamingPart = parts.any { it.body is NetworkBody.Stream || it.body is NetworkBody.FileRef }
    if (!hasStreamingPart) return null

    class PartPlan(
        val prologue: ByteArray,
        val scalarBytes: ByteArray?,
        val streamBody: NetworkBody?,
        val bodyLength: Long?
    )

    val plans = parts.map { part ->
        val prologue = NetworkMultipartFraming.partPrologue(
            boundary = boundary,
            name = part.name,
            fileName = part.fileName,
            contentType = part.body.contentType,
            headers = part.headers
        )
        when (val body = part.body) {
            is NetworkBody.Stream ->
                PartPlan(prologue, null, body, body.stream.contentLength)
            is NetworkBody.FileRef ->
                PartPlan(prologue, null, body, body.contentLength)
            else -> {
                val bytes = body.toBytes(null)
                // A scalar part that fails to encode must not invent a second
                // error contract on the streaming path: fall back to the
                // buffered path, whose toBytes error handling classifies the
                // failure into a NetworkResponse.error like every other body.
                if (bytes.error != null) return null
                PartPlan(prologue, bytes.bytes ?: ByteArray(0), null, (bytes.bytes ?: ByteArray(0)).size.toLong())
            }
        }
    }
    val epilogue = NetworkMultipartFraming.partEpilogue()
    val terminator = NetworkMultipartFraming.terminator(boundary)
    val openedStreamLock = SynchronizedObject()
    val openedStreams = mutableListOf<NetworkByteStream>()
    var compositeCancelled = false
    var attemptSequence = 0L
    var activeAttempt = 0L
    var cancelledAttempt = 0L
    val totalLength: Long? =
        if (plans.all { it.bodyLength != null }) {
            plans.sumOf { it.prologue.size.toLong() + it.bodyLength!! + epilogue.size } + terminator.size
        } else {
            null
        }

    return NetworkByteStream.fromChunks(
        contentLength = totalLength,
        attemptCancelBlock = {
            val derived = synchronized(openedStreamLock) {
                cancelledAttempt = when {
                    activeAttempt != 0L -> activeAttempt
                    attemptSequence == 0L -> 1L
                    else -> attemptSequence
                }
                openedStreams.toList().also { openedStreams.clear() }
            }
            plans.mapNotNull { it.streamBody as? NetworkBody.Stream }.forEach {
                runCatching { it.cancel() }
            }
            derived.forEach { runCatching { it.cancel() } }
        },
        cancelBlock = {
            val derived = synchronized(openedStreamLock) {
                compositeCancelled = true
                openedStreams.toList().also { openedStreams.clear() }
            }
            parts.forEach {
                try {
                    it.body.cancel()
                } catch (_: Throwable) {
                    // One broken owner must not strand the remaining parts.
                }
            }
            derived.forEach {
                try {
                    it.cancel()
                } catch (_: Throwable) {
                    // Continue cancelling every lazily opened derived stream.
                }
            }
        }
    ) { sink ->
        val attemptId = synchronized(openedStreamLock) {
            (++attemptSequence).also { activeAttempt = it }
        }
        fun attemptActive(): Boolean = synchronized(openedStreamLock) {
            !compositeCancelled && activeAttempt == attemptId && cancelledAttempt != attemptId
        }
        val guardedSink = object : NetworkByteStreamSink {
            override suspend fun write(bytes: ByteArray) {
                if (attemptActive()) sink.write(bytes)
            }
        }
        try {
            plans.forEach { plan ->
                if (!attemptActive()) return@fromChunks
                guardedSink.write(plan.prologue)
                if (!attemptActive()) return@fromChunks
                when (val body = plan.streamBody) {
                    is NetworkBody.Stream -> {
                        body.stream.readChunks(guardedSink)
                        if (!attemptActive()) return@fromChunks
                    }
                    is NetworkBody.FileRef -> {
                        if (!attemptActive()) return@fromChunks
                        val stream = body.openStream()
                        if (stream != null) {
                            val cancelNow = synchronized(openedStreamLock) {
                                if (
                                    compositeCancelled ||
                                    activeAttempt != attemptId ||
                                    cancelledAttempt == attemptId
                                ) true
                                else {
                                    openedStreams += stream
                                    false
                                }
                            }
                            if (cancelNow) {
                                runCatching { stream.cancel() }
                                return@fromChunks
                            } else {
                                try {
                                    stream.readChunks(guardedSink)
                                } catch (throwable: Throwable) {
                                    runCatching { stream.cancel() }
                                    throw throwable
                                } finally {
                                    synchronized(openedStreamLock) {
                                        openedStreams.removeAll { it === stream }
                                    }
                                }
                                if (!attemptActive()) return@fromChunks
                            }
                        } else {
                            if (!attemptActive()) return@fromChunks
                            val bytes = body.readAll()
                                ?: throw IllegalStateException(
                                    "FileRef part requires a readAllBlock or openStreamBlock on this engine."
                                )
                            if (!attemptActive()) return@fromChunks
                            guardedSink.write(bytes)
                        }
                    }
                    else -> plan.scalarBytes?.let { if (it.isNotEmpty()) guardedSink.write(it) }
                }
                if (!attemptActive()) return@fromChunks
                guardedSink.write(epilogue)
            }
            if (!attemptActive()) return@fromChunks
            guardedSink.write(terminator)
        } finally {
            synchronized(openedStreamLock) {
                if (activeAttempt == attemptId) activeAttempt = 0L
            }
        }
    }
}

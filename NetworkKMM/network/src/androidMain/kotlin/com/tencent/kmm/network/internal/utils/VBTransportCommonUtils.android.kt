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
package com.tencent.kmm.network.internal.utils

import com.tencent.kmm.network.export.VBTransportAndroidEngine
import com.tencent.kmm.network.export.VBTransportBaseRequest
import com.tencent.kmm.network.export.VBTransportReusedHttp2Recovery
import com.tencent.kmm.network.export.VBTransportMethod
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.ArrayDeque

private const val ORIGIN_ROLLOVER_WINDOW_MILLIS = 30_000L

actual suspend fun readKnownSize(
    channel: ByteReadChannelWrapper,
    contentLength: Long
): ByteArray = channel.readAvailable(contentLength)

actual suspend fun readUnknownSize(channel: ByteReadChannelWrapper) =
    channel.readBytes(DEFAULT_BUFFER_SIZE.toLong())

actual fun ByteArray.mergeFromChunks(chunks: List<ByteArray>) {
    var offset = 0
    chunks.forEach { chunk ->
        System.arraycopy(
            chunk,       // 源数组
            0,          // 源起始位置
            this,       // 目标数组
            offset,     // 目标起始位置
            chunk.size  // 拷贝长度
        )
        offset += chunk.size
    }
}

// One client per process: connection pooling, TLS session reuse, and no
// per-request engine construction. Timeouts are applied per request through
// the HttpTimeout plugin (see IVBTransportService triggerRequest); Ktor's
// OkHttp engine honours them by deriving per-timeout clients via newBuilder(),
// which preserves the fastFallback setting.
private val sharedHttpClient: HttpClient by lazy {
    buildTransportHttpClient(VBTransportAndroidEngine.okHttpEnabled)
}

internal fun buildTransportHttpClient(
    okHttpEnabled: Boolean,
    recovery: VBTransportReusedHttp2Recovery = VBTransportReusedHttp2Recovery(),
): HttpClient =
    if (okHttpEnabled) {
        HttpClient(OkHttp) {
            install(HttpTimeout)
            engine {
                config { applyTransportOkHttpDefaults(recovery.pingIntervalMillis) }
            }
        }
    } else {
        HttpClient(Android) {
            install(HttpTimeout)
        }
    }

// fastFallback = RFC 8305 Happy Eyeballs racing. Explicit rather than relying
// on the OkHttp 5.x default, so the intent survives dependency bumps.
// maxRequestsPerHost lifts OkHttp's default of 5. A single origin (the app API
// edge) carries every request, so the default throttles the app to 5 concurrent
// calls per host — and because the edge negotiates HTTP/2, those calls multiplex
// on ONE connection, so the Dispatcher permit cap was strangling h2 rather than
// protecting connections. task #586 tracing measured this on a signed-in device:
// the foreground Thread cold burst fires ~10 concurrent requests, the 6th–10th
// stalled 212–752ms purely in Dispatcher permit wait (dispatcherQueueMs), all
// h2/reusedConnection=true with DNS/connect/TLS = 0. 16 covers the observed peak
// (~10) with headroom while keeping the worst case bounded if a connection ever
// degrades to HTTP/1.1 (where each concurrent call would be its own socket/TLS).
// Only the per-host cap changes; the global Dispatcher.maxRequests stays at
// OkHttp's default 64, so h1 degradation / multi-host traffic cannot expand the
// overall concurrency ceiling.
internal fun OkHttpClient.Builder.applyTransportOkHttpDefaults(
    pingIntervalMillis: Long = 0L,
): OkHttpClient.Builder =
    fastFallback(true)
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 })
        .pingInterval(pingIntervalMillis, TimeUnit.MILLISECONDS)
        .eventListenerFactory(AndroidTransportPhaseTracer.eventListenerFactory())
        .addInterceptor { chain ->
            val request = chain.request()
            request.header(NETWORK_KMM_TRACE_HEADER)?.toIntOrNull()?.let(
                AndroidTransportPhaseTracer::dispatcherStarted
            )
            chain.proceed(request.newBuilder().removeHeader(NETWORK_KMM_TRACE_HEADER).build())
        }

actual fun getHttpClient(kmmRequest: VBTransportBaseRequest): Any? = sharedHttpClient

/** A request-scoped hold on one origin/client-pool generation. */
internal class AndroidTransportClientLease internal constructor(
    val client: HttpClient,
    val origin: String,
    val shard: Int,
    val generation: Long,
    private val onRelease: () -> Unit,
    private val onDrain: () -> AndroidTransportGenerationRollover,
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    fun drainGeneration(): AndroidTransportGenerationRollover = onDrain()

    override fun close() {
        if (released.compareAndSet(false, true)) onRelease()
    }
}

internal data class AndroidTransportGenerationRollover(
    val generation: Long,
    /** True only for the first caller that retired the observed generation. */
    val initiated: Boolean,
    /** True when another caller already retired the observed generation. */
    val observedGenerationDraining: Boolean = initiated,
    /** True when the per-origin churn breaker suppressed another new client. */
    val rateLimited: Boolean = false,
)

/** Per-logical-request replay safety gate; retries are sequential, never hedged. */
internal class AndroidReusedH2RetryState(method: VBTransportMethod) {
    private val retryable = method == VBTransportMethod.GET || method == VBTransportMethod.HEAD
    var attempted: Boolean = false
        private set

    fun claimRetry(watchdogTriggered: Boolean, hasBudget: Boolean): Boolean {
        if (!watchdogTriggered || !hasBudget || !retryable || attempted) return false
        attempted = true
        return true
    }
}

/**
 * Owns independently pooled OkHttp/Ktor client slots per origin while recovery
 * is enabled. Slots are created lazily and selected round-robin. A rollover
 * swaps only the affected slot's generation atomically; the old client closes
 * after every in-flight lease releases, so replay-unsafe writes finish naturally.
 */
internal class AndroidTransportClientGenerationManager(
    private val clientFactory: (VBTransportReusedHttp2Recovery) -> HttpClient = {
        buildTransportHttpClient(okHttpEnabled = true, recovery = it)
    },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val origins = mutableMapOf<String, OriginState>()
    private var nextGeneration = 1L

    fun acquire(
        url: String,
        recovery: VBTransportReusedHttp2Recovery,
        avoidShard: Int? = null,
    ): AndroidTransportClientLease {
        val origin = transportOrigin(url)
        val (shardIndex, generation) = synchronized(lock) {
            val state = origins.getOrPut(origin) {
                OriginState(recovery)
            }
            if (state.recovery != recovery) {
                resetConfigurationLocked(state, recovery)
            }
            val selectedShard = state.selectShard(avoidShard)
            val shard = state.shards[selectedShard] ?: ShardState(newGeneration(recovery)).also {
                state.shards[selectedShard] = it
            }
            selectedShard to shard.current.also { it.inFlight += 1 }
        }
        return AndroidTransportClientLease(
            client = generation.client,
            origin = origin,
            shard = shardIndex,
            generation = generation.id,
            onRelease = { release(origin, generation) },
            onDrain = { drain(origin, shardIndex, generation.id, recovery) },
        )
    }

    private fun drain(
        origin: String,
        shardIndex: Int,
        observedGeneration: Long,
        recovery: VBTransportReusedHttp2Recovery,
    ): AndroidTransportGenerationRollover = synchronized(lock) {
        val state = origins.getValue(origin)
        val shard = state.shards.getOrNull(shardIndex)
        if (shard == null || shard.current.id != observedGeneration) {
            return@synchronized AndroidTransportGenerationRollover(
                generation = shard?.current?.id ?: observedGeneration,
                initiated = false,
                observedGenerationDraining = true,
            )
        }
        val now = nowMillis()
        while (
            state.rolloverTimes.isNotEmpty() &&
            now - state.rolloverTimes.first() >= ORIGIN_ROLLOVER_WINDOW_MILLIS
        ) {
            state.rolloverTimes.removeFirst()
        }
        if (state.rolloverTimes.size >= recovery.clientShardCount) {
            shard.quarantined = true
            return@synchronized AndroidTransportGenerationRollover(
                generation = shard.current.id,
                initiated = false,
                observedGenerationDraining = false,
                rateLimited = true,
            )
        }
        state.rolloverTimes.addLast(now)
        retireCurrentLocked(state, shard, recovery)
        AndroidTransportGenerationRollover(
            generation = shard.current.id,
            initiated = true,
        )
    }

    private fun retireCurrentLocked(
        state: OriginState,
        shard: ShardState,
        recovery: VBTransportReusedHttp2Recovery,
    ) {
        val retired = shard.current
        retired.draining = true
        state.retired += retired
        shard.current = newGeneration(recovery)
        shard.quarantined = false
        closeIfDrainedLocked(state, retired)
    }

    private fun resetConfigurationLocked(
        state: OriginState,
        recovery: VBTransportReusedHttp2Recovery,
    ) {
        state.shards.filterNotNull().forEach { shard ->
            val retired = shard.current
            retired.draining = true
            state.retired += retired
            closeIfDrainedLocked(state, retired)
        }
        state.recovery = recovery
        state.shards = MutableList(recovery.clientShardCount) { null }
        state.nextShard = 0
        state.rolloverTimes.clear()
    }

    private fun release(origin: String, generation: Generation) {
        synchronized(lock) {
            generation.inFlight -= 1
            check(generation.inFlight >= 0) { "negative generation lease count" }
            val state = origins[origin] ?: return
            closeIfDrainedLocked(state, generation)
        }
    }

    private fun closeIfDrainedLocked(state: OriginState, generation: Generation) {
        if (generation.draining && generation.inFlight == 0) {
            state.retired.remove(generation)
            generation.client.close()
        }
    }

    private fun newGeneration(recovery: VBTransportReusedHttp2Recovery): Generation =
        Generation(
            id = nextGeneration++,
            client = clientFactory(recovery),
            recovery = recovery,
        )

    private class OriginState(initialRecovery: VBTransportReusedHttp2Recovery) {
        var recovery: VBTransportReusedHttp2Recovery = initialRecovery
        var shards: MutableList<ShardState?> = MutableList(initialRecovery.clientShardCount) { null }
        var nextShard: Int = 0
        val retired = mutableSetOf<Generation>()
        val rolloverTimes = ArrayDeque<Long>()

        fun selectShard(avoidShard: Int?): Int {
            // Prefer healthy/uninitialized slots. A rate-limited stale slot is
            // quarantined from new work while any alternative remains.
            repeat(shards.size) {
                val candidate = nextShard
                nextShard = (nextShard + 1) % shards.size
                val avoided = shards.size > 1 && candidate == avoidShard
                val quarantined = shards[candidate]?.quarantined == true
                if (!avoided && !quarantined) return candidate
            }
            // All alternatives are quarantined. Keep traffic bounded to the
            // configured slots rather than creating another client.
            repeat(shards.size) {
                val candidate = nextShard
                nextShard = (nextShard + 1) % shards.size
                if (shards.size == 1 || candidate != avoidShard) return candidate
            }
            return 0
        }
    }

    private class ShardState(
        var current: Generation,
        var quarantined: Boolean = false,
    )

    private class Generation(
        val id: Long,
        val client: HttpClient,
        val recovery: VBTransportReusedHttp2Recovery,
        var inFlight: Int = 0,
        var draining: Boolean = false,
    )
}

internal object AndroidTransportClientProvider {
    private val generationManager = AndroidTransportClientGenerationManager()

    fun acquire(
        request: VBTransportBaseRequest,
        okHttpEnabled: Boolean = VBTransportAndroidEngine.okHttpEnabled,
        recovery: VBTransportReusedHttp2Recovery = VBTransportAndroidEngine.reusedHttp2Recovery,
        avoidShard: Int? = null,
    ): AndroidTransportClientLease {
        if (!okHttpEnabled || !recovery.enabled) {
            return AndroidTransportClientLease(
                client = sharedHttpClient,
                origin = transportOrigin(request.url),
                shard = 0,
                generation = 0L,
                onRelease = {},
                onDrain = { AndroidTransportGenerationRollover(0L, initiated = false) },
            )
        }
        return generationManager.acquire(request.url, recovery, avoidShard)
    }
}

internal fun transportOrigin(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: return "invalid-origin"
    val diagnosticHost = if (':' in parsed.host) "[${parsed.host}]" else parsed.host
    return "${parsed.scheme}://$diagnosticHost:${parsed.port}"
}

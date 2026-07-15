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

@Volatile
private var transportHttpClientFactoryForTests:
    ((VBTransportReusedHttp2Recovery) -> HttpClient)? = null

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
internal class AndroidReusedH2RetryState(
    method: VBTransportMethod,
    hasReplayUnsafeBody: Boolean = false,
) {
    val canFreshRetry: Boolean =
        (method == VBTransportMethod.GET || method == VBTransportMethod.HEAD) &&
            !hasReplayUnsafeBody
    var attempted: Boolean = false
        private set

    fun claimRetry(watchdogTriggered: Boolean, hasBudget: Boolean): Boolean {
        if (!watchdogTriggered || !hasBudget || !canFreshRetry || attempted) return false
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
        transportHttpClientFactoryForTests?.invoke(it)
            ?: buildTransportHttpClient(okHttpEnabled = true, recovery = it)
    },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val origins = mutableMapOf<String, OriginState>()
    private val openGenerations = mutableSetOf<Generation>()
    private var nextGeneration = 1L
    private var activeEpoch = 1L

    fun activateConfiguration(epoch: Long) {
        val pendingClose = mutableListOf<HttpClient>()
        synchronized(lock) {
            if (epoch <= activeEpoch) return
            origins.values.forEach { deactivateStateLocked(it, pendingClose) }
            origins.clear()
            activeEpoch = epoch
        }
        pendingClose.forEach(HttpClient::close)
    }

    fun acquire(
        url: String,
        recovery: VBTransportReusedHttp2Recovery,
        avoidShard: Int? = null,
        epoch: Long = activeEpoch,
    ): AndroidTransportClientLease {
        val origin = transportOrigin(url)
        val acquired = synchronized(lock) {
            check(epoch == activeEpoch) { "stale Android transport configuration epoch" }
            val state = origins.getOrPut(origin) {
                OriginState(recovery, epoch)
            }
            val selectedShard = state.selectShard(avoidShard, nowMillis())
            val shard = state.shards[selectedShard] ?: ShardState(newGeneration(recovery)).also {
                state.shards[selectedShard] = it
            }
            Triple(state, selectedShard, shard.current.also { it.inFlight += 1 })
        }
        val state = acquired.first
        val shardIndex = acquired.second
        val generation = acquired.third
        return AndroidTransportClientLease(
            client = generation.client,
            origin = origin,
            shard = shardIndex,
            generation = generation.id,
            onRelease = { release(state, generation) },
            onDrain = { drain(state, epoch, shardIndex, generation.id, recovery) },
        )
    }

    private fun drain(
        state: OriginState,
        epoch: Long,
        shardIndex: Int,
        observedGeneration: Long,
        recovery: VBTransportReusedHttp2Recovery,
    ): AndroidTransportGenerationRollover {
        var pendingClose: HttpClient? = null
        val rollover = synchronized(lock) {
            if (!state.active || epoch != activeEpoch) {
                return@synchronized AndroidTransportGenerationRollover(
                    generation = observedGeneration,
                    initiated = false,
                    observedGenerationDraining = true,
                )
            }
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
                shard.quarantinedAtMillis = now
                return@synchronized AndroidTransportGenerationRollover(
                    generation = shard.current.id,
                    initiated = false,
                    observedGenerationDraining = false,
                    rateLimited = true,
                )
            }
            state.rolloverTimes.addLast(now)
            pendingClose = retireCurrentLocked(state, shard, recovery)
            AndroidTransportGenerationRollover(
                generation = shard.current.id,
                initiated = true,
            )
        }
        pendingClose?.close()
        return rollover
    }

    private fun retireCurrentLocked(
        state: OriginState,
        shard: ShardState,
        recovery: VBTransportReusedHttp2Recovery,
    ): HttpClient? {
        val retired = shard.current
        retired.draining = true
        state.retired += retired
        shard.current = newGeneration(recovery)
        shard.quarantinedAtMillis = null
        return detachIfDrainedLocked(state, retired)
    }

    private fun deactivateStateLocked(
        state: OriginState,
        pendingClose: MutableList<HttpClient>,
    ) {
        state.active = false
        state.shards.filterNotNull().forEach { shard ->
            val retired = shard.current
            retired.draining = true
            state.retired += retired
            detachIfDrainedLocked(state, retired)?.let(pendingClose::add)
        }
    }

    private fun release(state: OriginState, generation: Generation) {
        val pendingClose = synchronized(lock) {
            generation.inFlight -= 1
            check(generation.inFlight >= 0) { "negative generation lease count" }
            detachIfDrainedLocked(state, generation)
        }
        pendingClose?.close()
    }

    // Detach only under the lock; callers close the returned client AFTER
    // releasing it. HttpClient.close() shuts down the OkHttp dispatcher and
    // evicts pooled sockets — doing that inside the manager lock would stall
    // every concurrent acquire/release/drain behind socket teardown.
    private fun detachIfDrainedLocked(state: OriginState, generation: Generation): HttpClient? {
        if (generation.draining && generation.inFlight == 0) {
            state.retired.remove(generation)
            openGenerations.remove(generation)
            return generation.client
        }
        return null
    }

    private fun newGeneration(recovery: VBTransportReusedHttp2Recovery): Generation =
        Generation(
            id = nextGeneration++,
            client = clientFactory(recovery),
            recovery = recovery,
        ).also(openGenerations::add)

    internal fun openGenerationCountForTests(): Int = synchronized(lock) {
        openGenerations.size
    }

    internal fun activeLeaseCountForTests(): Int = synchronized(lock) {
        openGenerations.sumOf { it.inFlight }
    }

    private class OriginState(
        initialRecovery: VBTransportReusedHttp2Recovery,
        val epoch: Long,
    ) {
        var active: Boolean = true
        var shards: MutableList<ShardState?> = MutableList(initialRecovery.clientShardCount) { null }
        var nextShard: Int = 0
        val retired = mutableSetOf<Generation>()
        val rolloverTimes = ArrayDeque<Long>()

        fun selectShard(avoidShard: Int?, now: Long): Int {
            // Prefer healthy/uninitialized slots. A rate-limited stale slot is
            // quarantined from new work while any alternative remains.
            repeat(shards.size) {
                val candidate = nextShard
                nextShard = (nextShard + 1) % shards.size
                val avoided = shards.size > 1 && candidate == avoidShard
                val quarantined = shards[candidate]?.quarantinedAtMillis?.let {
                    now - it < ORIGIN_ROLLOVER_WINDOW_MILLIS
                } == true
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
        var quarantinedAtMillis: Long? = null,
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
    private val configurationLock = Any()
    private var configurationEpoch = 1L
    private var configurationKey = currentConfigurationKey()

    internal data class ConfigurationSnapshot(
        val epoch: Long,
        val okHttpEnabled: Boolean,
        val recovery: VBTransportReusedHttp2Recovery,
    )

    fun configurationChanged() {
        synchronized(configurationLock) { publishCurrentConfigurationLocked() }
    }

    fun snapshot(): ConfigurationSnapshot = synchronized(configurationLock) {
        publishCurrentConfigurationLocked()
        ConfigurationSnapshot(
            epoch = configurationEpoch,
            okHttpEnabled = configurationKey.okHttpEnabled,
            recovery = configurationKey.recovery,
        )
    }

    fun isCurrent(snapshot: ConfigurationSnapshot): Boolean = synchronized(configurationLock) {
        publishCurrentConfigurationLocked()
        snapshot.epoch == configurationEpoch
    }

    internal fun openGenerationCountForTests(): Int =
        generationManager.openGenerationCountForTests()

    internal fun activeLeaseCountForTests(): Int =
        generationManager.activeLeaseCountForTests()

    internal fun setClientFactoryForTests(
        factory: ((VBTransportReusedHttp2Recovery) -> HttpClient)?,
    ) = synchronized(configurationLock) {
        transportHttpClientFactoryForTests = factory
        configurationEpoch += 1
        generationManager.activateConfiguration(configurationEpoch)
    }

    fun acquire(
        request: VBTransportBaseRequest,
        configuration: ConfigurationSnapshot = snapshot(),
        avoidShard: Int? = null,
    ): AndroidTransportClientLease = synchronized(configurationLock) {
        check(configuration.epoch == configurationEpoch) {
            "stale Android transport configuration snapshot"
        }
        if (!configuration.okHttpEnabled || !configuration.recovery.enabled) {
            return@synchronized AndroidTransportClientLease(
                    client = sharedHttpClient,
                    origin = transportOrigin(request.url),
                    shard = 0,
                    generation = 0L,
                    onRelease = {},
                    onDrain = { AndroidTransportGenerationRollover(0L, initiated = false) },
                )
        }
        generationManager.acquire(
                url = request.url,
                recovery = configuration.recovery,
                avoidShard = avoidShard,
                epoch = configuration.epoch,
            )
    }

    private fun publishCurrentConfigurationLocked() {
        val next = currentConfigurationKey()
        if (next == configurationKey) return
        configurationEpoch += 1
        configurationKey = next
        generationManager.activateConfiguration(configurationEpoch)
    }

    private data class ConfigurationKey(
        val okHttpEnabled: Boolean,
        val recovery: VBTransportReusedHttp2Recovery,
    )

    private fun currentConfigurationKey(): ConfigurationKey = ConfigurationKey(
        okHttpEnabled = VBTransportAndroidEngine.okHttpEnabled,
        recovery = VBTransportAndroidEngine.reusedHttp2Recovery,
    )
}

internal fun transportOrigin(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: return "invalid-origin"
    val diagnosticHost = if (':' in parsed.host) "[${parsed.host}]" else parsed.host
    return "${parsed.scheme}://$diagnosticHost:${parsed.port}"
}

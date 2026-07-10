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
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

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

internal fun buildTransportHttpClient(okHttpEnabled: Boolean): HttpClient =
    if (okHttpEnabled) {
        HttpClient(OkHttp) {
            install(HttpTimeout)
            engine {
                config { applyTransportOkHttpDefaults() }
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
internal fun OkHttpClient.Builder.applyTransportOkHttpDefaults(): OkHttpClient.Builder =
    fastFallback(true)
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 })
        .eventListenerFactory(AndroidTransportPhaseTracer.eventListenerFactory())
        .addInterceptor { chain ->
            val request = chain.request()
            request.header(NETWORK_KMM_TRACE_HEADER)?.toIntOrNull()?.let(
                AndroidTransportPhaseTracer::dispatcherStarted
            )
            chain.proceed(request.newBuilder().removeHeader(NETWORK_KMM_TRACE_HEADER).build())
        }

actual fun getHttpClient(kmmRequest: VBTransportBaseRequest): Any? = sharedHttpClient

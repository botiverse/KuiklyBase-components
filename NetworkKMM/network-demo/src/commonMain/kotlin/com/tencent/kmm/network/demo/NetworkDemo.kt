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
package com.tencent.kmm.network.demo

import com.tencent.kmm.network.export.NetworkBody
import com.tencent.kmm.network.export.NetworkErrorKind
import com.tencent.kmm.network.export.NetworkMultipartPart
import com.tencent.kmm.network.export.NetworkProgressCallbacks
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.VBTransportMethod
import com.tencent.kmm.network.service.NetworkCall
import com.tencent.kmm.network.service.NetworkClient
import com.tencent.kmm.network.service.NetworkClientConfig
import com.tencent.kmm.network.service.NetworkEngineDiagnosticsListener
import com.tencent.kmm.network.service.NetworkEngineExecutionDiagnostics
import com.tencent.kmm.network.service.NetworkEngineSelection
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sink for the demo's human-readable log lines.
 *
 * Lines are emitted from the network client's background dispatcher, so a UI
 * host must marshal [line] onto its own main thread (Android `runOnUiThread`,
 * iOS `DispatchQueue.main`). Kept as a tiny interface so it crosses the
 * Kotlin/Native ObjC boundary cleanly (Swift conforms to it directly).
 */
interface NetworkDemoLogSink {
    fun line(text: String)
}

/** Opaque cancel handle handed back to the UI for the cancel/streaming panels. */
class NetworkDemoHandle internal constructor(private val call: NetworkCall?) {
    fun cancel() {
        call?.cancel()
    }
}

/**
 * Shared demo logic for the NetworkKMM sample apps (task #27).
 *
 * Drives the production [NetworkClient] API — engine switching plus four smoke
 * panels (buffered / streaming / upload / cancel) — and reports everything as
 * log lines through [NetworkDemoLogSink]. Both the Android and iOS sample apps
 * are thin UI shells over this one facade.
 *
 * This lives in the non-published `:network-demo` module. It is sample code,
 * kept deliberately simple: [close] is a best-effort
 * teardown (stop the cancel-panel timer, fence further logs/runs), not a
 * production-grade linearizable lifecycle gate. A production close/callback
 * contract is tracked separately, not in this demo.
 */
class NetworkDemo(private val logSink: NetworkDemoLogSink) {

    private val engine = atomic(ENGINE_KTOR)
    private val closed = atomic(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val client = NetworkClient(
        NetworkClientConfig(
            engineSelector = {
                NetworkEngineSelection.fromExternalConfig(engine = engine.value, curlEnabled = true)
            },
            engineDiagnostics = object : NetworkEngineDiagnosticsListener {
                override fun onEngineCompleted(diagnostics: NetworkEngineExecutionDiagnostics) {
                    val selection = diagnostics.selection
                    log(
                        "  · engine: requested=" +
                            (selection.requestedEngine?.externalValue ?: "default") +
                            " selected=" + selection.selectedEngine.externalValue +
                            " (" + selection.reason + ")"
                    )
                }
            }
        )
    )

    /**
     * Select the transport engine for subsequent requests ("ktor" / "curl").
     * A no-op (and silent) when unchanged or after [close], so UI surfaces that
     * lack a change callback can safely sync it before every request.
     */
    fun selectEngine(engine: String) {
        if (closed.value) return
        if (this.engine.value == engine) return
        this.engine.value = engine
        log("引擎切换 → $engine")
    }

    /** Buffered: GET a full JSON body → status / protocol / snippet. */
    fun runBuffered(baseUrl: String): NetworkDemoHandle {
        if (closed.value) return NetworkDemoHandle(null)
        val url = "${normalize(baseUrl)}/get"
        log("→ [buffered] GET ${redactDemoUrl(url)}  (engine=${engine.value})")
        val request = NetworkRequest(method = VBTransportMethod.GET, url = url).apply {
            setHeader("X-Demo", "networkkmm")
            addQuery("panel", "buffered")
        }
        return NetworkDemoHandle(client.execute(request) { onResponse("buffered", it) })
    }

    /** Streaming: download a slow drip and log each chunk as it arrives. */
    fun runStreaming(baseUrl: String): NetworkDemoHandle {
        if (closed.value) return NetworkDemoHandle(null)
        val url = "${normalize(baseUrl)}/drip?duration=5&numbytes=2000&code=200&delay=0"
        log("→ [stream] GET ${redactDemoUrl(url)}  (engine=${engine.value})")
        val received = atomic(0L)
        return NetworkDemoHandle(
            client.downloadStream(
                request = NetworkRequest(method = VBTransportMethod.GET, url = url),
                onResponseStart = { status, contentLength, _ ->
                    log("  ⇢ start status=$status contentLength=${contentLength ?: "?"}")
                },
                onChunk = { chunk ->
                    val total = received.addAndGet(chunk.size.toLong())
                    log("  ⇢ chunk +${chunk.size}B (total ${total}B)")
                },
                onComplete = { response ->
                    val error = response.error
                    if (error != null) {
                        log("✗ [stream] ${error.kind}")
                    } else {
                        log("✓ [stream] done status=${response.statusCode} protocol=${response.protocol} total=${received.value}B")
                    }
                }
            )
        )
    }

    /** Upload: POST a multipart body with an in-memory file part + progress. */
    fun runUpload(baseUrl: String): NetworkDemoHandle {
        if (closed.value) return NetworkDemoHandle(null)
        val url = "${normalize(baseUrl)}/post"
        val payload = ByteArray(UPLOAD_BYTES) { (it % 251).toByte() }
        log("→ [upload] POST ${redactDemoUrl(url)}  multipart ${payload.size}B (engine=${engine.value})")
        val request = NetworkRequest(
            method = VBTransportMethod.POST,
            url = url,
            body = NetworkBody.Multipart(
                parts = listOf(
                    NetworkMultipartPart(name = "field", body = NetworkBody.Text("kuikly")),
                    NetworkMultipartPart(
                        name = "file",
                        fileName = "sample.bin",
                        body = NetworkBody.Bytes(payload)
                    )
                )
            ),
            progress = NetworkProgressCallbacks(
                uploadProgress = { progress ->
                    log("  ⇢ upload ${progress.bytesTransferred}/${progress.bytesTotal ?: "?"}B")
                }
            )
        )
        return NetworkDemoHandle(client.execute(request) { onResponse("upload", it) })
    }

    /** Cancel: start a slow request, then cancel it after a short delay. */
    fun runCancel(baseUrl: String): NetworkDemoHandle {
        if (closed.value) return NetworkDemoHandle(null)
        val url = "${normalize(baseUrl)}/delay/10"
        log("→ [cancel] GET ${redactDemoUrl(url)} — 将在 ${CANCEL_DELAY_MS}ms 后取消 (engine=${engine.value})")
        val call = client.execute(NetworkRequest(method = VBTransportMethod.GET, url = url)) { response ->
            if (response.error?.kind == NetworkErrorKind.CANCELLED) {
                log("✓ [cancel] 已取消 (CANCELLED)")
            } else {
                onResponse("cancel", response)
            }
        }
        scope.launch {
            delay(CANCEL_DELAY_MS)
            if (!call.isCancelled) {
                log("  ⇢ call.cancel()")
                call.cancel()
            }
        }
        return NetworkDemoHandle(call)
    }

    /**
     * Best-effort teardown for the sample: stop the cancel-panel timer and
     * fence further logs/runs. Idempotent. Hosts call it on Android
     * `onDestroy` / iOS `deinit`.
     */
    fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        scope.cancel()
    }

    private fun onResponse(tag: String, response: NetworkResponse) {
        val error = response.error
        if (error != null) {
            // Log only kind/status — error.message can echo the full request URL.
            log("✗ [$tag] ${error.kind} status=${error.statusCode ?: "-"}")
            return
        }
        val bodyText = response.body.text() ?: ""
        val snippet = if (bodyText.length > SNIPPET_LIMIT) {
            bodyText.substring(0, SNIPPET_LIMIT) + "…"
        } else {
            bodyText
        }
        log("✓ [$tag] status=${response.statusCode} protocol=${response.protocol}\n$snippet")
    }

    private fun normalize(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return trimmed.ifEmpty { DEFAULT_BASE_URL }
    }

    private fun log(text: String) {
        if (closed.value) return
        logSink.line(text)
    }

    companion object {
        const val ENGINE_KTOR: String = "ktor"
        const val ENGINE_CURL: String = "curl"
        const val DEFAULT_BASE_URL: String = "https://httpbin.org"
        private const val CANCEL_DELAY_MS = 800L
        private const val UPLOAD_BYTES = 256 * 1024
        private const val SNIPPET_LIMIT = 240
    }
}

/**
 * Redact a URL for logging: keep only `scheme://host[:port]/path`, dropping
 * userinfo (`user:pass@`), query (`?...`) and fragment (`#...`) so a
 * caller-typed Base URL cannot leak credentials or tokens into the log
 * (Android logcat included). Fail-safe on invalid/partial URLs. Pure.
 */
internal fun redactDemoUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val noQuery = trimmed.substringBefore('#').substringBefore('?')
    val schemeIndex = noQuery.indexOf("://")
    if (schemeIndex >= 0) {
        val scheme = noQuery.substring(0, schemeIndex + 3)
        val rest = noQuery.substring(schemeIndex + 3)
        val slashIndex = rest.indexOf('/')
        val authority = if (slashIndex >= 0) rest.substring(0, slashIndex) else rest
        val path = if (slashIndex >= 0) rest.substring(slashIndex) else ""
        val atIndex = authority.lastIndexOf('@')
        val hostPort = if (atIndex >= 0) authority.substring(atIndex + 1) else authority
        return scheme + hostPort + path
    }
    val atIndex = noQuery.indexOf('@')
    return if (atIndex >= 0) noQuery.substring(atIndex + 1) else noQuery
}

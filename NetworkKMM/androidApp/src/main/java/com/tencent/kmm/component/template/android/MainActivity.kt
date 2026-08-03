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
package com.tencent.kmm.component.template.android

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
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
import java.util.concurrent.atomic.AtomicLong

/**
 * Network demo app (task #27).
 *
 * Exercises the production [NetworkClient] API — not the legacy
 * `VBTransportServiceTest` helpers — across four smoke panels (buffered /
 * streaming / upload / cancel), with a runtime toggle between the Ktor and
 * curl transport engines. The typed engine selector + diagnostics listener
 * make the gray/rollback behaviour visible: when curl is requested but the
 * platform curl runtime is not installed, the diagnostics line shows the
 * fallback to the platform default.
 *
 * All requests hit a configurable Base URL (default `https://httpbin.org`,
 * whose `/get`, `/drip`, `/post`, `/delay/{n}` endpoints back the four
 * panels). Results stream into the on-screen log — no logcat digging needed.
 */
class MainActivity : Activity() {

    // Read inside the engineSelector lambda (which runs per call), so flipping
    // the radio takes effect on the next request without rebuilding the client.
    @Volatile
    private var selectedEngine: String = ENGINE_KTOR

    private lateinit var client: NetworkClient
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var baseUrlInput: EditText

    // The most recently started call, so the Cancel panel can cancel it too.
    private var activeCall: NetworkCall? = null

    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView = findViewById(R.id.logView)
        logScroll = findViewById(R.id.logScroll)
        baseUrlInput = findViewById(R.id.baseUrl)

        client = NetworkClient(
            NetworkClientConfig(
                engineSelector = {
                    NetworkEngineSelection.fromExternalConfig(
                        engine = selectedEngine,
                        curlEnabled = true
                    )
                },
                engineDiagnostics = object : NetworkEngineDiagnosticsListener {
                    override fun onEngineCompleted(diagnostics: NetworkEngineExecutionDiagnostics) {
                        val sel = diagnostics.selection
                        log(
                            "  · engine: requested=" +
                                (sel.requestedEngine?.externalValue ?: "default") +
                                " selected=" + sel.selectedEngine.externalValue +
                                " (" + sel.reason + ")"
                        )
                    }
                }
            )
        )

        findViewById<RadioGroup>(R.id.engineGroup).setOnCheckedChangeListener { _, checkedId ->
            selectedEngine = if (checkedId == R.id.engineCurl) ENGINE_CURL else ENGINE_KTOR
            log("引擎切换 → $selectedEngine")
        }

        findViewById<Button>(R.id.btnBuffered).setOnClickListener { runBuffered() }
        findViewById<Button>(R.id.btnStreaming).setOnClickListener { runStreaming() }
        findViewById<Button>(R.id.btnUpload).setOnClickListener { runUpload() }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { runCancel() }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            logView.text = ""
        }

        log("就绪。选择引擎，点面板发请求。Base URL = ${baseUrl()}")
    }

    /** Buffered: GET a full JSON body and print status/protocol/snippet. */
    private fun runBuffered() {
        val url = "${baseUrl()}/get"
        log("→ [buffered] GET $url  (engine=$selectedEngine)")
        val request = NetworkRequest(method = VBTransportMethod.GET, url = url).apply {
            setHeader("X-Demo", "networkkmm")
            addQuery("panel", "buffered")
        }
        activeCall = client.execute(request) { response -> onResponse("buffered", response) }
    }

    /** Streaming: download a slow drip and report each chunk as it arrives. */
    private fun runStreaming() {
        val url = "${baseUrl()}/drip?duration=5&numbytes=2000&code=200&delay=0"
        log("→ [stream] GET $url  (engine=$selectedEngine)")
        val received = AtomicLong(0)
        activeCall = client.downloadStream(
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
                    log("✗ [stream] ${error.kind} ${error.message}")
                } else {
                    log("✓ [stream] done status=${response.statusCode} protocol=${response.protocol} total=${received.get()}B")
                }
            }
        )
    }

    /** Upload: POST a multipart body with an in-memory file part + progress. */
    private fun runUpload() {
        val url = "${baseUrl()}/post"
        val payload = ByteArray(256 * 1024) { (it % 251).toByte() }
        log("→ [upload] POST $url  multipart ${payload.size}B (engine=$selectedEngine)")
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
        activeCall = client.execute(request) { response -> onResponse("upload", response) }
    }

    /** Cancel: start a slow request, then cancel it after a short delay. */
    private fun runCancel() {
        val url = "${baseUrl()}/delay/10"
        log("→ [cancel] GET $url — 将在 ${CANCEL_DELAY_MS}ms 后取消 (engine=$selectedEngine)")
        val call = client.execute(
            NetworkRequest(method = VBTransportMethod.GET, url = url)
        ) { response ->
            if (response.error?.kind == NetworkErrorKind.CANCELLED) {
                log("✓ [cancel] 已取消 (CANCELLED)")
            } else {
                onResponse("cancel", response)
            }
        }
        activeCall = call
        ui.postDelayed({
            if (!call.isCancelled) {
                log("  ⇢ call.cancel()")
                call.cancel()
            }
        }, CANCEL_DELAY_MS)
    }

    private fun onResponse(tag: String, response: NetworkResponse) {
        val error = response.error
        if (error != null) {
            log("✗ [$tag] ${error.kind} status=${error.statusCode ?: "-"} ${error.message}")
            return
        }
        val bodyText = response.body.text().orEmpty()
        val snippet = if (bodyText.length > SNIPPET_LIMIT) {
            bodyText.substring(0, SNIPPET_LIMIT) + "…"
        } else {
            bodyText
        }
        log("✓ [$tag] status=${response.statusCode} protocol=${response.protocol}\n$snippet")
    }

    private fun baseUrl(): String =
        baseUrlInput.text.toString().trim().trimEnd('/').ifEmpty { DEFAULT_BASE_URL }

    /** Append a line to the on-screen log (marshalled to the UI thread). */
    private fun log(line: String) {
        Log.i(TAG, line)
        ui.post {
            logView.append(line + "\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    companion object {
        private const val TAG = "NetworkKMMDemo"
        private const val ENGINE_KTOR = "ktor"
        private const val ENGINE_CURL = "curl"
        private const val DEFAULT_BASE_URL = "https://httpbin.org"
        private const val CANCEL_DELAY_MS = 800L
        private const val SNIPPET_LIMIT = 240
    }
}

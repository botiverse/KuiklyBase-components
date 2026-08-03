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
import com.tencent.kmm.network.demo.NetworkDemo
import com.tencent.kmm.network.demo.NetworkDemoLogSink

/**
 * Network demo app (task #27) — Android surface.
 *
 * A thin UI shell over the shared [NetworkDemo] facade: it wires the engine
 * toggle + four panel buttons (buffered / streaming / upload / cancel) to the
 * facade and renders the facade's log lines on screen. All network logic
 * (production NetworkClient API, engine switching, progress, cancel timing)
 * lives in shared code, so this class only marshals log lines to the UI thread.
 */
class MainActivity : Activity() {

    private lateinit var demo: NetworkDemo
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var baseUrlInput: EditText

    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView = findViewById(R.id.logView)
        logScroll = findViewById(R.id.logScroll)
        baseUrlInput = findViewById(R.id.baseUrl)

        demo = NetworkDemo(object : NetworkDemoLogSink {
            override fun line(text: String) {
                Log.i(TAG, text)
                ui.post {
                    logView.append(text + "\n")
                    logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        })

        findViewById<RadioGroup>(R.id.engineGroup).setOnCheckedChangeListener { _, checkedId ->
            demo.selectEngine(if (checkedId == R.id.engineCurl) NetworkDemo.ENGINE_CURL else NetworkDemo.ENGINE_KTOR)
        }

        findViewById<Button>(R.id.btnBuffered).setOnClickListener { demo.runBuffered(baseUrl()) }
        findViewById<Button>(R.id.btnStreaming).setOnClickListener { demo.runStreaming(baseUrl()) }
        findViewById<Button>(R.id.btnUpload).setOnClickListener { demo.runUpload(baseUrl()) }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { demo.runCancel(baseUrl()) }
        findViewById<Button>(R.id.btnClear).setOnClickListener { logView.text = "" }
    }

    private fun baseUrl(): String = baseUrlInput.text.toString()

    companion object {
        private const val TAG = "NetworkKMMDemo"
    }
}

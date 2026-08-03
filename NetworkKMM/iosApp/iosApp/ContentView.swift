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
import SwiftUI
import network

/// iOS surface of the NetworkKMM demo (task #27).
///
/// A thin SwiftUI shell over the shared `NetworkDemo` facade (Kotlin
/// commonMain): it wires the engine toggle + four panel buttons
/// (buffered / streaming / upload / cancel) to the facade and renders the
/// facade's log lines. All network logic lives in shared code, so this file
/// only implements `NetworkDemoLogSink` and marshals log lines to the main
/// queue — the same demo behaviour as the Android app.
final class DemoModel: ObservableObject, NetworkDemoLogSink {

    @Published var log: String = ""

    // `NetworkDemo` needs the log sink at construction; `self` conforms to
    // `NetworkDemoLogSink`, so build it once the stored properties have their
    // defaults.
    private lazy var demo: NetworkDemo = NetworkDemo(logSink: self)

    /// Called from the client's background dispatcher — hop to the main queue.
    func line(text: String) {
        DispatchQueue.main.async {
            self.log += text + "\n"
        }
    }

    func selectEngine(curl: Bool) {
        demo.selectEngine(engine: curl ? "curl" : "ktor")
    }

    func buffered(_ baseUrl: String) { _ = demo.runBuffered(baseUrl: baseUrl) }
    func streaming(_ baseUrl: String) { _ = demo.runStreaming(baseUrl: baseUrl) }
    func upload(_ baseUrl: String) { _ = demo.runUpload(baseUrl: baseUrl) }
    func cancel(_ baseUrl: String) { _ = demo.runCancel(baseUrl: baseUrl) }
    func clear() { log = "" }
}

struct ContentView: View {

    @ObservedObject private var model = DemoModel()
    @State private var baseUrl = "https://httpbin.org"
    @State private var engineIsCurl = false

    var body: some View {
        VStack(spacing: 8) {
            Text("NetworkKMM Demo — NetworkClient")
                .font(.headline)

            Picker("引擎 Engine", selection: $engineIsCurl) {
                Text("Ktor (默认)").tag(false)
                Text("curl").tag(true)
            }
            .pickerStyle(SegmentedPickerStyle())

            TextField("Base URL", text: $baseUrl)
                .textFieldStyle(RoundedBorderTextFieldStyle())
                .disableAutocorrection(true)

            HStack {
                Button("Buffered GET") { run { model.buffered($0) } }
                Spacer()
                Button("流式 Stream") { run { model.streaming($0) } }
            }
            HStack {
                Button("上传 Upload") { run { model.upload($0) } }
                Spacer()
                Button("取消 Cancel") { run { model.cancel($0) } }
            }
            Button("清空日志 Clear log") { model.clear() }

            ScrollView {
                Text(model.log)
                    .font(.system(.footnote, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxHeight: .infinity)
            .background(Color.gray.opacity(0.1))
        }
        .padding()
    }

    /// Sync the selected engine (quiet when unchanged) then run the panel.
    private func run(_ panel: (String) -> Void) {
        model.selectEngine(curl: engineIsCurl)
        panel(baseUrl)
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}

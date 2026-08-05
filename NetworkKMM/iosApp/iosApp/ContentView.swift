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
import networkDemo

/// Weak bridge from the Kotlin log sink back to the SwiftUI model. The Kotlin
/// `NetworkDemo` strongly holds its sink, so if the sink held the model
/// strongly we'd have a retain cycle (`DemoModel → NetworkDemo → sink →
/// DemoModel`) and `deinit` — hence `NetworkDemo.close()` — would never run.
/// Holding the model weakly here breaks that cycle.
final class WeakLogSink: NetworkDemoLogSink {
    weak var owner: DemoModel?
    func line(text: String) {
        owner?.appendLine(text)
    }
}

/// View model owning the shared `NetworkDemo` facade. Closes it on `deinit` so
/// the facade's coroutine scope and any in-flight calls are cancelled when the
/// view goes away.
final class DemoModel: ObservableObject {

    @Published var log: String = ""

    private let sink = WeakLogSink()
    private let demo: NetworkDemo

    init() {
        demo = NetworkDemo(logSink: sink)
        sink.owner = self
    }

    deinit {
        demo.close()
    }

    /// Called from the Kotlin client's background dispatcher — hop to main.
    func appendLine(_ text: String) {
        DispatchQueue.main.async {
            self.log += text + "\n"
        }
    }

    func selectEngine(curl: Bool) { demo.selectEngine(engine: curl ? "curl" : "ktor") }
    func buffered(_ baseUrl: String) { _ = demo.runBuffered(baseUrl: baseUrl) }
    func streaming(_ baseUrl: String) { _ = demo.runStreaming(baseUrl: baseUrl) }
    func upload(_ baseUrl: String) { _ = demo.runUpload(baseUrl: baseUrl) }
    func cancel(_ baseUrl: String) { _ = demo.runCancel(baseUrl: baseUrl) }
    func clear() { log = "" }
}

/// iOS surface of the NetworkKMM demo (task #27) — a thin SwiftUI shell over
/// the shared `NetworkDemo` facade: engine toggle + four panel buttons
/// (buffered / streaming / upload / cancel) + on-screen log.
struct ContentView: View {

    @StateObject private var model = DemoModel()
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

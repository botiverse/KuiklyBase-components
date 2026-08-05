### ChangeLog

##### Unreleased

- build: dual-publish every existing NetworkKMM GAV to GitHub Packages and Raft Artifacts
- build: bind remote Maven POMs to a clean-checkout source SHA and reject SNAPSHOT or partial immutable retries
- build: attach sources to the Android curl and HarmonyOS runtime publications and add publication mutation gates

##### 0.1.0-raft.0

- docs: point consumer setup to the bytemain GitHub Packages Maven repository
- docs: add file-based GitHub Packages credentials with environment fallback
- feature: add P0 unified NetworkClient request/response model, cancellation, auth middleware, and policy API
- feature: add P1 NetworkClient interceptors, progress callbacks, streaming descriptors, and error taxonomy
- build: point NetworkKMM publishing metadata to the bytemain fork repository
- docs: organize NetworkClient guides by use case in English and Chinese
- fix: return Android/iOS transport failures through callbacks
- fix: avoid duplicate HarmonyOS curl headers

##### 0.0.5-raft.0

- feature: support custom HTTP methods via VBTransportRequest
- feature: add multipart/form-data request body builder
- docs: add PUT and binary upload examples
- docs: add HarmonyOS native library integration guide
- build: add GitHub Packages publishing for Android, iOS, and OHOS artifacts
- docs: document manual and CI publishing to GitHub Packages
- build: publish HarmonyOS native runtime libraries as `network-ohos-runtime`
- build: add Gradle plugin to sync HarmonyOS native runtime libraries
- build: split GitHub Packages CI publishing across Linux/HarmonyOS and macOS hosts
- build: run NetworkKMM Android/OHOS and iOS publish jobs in parallel, then publish KMP metadata
- build: align NetworkKMM Kotlin, KSP, Android Gradle Plugin, and Gradle wrapper versions
- build: fail publishing early when Gradle task discovery cannot configure the project
- build: keep NetworkKMM KSP on the Kotlin 2.0.21-compatible line and disable the KBA suffix version warning
- build: configure HarmonyOS SDK environment variables from the CI image before publishing OHOS artifacts

##### 0.0.4

- feature: first publish

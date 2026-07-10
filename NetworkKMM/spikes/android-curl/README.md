# NetworkKMM Android curl spike

This is an opt-in feasibility harness. It does not replace the production
Ktor OkHttp transport.

The harness cross-compiles OpenSSL 3.5.4 and curl 8.16.0 with Android NDK 28,
links the existing `pbcurlwrapper` into a JNI shared library, packages a
minimal arm64-v8a or x86_64 APK, and performs two real HTTPS requests. Mozilla
roots are bundled as the initial self-managed trust store.

Run from `NetworkKMM` with an arm64 Android device or emulator connected:

```bash
./scripts/build-android-curl-spike.sh --run
```

The expected marker is:

```text
SLOCK_ANDROID_CURL_SPIKE completed passed=true reused=true
```

The `NetworkKMM Android curl spike` GitHub Actions workflow runs the same
harness on an x86_64 Android Emulator for hardware-accelerated Linux CI:

```bash
ANDROID_ABI=x86_64 ./scripts/build-android-curl-spike.sh --run
```

The default remains arm64-v8a so the local 16 KB-page packaging check is not
weakened by the CI architecture choice.

## Measured result

Environment: Apple Silicon Mac, Android NDK 28.0.13004108, API 23 target,
arm64-v8a Android emulator with 16 KB pages.

- Clean native plus APK build and device run: 112.01 seconds.
- Cached native relink, APK build, and device run: 13.58 seconds.
- `libcrypto.a`: 11 MB.
- `libssl.a`: 2.0 MB.
- `libcurl.a`: 8.7 MB before final link dead stripping.
- Stripped JNI shared library: 7.5 MB for one ABI.
- Minimal debug APK: 7.6 MB after a clean package.
- First HTTPS request: HTTP 200, 559 bytes, about 886 ms total in the clean
  recorded run (about 101 ms connect and 378 ms TLS).
- Second HTTPS request: HTTP 200, 559 bytes, about 101 ms total, with connect
  and TLS both 0 because the process-wide shared connection was reused.
- ELF load segments use 16 KB alignment (`p_align = 0x4000`) and the APK ran
  on a 16 KB-page emulator.

## What this proves

- The same wrapper C++ source can be built for Android and reached through a
  small JNI boundary.
- Static OpenSSL/curl packaging, 16 KB compatibility, HTTPS verification, and
  cross-client pooling are feasible on Android.
- Android does not have Kotlin/Native cinterop. A production migration still
  needs a real JVM-facing JNI API for buffered requests, streaming download,
  streaming upload, cancellation, callback threading, and memory ownership.

## Production boundary

The current 7.5 MB single-ABI library is an unoptimized first measurement,
not a target. A production lane should remove unused OpenSSL algorithms and
curl protocols, evaluate shared-vs-static packaging, build every shipping ABI,
and measure the delta in the real consumer APK/AAB.

The bundled CA approach intentionally makes trust application-owned. That is
compatible with private/self-signed roots and a shared three-platform policy,
but it also makes CA updates, revocation policy, pinning, and incident response
an application responsibility. Android `networkSecurityConfig` and platform
`TrustManager` behavior are not inherited automatically.

System proxy/PAC discovery is not inherited by libcurl itself. The production
transport now offers `NetworkCurlProxyConfiguration.androidSystem()`: static
decisions come from Android's default `ProxySelector`, while PAC connects curl
to Android's localhost forwarding proxy so the OS retains script evaluation,
ordered fallback, and proxy-change ownership. The spike remains only build/TLS
evidence; the production Android runtime matrix is the acceptance source.

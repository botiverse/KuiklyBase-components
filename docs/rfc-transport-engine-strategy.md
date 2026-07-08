# RFC: NetworkKMM Transport Engine Strategy

- Status: Draft — collecting inputs
- Author: CC-希乐 (KuiklyBase side), mobile-consumption inputs: HanXin
- Origin: #Kuiklybase:160e7a07 — proxy/IPv6 cold-connection timeout ladders
  (Raft mobile P1; Quiver 03c175b5: 16s channel opens behind ClashMeta)
- Related: raft.9 stopgap (CC-Cata) — connect-timeout decoupling on
  Android/iOS Ktor + OHOS libcurl `CURLOPT_CONNECTTIMEOUT_MS`/HE/`CURLINFO`
  markers. The stopgap ships regardless of this RFC's outcome.

## Problem

NetworkKMM's per-platform engines lack Happy Eyeballs (RFC 8305) semantics:
on dual-stack networks where one address family is broken (VPN/proxy blackhole,
misconfigured IPv6), a cold connection serially exhausts the broken family's
connect budget before falling back. Verified root causes:

- **Android** (`IVBTransportService.android.kt:81-83`): Ktor `Android` engine
  (HttpURLConnection) with `connect = socket = request = totalTimeout` welded
  together, and no parallel-family racing at all.
- **OHOS** (`pbcurlwrapper/curl_wrapper.cpp`): libcurl easy handle sets only
  `CURLOPT_TIMEOUT_MS`; `CURLOPT_CONNECTTIMEOUT_MS` unset (300s default), and
  native curl Happy Eyeballs untuned/unverified in our DNS path.

Evidence fingerprint: same-request pairs of 16024ms→345ms, bimodal latency
clustering at 5s/15s/30s across 246 samples, all responses 200.

The stopgap (short, decoupled connect timeouts) converts "eat the whole
budget" into "fail fast and retry" (~16s → ~3.3s worst case). True racing
(~250ms family switch) requires engine capability — this RFC decides that.

## Options

### A. Ktor engine swap: Android → OkHttp (`fastFallback = true`)

OkHttp 4.x+ implements RFC 8305 racing natively (`fastFallback`): v6 gets a
~250ms head start, then v4 joins in parallel; first connected route wins.

- Change surface: one dependency + one engine constructor in
  `VBTransportCommonUtils.android.kt`; Ktor API surface unchanged; all
  existing VBTransport semantics preserved.
- Solves: Android racing (the current P1 platform). Does NOT touch OHOS/iOS.
- Risks: behavioral deltas HttpURLConnection→OkHttp (redirects, connection
  pooling, proxy selector nuances) — need a regression pass on auth/upload
  paths. Well-trodden migration industry-wide.

### B. Cronet (Chromium network stack)

- Strongest capability: native HE, QUIC/HTTP3, connection migration,
  battle-tested proxy handling. artin has positive prior experience.
- Costs: binary size (+MB; Play-Services-provided variant mitigates on
  GMS devices but not on Chinese-market/OHOS-adjacent devices), no official
  Ktor engine (custom engine implementation), Android-only (breaks
  one-stack goals for OHOS/iOS).

### C. libcurl unification (all platforms on pbcurlwrapper)

- Cleanest architecture: three ends share one stack, one timeout/HE config
  surface, `CURLINFO_*` segmented markers everywhere.
- Costs: Android/iOS builds of curl + TLS backend choice (BoringSSL/OpenSSL),
  certificate store integration (Android CAStore, iOS Security.framework),
  proxy/VPN integration parity with platform engines, JNI/K-N bridges.
  Largest effort and risk of the three.

## Decision inputs (HanXin — mobile consumption side)

| Column | A: OkHttp | B: Cronet | C: libcurl unified |
| --- | --- | --- | --- |
| APK/HAP size delta | (fill) | (fill) | (fill) |
| Bump/publish complexity | (fill) | (fill) | (fill) |
| Three-end consistency | (fill) | (fill) | (fill) |
| Migration risk surface | (fill) | (fill) | (fill) |

## Preliminary lean (to be confirmed by inputs)

Stopgap + Option A likely captures ≥80% of the user-facing benefit at near-zero
cost for the platform currently hurting (Android). B/C re-enter the discussion
when QUIC or three-end stack unification become goals in their own right.

## Rollout sketch (if A)

1. raft.9 stopgap ships (independent).
2. raft.10: OkHttp engine behind a build/runtime flag, default off.
3. Mobile canary: enable flag, regression auth/upload/proxy scenarios +
   Quiver latency histogram comparison (connect/TTFB markers from stopgap).
4. Default on; keep `Android` engine fallback flag for one release.

## Open questions

- iOS engine (Darwin) HE behavior — NSURLSession has native HE; verify and
  document, likely no change needed.
- OHOS: is curl's native HE actually effective in our DoH/proxy DNS path
  (post-raft.9 markers will answer with data).

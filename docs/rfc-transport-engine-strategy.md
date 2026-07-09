# RFC: NetworkKMM Transport Engine Strategy

- Status: **Analysis** — artin's direction (2026-07-08): deep-cost C (libcurl
  unification) as the candidate target state; A (OkHttp) approved and being
  implemented as the Android root fix (#32); no engine migration decision is
  being taken in this phase beyond A.
- Author: CC-希乐 (KuiklyBase side); mobile-consumption inputs: HanXin;
  OHOS build/trust-store facts: CC-Cata
- Origin: #Kuiklybase:160e7a07 — proxy/IPv6 cold-connection timeout ladders
  (Raft mobile P1; Quiver 03c175b5: 16s channel opens behind ClashMeta)
- Related shipped work (all landed 2026-07-08):
  - **raft.9**: connect decoupled to `min(3s, total)` on Android/iOS Ktor;
    EOF-safe reads; classified failure reasons.
  - **raft.12** (published; consumed by mobile PR #348 on the 1.1.0 line):
    Android engine → Ktor-OkHttp with `fastFallback = true` (= Option A, #32)
    + OHOS `CURLOPT_CONNECTTIMEOUT_MS` and explicit
    `CURLOPT_HAPPY_EYEBALLS_TIMEOUT_MS` with slow-transfer phase logging
    (#33, CURLINFO timings were already collected in the wrapper).
    Version-number note: raft.10 was never published standalone, and the
    raft.11 coordinates are poisoned (a failed publish left partial
    alpha.16-built artifacts; GitHub Packages versions are immutable) —
    consume raft.12, never raft.11.

## Problem

NetworkKMM's per-platform engines lacked Happy Eyeballs (RFC 8305) semantics:
on dual-stack networks where one address family is broken (VPN/proxy
blackhole, misconfigured IPv6), a cold connection serially exhausts the broken
family's connect budget before falling back. Verified root causes:

- **Android** (`IVBTransportService.android.kt`): Ktor `Android` engine
  (HttpURLConnection) with `connect = socket = request = totalTimeout` welded
  together (fixed in raft.9), and no parallel-family racing at all (fixed by
  A in raft.10).
- **OHOS** (`pbcurlwrapper/curl_wrapper.cpp`): libcurl easy handle sets only
  `CURLOPT_TIMEOUT_MS`; `CURLOPT_CONNECTTIMEOUT_MS` unset (300s default), and
  native curl Happy Eyeballs untuned/unverified in our DNS path (raft.11).
- **iOS**: Darwin engine (NSURLSession) has native Happy Eyeballs; only the
  timeout weld applied (fixed in raft.9).

Evidence fingerprint: same-request pairs of 16024ms→345ms, bimodal latency
clustering at 5s/15s/30s across 246 samples, all responses 200.

With A shipped, the remaining question this RFC answers is **strategic**:
should the three platforms eventually converge on one stack (libcurl), and
what would that actually cost?

## Options

### A. Ktor engine swap: Android → OkHttp (`fastFallback = true`) — SHIPPED (raft.12, mobile #348)

OkHttp 5.x implements RFC 8305 racing natively (`fastFallback`): v6 gets a
~250ms head start, then v4 joins in parallel; first connected route wins.

- Landed shape: one dependency + one engine constructor
  (`buildTransportHttpClient`), explicit `fastFallback(true)`, kill switch
  `VBTransportAndroidEngine.okHttpEnabled` (default on, startup-latched),
  Ktor API surface unchanged, raft.9 `HttpTimeout` wiring applies as-is.
- Solves: Android racing (the current P1 platform). Does NOT touch OHOS/iOS.
- Cost actually paid during implementation (new data): **the KBA toolchain's
  Kotlin version is a hard dependency ceiling — twice over.** (1) Every
  OkHttp 5.x stable ships Kotlin 2.2 metadata; the KBA 2.0.21-ohos-aligned
  compiler reads ≤2.1. (2) Even a Kotlin-2.1 build (alpha.16) fails: its
  kotlin-stdlib 2.1.21 wins Gradle version resolution project-wide and ICEs
  the commonMain metadata compilation. The final pin is `5.0.0-alpha.14`
  (built with Kotlin 1.9.23, stays below the project stdlib; plain JVM
  artifact, so no okhttp-android/androidx.startup edge) until the toolchain
  moves. This ceiling applies to ANY JVM dependency this repo adds — a
  recurring line item for options A and B, and one libcurl (C) does not have
  on the native side. The PR test lane now compiles common metadata so this
  class of failure gates at PR time.
- Remaining risks: behavioral deltas HttpURLConnection→OkHttp (redirects,
  connection pooling, proxy selector nuances) — regression pass on
  auth/upload/proxy scenarios rides the mobile bump (#563).

### B. Cronet (Chromium network stack) — EXCLUDED for this problem class

- Capability: native HE, QUIC/HTTP3, connection migration.
- Exclusion rationale (HanXin, agreed): our bug lives in proxy environments,
  and Cronet's system-proxy handling differs from OkHttp/HttpURLConnection —
  fixing a proxy bug by introducing an engine whose proxy behavior needs
  re-validation is risk in the wrong direction. Plus +2–5 MB per-ABI binary
  cost, no official Ktor engine, Android-only. Re-enters only if QUIC/HTTP3
  becomes a goal in its own right, and then competes with C's curl-HTTP3 path.

### C. libcurl unification (all platforms on pbcurlwrapper) — the deep-cost analysis

The architectural upside is real and aligns with the "one contract,
structural convergence" direction:

- **One stack, three ends**: today's class of bug gets fixed once, not per
  platform (this thread fixed Android and OHOS separately).
- **One marker vocabulary**: CURLINFO segment timings
  (namelookup/connect/appconnect/starttransfer/total) everywhere — the OHOS
  wrapper already collects them (ElapseStats); Android/iOS would inherit
  instead of approximating with per-engine hooks.
- **Capability ceiling**: curl's HE is tunable (`HAPPY_EYEBALLS_TIMEOUT_MS`),
  HTTP/2 now, HTTP/3 as a build flag later — without a JVM-dependency Kotlin
  ceiling (the .so is toolchain-independent).

What it costs, priced per platform and per risk gate:

#### C-1. TLS trust (the gate artin set: "先系统，再自搭" — follow the platform default)

Per-platform pricing (this was originally misjudged as "libcurl always means
hand-rolled TLS"; Cata's OHOS data corrected it):

| End | Trust source today | Cost under C |
| --- | --- | --- |
| **OHOS** | Already libcurl + OpenSSL, trust anchors from the system store at `/etc/ssl` (baked `CURL_CA_BUNDLE`/`CURL_CA_PATH` at cross-compile; verified working on device since raft.2 — the raft.7 login failure was content-encoding, not TLS) | **~zero** (status quo) |
| **iOS** | Darwin/NSURLSession = system keychain + native HE | **Zero if iOS keeps Darwin** (recommended — see C-6) |
| **Android** | Platform TrustManager via HttpURLConnection/OkHttp (system CA store + user-installed CAs + `networkSecurityConfig`) | **The expensive one — a security-sensitive bridge, detailed below** |

Android specifics (verified, not speculation):

- `CURLSSLOPT_NATIVE_CA` is **not** an escape hatch: it only works for the
  OpenSSL/wolfSSL backends **on Windows**; on Android/OHOS/Linux it is a
  no-op. (Our curl headers are 8.16.0 so the flag exists — it just does
  nothing useful here.)
- Pointing `CURLOPT_CAPATH` at `/system/etc/security/cacerts` fails subtly:
  those files are named by the **old** OpenSSL subject-hash; modern
  OpenSSL/BoringSSL CAPATH lookup uses the new hash and finds nothing.
- The robust bridge is runtime: export trust anchors from the platform
  `TrustManager`/KeyStore and feed `CURLOPT_CAINFO_BLOB` (naturally includes
  user-installed CAs, e.g. ClashMeta MITM debug certs), or install a verify
  callback via `CURLOPT_SSL_CTX_FUNCTION` that delegates to platform APIs.
- **`networkSecurityConfig` is silently bypassed** by any non-platform stack:
  cleartext policy, per-domain trust, pin sets declared there are enforced by
  the platform trust manager — libcurl would not read them. Either the bridge
  re-implements the relevant semantics or the app accepts a documented
  divergence. This is a compliance surface, not just code.
- Verdict: **"follow the system" on Android/libcurl is not a config switch —
  it is a piece of security-critical bridge code that must be written,
  reviewed, and conformance-tested.** This is exactly what OkHttp/Ktor
  provide for free, and it is the single heaviest gate on C.

Acceptance guardrail (HanXin, adopted as a hard constraint): post-migration
certificate behavior must be **item-for-item equivalent** to the status quo —
enterprise CAs, user-installed CAs, pinning (if any — open question C-7),
expired/self-signed rejection — with a comparison test matrix, including the
baseline check of what current OkHttp/HttpURLConnection actually do with user
CAs. Note the flip side found by Cata: OHOS `/etc/ssl` likely does NOT
include user-installed certificates, so "system store ≠ user trust set" must
be tested on all three ends — including the possibility that migrating
Android to libcurl would *lose* user-CA trust the current stack has.

#### C-2. System proxy integration (where this whole bug class lives)

- libcurl does not read platform proxy settings; it wants explicit
  `CURLOPT_PROXY`. Android proxy comes from `ProxySelector`/connectivity
  (WiFi proxy, per-network config); a bridge must read and map it
  per-request, and **PAC scripts are not supported by libcurl at all** (the
  platform evaluates PAC; curl takes a fixed proxy URL).
- VPN-type proxies (ClashMeta in TUN mode) are transparent at socket level
  and unaffected; HTTP-proxy/PAC setups are the exposure.
- Required validation: the proxy test matrix (WiFi proxy, PAC, VPN/TUN,
  MITM-CA) run per end, against the current stack as baseline. Same matrix
  doubles as the A-case regression list, so building it is not throwaway.

#### C-3. Binary size and ABI matrix

- OHOS: already ships libcurl+OpenSSL (sunk cost, one ABI).
- Android: static libcurl + TLS backend ≈ **1–2 MB per ABI**, ×2 shipping
  ABIs (arm64-v8a, armeabi-v7a; +x86_64 if emulator artifacts ship) on top of
  the existing APK budget. Partially offset by dropping OkHttp (~800 KB) only
  if nothing else in the app pulls it back transitively (unlikely — check).
- iOS: zero if Darwin stays.

#### C-4. Build & release chain

- Android .so joins the `build-ohos-native.sh`-style cross-compile lane
  (NDK toolchain instead of OHOS SDK) — CI exists as a pattern (Cata's
  networkkmm-ohos-native.yml), so this is "another lane of an existing
  machine", not new machinery. Still: every curl/OpenSSL CVE bump now
  triggers a 2-platform .so rebuild + publish instead of a version-line edit.
- Publish complexity moves from "pure Kotlin raft.N" to "raft.N + native
  artifacts", i.e. every release looks like the heavier OHOS releases.

#### C-5. Migration & rollback

- Android JVM has **no official Ktor curl engine** (Ktor's Curl engine is
  Kotlin/Native only). C on Android means: JNI bindings over the wrapper
  (pbcurlwrapper-style, JVM flavor) + a custom Ktor engine implementing
  execute/stream against them. That is a transport-layer rewrite —
  well-bounded (VBTransport's Ktor API surface is small: request, stream,
  timeouts, headers, multipart) but strictly more code than A's one-line
  engine swap, and rollback means keeping the old engine path alive behind
  a flag through the entire canary window.
- OHOS: no migration (already there). iOS: recommend no migration (C-6).

#### C-6. The honest shape of "three-end unification"

If iOS keeps Darwin (native HE, system trust, zero cost — and losing it buys
nothing user-visible), C is really **2-end unification (Android+OHOS) + a
documented iOS divergence**. That still collapses the fix-it-twice problem
this thread hit (both fixes were Android/OHOS), and the marker vocabulary can
be unified at the `elapseStatis` contract level (iOS approximates from
NSURLSessionTaskMetrics, which maps 1:1 to the CURLINFO segments). But the
"one stack everywhere" slogan should be written as what it is.

#### C-7. Open questions (assigned)

1. Do we ship certificate pinning anywhere today (mobile `networkSecurityConfig`
   pin-set, or code-level)? Determines C-1 bridge scope. → mobile side (HanXin)
2. User-CA behavior baseline on all three current stacks (MITM debug cert
   accepted or not?) → QA matrix, doubles as A regression
3. Is curl's native HE effective in our OHOS DNS path? → **partially
   answered**: the explicit `HAPPY_EYEBALLS_TIMEOUT_MS=200` pin + phase
   markers shipped in raft.12 and the marker passthrough is device-verified;
   what remains is observing the connectMs distribution in a real
   dual-stack broken-family scenario (expect ~250ms-scale racing, not a 3s
   connect-cap ceiling) — folded into the QA proxy matrix (C-2/#2)
4. Android APK delta measured (not estimated) from a spike build → C spike,
   only if C proceeds past gate review

## Decision inputs (HanXin — mobile consumption side, filled 2026-07-08)

| Column | A: OkHttp | B: Cronet | C: libcurl unified |
| --- | --- | --- | --- |
| APK/HAP size delta | ~hundreds of KB (okhttp+okio, mostly already present transitively → near-zero net) | **+2–5 MB** (cronet .so ×2 ABIs; largest cost item) | Android adds libcurl+TLS .so ~1–2 MB/ABI (OHOS sunk) |
| Bump/publish complexity | Lowest: pure-Kotlin engine swap, existing raft.N line, no .so rebuild | Medium: Play-Services variant vs bundled .so ABI matrix | High: Android .so joins the native pipeline; every release ships native artifacts |
| Three-end consistency | Android-only fix (iOS native HE, OHOS libcurl) — engines stay heterogeneous but every end has HE | Worst: Android-only, no OHOS/iOS benefit | Best available: Android+OHOS same stack, unified markers; iOS stays Darwin (see C-6) |
| Migration risk surface | Low: Ktor API unchanged, one dependency, easy rollback (shipped with kill switch) | Medium-high: proxy behavior is a new variable exactly where our bug lives | High: TLS bridge (security review), proxy bridge, PAC unsupported, JNI + custom Ktor engine |

Plus the toolchain line item from A's implementation: **KBA Kotlin 2.0.21
caps JVM dependency versions** (OkHttp had to be pinned to the last
Kotlin-2.1 build). Affects A/B recurringly; C's native side is immune, its
JVM-side JNI shim minimal.

## Where this lands (analysis conclusion, decision = artin)

- **Now**: A + the OHOS curl HE/connect cap shipped together in raft.12 and
  are consumed by mobile (PR #348, 1.1.0 line). Every end now has Happy
  Eyeballs or a 3s connect cap. QA acceptance: proxy+IPv6 ladder collapses
  to sub-second (Android) / ≤3s (OHOS until HE verified by the raft.12
  phase-log data).
- **C as target state**: the architecture value is real but its gate is
  C-1 (Android TLS bridge = new security responsibility) and C-2 (proxy
  parity). Recommended trigger conditions to reopen C for execution:
  (a) HTTP/3/QUIC becomes a product goal, or (b) a third
  fix-it-per-platform incident of this kind, or (c) the marker-unification
  need outgrows the `elapseStatis` contract. Until then C stays analyzed,
  not scheduled — A is reversible and does not block it.
- **B stays excluded** unless QUIC becomes a goal AND C's curl-HTTP3 path is
  rejected.

## Rollout state (A) — complete

1. raft.9 stopgap — shipped (2026-07-08).
2. OkHttp engine (default on, kill switch
   `VBTransportAndroidEngine.okHttpEnabled`) + OHOS connect cap/HE/phase
   log — shipped as **raft.12** (raft.10 unpublished, raft.11 poisoned).
3. Mobile bump — **merged** (PR #348, raft.8 → raft.12 + foreground
   prewarm marker), on the 1.1.0 packaging line.
4. Remaining: device measurement in the reproducing environment (ClashMeta
   proxy + IPv6) — ladder gone = root fix confirmed.

---

# Appendices — thread Q&A distilled (2026-07-08, artin's questions)

## Appendix A: TLS backend for C — BoringSSL vs OpenSSL

Same lineage (BoringSSL = Google's post-Heartbleed 2014 fork), different
governance. What matters for us:

| | OpenSSL | BoringSSL |
| --- | --- | --- |
| Releases | Versioned + LTS, formal CVE process, ABI-stable within 3.x | **No releases, no API/ABI promise** — consumers (Chrome/Android/gRPC) vendor a snapshot and track master |
| Surface | Full-featured (provider architecture, FIPS provider) | Legacy trimmed (no engines/old protocols) — smaller attack surface and binary |
| Ecosystem | The well-trodden curl pairing; docs everywhere | Hardened by Chrome-scale fuzzing; curl support is "works, with sharp edges"; required if HTTP/3 goes via quiche |

**Recommendation: OpenSSL.** It is what the OHOS lane already cross-compiles,
version-pinnable CVE handling suits a small team, and the only BoringSSL
draws (size, quiche/QUIC alignment) don't outweigh the track-master
maintenance tax. Revisit only if C reopens *because of* QUIC. (Note: the
excluded option B — Cronet — is where BoringSSL would have entered.)

## Appendix B: can the C wrapper be handed to all three ends directly?

The C++ core (curl_wrapper.cpp + libcurl + OpenSSL) is 100% shareable; the
question is the **binding layer into Kotlin**, which differs per platform:

| End | Binding | Status |
| --- | --- | --- |
| OHOS | Kotlin/Native cinterop | **Live today** |
| iOS | Kotlin/Native cinterop — same mechanism, same headers | **Near-reuse**: apple cross-compile lane + TLS/proxy bridges (Appendix C); whether to migrate at all is Appendix C's call |
| Android | **No cinterop exists on JVM/ART** | Hand-written JNI binding (incl. callback thread/memory marshalling — entirely different from K/N function pointers) + a custom Ktor engine, *then* the C-1 trust bridge and C-2 proxy/PAC bridge on top |

Minor shared item: the wrapper's log layer is currently pinned to OHOS hilog
and needs a per-platform abstraction (android log / os_log) — half-day scale.

So "one C wrapper for three ends" is true at the source level; the cost lives
almost entirely in Android's binding + behavior bridges, which is exactly why
C-5 names Android as option C's main engineering line.

## Appendix C: iOS — keep Darwin or migrate? (cost sheet)

**Keeping Darwin costs ≈ 0** and is the recommendation. NSURLSession natively
provides Happy Eyeballs, HTTP/3 (iOS 15+), the system trust chain
(keychain/enterprise/user certs, ATS), system proxy/PAC/VPN integration, and
power-aware scheduling — and iOS never exhibited the P1 symptom.

**Migration cost estimate (if C reopens and insists on iOS):**

1. curl+OpenSSL apple cross-compile lane (device + simulator, macOS runner):
   ~2–3 person-days
2. cinterop wiring + promoting CurlRequestService from ohosArm64Main to an
   apple/ohos shared source set: ~2–3 person-days
3. **TLS trust bridge (the critical item)**: iOS has **no public API to
   enumerate system root certificates** — a baked CA path (OHOS-style) is
   impossible. The correct shape is a verify callback delegating to
   `SecTrustEvaluateWithError` (which also honours user-installed certs):
   ~2–3 person-days **+ security review**
4. Proxy bridge: `CFNetworkCopyProxiesForURL` (includes PAC evaluation) →
   `CURLOPT_PROXY`: ~1–2 person-days
5. Recurring tax: every curl/OpenSSL CVE changes from a version-line edit to
   an iOS static-library rebuild as well

Total ≈ two weeks of one-time work + security review, and the purchase is
**only** three-end marker unification — while HE/H3 are native there already
(migrating would actually regress HTTP/3 → HTTP/2). The cheap substitute:
align iOS diagnostics to the `elapseStatis` contract via
NSURLSessionTaskMetrics (~1–2 days) and get ~90% of the marker value.

# KuiklyBase Datetime provenance and divergence

This file is shipped with every KuiklyBase Datetime variant. It separates the
official JetBrains baseline, the CPF OHOS reference, and the independent
KuiklyBase implementation so a published artifact can be audited without
reconstructing a local checkout.

## Frozen references

| Layer | Repository / ref | Exact object | License |
|---|---|---|---|
| Official upstream | `https://github.com/Kotlin/kotlinx-datetime`, annotated tag `v0.7.1` | tag object `779f381cb316ace0e3208c6a3a2a9170a835f61a`; peeled commit `1ee0daac9fc4c22a7da33b830c7cc60e625c49bb` | Apache-2.0 |
| CPF reference fork | `https://gitcode.com/CPF-KMP-CMP/kotlinx-datetime.git`, branch `main-0.7.1-OH`, tag `v0.7.1-0.3.0` | commit `cf0c17fec83df813182f8c444ae75760833c67a4`; tree `38a56b0d004000f53d39741139b4fc22b1ea463d` | Apache-2.0 |
| KuiklyBase base when the component was introduced | `https://github.com/bytemain/KuiklyBase-components`, `master` | commit `730f1642071042db7cfea165c078a97667ce86af` | Apache-2.0 |

The CPF import and the official tag have no common Git ancestry. A tree-level
comparison at the frozen refs reports 45 changed paths and roughly
3,572 insertions / 375 deletions. The CPF tree also builds with Kotlin
`2.2.21-0.3.0` and serialization `1.9.1-0.3.0`; that artifact cannot serve an
OHOS consumer pinned to Kotlin/Native `2.0.21-KBA-010`.

## Deliberately excluded upstream/fork surface

KuiklyBase Datetime does **not** vendor or relocate the fork. It excludes:

- the `kotlinx.datetime` package and ABI;
- calendar/date/time types, parsing, formatting, arithmetic, and serialization;
- the deprecated Clock/Instant compatibility layer;
- the complete IANA/Bionic timezone database and tzfile parser;
- JVM, JS, Wasm, desktop, watchOS, tvOS, and mingw target machinery;
- upstream publishing, benchmarks, API dumps, and integration-test projects.

The public contract is intentionally limited to a millisecond Unix [Instant],
an injectable wall [Clock], a second-precision [UtcOffset], and a fresh
system-timezone snapshot/invalidation probe.

## KuiklyBase patch catalog

| Area | KuiklyBase implementation | Relationship to references |
|---|---|---|
| Common API | New `com.tencent.kuiklybase.datetime` types; no dependency on `kotlinx-datetime` | Independently implemented for the Mobile migration boundary |
| Android | `System.currentTimeMillis()` and one fresh `java.util.TimeZone.getDefault()` object per snapshot | Standard JDK/Android APIs; no copied source |
| iOS | `NSDate` wall clock; `NSTimeZone.resetSystemTimeZone()` before a fresh `systemTimeZone` offset read | Cache-reset requirement was confirmed against JetBrains/CPF Darwin behavior; implementation and API surface are new |
| OHOS | `OH_TimeService_GetTimeZone` for the current ID; a small POSIX `clock_gettime` / `tzset` / `localtime_r` bridge for wall time and offset | The CPF fork demonstrated the supported TimeService NDK API. KuiklyBase does not copy its `TzdbBionic` or timezone-rule parser |
| Freshness | No cross-call zone/offset cache; immutable snapshot plus `SystemTimeZone.isCurrent` active probe | New minimal contract; avoids retaining either upstream rules objects or a one-time Mobile host offset |
| Build | Normal Kotlin 2.1.21 Android/iOS tree and separate OHOS K/N 2.0.21-KBA-010 tree with `-ohos` version suffix | Follows the established KuiklyBase dual-tree publication convention |

No Kotlin/C source file from either reference was copied. If that changes in a
future patch, the modified file must retain the applicable copyright header,
carry a prominent modification notice, and this catalog must name it.

## Verification boundary

Deterministic common tests cover exact epoch forwarding, a local-midnight
boundary, both sides of a DST transition, a runtime timezone change, same-ID
rule changes, and invalid platform output. Android exercises a real
`java.util.TimeZone` DST/change sequence. iOS compares the implementation with
a freshly reset Foundation timezone. OHOS CI compiles the C interop and checks
the KBA publication metadata; an OHOS device runtime result must be reported
separately and must never be inferred from cross-compilation alone.

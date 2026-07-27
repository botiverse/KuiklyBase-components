# Mobile adoption plan (separate Phase 2)

This document defines the boundary only. The DatetimeKMM source PR must not
change Mobile, KuiklyUI, a consumer gitlink, route payloads, or release flags.

## Current boundary

- Common code declares `internal expect fun defaultNowMillis(): Long`.
- Android uses `System.currentTimeMillis()`.
- iOS uses POSIX `gettimeofday`.
- OHOS uses the KBA-supported `kotlin.system.getTimeMillis()` because Kotlin
  2.0.21-KBA-010 does not expose the newer common `kotlin.time.Clock.System`.
- All three platform hosts inject a snapshot offset into page data; the legacy
  payload fields are `localOffsetMinutes`, `todayEpochDay`, and `nowEpochMillis`:
  - Android host computes `TimeZone.getDefault().getOffset(nowMillis) / 60_000`.
  - Both iOS entries compute the offset from the current `NSTimeZone` at the
    page epoch (two navigation/root injection points, not one).
  - OHOS host computes the offset from the current system zone at the page epoch.
- Common formatting manually adds that snapshot offset. It can become stale
  after a runtime system-timezone change unless the host refreshes the page
  context. Removal/rollback must therefore cover all three hosts (Android and
  both iOS entries, and OHOS), not only the Android host.

## Legacy optional time foundation that Phase 2 must retire/fence

- Mobile still carries a canonical preparation script and inventory entry for
  `org.jetbrains.kotlinx:kotlinx-datetime:0.6.0-RC.2-KBA-003`. The current
  Gradle/source classpath does not actively consume it, but the optional path
  exists. Before Mobile adds the KuiklyBase datetime coordinate, Phase 2 must
  explicitly retire or fence that legacy `kotlinx-datetime` preparation so the
  two time foundations cannot be selected accidentally (no dual classpath, no
  ambiguous import, no inventory drift).

## Clock is a read source, not a scheduler

- KuiklyBase `Clock` only reads the wall clock; it is not a timer/scheduler and
  carries no tick/observable. Phase 2 must preserve Mobile's existing live
  minute-tick scheduler/observable layer that drives cross-minute and
  cross-midnight refresh. Replacing `defaultNowMillis()` must not silently
  remove that reactive refresh; the scheduler keeps its own cadence and simply
  reads `Clock.System.now()` (or an injected clock in tests) when it fires.

## Rollout order

1. Add the released DatetimeKMM normal/OHOS coordinates to the appropriate
   Mobile build trees. Keep the old implementation compiled and selectable.
2. Replace `defaultNowMillis()` call sites with an injected KuiklyBase `Clock`.
   Preserve fixed/fake clocks in tests. Compare old/new epoch values at the
   boundary before deleting any actual implementation.
3. Add a narrow timezone owner that calls `SystemTimeZone.snapshot(instant)`
   after the host's platform timezone-change signal. Do not cache only
   `offset.totalSeconds` or `localOffsetMinutes`.
4. Migrate consumers one route/page at a time. Continue accepting the existing
   `localOffsetMinutes`, `todayEpochDay`, and `nowEpochMillis` payload fields
   during the compatibility window so an app rollback does not break old
   page bundles.
5. Convert to minutes only at that legacy payload boundary with
   `toWholeMinutesExact()`. Historical second offsets require an explicit
   product fallback; truncation is forbidden.
6. Remove host-injected offset ownership only after Android, iOS, and OHOS
   runtime evidence proves fresh values across foreground/resume and a
   timezone change while the process remains alive.
7. Remove `defaultNowMillis()` and legacy payload fields in a later cleanup PR
   after the rollback window closes.

## Required Phase 2 evidence

- Same fixed epoch produces the same old/new result on all three build trees.
- UTC and local midnight boundaries do not select the wrong day.
- Known DST before/after instants produce the expected offset.
- Changing the system timezone while the process remains alive invalidates an
  old snapshot and refreshes the page/model without restart.
- The live minute-tick/cross-midnight observable still fires on schedule after
  the clock source is swapped.
- Route payloads remain backward-compatible during rollout.
- A feature/build flag can revert the owner to the legacy clock and injected
  offset without changing the server contract.
- The legacy `kotlinx-datetime:0.6.0-RC.2-KBA-003` preparation is retired or
  fenced and cannot be selected alongside the KuiklyBase coordinate.
- Android, iOS, and OHOS each have compile evidence; system-zone runtime claims
  are reported per platform and are never inherited from another platform.

## OHOS runtime is NOT verified by the foundation PR

- The DatetimeKMM Phase 1 PR cross-compiles the OHOS tree with KBA
  `2.0.21-KBA-010` and the TimeService cinterop; that is source/KBA compile
  evidence only. TimeService-ID vs POSIX-offset coherence on a real device,
  known-DST instants, and in-process timezone change/freshness remain
  `NOT RUN` until an OHOS device test covers them. Phase 2 must not claim OHOS
  system-zone runtime PASS from cross-compilation.

This migration must not be bundled with Forwarded task #821 or an unrelated
framework/toolchain upgrade.

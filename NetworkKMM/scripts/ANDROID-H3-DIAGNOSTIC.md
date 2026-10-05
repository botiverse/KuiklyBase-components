# Task 172: same-emulator H3 diagnostic (not for SDK merge)

This branch starts at `3a24164ce4a9f049adfe72d2da5aded63f452937`
(PR152 buffered-multi barrier + PR153 hosted-runner probes).
Dispatch `networkkmm-tests.yml` with `android_h3_diagnostic=true`.
This skips unrelated jobs and **does not constitute complete SDK validation**.
The original committed-library Android test and its assertions still run, and
its exit code is preserved after probes are collected. Neither the library nor
product network policy is changed.

The diagnostic builds a small Android executable that dynamically loads the
committed x86_64 `libnetworkkmmcurl.so`. Its easy requests use H3-only, H3 with
fallback, and H2; output records the actual version/features/TLS/QUIC backend,
verbose text (no body/headers), errors, remote address, protocol and timing.
It bypasses Kotlin/JNI and uses easy rather than the product multi engine.

An independent Debian curl rootfs is exported once and used on both the hosted
runner and the Android emulator (chroot). Source image digest, installed package
versions, executable/rootfs/CA/library hashes and current host results are
retained. A reference is only called known-good if that run actually succeeds.
Both guest probes use the same public origin, forced IPv4 address, and CA file,
with proxies disabled. Before/after controls bracket the original Android gate.
The original test retains normal DNS/address selection.

The disposable hosted emulator is rooted and SELinux is set permissive to run
the reference glibc userspace. Guest control is **not** the app UID, Android
resolver or product wrapper. A successful control narrows emulator reachability;
it alone proves neither wrapper correctness nor the original iOS user's cause.
Failure requires reading handshake logs and validating setup before attributing
it to network restrictions. These probes never waive the original assertions.

Evidence includes gate log/exit code, instrumentation logcat, explicit installed
APK SHA-256, test APK and embedded library SHA-256, guest input SHA-256, device
fingerprint, SELinux state, IP routes, UTC boundaries, host and guest controls.
The test APK's x86_64 library is checked against the committed library. Gradle
may uninstall the package on test completion; post-test package output records
that rather than pretending it remains installed.

Local checks: Android NDK28 cross-compilation with warnings-as-errors, Bash syntax,
shellcheck, actionlint (workflow-only validation; pre-existing SC2129 warnings in
unrelated jobs), and git diff whitespace checks. Runtime results come from the
bound hosted run, not these checks.

The follow-up adds direct curl multi controls and normal DNS/address selection
controls with the original library. It also observes the unchanged effective
native request at the test bridge, then invokes the existing fresh/blocking JNI
path with the same request policy in the **same app UID** after the original
multi call completes. Its result cannot replace the original assertion. This
separates configuration propagation, address selection and easy/multi behavior;
no product native binary is rebuilt.

The diagnostic additionally invokes `diagnosticPublicHttp3Path` in a separate
instrumentation process after the original full gate. This preserves H3 evidence
when an earlier gate aborts. Both exit codes and original XML reports are saved;
either failure leaves the workflow failed. This isolated call is not full-gate
acceptance, and no original assertion or product library is changed.

Buffered-multi logs availableProcessors, launch/completion counts and threads,
server arrival/barrier/error timestamps, plus a read-only reflection observation
of the bridge's existing asyncSubmitAvailable flag. A false flag before execute
selects the existing blocking compatibility path. Reflection failure is reported
as unobserved. No dispatcher, timeout, barrier, exception, or assertion behavior
is changed by this observation.

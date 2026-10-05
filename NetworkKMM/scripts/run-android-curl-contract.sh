#!/usr/bin/env bash
# Actual App/JNI/engine tests against the committed native artifact.
set -euo pipefail
mode="${1:?ordinary or positive}"
out="${2:?evidence directory}"
scope="${3:-full}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$out"
args=()
case "$mode" in
  ordinary)
    classes=com.tencent.kmm.network.internal.platform.AndroidCurlHttp3NegotiationInstrumentedTest
    if [ "$scope" = full ]; then
      classes="com.tencent.kmm.network.internal.platform.AndroidCurlRuntimeInstrumentedTest,$classes"
    fi
    ;;
  positive)
    classes=com.tencent.kmm.network.internal.platform.AndroidCurlHttp3PositiveInstrumentedTest
    test -n "${H3_EXPECTED_IPV4:?DNS fixture must publish its selected address}"
    args+=("-Pandroid.testInstrumentationRunnerArguments.h3ExpectedIpv4=$H3_EXPECTED_IPV4")
    adb shell settings put global private_dns_mode off
    ;;
  *) echo "Unknown mode: $mode" >&2; exit 2;;
esac
(cd "$root" && ./gradlew :network:assembleDebugAndroidTest --no-daemon --stacktrace)
mapfile -t apks < <(find "$root/network/build/outputs/apk/androidTest" -name '*.apk')
test "${#apks[@]}" = 1
adb install -t -r --bypass-low-target-sdk-block "${apks[0]}" > "$out/explicit-install.txt"
adb shell pm path com.tencent.tmm.networkkmm.test > "$out/installed-apk-path.txt"
installed="$(tr -d '\r' < "$out/installed-apk-path.txt" | sed -n 's/^package://p' | head -n 1)"
test -n "$installed"
adb shell "sha256sum '$installed'" > "$out/installed-apk-sha256.txt"
test "$(sha256sum "${apks[0]}" | cut -d ' ' -f 1)" = "$(cut -d ' ' -f 1 "$out/installed-apk-sha256.txt")"
python3 - "$root" "${apks[0]}" "$out" <<'PY'
import hashlib, json, pathlib, sys, zipfile
root, apk, out = map(pathlib.Path, sys.argv[1:])
source = hashlib.sha256((root/'network/libs/android/x86_64/libnetworkkmmcurl.so').read_bytes()).hexdigest()
with zipfile.ZipFile(apk) as z:
    embedded = hashlib.sha256(z.read('lib/x86_64/libnetworkkmmcurl.so')).hexdigest()
assert source == embedded, 'APK must use the committed x86_64 library'
(out/'identity.json').write_text(json.dumps({'apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
    'committed_library_sha256':source, 'embedded_library_sha256':embedded}, indent=2)+'\n')
PY
adb shell 'id; getprop ro.build.fingerprint; settings get global private_dns_mode' > "$out/device.txt"
adb logcat -c
adb logcat -v threadtime > "$out/logcat.txt" &
log_pid=$!
trap 'kill "$log_pid" 2>/dev/null || true' EXIT
set +e
(cd "$root" && ./gradlew :network:connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.class=$classes" "${args[@]}" \
  --no-daemon --stacktrace) 2>&1 | tee "$out/instrumentation.txt"
result=${PIPESTATUS[0]}
set -e
printf '%s\n' "$result" > "$out/exit.txt"
exit "$result"

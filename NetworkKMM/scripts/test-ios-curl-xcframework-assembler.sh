#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ASSEMBLER="$SCRIPT_DIR/assemble-ios-curl-xcframework.sh"
MATRIX="$SCRIPT_DIR/ios-curl-xcframework-matrix.tsv"
BUILD_SCRIPT="$SCRIPT_DIR/build-ios-curl.sh"
WORKFLOW="$(cd "$SCRIPT_DIR/../.." && pwd)/.github/workflows/networkkmm-ios-native.yml"
EXPECTED_PLIST_SHA256="bdf78905324f1c46d5620ead7d5211781c58fa2605b539d76bcba8aa4556b7e7"

temporary_root="$(mktemp -d "${TMPDIR:-/tmp}/networkkmm-xcframework-test.XXXXXX")"
cleanup() {
  rm -rf "$temporary_root"
}
trap cleanup EXIT

mkdir -p "$temporary_root/bin" "$temporary_root/device" "$temporary_root/simulator" "$temporary_root/Headers"
printf 'device-archive-byte-fixture\n' > "$temporary_root/device/libNetworkKMMCurl.a"
printf 'simulator-archive-byte-fixture\n' > "$temporary_root/simulator/libNetworkKMMCurl.a"
printf 'header-byte-fixture\n' > "$temporary_root/Headers/curl_wrapper.h"
cat > "$temporary_root/Headers/module.modulemap" <<'MODULEMAP'
module NetworkKMMCurl {
    header "curl_wrapper.h"
    export *
}
MODULEMAP

cat > "$temporary_root/bin/xcrun" <<'XCRUN'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == "lipo" && "$2" == "-archs" && $# -eq 3 ]] || exit 2
case "$3" in
  */device/libNetworkKMMCurl.a)
    if [[ "${BAD_DEVICE_ARCH:-0}" == "1" ]]; then
      echo "x86_64"
    else
      echo "arm64"
    fi
    ;;
  */simulator/libNetworkKMMCurl.a) echo "x86_64 arm64" ;;
  *) exit 2 ;;
esac
XCRUN
chmod +x "$temporary_root/bin/xcrun"

run_assembler() {
  local script="$1" matrix="$2" output="$3"
  PATH="$temporary_root/bin:$PATH" bash "$script" \
    --matrix "$matrix" \
    --device-library "$temporary_root/device/libNetworkKMMCurl.a" \
    --simulator-library "$temporary_root/simulator/libNetworkKMMCurl.a" \
    --headers "$temporary_root/Headers" \
    --output "$output"
}

run_assembler_with_bad_device_archive() {
  local output="$1"
  BAD_DEVICE_ARCH=1 PATH="$temporary_root/bin:$PATH" bash "$ASSEMBLER" \
    --matrix "$MATRIX" \
    --device-library "$temporary_root/device/libNetworkKMMCurl.a" \
    --simulator-library "$temporary_root/simulator/libNetworkKMMCurl.a" \
    --headers "$temporary_root/Headers" \
    --output "$output"
}

expect_failure() {
  local label="$1"
  shift
  if "$@" >"$temporary_root/$label.stdout" 2>"$temporary_root/$label.stderr"; then
    echo "Mutation unexpectedly passed: $label" >&2
    exit 1
  fi
  echo "mutation rejected: $label"
}

canonical_output="$temporary_root/canonical/NetworkKMMCurl.xcframework"
run_assembler "$ASSEMBLER" "$MATRIX" "$canonical_output"

[[ "$(shasum -a 256 "$canonical_output/Info.plist" | awk '{print $1}')" == "$EXPECTED_PLIST_SHA256" ]]
[[ "$(find "$canonical_output" -type f | wc -l | tr -d ' ')" == "7" ]]
cmp -s "$temporary_root/device/libNetworkKMMCurl.a" "$canonical_output/ios-arm64/libNetworkKMMCurl.a"
cmp -s "$temporary_root/simulator/libNetworkKMMCurl.a" "$canonical_output/ios-arm64_x86_64-simulator/libNetworkKMMCurl.a"

python3 - "$canonical_output/Info.plist" <<'PY'
import plistlib
import sys

with open(sys.argv[1], "rb") as source:
    payload = plistlib.load(source)

expected = {
    "AvailableLibraries": [
        {
            "BinaryPath": "libNetworkKMMCurl.a",
            "HeadersPath": "Headers",
            "LibraryIdentifier": "ios-arm64",
            "LibraryPath": "libNetworkKMMCurl.a",
            "SupportedArchitectures": ["arm64"],
            "SupportedPlatform": "ios",
        },
        {
            "BinaryPath": "libNetworkKMMCurl.a",
            "HeadersPath": "Headers",
            "LibraryIdentifier": "ios-arm64_x86_64-simulator",
            "LibraryPath": "libNetworkKMMCurl.a",
            "SupportedArchitectures": ["arm64", "x86_64"],
            "SupportedPlatform": "ios",
            "SupportedPlatformVariant": "simulator",
        },
    ],
    "CFBundlePackageType": "XFWK",
    "XCFrameworkFormatVersion": "1.0",
}
if payload != expected:
    raise SystemExit(f"unexpected plist payload: {payload!r}")
PY

matrix_data="$temporary_root/matrix.canonical.tsv"
grep -v '^#' "$MATRIX" > "$matrix_data"

awk 'NR == 1 { first = $0; next } NR == 2 { print; print first }' "$matrix_data" > "$temporary_root/matrix.reversed.tsv"
expect_failure reversed_library run_assembler "$ASSEMBLER" "$temporary_root/matrix.reversed.tsv" "$temporary_root/reversed/NetworkKMMCurl.xcframework"

sed -n '1p' "$matrix_data" > "$temporary_root/matrix.missing.tsv"
expect_failure missing_library run_assembler "$ASSEMBLER" "$temporary_root/matrix.missing.tsv" "$temporary_root/missing/NetworkKMMCurl.xcframework"

sed -n '1p;1p' "$matrix_data" > "$temporary_root/matrix.duplicate.tsv"
expect_failure duplicate_library run_assembler "$ASSEMBLER" "$temporary_root/matrix.duplicate.tsv" "$temporary_root/duplicate/NetworkKMMCurl.xcframework"

sed 's/libNetworkKMMCurl\.a/libWrong.a/' "$matrix_data" > "$temporary_root/matrix.bad-path.tsv"
expect_failure bad_path run_assembler "$ASSEMBLER" "$temporary_root/matrix.bad-path.tsv" "$temporary_root/bad-path/NetworkKMMCurl.xcframework"

awk 'BEGIN { FS = OFS = "\t" } NR == 1 { $3 = "WrongHeaders" } { print }' "$matrix_data" > "$temporary_root/matrix.bad-headers-path.tsv"
expect_failure bad_headers_path run_assembler "$ASSEMBLER" "$temporary_root/matrix.bad-headers-path.tsv" "$temporary_root/bad-headers-path/NetworkKMMCurl.xcframework"

awk 'BEGIN { FS = OFS = "\t" } NR == 1 { $4 = "macos" } { print }' "$matrix_data" > "$temporary_root/matrix.bad-platform.tsv"
expect_failure bad_platform run_assembler "$ASSEMBLER" "$temporary_root/matrix.bad-platform.tsv" "$temporary_root/bad-platform/NetworkKMMCurl.xcframework"

sed '2s/arm64,x86_64/arm64/' "$matrix_data" > "$temporary_root/matrix.bad-arch.tsv"
expect_failure bad_arch run_assembler "$ASSEMBLER" "$temporary_root/matrix.bad-arch.tsv" "$temporary_root/bad-arch/NetworkKMMCurl.xcframework"

awk 'BEGIN { FS = OFS = "\t" } NR == 2 { $5 = "device" } { print }' "$matrix_data" > "$temporary_root/matrix.bad-variant.tsv"
expect_failure bad_variant run_assembler "$ASSEMBLER" "$temporary_root/matrix.bad-variant.tsv" "$temporary_root/bad-variant/NetworkKMMCurl.xcframework"

sed '1s/ios-arm64/ios-device-arm64/' "$matrix_data" > "$temporary_root/matrix.bad-identifier.tsv"
expect_failure bad_identifier run_assembler "$ASSEMBLER" "$temporary_root/matrix.bad-identifier.tsv" "$temporary_root/bad-identifier/NetworkKMMCurl.xcframework"

expect_failure bad_archive_architecture run_assembler_with_bad_device_archive "$temporary_root/bad-archive-architecture/NetworkKMMCurl.xcframework"

random_plist="$temporary_root/random.plist"
printf '<plist><dict><key>random</key><true/></dict></plist>\n' > "$random_plist"
mutated_assembler="$temporary_root/assemble-random-plist-mutation.sh"
awk '
  { print }
  /MUTATION_POINT_RANDOM_PLIST_COPY/ {
    print "cp \"${MUTATION_RANDOM_PLIST:?}\" \"$output_dir/Info.plist\""
  }
' "$ASSEMBLER" > "$mutated_assembler"
chmod +x "$mutated_assembler"
expect_failure random_plist_input env MUTATION_RANDOM_PLIST="$random_plist" bash -c '
  PATH="$1:$PATH" bash "$2" \
    --matrix "$3" \
    --device-library "$4" \
    --simulator-library "$5" \
    --headers "$6" \
    --output "$7"
' _ "$temporary_root/bin" "$mutated_assembler" "$MATRIX" \
  "$temporary_root/device/libNetworkKMMCurl.a" \
  "$temporary_root/simulator/libNetworkKMMCurl.a" \
  "$temporary_root/Headers" \
  "$temporary_root/random-input/NetworkKMMCurl.xcframework"

# The restored canonical source must still reproduce the original bytes after
# every mutation above.
restored_output="$temporary_root/restored/NetworkKMMCurl.xcframework"
run_assembler "$ASSEMBLER" "$MATRIX" "$restored_output"
cmp -s "$canonical_output/Info.plist" "$restored_output/Info.plist"

# Cache identity is part of the contract, not a workflow comment.
grep -Fq 'xcframework-assembler=${XCFRAMEWORK_ASSEMBLER_SHA256}' "$BUILD_SCRIPT"
grep -Fq 'xcframework-matrix=${XCFRAMEWORK_MATRIX_SHA256}' "$BUILD_SCRIPT"
grep -Fq 'epoch-file=${SOURCE_DATE_EPOCH_FILE_SHA256}' "$BUILD_SCRIPT"
grep -Fq "NetworkKMM/scripts/assemble-ios-curl-xcframework.sh" "$WORKFLOW"
grep -Fq "NetworkKMM/scripts/ios-curl-xcframework-matrix.tsv" "$WORKFLOW"
grep -Fq "NetworkKMM/scripts/native-source-date-epoch.txt" "$WORKFLOW"

echo "XCFramework assembler contract PASS"

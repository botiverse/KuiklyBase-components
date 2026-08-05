#!/usr/bin/env bash
# Assemble the final NetworkKMMCurl XCFramework from a repository-owned fixed
# matrix. xcodebuild may validate the same input libraries in the caller, but
# its nondeterministically ordered Info.plist is deliberately not an input to
# this producer.
set -euo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage: assemble-ios-curl-xcframework.sh \
  --matrix <matrix.tsv> \
  --device-library <libNetworkKMMCurl.a> \
  --simulator-library <libNetworkKMMCurl.a> \
  --headers <Headers directory> \
  --output <NetworkKMMCurl.xcframework>
USAGE
  exit 2
}

matrix_file=""
device_library=""
simulator_library=""
headers_dir=""
output_dir=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --matrix)
      [[ $# -ge 2 ]] || usage
      matrix_file="$2"
      shift 2
      ;;
    --device-library)
      [[ $# -ge 2 ]] || usage
      device_library="$2"
      shift 2
      ;;
    --simulator-library)
      [[ $# -ge 2 ]] || usage
      simulator_library="$2"
      shift 2
      ;;
    --headers)
      [[ $# -ge 2 ]] || usage
      headers_dir="$2"
      shift 2
      ;;
    --output)
      [[ $# -ge 2 ]] || usage
      output_dir="$2"
      shift 2
      ;;
    *) usage ;;
  esac
done

[[ -n "$matrix_file" && -n "$device_library" && -n "$simulator_library" && -n "$headers_dir" && -n "$output_dir" ]] || usage
[[ -f "$matrix_file" ]] || { echo "XCFramework matrix not found: $matrix_file" >&2; exit 2; }
[[ -f "$device_library" ]] || { echo "Device archive not found: $device_library" >&2; exit 2; }
[[ -f "$simulator_library" ]] || { echo "Simulator archive not found: $simulator_library" >&2; exit 2; }
[[ -f "$headers_dir/curl_wrapper.h" ]] || { echo "curl_wrapper.h not found beneath: $headers_dir" >&2; exit 2; }
[[ -f "$headers_dir/module.modulemap" ]] || { echo "module.modulemap not found beneath: $headers_dir" >&2; exit 2; }
[[ "$(basename "$output_dir")" == "NetworkKMMCurl.xcframework" ]] || {
  echo "Output basename must be exactly NetworkKMMCurl.xcframework" >&2
  exit 2
}

expected_rows=()
expected_rows[0]=$'ios-arm64\tlibNetworkKMMCurl.a\tHeaders\tios\t-\tarm64'
expected_rows[1]=$'ios-arm64_x86_64-simulator\tlibNetworkKMMCurl.a\tHeaders\tios\tsimulator\tarm64,x86_64'

matrix_rows=()
while IFS= read -r line || [[ -n "$line" ]]; do
  [[ -z "$line" || "$line" == \#* ]] && continue
  matrix_rows[${#matrix_rows[@]}]="$line"
done < "$matrix_file"

[[ ${#matrix_rows[@]} -eq 2 ]] || {
  echo "XCFramework matrix must contain exactly two libraries" >&2
  exit 2
}
for index in 0 1; do
  [[ "${matrix_rows[$index]}" == "${expected_rows[$index]}" ]] || {
    echo "XCFramework matrix row $((index + 1)) is not the canonical fixed target" >&2
    echo "expected: ${expected_rows[$index]}" >&2
    echo "actual:   ${matrix_rows[$index]}" >&2
    exit 2
  }
done

normalized_arches() {
  local arches="$1"
  local arch
  for arch in $arches; do
    case "$arch" in
      arm64|x86_64) printf '%s\n' "$arch" ;;
      *) echo "Unsupported archive architecture: $arch" >&2; return 2 ;;
    esac
  done | LC_ALL=C sort -u | paste -sd, -
}

require_archive_arches() {
  local library="$1" expected_csv="$2" label="$3"
  local actual_raw actual expected
  actual_raw="$(xcrun lipo -archs "$library")" || {
    echo "Unable to inspect $label archive architectures" >&2
    exit 2
  }
  actual="$(normalized_arches "$actual_raw")"
  expected="$(normalized_arches "${expected_csv//,/ }")"
  [[ "$actual" == "$expected" ]] || {
    echo "$label archive architectures are '$actual', expected '$expected'" >&2
    exit 2
  }
}

require_archive_arches "$device_library" "arm64" "device"
require_archive_arches "$simulator_library" "arm64,x86_64" "simulator"

render_plist() {
  local destination="$1"
  local row identifier library_path headers_path platform variant architectures arch old_ifs
  {
    cat <<'PLIST_HEAD'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>AvailableLibraries</key>
	<array>
PLIST_HEAD
    for row in "${matrix_rows[@]}"; do
      IFS=$'\t' read -r identifier library_path headers_path platform variant architectures <<< "$row"
      cat <<PLIST_LIBRARY_HEAD
		<dict>
			<key>BinaryPath</key>
			<string>${library_path}</string>
			<key>HeadersPath</key>
			<string>${headers_path}</string>
			<key>LibraryIdentifier</key>
			<string>${identifier}</string>
			<key>LibraryPath</key>
			<string>${library_path}</string>
			<key>SupportedArchitectures</key>
			<array>
PLIST_LIBRARY_HEAD
      old_ifs="$IFS"
      IFS=','
      for arch in $architectures; do
        printf '\t\t\t\t<string>%s</string>\n' "$arch"
      done
      IFS="$old_ifs"
      cat <<PLIST_LIBRARY_PLATFORM
			</array>
			<key>SupportedPlatform</key>
			<string>${platform}</string>
PLIST_LIBRARY_PLATFORM
      if [[ "$variant" != "-" ]]; then
        cat <<PLIST_LIBRARY_VARIANT
			<key>SupportedPlatformVariant</key>
			<string>${variant}</string>
PLIST_LIBRARY_VARIANT
      fi
      cat <<'PLIST_LIBRARY_TAIL'
		</dict>
PLIST_LIBRARY_TAIL
    done
    cat <<'PLIST_TAIL'
	</array>
	<key>CFBundlePackageType</key>
	<string>XFWK</string>
	<key>XCFrameworkFormatVersion</key>
	<string>1.0</string>
</dict>
</plist>
PLIST_TAIL
  } > "$destination"
}

temporary_root="$(mktemp -d "${TMPDIR:-/tmp}/networkkmm-xcframework.XXXXXX")"
cleanup() {
  rm -rf "$temporary_root"
}
trap cleanup EXIT

staged="$temporary_root/NetworkKMMCurl.xcframework"
mkdir -p \
  "$staged/ios-arm64/Headers" \
  "$staged/ios-arm64_x86_64-simulator/Headers"
cp "$device_library" "$staged/ios-arm64/libNetworkKMMCurl.a"
cp "$simulator_library" "$staged/ios-arm64_x86_64-simulator/libNetworkKMMCurl.a"
for identifier in ios-arm64 ios-arm64_x86_64-simulator; do
  cp "$headers_dir/curl_wrapper.h" "$staged/$identifier/Headers/curl_wrapper.h"
  cp "$headers_dir/module.modulemap" "$staged/$identifier/Headers/module.modulemap"
done
render_plist "$staged/Info.plist"

mkdir -p "$(dirname "$output_dir")"
rm -rf "$output_dir"
mv "$staged" "$output_dir"

# MUTATION_POINT_RANDOM_PLIST_COPY
# Re-render independently after installation. This is both the stable-byte
# postcondition and the fail-closed tooth against restoring an arbitrary
# xcodebuild/random plist as the final producer input.
reference_plist="$temporary_root/reference.plist"
render_plist "$reference_plist"
cmp -s "$reference_plist" "$output_dir/Info.plist" || {
  echo "Final Info.plist is not the canonical fixed-matrix serialization" >&2
  exit 2
}
cmp -s "$device_library" "$output_dir/ios-arm64/libNetworkKMMCurl.a"
cmp -s "$simulator_library" "$output_dir/ios-arm64_x86_64-simulator/libNetworkKMMCurl.a"
for identifier in ios-arm64 ios-arm64_x86_64-simulator; do
  cmp -s "$headers_dir/curl_wrapper.h" "$output_dir/$identifier/Headers/curl_wrapper.h"
  cmp -s "$headers_dir/module.modulemap" "$output_dir/$identifier/Headers/module.modulemap"
done

actual_files="$(cd "$output_dir" && find . -type f | sed 's#^\./##' | LC_ALL=C sort)"
expected_files="$(cat <<'FILES'
Info.plist
ios-arm64/Headers/curl_wrapper.h
ios-arm64/Headers/module.modulemap
ios-arm64/libNetworkKMMCurl.a
ios-arm64_x86_64-simulator/Headers/curl_wrapper.h
ios-arm64_x86_64-simulator/Headers/module.modulemap
ios-arm64_x86_64-simulator/libNetworkKMMCurl.a
FILES
)"
[[ "$actual_files" == "$expected_files" ]] || {
  echo "Final XCFramework file set is not canonical" >&2
  diff -u <(printf '%s\n' "$expected_files") <(printf '%s\n' "$actual_files") >&2 || true
  exit 2
}

echo "Canonical XCFramework assembled: $output_dir"
echo "Info.plist SHA-256: $(shasum -a 256 "$output_dir/Info.plist" | awk '{print $1}')"

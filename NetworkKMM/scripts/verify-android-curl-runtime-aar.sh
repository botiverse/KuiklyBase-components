#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

normal_aar="${1:-$PROJECT_DIR/network/build/outputs/aar/network-release.aar}"
runtime_aar="${2:-$PROJECT_DIR/network-android-curl-runtime/build/outputs/aar/network-android-curl-runtime-release.aar}"

for artifact in "$normal_aar" "$runtime_aar"; do
  if [[ ! -f "$artifact" ]]; then
    echo "AAR not found: $artifact" >&2
    exit 1
  fi
done

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
mkdir -p "$tmp_dir/normal" "$tmp_dir/runtime"
unzip -q "$normal_aar" -d "$tmp_dir/normal"
unzip -q "$runtime_aar" -d "$tmp_dir/runtime"

normal_so_list="$tmp_dir/normal-so.txt"
find "$tmp_dir/normal" -type f -name '*.so' -print | LC_ALL=C sort > "$normal_so_list"
if [[ -s "$normal_so_list" ]]; then
  echo "Default NetworkKMM AAR must remain zero-so; found:" >&2
  cat "$normal_so_list" >&2
  exit 1
fi

expected_so_list="$tmp_dir/expected-so.txt"
actual_so_list="$tmp_dir/actual-so.txt"
printf '%s\n' \
  'jni/arm64-v8a/libnetworkkmmcurl.so' \
  'jni/x86_64/libnetworkkmmcurl.so' > "$expected_so_list"
find "$tmp_dir/runtime" -type f -name '*.so' -print \
  | sed "s#^$tmp_dir/runtime/##" \
  | LC_ALL=C sort > "$actual_so_list"
if ! diff -u "$expected_so_list" "$actual_so_list"; then
  echo "Android curl runtime AAR must contain exactly the two supported ABI payloads." >&2
  exit 1
fi

for abi in arm64-v8a x86_64; do
  source_so="$PROJECT_DIR/network/libs/android/$abi/libnetworkkmmcurl.so"
  packaged_so="$tmp_dir/runtime/jni/$abi/libnetworkkmmcurl.so"
  if [[ ! -s "$source_so" || ! -s "$packaged_so" ]]; then
    echo "Missing or empty curl payload for $abi." >&2
    exit 1
  fi
  if ! cmp -s "$source_so" "$packaged_so"; then
    echo "Packaged curl payload differs from committed source for $abi." >&2
    exit 1
  fi
done

if [[ -f "$tmp_dir/runtime/classes.jar" ]]; then
  if unzip -Z1 "$tmp_dir/runtime/classes.jar" | grep -Eq '\.class$'; then
    echo "Android curl runtime AAR must not contain Java/Kotlin classes." >&2
    exit 1
  fi
fi

echo "Android curl runtime AAR verified: default AAR zero-so; runtime AAR exact arm64-v8a/x86_64 payloads."

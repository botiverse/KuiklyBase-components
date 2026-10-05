#!/usr/bin/env bash
set -euo pipefail
[[ "$(uname -s)" == Darwin ]] || { echo 'Requires Apple linker/runtime' >&2; exit 1; }
root="$(cd "$(dirname "$0")/.." && pwd)"
include="${NETWORKKMM_COMPAT_INCLUDE_DIR:-$root/ohosApp/pbcurlwrapper/src/main/cpp/wrapper/include}"
fixtures="$root/tests/apple-optional-api"
# Consume the same optional-symbol link contract exported by the cinterop klib.
read -r -a linker_flags <<< "$(sed -n 's/^linkerOpts = //p' "$root/network/src/iosMain/c_interop/ios_curl.def")"
work="$(mktemp -d "${TMPDIR:-/tmp}/networkkmm-apple-optional.XXXXXX")"
trap 'rm -rf "$work"' EXIT
for mode in current old; do
  flags=()
  expected=0
  if [[ "$mode" == current ]]; then flags=(-DOPTIONAL_API); expected=1; fi
  for language in c c++; do
    compiler=clang
    [[ "$language" == c++ ]] && compiler=clang++
    # Compile both wrapper and caller with the same linkage for the test-only
    # counter. Public wrapper functions retain curl_wrapper.h's extern C ABI.
    xcrun "$compiler" -x "$language" -O2 -Wall -Wextra -Werror \
      -I "$include" "${flags[@]}" -c "$fixtures/wrapper.c" -o "$work/$mode-$language.o"
    xcrun ar rcs "$work/$mode-$language.a" "$work/$mode-$language.o"
    xcrun "$compiler" -x "$language" -O2 -Wall -Wextra -Werror -I "$include" \
      -DEXPECT_AVAILABLE="$expected" -c "$fixtures/client.c" -o "$work/client.o"
    xcrun "$compiler" "$work/client.o" "$work/$mode-$language.a" \
      "${linker_flags[@]}" -Wl,-dead_strip -Wl,-exported_symbol,_main -o "$work/probe"
    xcrun strip -x "$work/probe"
    xcrun dyld_info -exports "$work/probe" > "$work/exports"
    if grep -Eq '_(CreateCurlMultiEngine|GetCurlCompletionInfoV1)' "$work/exports"; then
      echo 'Test invalid: optional API unexpectedly exported' >&2; exit 1
    fi
    "$work/probe"
  done
done

# Optional direct check of the committed production iOS wrapper, on an
# explicitly selected simulator. Never select or boot somebody else's device.
if [[ -n "${NETWORKKMM_OPTIONAL_API_SIMULATOR_UDID:-}" ]]; then
  sdk="$(xcrun --sdk iphonesimulator --show-sdk-path)"
  arch="$(uname -m)"
  native="$root/network/libs/ios/NetworkKMMCurl.xcframework/ios-arm64_x86_64-simulator/libNetworkKMMCurl.a"
  xcrun clang -target "$arch-apple-ios15.0-simulator" -isysroot "$sdk" -O2 \
    -I "$include" "$fixtures/native-probe.c" "$native" "${linker_flags[@]}" \
    -Wl,-dead_strip -Wl,-exported_symbol,_main -o "$work/native-probe"
  xcrun simctl spawn "$NETWORKKMM_OPTIONAL_API_SIMULATOR_UDID" "$work/native-probe"
fi

#!/usr/bin/env bash
# Build the PRODUCTION iOS curl native artifact (task #24, consumed by the
# task #23 cinterop): the shared pbcurlwrapper merged with cross-compiled
# static OpenSSL/curl into ONE static library per slice, packaged as
#   network/libs/ios/NetworkKMMCurl.xcframework
#     ios-arm64/libNetworkKMMCurl.a                 (device)
#     ios-arm64_x86_64-simulator/libNetworkKMMCurl.a (fat simulator)
# each slice exposing Headers/curl_wrapper.h + module.modulemap. The KMP
# ios targets cinterop against the slice archives (staticLibraries), so the
# consumer needs no extra link flags. Descended from build-ios-curl-spike.sh
# (task #20); flags follow build-android-curl.sh for engine parity. The
# engine takes an app-provided CA path (SetCurlCaInfo) and stays fail-closed
# without one — no CA bundle ships here (Phase 3 #25 owns bundle strategy).
set -euo pipefail

OPENSSL_VERSION="${OPENSSL_VERSION:-3.5.4}"
CURL_VERSION="${CURL_VERSION:-8.16.0}"
# SHA-256 pins: same values as build-android-curl.sh — one source set, three
# platforms. No unchecked build-time downloads in the production line.
OPENSSL_SHA256="${OPENSSL_SHA256:-967311f84955316969bdb1d8d4b983718ef42338639c621ec4c34fddef355e99}"
CURL_SHA256="${CURL_SHA256:-a21e20476e39eca5a4fc5cfb00acf84bbc1f5d8443ec3853ad14c26b3c85b970}"
NGHTTP2_VERSION="${NGHTTP2_VERSION:-1.64.0}"
NGHTTP2_SHA256="${NGHTTP2_SHA256:-20e73f3cf9db3f05988996ac8b3a99ed529f4565ca91a49eb0550498e10621e8}"
IOS_DEPLOYMENT_TARGET="${IOS_DEPLOYMENT_TARGET:-12.0}"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "iOS builds require a macOS host (xcrun/xcodebuild)." >&2
  exit 2
fi
JOBS="$(sysctl -n hw.ncpu)"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NETWORK_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CPP_ROOT="${NETWORK_ROOT}/ohosApp/pbcurlwrapper/src/main/cpp"
WRAPPER_HEADER="${CPP_ROOT}/wrapper/include/curl_wrapper.h"
OUT_DIR="${NETWORK_ROOT}/network/libs/ios"
XCFRAMEWORK="${OUT_DIR}/NetworkKMMCurl.xcframework"
BUILD_ROOT="${IOS_CURL_BUILD_ROOT:-${NETWORK_ROOT}/build/ios-curl}"
DOWNLOADS_DIR="${BUILD_ROOT}/downloads"

if [[ ! -f "$WRAPPER_HEADER" ]]; then
  echo "Wrapper header not found: $WRAPPER_HEADER" >&2
  exit 2
fi

mkdir -p "$DOWNLOADS_DIR" "$OUT_DIR"

fetch() {
  local url="$1" dest="$2" sha="$3"
  if [[ ! -f "$dest" ]]; then
    curl -fsSL "$url" -o "$dest"
  fi
  echo "${sha}  ${dest}" | shasum -a 256 -c - >/dev/null || {
    echo "SHA-256 mismatch for $dest (source: $url)" >&2
    rm -f "$dest"
    exit 2
  }
}

fetch "https://github.com/openssl/openssl/releases/download/openssl-${OPENSSL_VERSION}/openssl-${OPENSSL_VERSION}.tar.gz" \
  "${DOWNLOADS_DIR}/openssl-${OPENSSL_VERSION}.tar.gz" "$OPENSSL_SHA256"
fetch "https://curl.se/download/curl-${CURL_VERSION}.tar.gz" \
  "${DOWNLOADS_DIR}/curl-${CURL_VERSION}.tar.gz" "$CURL_SHA256"
fetch "https://github.com/nghttp2/nghttp2/releases/download/v${NGHTTP2_VERSION}/nghttp2-${NGHTTP2_VERSION}.tar.gz" \
  "${DOWNLOADS_DIR}/nghttp2-${NGHTTP2_VERSION}.tar.gz" "$NGHTTP2_SHA256"

# min-version flag differs between device and simulator compilations.
min_flag() {
  local sdk="$1"
  if [[ "$sdk" == "iphoneos" ]]; then
    echo "-miphoneos-version-min=${IOS_DEPLOYMENT_TARGET}"
  else
    echo "-mios-simulator-version-min=${IOS_DEPLOYMENT_TARGET}"
  fi
}

# build_slice_arch <sdk> <arch> — cross-compiles OpenSSL + curl + wrapper for
# one (sdk, arch) pair and merges everything into a single static archive at
# ${BUILD_ROOT}/<sdk>-<arch>/libNetworkKMMCurl.a
build_slice_arch() {
  local sdk="$1" arch="$2"
  local slice_root="${BUILD_ROOT}/${sdk}-${arch}"
  local openssl_source="${slice_root}/openssl-${OPENSSL_VERSION}"
  local openssl_prefix="${slice_root}/openssl-out"
  local openssl_stamp="${openssl_prefix}/.build-config"
  local curl_source="${slice_root}/curl-${CURL_VERSION}"
  local curl_build="${slice_root}/curl-build"
  local curl_stamp="${curl_build}/.build-config"
  local nghttp2_source="${slice_root}/nghttp2-${NGHTTP2_VERSION}"
  local nghttp2_build="${slice_root}/nghttp2-build"
  local nghttp2_stamp="${nghttp2_build}/.build-config"
  local wrapper_build="${slice_root}/wrapper-build"
  local merged="${slice_root}/libNetworkKMMCurl.a"
  local build_config="${sdk}:${arch}:${IOS_DEPLOYMENT_TARGET}:${OPENSSL_VERSION}:${CURL_VERSION}:${NGHTTP2_VERSION}"
  local sdk_path
  sdk_path="$(xcrun --sdk "$sdk" --show-sdk-path)"

  local openssl_target
  if [[ "$sdk" == "iphoneos" ]]; then
    openssl_target="ios64-xcrun"
  else
    openssl_target="iossimulator-xcrun"
  fi

  mkdir -p "$slice_root" "$wrapper_build"

  echo "==> [${sdk}/${arch}] OpenSSL ${OPENSSL_VERSION}"
  if [[ ! -f "${openssl_prefix}/lib/libssl.a" || "$(cat "$openssl_stamp" 2>/dev/null)" != "$build_config" ]]; then
    rm -rf "$openssl_source" "$openssl_prefix"
    tar -xzf "${DOWNLOADS_DIR}/openssl-${OPENSSL_VERSION}.tar.gz" -C "$slice_root"
    (
      cd "$openssl_source"
      CFLAGS="-arch ${arch} $(min_flag "$sdk")" \
      LDFLAGS="-arch ${arch} $(min_flag "$sdk")" \
        ./Configure "$openssl_target" \
        no-shared no-tests no-apps no-docs \
        --prefix="$openssl_prefix" \
        --openssldir="$openssl_prefix/ssl"
      make -j"$JOBS" build_libs
      make install_dev
    ) >/dev/null
    printf '%s' "$build_config" > "$openssl_stamp"
  fi

  echo "==> [${sdk}/${arch}] nghttp2 ${NGHTTP2_VERSION}"
  if [[ ! -f "${nghttp2_build}/lib/libnghttp2.a" || "$(cat "$nghttp2_stamp" 2>/dev/null)" != "$build_config" ]]; then
    rm -rf "$nghttp2_source" "$nghttp2_build"
    tar -xzf "${DOWNLOADS_DIR}/nghttp2-${NGHTTP2_VERSION}.tar.gz" -C "$slice_root"
    cmake -S "$nghttp2_source" -B "$nghttp2_build" \
      -DCMAKE_SYSTEM_NAME=iOS \
      -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY \
      -DCMAKE_OSX_SYSROOT="$sdk" \
      -DCMAKE_OSX_ARCHITECTURES="$arch" \
      -DCMAKE_OSX_DEPLOYMENT_TARGET="$IOS_DEPLOYMENT_TARGET" \
      -DCMAKE_BUILD_TYPE=Release \
      -DENABLE_LIB_ONLY=ON \
      -DBUILD_SHARED_LIBS=OFF \
      -DBUILD_STATIC_LIBS=ON \
      -DENABLE_DOC=OFF \
      -DCMAKE_POSITION_INDEPENDENT_CODE=ON >/dev/null
    cmake --build "$nghttp2_build" -j"$JOBS" >/dev/null
    printf '%s' "$build_config" > "$nghttp2_stamp"
  fi

  echo "==> [${sdk}/${arch}] curl ${CURL_VERSION}"
  if [[ ! -f "${curl_build}/lib/libcurl.a" || "$(cat "$curl_stamp" 2>/dev/null)" != "$build_config" ]]; then
    rm -rf "$curl_source" "$curl_build"
    tar -xzf "${DOWNLOADS_DIR}/curl-${CURL_VERSION}.tar.gz" -C "$slice_root"
    # Explicit OpenSSL paths for parity with the Android line (toolchain
    # find-root rerooting swallows OPENSSL_ROOT_DIR there; harmless here).
    cmake -S "$curl_source" -B "$curl_build" \
      -DCMAKE_SYSTEM_NAME=iOS \
      -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY \
      -DCMAKE_OSX_SYSROOT="$sdk" \
      -DCMAKE_OSX_ARCHITECTURES="$arch" \
      -DCMAKE_OSX_DEPLOYMENT_TARGET="$IOS_DEPLOYMENT_TARGET" \
      -DCMAKE_BUILD_TYPE=Release \
      -DBUILD_SHARED_LIBS=OFF \
      -DBUILD_CURL_EXE=OFF \
      -DBUILD_LIBCURL_DOCS=OFF \
      -DBUILD_MISC_DOCS=OFF \
      -DENABLE_CURL_MANUAL=OFF \
      -DBUILD_TESTING=OFF \
      -DHTTP_ONLY=ON \
      -DCURL_USE_OPENSSL=ON \
      -DOPENSSL_ROOT_DIR="$openssl_prefix" \
      -DOPENSSL_USE_STATIC_LIBS=ON \
      -DOPENSSL_INCLUDE_DIR="${openssl_prefix}/include" \
      -DOPENSSL_CRYPTO_LIBRARY="${openssl_prefix}/lib/libcrypto.a" \
      -DOPENSSL_SSL_LIBRARY="${openssl_prefix}/lib/libssl.a" \
      -DCURL_USE_LIBPSL=OFF \
      -DCURL_USE_LIBSSH2=OFF \
      -DCURL_ZLIB=OFF \
      -DCURL_BROTLI=OFF \
      -DCURL_ZSTD=OFF \
      -DUSE_NGHTTP2=ON \
      -DNGHTTP2_INCLUDE_DIR="${nghttp2_source}/lib/includes" \
      -DNGHTTP2_LIBRARY="${nghttp2_build}/lib/libnghttp2.a" \
      -DUSE_LIBIDN2=OFF \
      -DCMAKE_POSITION_INDEPENDENT_CODE=ON
    cmake --build "$curl_build" -j"$JOBS" >/dev/null
    printf '%s' "$build_config" > "$curl_stamp"
  fi

  # HTTP/2 hard gate (same rationale as the Android/OHOS gates). Two layers:
  # generated config as the stable primary (decoupled from symbol names),
  # archive symbol as the pin-exact supplement. Mach-O prefixes C symbols
  # with "_" (xcrun nm prints _nghttp2_session_client_new3), so the symbol
  # match is end-anchored — grep -w can never match the prefixed form
  # because "_" is a word character. Here-string, not a pipe.
  if grep -q "#define USE_NGHTTP2 1" "$curl_build/lib/curl_config.h"; then
    echo "==> [${sdk}/${arch}] curl_config.h USE_NGHTTP2: ENABLED"
  else
    echo "[${sdk}/${arch}] curl_config.h USE_NGHTTP2: MISSING" >&2
    exit 2
  fi
  local curl_syms
  curl_syms="$(xcrun nm "$curl_build/lib/libcurl.a" 2>/dev/null || true)"
  if grep -q "nghttp2_session_client_new3$" <<<"$curl_syms"; then
    echo "==> [${sdk}/${arch}] libcurl HTTP/2 (nghttp2): ENABLED"
  else
    echo "[${sdk}/${arch}] libcurl HTTP/2 (nghttp2): MISSING" >&2
    exit 2
  fi

  echo "==> [${sdk}/${arch}] pbcurlwrapper + merge"
  local common_flags=(
    -arch "$arch"
    -isysroot "$sdk_path"
    "$(min_flag "$sdk")"
    -std=c++17
    -O2
    -fPIC
    -I "$CPP_ROOT"
    -I "$CPP_ROOT/wrapper/include"
    -I "$curl_source/include"
    -I "$curl_build/lib"
  )
  # No -fvisibility=hidden: cinterop links these archives statically, so
  # symbol resolution happens at link time; keep the object symbols global.
  xcrun --sdk "$sdk" clang++ "${common_flags[@]}" \
    -c "$CPP_ROOT/wrapper/src/curl_wrapper.cpp" -o "$wrapper_build/curl_wrapper.o"
  xcrun --sdk "$sdk" clang++ "${common_flags[@]}" \
    -c "$CPP_ROOT/wrapper/src/log/curl_log.cpp" -o "$wrapper_build/curl_log.o"
  xcrun --sdk "$sdk" clang++ "${common_flags[@]}" \
    -c "$CPP_ROOT/wrapper/src/utils/curl_utils.cpp" -o "$wrapper_build/curl_utils.o"

  rm -f "$merged"
  xcrun libtool -static -no_warning_for_no_symbols -o "$merged" \
    "$wrapper_build/curl_wrapper.o" \
    "$wrapper_build/curl_log.o" \
    "$wrapper_build/curl_utils.o" \
    "$curl_build/lib/libcurl.a" \
    "$nghttp2_build/lib/libnghttp2.a" \
    "$openssl_prefix/lib/libssl.a" \
    "$openssl_prefix/lib/libcrypto.a"
  echo "    $(du -h "$merged" | cut -f1)  $merged"
}

build_slice_arch iphoneos arm64
build_slice_arch iphonesimulator arm64
build_slice_arch iphonesimulator x86_64

echo "==> Fat simulator archive"
SIM_FAT_DIR="${BUILD_ROOT}/simulator-fat"
mkdir -p "$SIM_FAT_DIR"
xcrun lipo -create \
  "${BUILD_ROOT}/iphonesimulator-arm64/libNetworkKMMCurl.a" \
  "${BUILD_ROOT}/iphonesimulator-x86_64/libNetworkKMMCurl.a" \
  -output "${SIM_FAT_DIR}/libNetworkKMMCurl.a"

echo "==> Headers + modulemap"
HEADERS_DIR="${BUILD_ROOT}/Headers"
rm -rf "$HEADERS_DIR"
mkdir -p "$HEADERS_DIR"
cp "$WRAPPER_HEADER" "$HEADERS_DIR/"
cat > "$HEADERS_DIR/module.modulemap" <<'EOF'
module NetworkKMMCurl {
    header "curl_wrapper.h"
    export *
}
EOF

echo "==> NetworkKMMCurl.xcframework"
rm -rf "$XCFRAMEWORK"
xcodebuild -create-xcframework \
  -library "${BUILD_ROOT}/iphoneos-arm64/libNetworkKMMCurl.a" -headers "$HEADERS_DIR" \
  -library "${SIM_FAT_DIR}/libNetworkKMMCurl.a" -headers "$HEADERS_DIR" \
  -output "$XCFRAMEWORK"

echo "==> Verify exported wrapper surface"
for lib in "$XCFRAMEWORK"/ios-arm64/libNetworkKMMCurl.a \
           "$XCFRAMEWORK"/ios-arm64_x86_64-simulator/libNetworkKMMCurl.a; do
  test -f "$lib"
  # nm -g --defined-only prints "ADDR T _Name" lines (format verified in CI
  # diagnostics on 2026-07-10); -j is NOT portable across Xcode nm versions
  # and fails silently, so match the full-line format instead.
  syms="$(xcrun nm -g --defined-only -arch arm64 "$lib" 2>/dev/null || true)"
  for sym in _CreateCurlClient _DeleteCurlClient _Cancel _SetCurlCaInfo \
             _StartRequest _StartStreamRequest _StartUploadRequest; do
    # herestring, not a pipe: grep -q exits on first match and pipefail
    # would turn printf's SIGPIPE into a pipeline failure.
    grep -q " T ${sym}\$" <<<"$syms" || {
      echo "Missing exported symbol ${sym} in ${lib}" >&2
      echo "-- exported entry-point-family globals:" >&2
      grep -E "Start|Cancel|CurlClient|SetCurl" <<<"$syms" | head -30 >&2 || true
      exit 2
    }
  done
done

echo "==> Artifact"
du -sh "$XCFRAMEWORK"
find "$XCFRAMEWORK" -type f | sort

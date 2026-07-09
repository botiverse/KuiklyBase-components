#!/usr/bin/env bash
set -euo pipefail

OPENSSL_VERSION="${OPENSSL_VERSION:-3.5.4}"
CURL_VERSION="${CURL_VERSION:-8.16.0}"
IOS_DEPLOYMENT_TARGET="${IOS_DEPLOYMENT_TARGET:-12.0}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NETWORK_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CPP_ROOT="${NETWORK_ROOT}/ohosApp/pbcurlwrapper/src/main/cpp"
SPIKE_ROOT="${NETWORK_ROOT}/spikes/ios-curl"
BUILD_ROOT="${IOS_CURL_SPIKE_BUILD_ROOT:-${NETWORK_ROOT}/build/ios-curl-spike}"
DOWNLOADS_DIR="${BUILD_ROOT}/downloads"
OPENSSL_SOURCE="${BUILD_ROOT}/openssl-${OPENSSL_VERSION}"
OPENSSL_PREFIX="${BUILD_ROOT}/openssl-out"
OPENSSL_STAMP="${OPENSSL_PREFIX}/.ios-deployment-target"
CURL_SOURCE="${BUILD_ROOT}/curl-${CURL_VERSION}"
CURL_BUILD="${BUILD_ROOT}/curl-build"
WRAPPER_BUILD="${BUILD_ROOT}/wrapper-build"
APP_DIR="${BUILD_ROOT}/NetworkKMMCurlSpike.app"
APP_EXECUTABLE="${APP_DIR}/NetworkKMMCurlSpike"
BUNDLE_ID="com.tencent.networkkmm.curl-spike"

RUN_SPIKE=0
if [[ "${1:-}" == "--run" ]]; then
  RUN_SPIKE=1
fi

SDK="iphonesimulator"
ARCH="arm64"
SDK_PATH="$(xcrun --sdk "$SDK" --show-sdk-path)"

mkdir -p "$DOWNLOADS_DIR" "$WRAPPER_BUILD" "$APP_DIR"

fetch() {
  local url="$1" output="$2"
  if [[ ! -f "$output" ]]; then
    curl -fsSL "$url" -o "$output"
  fi
}

echo "==> Building OpenSSL ${OPENSSL_VERSION} for arm64 iOS Simulator"
fetch \
  "https://github.com/openssl/openssl/releases/download/openssl-${OPENSSL_VERSION}/openssl-${OPENSSL_VERSION}.tar.gz" \
  "${DOWNLOADS_DIR}/openssl-${OPENSSL_VERSION}.tar.gz"
if [[ ! -f "${OPENSSL_PREFIX}/lib/libssl.a" || ! -f "${OPENSSL_PREFIX}/lib/libcrypto.a" || ! -f "$OPENSSL_STAMP" || "$(cat "$OPENSSL_STAMP" 2>/dev/null)" != "$IOS_DEPLOYMENT_TARGET" ]]; then
  rm -rf "$OPENSSL_SOURCE" "$OPENSSL_PREFIX"
  tar -xf "${DOWNLOADS_DIR}/openssl-${OPENSSL_VERSION}.tar.gz" -C "$BUILD_ROOT"
  (
    cd "$OPENSSL_SOURCE"
    CFLAGS="-mios-simulator-version-min=${IOS_DEPLOYMENT_TARGET}" \
    LDFLAGS="-mios-simulator-version-min=${IOS_DEPLOYMENT_TARGET}" \
      ./Configure iossimulator-xcrun \
      no-shared no-tests no-apps no-docs \
      --prefix="$OPENSSL_PREFIX" \
      --openssldir="$OPENSSL_PREFIX/ssl"
    make -j"$(sysctl -n hw.ncpu)" build_libs
    make install_dev
  )
  printf '%s' "$IOS_DEPLOYMENT_TARGET" > "$OPENSSL_STAMP"
fi

echo "==> Building curl ${CURL_VERSION} with OpenSSL"
fetch \
  "https://curl.se/download/curl-${CURL_VERSION}.tar.gz" \
  "${DOWNLOADS_DIR}/curl-${CURL_VERSION}.tar.gz"
if [[ ! -f "${CURL_BUILD}/lib/libcurl.a" ]]; then
  rm -rf "$CURL_SOURCE" "$CURL_BUILD"
  tar -xf "${DOWNLOADS_DIR}/curl-${CURL_VERSION}.tar.gz" -C "$BUILD_ROOT"
  cmake -S "$CURL_SOURCE" -B "$CURL_BUILD" \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_SYSROOT="$SDK" \
    -DCMAKE_OSX_ARCHITECTURES="$ARCH" \
    -DCMAKE_OSX_DEPLOYMENT_TARGET="$IOS_DEPLOYMENT_TARGET" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DBUILD_CURL_EXE=OFF \
    -DBUILD_LIBCURL_DOCS=OFF \
    -DBUILD_MISC_DOCS=OFF \
    -DENABLE_CURL_MANUAL=OFF \
    -DBUILD_TESTING=OFF \
    -DCURL_USE_OPENSSL=ON \
    -DOPENSSL_ROOT_DIR="$OPENSSL_PREFIX" \
    -DOPENSSL_USE_STATIC_LIBS=ON \
    -DOPENSSL_INCLUDE_DIR="${OPENSSL_PREFIX}/include" \
    -DOPENSSL_CRYPTO_LIBRARY="${OPENSSL_PREFIX}/lib/libcrypto.a" \
    -DOPENSSL_SSL_LIBRARY="${OPENSSL_PREFIX}/lib/libssl.a" \
    -DCURL_USE_LIBPSL=OFF \
    -DCURL_USE_LIBSSH2=OFF \
    -DCURL_ZLIB=OFF \
    -DCURL_BROTLI=OFF \
    -DCURL_ZSTD=OFF \
    -DUSE_NGHTTP2=OFF \
    -DUSE_LIBIDN2=OFF \
    -DCURL_DISABLE_LDAP=ON \
    -DCURL_DISABLE_LDAPS=ON \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
  cmake --build "$CURL_BUILD" -j"$(sysctl -n hw.ncpu)"
fi

echo "==> Compiling pbcurlwrapper for iOS Simulator"
COMMON_CXX_FLAGS=(
  -arch "$ARCH"
  -isysroot "$SDK_PATH"
  -mios-simulator-version-min="$IOS_DEPLOYMENT_TARGET"
  -std=c++17
  -fPIC
  -I "$CPP_ROOT"
  -I "$CPP_ROOT/wrapper/include"
  -I "$CURL_SOURCE/include"
  -I "$CURL_BUILD/lib"
)
xcrun --sdk "$SDK" clang++ "${COMMON_CXX_FLAGS[@]}" \
  -c "$CPP_ROOT/wrapper/src/curl_wrapper.cpp" \
  -o "$WRAPPER_BUILD/curl_wrapper.o"
xcrun --sdk "$SDK" clang++ "${COMMON_CXX_FLAGS[@]}" \
  -c "$CPP_ROOT/wrapper/src/log/curl_log.cpp" \
  -o "$WRAPPER_BUILD/curl_log.o"
xcrun --sdk "$SDK" clang++ "${COMMON_CXX_FLAGS[@]}" \
  -c "$CPP_ROOT/wrapper/src/utils/curl_utils.cpp" \
  -o "$WRAPPER_BUILD/curl_utils.o"
xcrun libtool -static \
  -o "$WRAPPER_BUILD/libpbcurlwrapper.a" \
  "$WRAPPER_BUILD/curl_wrapper.o" \
  "$WRAPPER_BUILD/curl_log.o" \
  "$WRAPPER_BUILD/curl_utils.o"

echo "==> Assembling simulator app"
cp "$SPIKE_ROOT/Info.plist" "$APP_DIR/Info.plist"
fetch "https://curl.se/ca/cacert.pem" "$APP_DIR/cacert.pem"
xcrun --sdk "$SDK" clang++ \
  -arch "$ARCH" \
  -isysroot "$SDK_PATH" \
  -mios-simulator-version-min="$IOS_DEPLOYMENT_TARGET" \
  -fobjc-arc \
  -std=c++17 \
  -I "$CPP_ROOT/wrapper/include" \
  "$SPIKE_ROOT/main.mm" \
  "$WRAPPER_BUILD/libpbcurlwrapper.a" \
  "$CURL_BUILD/lib/libcurl.a" \
  "$OPENSSL_PREFIX/lib/libssl.a" \
  "$OPENSSL_PREFIX/lib/libcrypto.a" \
  -framework UIKit \
  -framework Foundation \
  -framework Security \
  -framework SystemConfiguration \
  -lz \
  -o "$APP_EXECUTABLE"
codesign --force --sign - "$APP_DIR" >/dev/null

echo "==> Artifact sizes"
du -h \
  "$OPENSSL_PREFIX/lib/libcrypto.a" \
  "$OPENSSL_PREFIX/lib/libssl.a" \
  "$CURL_BUILD/lib/libcurl.a" \
  "$WRAPPER_BUILD/libpbcurlwrapper.a" \
  "$APP_EXECUTABLE"

if [[ "$RUN_SPIKE" -eq 1 ]]; then
  echo "==> Installing and launching on the booted simulator"
  xcrun simctl install booted "$APP_DIR"
  xcrun simctl launch --terminate-running-process --console-pty booted "$BUNDLE_ID"
fi

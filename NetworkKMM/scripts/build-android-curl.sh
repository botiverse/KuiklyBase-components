#!/usr/bin/env bash
# Build the PRODUCTION Android curl native artifact (task #24): the shared
# pbcurlwrapper + the task #22 JNI shim, statically linked against
# cross-compiled OpenSSL/curl, into per-ABI libnetworkkmmcurl.so under
# network/libs/android/<abi>/. The default Android AAR intentionally excludes
# these artifacts; the instrumentation runtime gate injects them into its test
# APK. Descended from build-android-curl-spike.sh (task #20), minus the spike
# app/CA download: the production engine takes an app-provided CA path
# (SetCurlCaInfo, Phase 3 #25 owns bundle strategy) and stays fail-closed
# without one.
set -euo pipefail

OPENSSL_VERSION="${OPENSSL_VERSION:-3.5.4}"
CURL_VERSION="${CURL_VERSION:-8.16.0}"
# SHA-256 pins (task #24 carry-forward from the spike review: no unchecked
# build-time downloads in the production line).
OPENSSL_SHA256="${OPENSSL_SHA256:-967311f84955316969bdb1d8d4b983718ef42338639c621ec4c34fddef355e99}"
CURL_SHA256="${CURL_SHA256:-a21e20476e39eca5a4fc5cfb00acf84bbc1f5d8443ec3853ad14c26b3c85b970}"
NGHTTP2_VERSION="${NGHTTP2_VERSION:-1.64.0}"
NGHTTP2_SHA256="${NGHTTP2_SHA256:-20e73f3cf9db3f05988996ac8b3a99ed529f4565ca91a49eb0550498e10621e8}"
NGHTTP3_VERSION="${NGHTTP3_VERSION:-1.17.0}"
NGHTTP3_SHA256="${NGHTTP3_SHA256:-9635173e703174a41f9abd0d790e70562c74ec3805064403477db5a1ef94b8f5}"
ANDROID_API="${ANDROID_API:-23}"
ANDROID_ABI="${ANDROID_ABI:-arm64-v8a}"
NDK_VERSION="${NDK_VERSION:-28.0.13004108}"
if [[ -z "${ANDROID_SDK_ROOT:-}" ]]; then
  if [[ "$(uname -s)" == "Darwin" ]]; then
    ANDROID_SDK_ROOT="${HOME}/Library/Android/sdk"
  else
    ANDROID_SDK_ROOT="${HOME}/Android/Sdk"
  fi
fi
ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-${ANDROID_SDK_ROOT}/ndk/${NDK_VERSION}}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NETWORK_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CPP_ROOT="${NETWORK_ROOT}/ohosApp/pbcurlwrapper/src/main/cpp"
SHIM_SOURCE="${NETWORK_ROOT}/network/src/androidMain/cpp/networkkmm_curl_jni.cpp"
OUT_ROOT="${NETWORK_ROOT}/network/libs/android"
OUT_ABI_DIR="${OUT_ROOT}/${ANDROID_ABI}"
BUILD_ROOT="${ANDROID_CURL_BUILD_ROOT:-${NETWORK_ROOT}/build/android-curl}/${ANDROID_ABI}"
DOWNLOADS_DIR="${BUILD_ROOT}/downloads"
OPENSSL_SOURCE="${BUILD_ROOT}/openssl-${OPENSSL_VERSION}"
OPENSSL_PREFIX="${BUILD_ROOT}/openssl-out"
OPENSSL_STAMP="${OPENSSL_PREFIX}/.android-build-config"
NGHTTP2_SOURCE="${BUILD_ROOT}/nghttp2-${NGHTTP2_VERSION}"
NGHTTP2_BUILD="${BUILD_ROOT}/nghttp2-build"
NGHTTP2_STAMP="${NGHTTP2_BUILD}/.android-build-config"
NGHTTP3_SOURCE="${BUILD_ROOT}/nghttp3-${NGHTTP3_VERSION}"
NGHTTP3_BUILD="${BUILD_ROOT}/nghttp3-build"
NGHTTP3_PREFIX="${BUILD_ROOT}/nghttp3-out"
NGHTTP3_STAMP="${NGHTTP3_PREFIX}/.android-build-config"
CURL_SOURCE="${BUILD_ROOT}/curl-${CURL_VERSION}"
CURL_BUILD="${BUILD_ROOT}/curl-build"
CURL_STAMP="${CURL_BUILD}/.android-build-config"
BUILD_CONFIG="${NDK_VERSION}:${ANDROID_API}:${ANDROID_ABI}:${OPENSSL_VERSION}:${CURL_VERSION}:${NGHTTP2_VERSION}:${NGHTTP3_VERSION}"

if [[ ! -f "$SHIM_SOURCE" ]]; then
  echo "JNI shim not found: $SHIM_SOURCE" >&2
  exit 2
fi
if [[ ! -d "$ANDROID_NDK_ROOT" ]]; then
  echo "Android NDK not found: $ANDROID_NDK_ROOT" >&2
  exit 2
fi

case "$(uname -s)" in
  Darwin)
    HOST_TAG="darwin-x86_64"
    JOBS="$(sysctl -n hw.ncpu)"
    ;;
  Linux)
    HOST_TAG="linux-x86_64"
    JOBS="$(nproc)"
    ;;
  *)
    echo "Unsupported Android build host: $(uname -s)" >&2
    exit 2
    ;;
esac

case "$ANDROID_ABI" in
  arm64-v8a)
    OPENSSL_TARGET="android-arm64"
    CLANG_TRIPLE="aarch64-linux-android"
    ;;
  x86_64)
    OPENSSL_TARGET="android-x86_64"
    CLANG_TRIPLE="x86_64-linux-android"
    ;;
  *)
    echo "Unsupported Android ABI: $ANDROID_ABI" >&2
    exit 2
    ;;
esac

TOOLCHAIN_ROOT="${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/${HOST_TAG}"
CXX="${TOOLCHAIN_ROOT}/bin/${CLANG_TRIPLE}${ANDROID_API}-clang++"
STRIP="${TOOLCHAIN_ROOT}/bin/llvm-strip"

mkdir -p "$DOWNLOADS_DIR" "$OUT_ABI_DIR"

fetch() {
  local url="$1" dest="$2" sha="$3"
  if [[ ! -f "$dest" ]]; then
    curl -fsSL "$url" -o "$dest"
  fi
  echo "${sha}  ${dest}" | sha256sum -c - >/dev/null || {
    echo "SHA-256 mismatch for $dest (source: $url)" >&2
    rm -f "$dest"
    exit 2
  }
}

echo "==> Building OpenSSL ${OPENSSL_VERSION} for ${ANDROID_ABI}"
fetch "https://github.com/openssl/openssl/releases/download/openssl-${OPENSSL_VERSION}/openssl-${OPENSSL_VERSION}.tar.gz" \
  "${DOWNLOADS_DIR}/openssl-${OPENSSL_VERSION}.tar.gz" "$OPENSSL_SHA256"
if [[ ! -f "${OPENSSL_PREFIX}/lib/libssl.a" || "$(cat "$OPENSSL_STAMP" 2>/dev/null)" != "$BUILD_CONFIG" ]]; then
  rm -rf "$OPENSSL_SOURCE" "$OPENSSL_PREFIX"
  tar -xzf "${DOWNLOADS_DIR}/openssl-${OPENSSL_VERSION}.tar.gz" -C "$BUILD_ROOT"
  (
    cd "$OPENSSL_SOURCE"
    ANDROID_NDK_ROOT="$ANDROID_NDK_ROOT" \
    PATH="${TOOLCHAIN_ROOT}/bin:${PATH}" \
      ./Configure "$OPENSSL_TARGET" \
      -D__ANDROID_API__="$ANDROID_API" \
      no-shared no-tests no-apps no-docs \
      --prefix="$OPENSSL_PREFIX" \
      --openssldir="$OPENSSL_PREFIX/ssl"
    PATH="${TOOLCHAIN_ROOT}/bin:${PATH}" make -j"$JOBS" build_libs
    PATH="${TOOLCHAIN_ROOT}/bin:${PATH}" make install_dev
  ) >/dev/null
  printf '%s' "$BUILD_CONFIG" > "$OPENSSL_STAMP"
fi

echo "==> Building nghttp2 ${NGHTTP2_VERSION} for ${ANDROID_ABI}"
fetch "https://github.com/nghttp2/nghttp2/releases/download/v${NGHTTP2_VERSION}/nghttp2-${NGHTTP2_VERSION}.tar.gz" \
  "${DOWNLOADS_DIR}/nghttp2-${NGHTTP2_VERSION}.tar.gz" "$NGHTTP2_SHA256"
if [[ ! -f "${NGHTTP2_BUILD}/lib/libnghttp2.a" || "$(cat "$NGHTTP2_STAMP" 2>/dev/null)" != "$BUILD_CONFIG" ]]; then
  rm -rf "$NGHTTP2_SOURCE" "$NGHTTP2_BUILD"
  tar -xzf "${DOWNLOADS_DIR}/nghttp2-${NGHTTP2_VERSION}.tar.gz" -C "$BUILD_ROOT"
  cmake -S "$NGHTTP2_SOURCE" -B "$NGHTTP2_BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK_ROOT}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ANDROID_ABI" \
    -DANDROID_PLATFORM="android-${ANDROID_API}" \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DENABLE_LIB_ONLY=ON \
    -DBUILD_SHARED_LIBS=OFF \
    -DBUILD_STATIC_LIBS=ON \
    -DENABLE_DOC=OFF \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON >/dev/null
  cmake --build "$NGHTTP2_BUILD" -j"$JOBS" >/dev/null
  printf '%s' "$BUILD_CONFIG" > "$NGHTTP2_STAMP"
fi

echo "==> Building nghttp3 ${NGHTTP3_VERSION} for ${ANDROID_ABI}"
fetch "https://github.com/ngtcp2/nghttp3/releases/download/v${NGHTTP3_VERSION}/nghttp3-${NGHTTP3_VERSION}.tar.gz" \
  "${DOWNLOADS_DIR}/nghttp3-${NGHTTP3_VERSION}.tar.gz" "$NGHTTP3_SHA256"
if [[ ! -f "${NGHTTP3_PREFIX}/lib/libnghttp3.a" || "$(cat "$NGHTTP3_STAMP" 2>/dev/null)" != "$BUILD_CONFIG" ]]; then
  rm -rf "$NGHTTP3_SOURCE" "$NGHTTP3_BUILD" "$NGHTTP3_PREFIX"
  tar -xzf "${DOWNLOADS_DIR}/nghttp3-${NGHTTP3_VERSION}.tar.gz" -C "$BUILD_ROOT"
  cmake -S "$NGHTTP3_SOURCE" -B "$NGHTTP3_BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK_ROOT}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ANDROID_ABI" \
    -DANDROID_PLATFORM="android-${ANDROID_API}" \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$NGHTTP3_PREFIX" \
    -DENABLE_LIB_ONLY=ON \
    -DENABLE_SHARED_LIB=OFF \
    -DENABLE_STATIC_LIB=ON \
    -DBUILD_TESTING=OFF \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON >/dev/null
  cmake --build "$NGHTTP3_BUILD" -j"$JOBS" --target install >/dev/null
  printf '%s' "$BUILD_CONFIG" > "$NGHTTP3_STAMP"
fi

echo "==> Building curl ${CURL_VERSION} for ${ANDROID_ABI}"
fetch "https://curl.se/download/curl-${CURL_VERSION}.tar.gz" \
  "${DOWNLOADS_DIR}/curl-${CURL_VERSION}.tar.gz" "$CURL_SHA256"
if [[ ! -f "${CURL_BUILD}/lib/libcurl.a" || "$(cat "$CURL_STAMP" 2>/dev/null)" != "$BUILD_CONFIG" ]]; then
  rm -rf "$CURL_SOURCE" "$CURL_BUILD"
  tar -xzf "${DOWNLOADS_DIR}/curl-${CURL_VERSION}.tar.gz" -C "$BUILD_ROOT"
  # Explicit OpenSSL paths: the NDK toolchain file re-roots find_library into
  # the sysroot (CMAKE_FIND_ROOT_PATH_MODE=ONLY), so OPENSSL_ROOT_DIR alone is
  # ignored — the spike learned this the hard way; keep its proven invocation.
  cmake -S "$CURL_SOURCE" -B "$CURL_BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK_ROOT}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ANDROID_ABI" \
    -DANDROID_PLATFORM="android-${ANDROID_API}" \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DBUILD_CURL_EXE=OFF \
    -DBUILD_LIBCURL_DOCS=OFF \
    -DBUILD_MISC_DOCS=OFF \
    -DENABLE_CURL_MANUAL=OFF \
    -DBUILD_TESTING=OFF \
    -DHTTP_ONLY=ON \
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
    -DUSE_NGHTTP2=ON \
    -DNGHTTP2_INCLUDE_DIR="${NGHTTP2_SOURCE}/lib/includes" \
    -DNGHTTP2_LIBRARY="${NGHTTP2_BUILD}/lib/libnghttp2.a" \
    -DUSE_OPENSSL_QUIC=ON \
    -DNGHTTP3_INCLUDE_DIR="${NGHTTP3_PREFIX}/include" \
    -DNGHTTP3_LIBRARY="${NGHTTP3_PREFIX}/lib/libnghttp3.a" \
    -DUSE_LIBIDN2=OFF \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON >/dev/null
  cmake --build "$CURL_BUILD" -j"$JOBS" >/dev/null
  printf '%s' "$BUILD_CONFIG" > "$CURL_STAMP"
fi

# HTTP/2 + HTTP/3 hard gates: curl's cmake can silently drop an optional
# backend if cross-compilation detection wobbles. Treat generated config as
# the stable primary gate and pin-exact archive references as supplements.
for define in USE_NGHTTP2 USE_OPENSSL_QUIC USE_NGHTTP3; do
  if grep -q "#define ${define} 1" "$CURL_BUILD/lib/curl_config.h"; then
    echo "curl_config.h ${define}: ENABLED"
  else
    echo "curl_config.h ${define}: MISSING" >&2
    exit 2
  fi
done
CURL_SYMS="$("${TOOLCHAIN_ROOT}/bin/llvm-nm" "$CURL_BUILD/lib/libcurl.a" 2>/dev/null || true)"
check_curl_reference() {
  local label="$1" symbol="$2"
  if grep -qw "$symbol" <<<"$CURL_SYMS"; then
    echo "libcurl ${label}: ENABLED (${symbol} referenced)"
  else
    echo "libcurl ${label}: MISSING — ${symbol} not referenced" >&2
    exit 2
  fi
}
check_curl_reference "HTTP/2 (nghttp2)" "nghttp2_session_client_new3"
check_curl_reference "HTTP/3 (nghttp3)" "nghttp3_conn_client_new_versioned"
check_curl_reference "HTTP/3 (OpenSSL stream)" "SSL_new_stream"
check_curl_reference "HTTP/3 (OpenSSL QUIC method)" "OSSL_QUIC_client_method"

echo "==> Linking libnetworkkmmcurl.so (${ANDROID_ABI})"
"$CXX" \
  -shared \
  -std=c++17 \
  -O2 \
  -fPIC \
  -ffunction-sections \
  -fdata-sections \
  -fvisibility=hidden \
  -Wl,--gc-sections \
  -Wl,-z,max-page-size=16384 \
  -static-libstdc++ \
  -I "$CPP_ROOT" \
  -I "$CPP_ROOT/include" \
  -I "$CPP_ROOT/wrapper/include" \
  -I "$CURL_SOURCE/include" \
  -I "$CURL_BUILD/lib" \
  "$CPP_ROOT/wrapper/src/curl_wrapper.cpp" \
  "$CPP_ROOT/wrapper/src/log/curl_log.cpp" \
  "$CPP_ROOT/wrapper/src/utils/curl_utils.cpp" \
  "$SHIM_SOURCE" \
  "$CURL_BUILD/lib/libcurl.a" \
  "$NGHTTP2_BUILD/lib/libnghttp2.a" \
  "$NGHTTP3_PREFIX/lib/libnghttp3.a" \
  "$OPENSSL_PREFIX/lib/libssl.a" \
  "$OPENSSL_PREFIX/lib/libcrypto.a" \
  -llog \
  -lz \
  -ldl \
  -latomic \
  -o "$OUT_ABI_DIR/libnetworkkmmcurl.so"
"$STRIP" --strip-unneeded "$OUT_ABI_DIR/libnetworkkmmcurl.so"

if ! strings "$OUT_ABI_DIR/libnetworkkmmcurl.so" \
    | grep -F '[Ljava/lang/String;JJJJJ[BJLjava/lang/String;' >/dev/null; then
  echo "JNI nativePerform descriptor is missing timeoutMillis + four stream timeout longs" >&2
  exit 2
fi

echo "==> Artifact"
du -h "$OUT_ABI_DIR/libnetworkkmmcurl.so"

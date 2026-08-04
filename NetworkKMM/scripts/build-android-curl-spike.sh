#!/usr/bin/env bash
set -euo pipefail

OPENSSL_VERSION="${OPENSSL_VERSION:-3.5.4}"
CURL_VERSION="${CURL_VERSION:-8.16.0}"
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
SPIKE_ROOT="${NETWORK_ROOT}/spikes/android-curl"
BUILD_ROOT="${ANDROID_CURL_SPIKE_BUILD_ROOT:-${NETWORK_ROOT}/build/android-curl-spike}"
DOWNLOADS_DIR="${BUILD_ROOT}/downloads"
OPENSSL_SOURCE="${BUILD_ROOT}/openssl-${OPENSSL_VERSION}"
OPENSSL_PREFIX="${BUILD_ROOT}/openssl-out"
OPENSSL_STAMP="${OPENSSL_PREFIX}/.android-build-config"
CURL_SOURCE="${BUILD_ROOT}/curl-${CURL_VERSION}"
CURL_BUILD="${BUILD_ROOT}/curl-build"
CURL_STAMP="${CURL_BUILD}/.android-build-config"
JNI_ROOT="${BUILD_ROOT}/jniLibs"
JNI_ABI_DIR="${JNI_ROOT}/${ANDROID_ABI}"
JNI_STAMP="${JNI_ROOT}/.android-abi"
ASSETS_ROOT="${BUILD_ROOT}/assets"
APK_PATH="${SPIKE_ROOT}/build/outputs/apk/debug/android-curl-spike-debug.apk"
PACKAGE_NAME="com.tencent.networkkmm.curlspike"
ACTIVITY_NAME="${PACKAGE_NAME}/.MainActivity"
LOG_PATH="${BUILD_ROOT}/android-curl-spike.log"
BUILD_CONFIG="${NDK_VERSION}:${ANDROID_API}:${ANDROID_ABI}"

RUN_SPIKE=0
if [[ "${1:-}" == "--run" ]]; then
  RUN_SPIKE=1
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
    COMPILER_TRIPLE="aarch64-linux-android"
    ;;
  x86_64)
    OPENSSL_TARGET="android-x86_64"
    COMPILER_TRIPLE="x86_64-linux-android"
    ;;
  *)
    echo "Unsupported Android ABI: $ANDROID_ABI" >&2
    exit 2
    ;;
esac

TOOLCHAIN_ROOT="${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/${HOST_TAG}"
TOOLCHAIN_BIN="${TOOLCHAIN_ROOT}/bin"
CXX="${TOOLCHAIN_BIN}/${COMPILER_TRIPLE}${ANDROID_API}-clang++"
STRIP="${TOOLCHAIN_BIN}/llvm-strip"
if [[ ! -x "$CXX" ]]; then
  echo "Android compiler not found: $CXX" >&2
  exit 2
fi

if [[ ! -f "$JNI_STAMP" || "$(cat "$JNI_STAMP" 2>/dev/null)" != "$ANDROID_ABI" ]]; then
  rm -rf "$JNI_ROOT" "$SPIKE_ROOT/build"
fi
mkdir -p "$DOWNLOADS_DIR" "$JNI_ABI_DIR" "$ASSETS_ROOT"
printf '%s' "$ANDROID_ABI" > "$JNI_STAMP"

fetch() {
  local url="$1" output="$2"
  if [[ ! -f "$output" ]]; then
    curl -fsSL "$url" -o "$output"
  fi
}

echo "==> Building OpenSSL ${OPENSSL_VERSION} for ${ANDROID_ABI}"
fetch \
  "https://github.com/openssl/openssl/releases/download/openssl-${OPENSSL_VERSION}/openssl-${OPENSSL_VERSION}.tar.gz" \
  "${DOWNLOADS_DIR}/openssl-${OPENSSL_VERSION}.tar.gz"
if [[ ! -f "${OPENSSL_PREFIX}/lib/libssl.a" || ! -f "${OPENSSL_PREFIX}/lib/libcrypto.a" || ! -f "$OPENSSL_STAMP" || "$(cat "$OPENSSL_STAMP" 2>/dev/null)" != "$BUILD_CONFIG" ]]; then
  rm -rf "$OPENSSL_SOURCE" "$OPENSSL_PREFIX"
  tar -xf "${DOWNLOADS_DIR}/openssl-${OPENSSL_VERSION}.tar.gz" -C "$BUILD_ROOT"
  (
    cd "$OPENSSL_SOURCE"
    ANDROID_NDK_ROOT="$ANDROID_NDK_ROOT" \
      PATH="${TOOLCHAIN_BIN}:${PATH}" \
      ./Configure "$OPENSSL_TARGET" \
      -D__ANDROID_API__="$ANDROID_API" \
      no-shared no-tests no-apps no-docs \
      --prefix="$OPENSSL_PREFIX" \
      --openssldir="$OPENSSL_PREFIX/ssl"
    PATH="${TOOLCHAIN_BIN}:${PATH}" make -j"$JOBS" build_libs
    PATH="${TOOLCHAIN_BIN}:${PATH}" make install_dev
  )
  printf '%s' "$BUILD_CONFIG" > "$OPENSSL_STAMP"
fi

echo "==> Building curl ${CURL_VERSION} with OpenSSL"
fetch \
  "https://curl.se/download/curl-${CURL_VERSION}.tar.gz" \
  "${DOWNLOADS_DIR}/curl-${CURL_VERSION}.tar.gz"
if [[ ! -f "${CURL_BUILD}/lib/libcurl.a" || ! -f "$CURL_STAMP" || "$(cat "$CURL_STAMP" 2>/dev/null)" != "$BUILD_CONFIG" ]]; then
  rm -rf "$CURL_SOURCE" "$CURL_BUILD"
  tar -xf "${DOWNLOADS_DIR}/curl-${CURL_VERSION}.tar.gz" -C "$BUILD_ROOT"
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
    -DUSE_NGHTTP2=OFF \
    -DUSE_LIBIDN2=OFF \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
  cmake --build "$CURL_BUILD" -j"$JOBS"
  printf '%s' "$BUILD_CONFIG" > "$CURL_STAMP"
fi

echo "==> Linking JNI spike library"
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
  "$SPIKE_ROOT/src/main/cpp/android_curl_spike.cpp" \
  "$CURL_BUILD/lib/libcurl.a" \
  "$OPENSSL_PREFIX/lib/libssl.a" \
  "$OPENSSL_PREFIX/lib/libcrypto.a" \
  -llog \
  -lz \
  -ldl \
  -latomic \
  -o "$JNI_ABI_DIR/libnetworkkmmcurlspike.so"
"$STRIP" --strip-unneeded "$JNI_ABI_DIR/libnetworkkmmcurlspike.so"

fetch "https://curl.se/ca/cacert.pem" "$ASSETS_ROOT/cacert.pem"

echo "==> Building Android spike APK"
"${NETWORK_ROOT}/gradlew" \
  :android-curl-spike:assembleDebug \
  -PandroidCurlSpike \
  -PandroidCurlSpikeNativeRoot="$JNI_ROOT" \
  -PandroidCurlSpikeAssetsRoot="$ASSETS_ROOT" \
  --no-daemon

echo "==> Artifact sizes"
du -h \
  "$OPENSSL_PREFIX/lib/libcrypto.a" \
  "$OPENSSL_PREFIX/lib/libssl.a" \
  "$CURL_BUILD/lib/libcurl.a" \
  "$JNI_ABI_DIR/libnetworkkmmcurlspike.so" \
  "$APK_PATH"

if [[ "$RUN_SPIKE" -eq 1 ]]; then
  echo "==> Installing and launching on Android"
  adb install -r "$APK_PATH" >/dev/null
  adb logcat -c
  adb shell am force-stop "$PACKAGE_NAME"
  adb shell am start -W -n "$ACTIVITY_NAME" >/dev/null

  result=""
  for _ in $(seq 1 150); do
    adb logcat -d -s NetworkKMMCurlSpike:I '*:S' > "$LOG_PATH"
    result="$(grep 'SLOCK_ANDROID_CURL_SPIKE completed' "$LOG_PATH" | tail -1 || true)"
    if [[ -n "$result" ]]; then
      break
    fi
    sleep 0.2
  done
  if [[ -z "$result" ]]; then
    echo "Android spike did not emit a completion marker" >&2
    exit 3
  fi
  cat "$LOG_PATH"
  if [[ "$result" != *"passed=true independentConnections=true"* ]]; then
    exit 3
  fi
fi

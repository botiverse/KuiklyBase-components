#!/usr/bin/env bash
set -euo pipefail

# Build the NetworkKMM OHOS native runtime from source:
#   libopenssl.so   (combined libssl + libcrypto, OpenSSL ${OPENSSL_VERSION})
#   libcurl.a       (static, curl ${CURL_VERSION}, TLS via the OpenSSL above)
#   libpbcurlwrapper.so (wrapper source in ohosApp/pbcurlwrapper)
#   libc++_shared.so (copied from the OHOS SDK llvm runtime)
#
# Everything is built with the HarmonyOS command-line SDK's llvm toolchain so
# the shipped binaries always match the wrapper headers the Kotlin cinterop is
# compiled against (the 0.1.0-raft.0 crash was exactly this drift: a checked-in
# prebuilt libpbcurlwrapper.so older than curl_wrapper.h).
#
# Requires: OHOS_SDK_HOME pointing at .../sdk/default/openharmony (native/llvm,
# native/sysroot, native/build/cmake/ohos.toolchain.cmake). This is the layout
# inside ghcr.io/bytemain/harmony-next-pipeline-docker/harmonyos-ci-image.

OPENSSL_VERSION="${OPENSSL_VERSION:-3.5.4}"
CURL_VERSION="${CURL_VERSION:-8.16.0}"
OHOS_ARCH="arm64-v8a"
OHOS_TRIPLE="aarch64-linux-ohos"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NETWORK_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
BUILD_ROOT="${NETWORK_BUILD_ROOT:-${NETWORK_ROOT}/build/ohos-native}"
WRAPPER_LIBS_DIR="${NETWORK_ROOT}/ohosApp/pbcurlwrapper/libs/${OHOS_ARCH}"
ENTRY_LIBS_DIR="${NETWORK_ROOT}/ohosApp/entry/libs/${OHOS_ARCH}"
NETWORK_LIBS_DIR="${NETWORK_ROOT}/network/libs"

OHOS_SDK_HOME="${OHOS_SDK_HOME:-${OHOS_BASE_SDK_HOME:-/opt/harmonyos-tools/command-line-tools/sdk/default/openharmony}}"
LLVM_BIN="${OHOS_SDK_HOME}/native/llvm/bin"
SYSROOT="${OHOS_SDK_HOME}/native/sysroot"
TOOLCHAIN_FILE="${OHOS_SDK_HOME}/native/build/cmake/ohos.toolchain.cmake"
LIBCXX_SHARED="${OHOS_SDK_HOME}/native/llvm/lib/${OHOS_TRIPLE}/libc++_shared.so"

for path in "$LLVM_BIN/clang" "$SYSROOT" "$TOOLCHAIN_FILE" "$LIBCXX_SHARED"; do
  if [[ ! -e "$path" ]]; then
    echo "Missing OHOS SDK component: $path" >&2
    exit 1
  fi
done

mkdir -p "$BUILD_ROOT"
cd "$BUILD_ROOT"

# The harmonyos-ci-image ships wget, not curl.
fetch() {
  local url="$1" out="$2"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$url" -o "$out"
  else
    wget -q -O "$out" "$url"
  fi
}

fetch_and_verify() {
  local url="$1" out="$2" sha_url="$3"
  if [[ ! -f "$out" ]]; then
    fetch "$url" "$out"
  fi
  # Best-effort integrity check: some mirrors (curl.se) don't publish a
  # sibling .sha256, so a missing checksum is a warning, not a hard failure.
  # The CI-hardened build pins exact sha256 values instead.
  local sha_file expected
  sha_file="$(mktemp)"
  if fetch "$sha_url" "$sha_file" 2>/dev/null && [[ -s "$sha_file" ]]; then
    expected="$(awk '{print $1}' "$sha_file" | head -n1)"
    echo "${expected}  ${out}" | sha256sum -c -
  else
    echo "WARN: no checksum available for ${out} ($(sha256sum "$out" | awk '{print $1}'))" >&2
  fi
}

echo "==> Fetching OpenSSL ${OPENSSL_VERSION}"
fetch_and_verify \
  "https://github.com/openssl/openssl/releases/download/openssl-${OPENSSL_VERSION}/openssl-${OPENSSL_VERSION}.tar.gz" \
  "openssl-${OPENSSL_VERSION}.tar.gz" \
  "https://github.com/openssl/openssl/releases/download/openssl-${OPENSSL_VERSION}/openssl-${OPENSSL_VERSION}.tar.gz.sha256"
OPENSSL_PREFIX="$BUILD_ROOT/openssl-out"
if [[ -f "$OPENSSL_PREFIX/lib/libssl.a" && -f "$OPENSSL_PREFIX/lib/libcrypto.a" ]]; then
  echo "==> OpenSSL already built, reusing $OPENSSL_PREFIX"
else
rm -rf "openssl-${OPENSSL_VERSION}"
tar xf "openssl-${OPENSSL_VERSION}.tar.gz"

echo "==> Building OpenSSL (static libs)"
(
  cd "openssl-${OPENSSL_VERSION}"
  ./Configure linux-aarch64 \
    no-shared no-tests no-apps no-docs \
    --prefix="$OPENSSL_PREFIX" \
    CC="$LLVM_BIN/clang" \
    AR="$LLVM_BIN/llvm-ar" \
    RANLIB="$LLVM_BIN/llvm-ranlib" \
    CFLAGS="--target=${OHOS_TRIPLE} --sysroot=${SYSROOT} -fPIC -O2" \
    LDFLAGS="--target=${OHOS_TRIPLE} --sysroot=${SYSROOT}"
  make -j"$(nproc)" build_libs
  make install_dev >/dev/null
)
fi

echo "==> Linking combined libopenssl.so (libssl + libcrypto)"
"$LLVM_BIN/clang" \
  --target="${OHOS_TRIPLE}" --sysroot="${SYSROOT}" \
  -shared -o "$BUILD_ROOT/libopenssl.so" \
  -Wl,--whole-archive \
  "$OPENSSL_PREFIX/lib/libssl.a" \
  "$OPENSSL_PREFIX/lib/libcrypto.a" \
  -Wl,--no-whole-archive \
  -Wl,-soname,libopenssl.so

echo "==> Fetching curl ${CURL_VERSION}"
fetch_and_verify \
  "https://curl.se/download/curl-${CURL_VERSION}.tar.gz" \
  "curl-${CURL_VERSION}.tar.gz" \
  "https://curl.se/download/curl-${CURL_VERSION}.tar.gz.sha256"
rm -rf "curl-${CURL_VERSION}"
tar xf "curl-${CURL_VERSION}.tar.gz"

echo "==> Building libcurl.a"
cmake -S "curl-${CURL_VERSION}" -B curl-build \
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
  -DOHOS_ARCH="$OHOS_ARCH" \
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
  -DOPENSSL_INCLUDE_DIR="$OPENSSL_PREFIX/include" \
  -DOPENSSL_CRYPTO_LIBRARY="$OPENSSL_PREFIX/lib/libcrypto.a" \
  -DOPENSSL_SSL_LIBRARY="$OPENSSL_PREFIX/lib/libssl.a" \
  -DCMAKE_FIND_ROOT_PATH="$OPENSSL_PREFIX" \
  -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH \
  -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH \
  -DCURL_USE_LIBPSL=OFF \
  -DCURL_USE_LIBSSH2=OFF \
  -DCURL_BROTLI=OFF \
  -DCURL_ZSTD=OFF \
  -DUSE_NGHTTP2=OFF \
  -DUSE_LIBIDN2=OFF \
  -DCURL_DISABLE_LDAP=ON \
  -DCURL_ZLIB=OFF \
  -DCURL_DISABLE_WEBSOCKETS=OFF \
  -DENABLE_THREADED_RESOLVER=OFF \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON
cmake --build curl-build -j"$(nproc)"
CURL_STATIC_LIB="$(find curl-build/lib -name 'libcurl.a' | head -n1)"
if [[ -z "$CURL_STATIC_LIB" ]]; then
  echo "libcurl.a not produced" >&2
  exit 1
fi

echo "==> Staging curl/openssl for the wrapper build"
mkdir -p "$WRAPPER_LIBS_DIR"
cp -f "$CURL_STATIC_LIB" "$WRAPPER_LIBS_DIR/libcurl.a"
cp -f "$BUILD_ROOT/libopenssl.so" "$WRAPPER_LIBS_DIR/libopenssl.so"
# Wrapper includes <curl/curl.h> from its include path; refresh curl headers.
rm -rf "$NETWORK_ROOT/ohosApp/pbcurlwrapper/src/main/cpp/include/curl"
mkdir -p "$NETWORK_ROOT/ohosApp/pbcurlwrapper/src/main/cpp/include"
cp -R "curl-${CURL_VERSION}/include/curl" "$NETWORK_ROOT/ohosApp/pbcurlwrapper/src/main/cpp/include/curl"

echo "==> Building libpbcurlwrapper.so"
cmake -S "$NETWORK_ROOT/ohosApp/pbcurlwrapper/src/main/cpp" -B wrapper-build \
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
  -DOHOS_ARCH="$OHOS_ARCH" \
  -DCMAKE_BUILD_TYPE=Release
cmake --build wrapper-build -j"$(nproc)"
WRAPPER_SO="$(find wrapper-build -name 'libpbcurlwrapper.so' | head -n1)"
if [[ -z "$WRAPPER_SO" ]]; then
  echo "libpbcurlwrapper.so not produced" >&2
  exit 1
fi

echo "==> Publishing outputs into repo library directories"
for dir in "$ENTRY_LIBS_DIR" "$NETWORK_LIBS_DIR"; do
  mkdir -p "$dir"
  cp -f "$WRAPPER_SO" "$dir/libpbcurlwrapper.so"
  cp -f "$BUILD_ROOT/libopenssl.so" "$dir/libopenssl.so"
  cp -f "$LIBCXX_SHARED" "$dir/libc++_shared.so"
done

# WebSocket readiness: the definitive check is whether the built libcurl.a
# exports the curl_ws_* API, so the planned curl-ws realtime transport won't
# require another native rebuild.
echo "==> Checking libcurl WebSocket support"
if "$LLVM_BIN/llvm-nm" "$CURL_STATIC_LIB" 2>/dev/null | grep -q "T curl_ws_send"; then
  echo "libcurl WebSocket: ENABLED (curl_ws_send/curl_ws_recv present)"
else
  echo "libcurl WebSocket: MISSING — curl-ws realtime would need a rebuild" >&2
  exit 1
fi

echo "==> Result"
echo "openssl: ${OPENSSL_VERSION}"
echo "curl:    ${CURL_VERSION}"
sha256sum \
  "$ENTRY_LIBS_DIR/libpbcurlwrapper.so" \
  "$ENTRY_LIBS_DIR/libopenssl.so" \
  "$ENTRY_LIBS_DIR/libc++_shared.so"

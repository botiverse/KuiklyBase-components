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
# Content-encoding codecs compiled into libcurl so the general-purpose network
# service can decode any standard Content-Encoding (gzip/deflate/br/zstd). Built
# without these, libcurl only understands "identity" and fails compressed
# responses with CURLE_BAD_CONTENT_ENCODING (61) — e.g. Cloudflare serving br.
ZLIB_VERSION="${ZLIB_VERSION:-1.3.1}"
BROTLI_VERSION="${BROTLI_VERSION:-1.1.0}"
ZSTD_VERSION="${ZSTD_VERSION:-1.5.6}"
NGHTTP2_VERSION="${NGHTTP2_VERSION:-1.64.0}"
# nghttp2 publishes no sibling .sha256 — hard-pinned (verified against the
# real release download; same discipline as the android/ios lines).
NGHTTP2_SHA256="${NGHTTP2_SHA256:-20e73f3cf9db3f05988996ac8b3a99ed529f4565ca91a49eb0550498e10621e8}"
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
  # --openssldir=/etc/ssl points OpenSSL's default trust dir at the OHOS system
  # CA store (/etc/ssl/certs) so verification works on-device with no bundled CA.
  ./Configure linux-aarch64 \
    no-shared no-tests no-apps no-docs \
    --prefix="$OPENSSL_PREFIX" \
    --openssldir=/etc/ssl \
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

# ---------------------------------------------------------------------------
# Content-encoding codecs (static): zlib, brotli, zstd. All installed into one
# prefix so curl's cmake find modules locate them, and their static archives
# are linked into libpbcurlwrapper.so (libcurl.a is static, so its codec deps
# must be provided at the final .so link — see the wrapper CMakeLists).
# ---------------------------------------------------------------------------
DEPS_PREFIX="$BUILD_ROOT/deps-out"
mkdir -p "$DEPS_PREFIX"

cmake_cross() {
  # cmake_cross <src-dir> <build-dir> [extra -D args...]
  local src="$1" bld="$2"; shift 2
  cmake -S "$src" -B "$bld" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
    -DOHOS_ARCH="$OHOS_ARCH" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$DEPS_PREFIX" \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DBUILD_SHARED_LIBS=OFF \
    "$@"
  cmake --build "$bld" -j"$(nproc)" --target install
}

if [[ -f "$DEPS_PREFIX/lib/libz.a" ]]; then
  echo "==> zlib already built, reusing"
else
  echo "==> Fetching zlib ${ZLIB_VERSION}"
  fetch_and_verify \
    "https://github.com/madler/zlib/releases/download/v${ZLIB_VERSION}/zlib-${ZLIB_VERSION}.tar.gz" \
    "zlib-${ZLIB_VERSION}.tar.gz" \
    "https://github.com/madler/zlib/releases/download/v${ZLIB_VERSION}/zlib-${ZLIB_VERSION}.tar.gz.sha256"
  rm -rf "zlib-${ZLIB_VERSION}"
  tar xf "zlib-${ZLIB_VERSION}.tar.gz"
  echo "==> Building zlib (static)"
  cmake_cross "zlib-${ZLIB_VERSION}" zlib-build
  # zlib's cmake also installs a shared libz.so; drop it so curl/find + the
  # wrapper link resolve to the static libz.a only.
  rm -f "$DEPS_PREFIX"/lib/libz.so*
fi

if [[ -f "$DEPS_PREFIX/lib/libbrotlidec.a" ]]; then
  echo "==> brotli already built, reusing"
else
  echo "==> Fetching brotli ${BROTLI_VERSION}"
  fetch_and_verify \
    "https://github.com/google/brotli/archive/refs/tags/v${BROTLI_VERSION}.tar.gz" \
    "brotli-${BROTLI_VERSION}.tar.gz" \
    "https://github.com/google/brotli/archive/refs/tags/v${BROTLI_VERSION}.tar.gz.sha256"
  rm -rf "brotli-${BROTLI_VERSION}"
  tar xf "brotli-${BROTLI_VERSION}.tar.gz"
  echo "==> Building brotli (static)"
  cmake_cross "brotli-${BROTLI_VERSION}" brotli-build -DBROTLI_DISABLE_TESTS=ON
  rm -f "$DEPS_PREFIX"/lib/libbrotli*.so*
fi

if [[ -f "$DEPS_PREFIX/lib/libzstd.a" ]]; then
  echo "==> zstd already built, reusing"
else
  echo "==> Fetching zstd ${ZSTD_VERSION}"
  fetch_and_verify \
    "https://github.com/facebook/zstd/releases/download/v${ZSTD_VERSION}/zstd-${ZSTD_VERSION}.tar.gz" \
    "zstd-${ZSTD_VERSION}.tar.gz" \
    "https://github.com/facebook/zstd/releases/download/v${ZSTD_VERSION}/zstd-${ZSTD_VERSION}.tar.gz.sha256"
  rm -rf "zstd-${ZSTD_VERSION}"
  tar xf "zstd-${ZSTD_VERSION}.tar.gz"
  echo "==> Building zstd (static)"
  # zstd's CMake project lives under build/cmake.
  cmake_cross "zstd-${ZSTD_VERSION}/build/cmake" zstd-build \
    -DZSTD_BUILD_SHARED=OFF -DZSTD_BUILD_STATIC=ON \
    -DZSTD_BUILD_PROGRAMS=OFF -DZSTD_BUILD_TESTS=OFF -DZSTD_LEGACY_SUPPORT=OFF
  rm -f "$DEPS_PREFIX"/lib/libzstd.so*
fi

if [[ -f "$DEPS_PREFIX/lib/libnghttp2.a" ]]; then
  echo "==> nghttp2 already built, reusing"
else
  echo "==> Fetching nghttp2 ${NGHTTP2_VERSION}"
  fetch "https://github.com/nghttp2/nghttp2/releases/download/v${NGHTTP2_VERSION}/nghttp2-${NGHTTP2_VERSION}.tar.gz" \
    "nghttp2-${NGHTTP2_VERSION}.tar.gz"
  echo "${NGHTTP2_SHA256}  nghttp2-${NGHTTP2_VERSION}.tar.gz" | sha256sum -c -
  rm -rf "nghttp2-${NGHTTP2_VERSION}"
  tar xf "nghttp2-${NGHTTP2_VERSION}.tar.gz"
  echo "==> Building nghttp2 (static)"
  cmake_cross "nghttp2-${NGHTTP2_VERSION}" nghttp2-build \
    -DENABLE_LIB_ONLY=ON -DBUILD_STATIC_LIBS=ON -DENABLE_DOC=OFF
  rm -f "$DEPS_PREFIX"/lib/libnghttp2.so*
fi

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
  -DCMAKE_FIND_ROOT_PATH="$OPENSSL_PREFIX;$DEPS_PREFIX" \
  -DCMAKE_PREFIX_PATH="$DEPS_PREFIX" \
  -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH \
  -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH \
  -DCURL_USE_LIBPSL=OFF \
  -DCURL_USE_LIBSSH2=OFF \
  -DCURL_ZLIB=ON \
  -DZLIB_USE_STATIC_LIBS=ON \
  -DZLIB_LIBRARY="$DEPS_PREFIX/lib/libz.a" \
  -DZLIB_INCLUDE_DIR="$DEPS_PREFIX/include" \
  -DCURL_BROTLI=ON \
  -DCURL_ZSTD=ON \
  -DUSE_NGHTTP2=ON \
  -DNGHTTP2_INCLUDE_DIR="$DEPS_PREFIX/include" \
  -DNGHTTP2_LIBRARY="$DEPS_PREFIX/lib/libnghttp2.a" \
  -DUSE_LIBIDN2=OFF \
  -DCURL_DISABLE_LDAP=ON \
  -DCURL_DISABLE_WEBSOCKETS=OFF \
  -DENABLE_THREADED_RESOLVER=OFF \
  -DCURL_CA_BUNDLE=/etc/ssl/certs/cacert.pem \
  -DCURL_CA_PATH=/etc/ssl/certs \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON
cmake --build curl-build -j"$(nproc)"
CURL_STATIC_LIB="$(find curl-build/lib -name 'libcurl.a' | head -n1)"
if [[ -z "$CURL_STATIC_LIB" ]]; then
  echo "libcurl.a not produced" >&2
  exit 1
fi

# Confirm libcurl was actually compiled with each content-encoding codec. If a
# CURL_ZLIB/BROTLI/ZSTD flag silently failed to find its lib, curl falls back to
# identity-only and compressed responses fail at runtime with CURLE_BAD_CONTENT_
# ENCODING (61) — the raft.6 login regression. A codec-using libcurl.a carries an
# undefined reference to the decoder entrypoint (resolved later against the
# staged static archive).
echo "==> Checking libcurl content-encoding codecs"
codec_missing=0
# Capture the full symbol table once. Piping llvm-nm directly into `grep -q`
# under `set -o pipefail` makes grep close the pipe on first match, killing
# llvm-nm with SIGPIPE and turning a real match into a pipeline failure.
CURL_SYMBOLS="$("$LLVM_BIN/llvm-nm" "$CURL_STATIC_LIB" 2>/dev/null || true)"
check_codec() {
  local name="$1" symbol="$2"
  # Here-string (not a pipe): grep -q short-circuiting can't SIGPIPE a producer
  # and trip pipefail into a false negative.
  if grep -qw "$symbol" <<<"$CURL_SYMBOLS"; then
    echo "libcurl codec ${name}: ENABLED (${symbol} referenced)"
  else
    echo "libcurl codec ${name}: MISSING — ${symbol} not referenced" >&2
    codec_missing=1
  fi
}
check_codec "zlib (gzip/deflate)" "inflate"
check_codec "brotli" "BrotliDecoderDecompressStream"
check_codec "zstd" "ZSTD_decompressStream"
check_codec "nghttp2 (HTTP/2)" "nghttp2_session_client_new"
if [[ "$codec_missing" -ne 0 ]]; then
  echo "One or more content-encoding codecs are missing from libcurl.a" >&2
  exit 1
fi

echo "==> Staging curl/openssl/codecs for the wrapper build"
mkdir -p "$WRAPPER_LIBS_DIR"
cp -f "$CURL_STATIC_LIB" "$WRAPPER_LIBS_DIR/libcurl.a"
cp -f "$BUILD_ROOT/libopenssl.so" "$WRAPPER_LIBS_DIR/libopenssl.so"
# libcurl.a is static, so the content-encoding codecs it references must be
# provided at the final libpbcurlwrapper.so link. Stage their static archives
# next to libcurl.a; the wrapper CMakeLists links them.
for codec in libz.a libbrotlidec.a libbrotlicommon.a libzstd.a libnghttp2.a; do
  if [[ ! -f "$DEPS_PREFIX/lib/$codec" ]]; then
    echo "missing codec archive: $DEPS_PREFIX/lib/$codec" >&2
    exit 1
  fi
  cp -f "$DEPS_PREFIX/lib/$codec" "$WRAPPER_LIBS_DIR/$codec"
done
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
# require another native rebuild. Match any DEFINED symbol type (the API can be
# emitted as a weak symbol 'W', not just text 'T'), not a hard-coded "T " prefix.
echo "==> Checking libcurl WebSocket support"
# A DEFINED symbol has a type letter other than 'U' (undefined). --defined-only
# isn't supported by every llvm-nm build, so filter out the ' U ' lines instead.
# Reuse the captured symbol table (see the codec check) to avoid the pipefail +
# grep -q SIGPIPE pitfall.
WS_DEFINED="$(grep -w "curl_ws_send" <<<"$CURL_SYMBOLS" | grep -v ' U ' || true)"
if [[ -n "$WS_DEFINED" ]]; then
  echo "libcurl WebSocket: ENABLED (curl_ws_send/curl_ws_recv present)"
else
  echo "libcurl WebSocket: MISSING — curl-ws realtime would need a rebuild" >&2
  echo "  curl_ws symbols found:" >&2
  "$LLVM_BIN/llvm-nm" "$CURL_STATIC_LIB" 2>/dev/null | grep -i "curl_ws" >&2 || echo "  (none)" >&2
  exit 1
fi

echo "==> Result"
echo "openssl: ${OPENSSL_VERSION}"
echo "curl:    ${CURL_VERSION}"
sha256sum \
  "$ENTRY_LIBS_DIR/libpbcurlwrapper.so" \
  "$ENTRY_LIBS_DIR/libopenssl.so" \
  "$ENTRY_LIBS_DIR/libc++_shared.so"

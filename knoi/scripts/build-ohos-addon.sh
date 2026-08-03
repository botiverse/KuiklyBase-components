#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KNOI_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SOURCE_DIR="${KNOI_ROOT}/ohosApp/knoi/src/main/cpp"
BUILD_ROOT="${KNOI_OHOS_BUILD_ROOT:-${KNOI_ROOT}/build/ohos-addon}"
OHOS_SDK_HOME="${OHOS_SDK_HOME:-${OHOS_BASE_SDK_HOME:-/opt/harmonyos-tools/command-line-tools/sdk/default/openharmony}}"
TOOLCHAIN_FILE="${OHOS_SDK_HOME}/native/build/cmake/ohos.toolchain.cmake"
OHOS_ARCH="${OHOS_ARCH:-arm64-v8a}"

if [[ -n "${KNOI_BUILD_JOBS:-}" ]]; then
  build_jobs="${KNOI_BUILD_JOBS}"
elif command -v nproc >/dev/null 2>&1; then
  build_jobs="$(nproc)"
elif command -v sysctl >/dev/null 2>&1; then
  build_jobs="$(sysctl -n hw.logicalcpu)"
else
  build_jobs=2
fi

if [[ ! -f "${TOOLCHAIN_FILE}" ]]; then
  echo "OHOS toolchain not found: ${TOOLCHAIN_FILE}" >&2
  exit 1
fi

build_one() {
  local mode="$1"
  local build_type="$2"
  local use_aki=OFF
  if [[ "${mode}" == "aki" ]]; then
    use_aki=ON
  elif [[ "${mode}" != "legacy" ]]; then
    echo "unknown KNOI mode: ${mode}" >&2
    exit 1
  fi

  local build_dir="${BUILD_ROOT}/${mode}/${build_type}"
  rm -rf "${build_dir}"
  cmake -S "${SOURCE_DIR}" -B "${build_dir}" \
    -DCMAKE_TOOLCHAIN_FILE="${TOOLCHAIN_FILE}" \
    -DOHOS_ARCH="${OHOS_ARCH}" \
    -DCMAKE_BUILD_TYPE="${build_type}" \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DKNOI_USE_AKI="${use_aki}"
  cmake --build "${build_dir}" --target knoi -j"${build_jobs}"
  local library
  library="$(find "${build_dir}" -type f -name 'libknoi.so' -print -quit)"
  if [[ -z "${library}" || ! -s "${library}" ]]; then
    echo "libknoi.so not produced for ${mode}/${build_type}" >&2
    exit 1
  fi
  bash "${SCRIPT_DIR}/verify-built-addon.sh" --library "${library}" --mode "${mode}"
  printf '%s\n' "${library}"
}

modes=(aki legacy)
types=(Debug Release)
if [[ $# -gt 0 ]]; then
  modes=("$1")
fi
if [[ $# -gt 1 ]]; then
  types=("$2")
fi

for mode in "${modes[@]}"; do
  for build_type in "${types[@]}"; do
    build_one "${mode}" "${build_type}"
  done
done

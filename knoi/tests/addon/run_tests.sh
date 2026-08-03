#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KNOI_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
CPP_ROOT="${KNOI_ROOT}/ohosApp/knoi/src/main/cpp"
AKI_ROOT="${KNOI_ROOT}/third_party/aki"
BUILD_ROOT="${KNOI_ADDON_HOST_BUILD_ROOT:-${KNOI_ROOT}/build/addon-host-probe}"
CXX="${CXX:-c++}"
NODE="${NODE:-$(command -v node || true)}"

if [[ -z "${NODE}" ]]; then
  echo "node is required for the addon host runtime contract" >&2
  exit 1
fi

if [[ -n "${NODE_INCLUDE_DIR:-}" ]]; then
  node_include="${NODE_INCLUDE_DIR}"
else
  node_root="$(cd "$(dirname "${NODE}")/.." && pwd)"
  node_include="${node_root}/include/node"
  if [[ ! -f "${node_include}/node_api.h" && -f /usr/include/node/node_api.h ]]; then
    node_include=/usr/include/node
  fi
fi
if [[ ! -f "${node_include}/node_api.h" ]]; then
  echo "Node-API headers not found; set NODE_INCLUDE_DIR" >&2
  exit 1
fi

rm -rf "${BUILD_ROOT}"
mkdir -p "${BUILD_ROOT}"

common_flags=(
  -std=c++17
  -fPIC
  -pthread
  -DJSBIND_USING_NAPI=1
  -DAKI_BUILDING_SHARED=0
  -DKNOI_AKI_VERSION=\"1.3.1\"
  -DKNOI_WAITER_TIMEOUT_MS=250
  -I"${node_include}"
  -I"${SCRIPT_DIR}/host_include"
  -isystem "${AKI_ROOT}/include"
  -isystem "${AKI_ROOT}/src"
  -I"${CPP_ROOT}"
)

shared_flags=(-shared)
if [[ "$(uname -s)" == "Darwin" ]]; then
  shared_flags=(-bundle -undefined dynamic_lookup)
  common_flags+=(-Wno-deprecated-declarations)
fi

for fixture in bridge_valid bridge_missing_env bridge_missing_bridge; do
  "${CXX}" -std=c++17 -fPIC "${shared_flags[@]}" \
    "${KNOI_ROOT}/tests/native/fixtures/${fixture}.cpp" \
    -o "${BUILD_ROOT}/lib${fixture}.so"
done

aki_sources=(
  "${AKI_ROOT}/src/binding.cpp"
  "${AKI_ROOT}/src/function.cpp"
  "${AKI_ROOT}/src/jsbind.cpp"
  "${AKI_ROOT}/src/version.cpp"
  "${AKI_ROOT}/src/value.cpp"
  "${AKI_ROOT}/src/class/class_base.cpp"
  "${AKI_ROOT}/src/class/class_wrapper.cpp"
  "${AKI_ROOT}/src/invoker/invoker.cpp"
  "${AKI_ROOT}/src/logging/log_setting.cpp"
  "${AKI_ROOT}/src/logging/logging.cpp"
  "${AKI_ROOT}/src/value/array_buffer.cpp"
  "${AKI_ROOT}/src/value/promise.cpp"
  "${AKI_ROOT}/src/value/napi/napi_value_base.cpp"
  "${AKI_ROOT}/src/status/status.cpp"
  "${AKI_ROOT}/src/task_runner/task_runner.cpp"
  "${AKI_ROOT}/src/persistent/persistent.cpp"
  "${AKI_ROOT}/src/asyncworker/asyncworker.cpp"
  "${AKI_ROOT}/src/napi/napi_init.cpp"
  "${AKI_ROOT}/src/overloader/napi/napi_overloader.cpp"
)
knoi_sources=(
  "${CPP_ROOT}/knoi_aki.cpp"
  "${CPP_ROOT}/async_invoker_aki.cpp"
  "${CPP_ROOT}/native_bridge_loader.cpp"
  "${CPP_ROOT}/function_waiter_registry.cpp"
)

"${CXX}" "${common_flags[@]}" "${shared_flags[@]}" \
  "${aki_sources[@]}" "${knoi_sources[@]}" \
  -o "${BUILD_ROOT}/knoi.node"

"${NODE}" "${SCRIPT_DIR}/addon_contract_test.js" \
  "${BUILD_ROOT}/knoi.node" \
  "${BUILD_ROOT}/libbridge_valid.so" \
  "${BUILD_ROOT}/libbridge_missing_env.so" \
  "${BUILD_ROOT}/libbridge_missing_bridge.so"

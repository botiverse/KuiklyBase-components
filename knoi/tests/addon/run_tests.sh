#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KNOI_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
CPP_ROOT="${KNOI_ROOT}/ohosApp/knoi/src/main/cpp"
AKI_ROOT="${KNOI_ROOT}/third_party/aki"
BUILD_ROOT="${KNOI_ADDON_HOST_BUILD_ROOT:-${KNOI_ROOT}/build/addon-host-probe}"
if [[ -z "${CXX:-}" ]]; then
  if command -v clang++ >/dev/null 2>&1; then
    CXX="$(command -v clang++)"
  else
    CXX="c++"
  fi
fi
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
  -include "${SCRIPT_DIR}/host_include/aki_host_compat.h"
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
knoi_common_sources=(
  "${CPP_ROOT}/knoi_aki.cpp"
  "${CPP_ROOT}/native_bridge_loader.cpp"
  "${CPP_ROOT}/function_waiter_registry.cpp"
)

build_addon() {
  local async_source="$1"
  local output="$2"
  "${CXX}" "${common_flags[@]}" "${shared_flags[@]}" \
    "${aki_sources[@]}" "${knoi_common_sources[@]}" "${async_source}" \
    -o "${output}"
}

run_addon_contract() {
  local addon="$1"
  "${NODE}" "${SCRIPT_DIR}/addon_contract_test.js" \
    "${addon}" \
    "${BUILD_ROOT}/libbridge_valid.so" \
    "${BUILD_ROOT}/libbridge_missing_env.so" \
    "${BUILD_ROOT}/libbridge_missing_bridge.so"
}

build_addon "${CPP_ROOT}/async_invoker_aki.cpp" "${BUILD_ROOT}/knoi.node"
run_addon_contract "${BUILD_ROOT}/knoi.node"

if [[ "${KNOI_RUN_MUTATIONS:-1}" == "1" ]]; then
  thread_local_source="${BUILD_ROOT}/async_invoker_thread_local.cpp"
  cp "${CPP_ROOT}/async_invoker_aki.cpp" "${thread_local_source}"
  perl -0pi -e \
    's/knoi::FunctionWaiterRegistry gWaiters;/thread_local knoi::FunctionWaiterRegistry gWaiters;/' \
    "${thread_local_source}"
  build_addon "${thread_local_source}" "${BUILD_ROOT}/knoi-thread-local.node"

  set +e
  run_addon_contract "${BUILD_ROOT}/knoi-thread-local.node" \
    >"${BUILD_ROOT}/thread-local-mutation.log" 2>&1
  mutation_status=$?
  set -e
  if [[ ${mutation_status} -eq 0 ]]; then
    echo "cross-environment waiter mutation survived" >&2
    exit 1
  fi
  if ! grep -Eq 'KNOI_WAITER_(NOT_FOUND|TIMED_OUT)' \
    "${BUILD_ROOT}/thread-local-mutation.log"; then
    echo "cross-environment waiter mutation failed for an unexpected reason" >&2
    exit 1
  fi
  echo "addon cross-environment mutation killed"
fi

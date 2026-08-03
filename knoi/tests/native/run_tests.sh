#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KNOI_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
CPP_ROOT="${KNOI_ROOT}/ohosApp/knoi/src/main/cpp"
BUILD_ROOT="${KNOI_NATIVE_TEST_BUILD_ROOT:-${KNOI_ROOT}/build/native-contract-tests}"
CXX="${CXX:-c++}"

rm -rf "${BUILD_ROOT}"
mkdir -p "${BUILD_ROOT}"

shared_flags=(-std=c++17 -fPIC -shared)
if [[ "$(uname -s)" == "Darwin" ]]; then
  shared_flags+=(-undefined dynamic_lookup)
fi

"${CXX}" "${shared_flags[@]}" \
  "${SCRIPT_DIR}/fixtures/bridge_valid.cpp" \
  -o "${BUILD_ROOT}/libbridge_valid.so"
"${CXX}" "${shared_flags[@]}" \
  "${SCRIPT_DIR}/fixtures/bridge_missing_env.cpp" \
  -o "${BUILD_ROOT}/libbridge_missing_env.so"
"${CXX}" "${shared_flags[@]}" \
  "${SCRIPT_DIR}/fixtures/bridge_missing_bridge.cpp" \
  -o "${BUILD_ROOT}/libbridge_missing_bridge.so"

compile_tests() {
  local source_root="$1"
  local suffix="$2"
  "${CXX}" -std=c++17 -Wall -Wextra -Werror -pthread \
    -I"${source_root}" \
    "${source_root}/native_bridge_loader.cpp" \
    "${SCRIPT_DIR}/native_bridge_loader_test.cpp" \
    -ldl \
    -o "${BUILD_ROOT}/native_bridge_loader_test${suffix}"
  "${CXX}" -std=c++17 -Wall -Wextra -Werror -pthread \
    -I"${source_root}" \
    "${source_root}/function_waiter_registry.cpp" \
    "${SCRIPT_DIR}/function_waiter_registry_test.cpp" \
    -o "${BUILD_ROOT}/function_waiter_registry_test${suffix}"
}

run_tests() {
  local suffix="$1"
  "${BUILD_ROOT}/native_bridge_loader_test${suffix}" \
    "${BUILD_ROOT}/libbridge_valid.so" \
    "${BUILD_ROOT}/libbridge_missing_env.so" \
    "${BUILD_ROOT}/libbridge_missing_bridge.so" || return $?
  "${BUILD_ROOT}/function_waiter_registry_test${suffix}" || return $?
}

compile_tests "${CPP_ROOT}" ""
run_tests ""

if [[ "${KNOI_RUN_MUTATIONS:-1}" == "1" ]]; then
  mutation_root="${BUILD_ROOT}/mutation-src"
  mkdir -p "${mutation_root}"
  cp "${CPP_ROOT}/native_bridge_loader.h" "${mutation_root}/"
  cp "${CPP_ROOT}/native_bridge_loader.cpp" "${mutation_root}/"
  cp "${CPP_ROOT}/function_waiter_registry.h" "${mutation_root}/"
  cp "${CPP_ROOT}/function_waiter_registry.cpp" "${mutation_root}/"

  perl -0pi -e \
    's/candidateInitBridge == nullptr \|\| initBridgeError != nullptr/candidateInitBridge == nullptr \&\& initBridgeError == nullptr/' \
    "${mutation_root}/native_bridge_loader.cpp"
  compile_tests "${mutation_root}" "_missing_symbol_mutant"
  set +e
  run_tests "_missing_symbol_mutant" >/dev/null 2>&1
  mutation_status=$?
  set -e
  if [[ ${mutation_status} -eq 0 ]]; then
    echo "missing-symbol mutation survived" >&2
    exit 1
  fi

  cp "${CPP_ROOT}/native_bridge_loader.cpp" "${mutation_root}/native_bridge_loader.cpp"
  cp "${CPP_ROOT}/function_waiter_registry.cpp" "${mutation_root}/function_waiter_registry.cpp"
  perl -0pi -e 's/waiter->value = std::move\(value\);/waiter->value = value.substr(0, value.size() \/ 2);/' \
    "${mutation_root}/function_waiter_registry.cpp"
  compile_tests "${mutation_root}" "_utf8_mutant"
  set +e
  run_tests "_utf8_mutant" >/dev/null 2>&1
  mutation_status=$?
  set -e
  if [[ ${mutation_status} -eq 0 ]]; then
    echo "UTF-8 truncation mutation survived" >&2
    exit 1
  fi
  echo "native contract mutations killed"
fi

echo "KNOI native contract tests PASS"

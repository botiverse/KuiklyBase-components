#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
library=""
mode=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --library)
      library="$2"
      shift 2
      ;;
    --mode)
      mode="$2"
      shift 2
      ;;
    *)
      echo "unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

if [[ ! -s "${library}" ]]; then
  echo "missing addon library: ${library}" >&2
  exit 1
fi
if [[ "${mode}" != "aki" && "${mode}" != "legacy" ]]; then
  echo "mode must be aki or legacy" >&2
  exit 1
fi

READELF="${READELF:-$(command -v readelf || command -v llvm-readelf || true)}"
if [[ -z "${READELF}" ]]; then
  echo "readelf or llvm-readelf is required" >&2
  exit 1
fi
STRINGS="${STRINGS:-$(command -v llvm-strings || command -v strings || true)}"
if [[ -z "${STRINGS}" ]]; then
  echo "strings or llvm-strings is required" >&2
  exit 1
fi

python3 "${SCRIPT_DIR}/verify-built-addon-elf.py" \
  --library "${library}" \
  --mode "${mode}" \
  --readelf "${READELF}" \
  --self-test

dynamic="$(${READELF} -d "${library}")"
printf '%s\n' "${dynamic}" | grep -F '(SONAME)' | grep -F '[libknoi.so]' >/dev/null
printf '%s\n' "${dynamic}" | grep -E '\(FLAGS\).*BIND_NOW' >/dev/null
program="$(${READELF} -l "${library}")"
printf '%s\n' "${program}" | grep -F 'GNU_RELRO' >/dev/null

symbols="$(${READELF} --dyn-syms --wide "${library}")"
printf '%s\n' "${symbols}" | grep -E 'UND[[:space:]]+napi_module_register$' >/dev/null
visible_aki_symbols="$(
  printf '%s\n' "${symbols}" |
    awk '$5 ~ /^(GLOBAL|WEAK)$/ && $6 == "DEFAULT" && $7 != "UND" { print $8 }' |
    grep -Ei 'aki|jsbind' || true
)"
if [[ -n "${visible_aki_symbols}" ]]; then
  echo "default-visible Aki C++ symbol escaped libknoi.so" >&2
  printf '%s\n' "${visible_aki_symbols}" >&2
  exit 1
fi

for export_name in setup init create_function_waiter wait_on_function_waiter notify_function_waiter; do
  if ! "${STRINGS}" "${library}" | grep -Fx "${export_name}" >/dev/null; then
    echo "native export marker missing: ${export_name}" >&2
    exit 1
  fi
done
for bootstrap in com_tencent_tmm_knoi_initEnv com_tencent_tmm_knoi_initBridge; do
  if ! "${STRINGS}" "${library}" | grep -Fx "${bootstrap}" >/dev/null; then
    echo "bootstrap symbol marker missing: ${bootstrap}" >&2
    exit 1
  fi
done

if [[ "${mode}" == "aki" ]]; then
  "${STRINGS}" "${library}" | grep -Fx '1.3.1' >/dev/null
  printf '%s\n' "${dynamic}" | grep -F 'Shared library: [libuv.so]' >/dev/null
else
  if "${STRINGS}" "${library}" | grep -Fx '1.3.1' >/dev/null; then
    echo "Aki version marker escaped the legacy rollback build" >&2
    exit 1
  fi
  if printf '%s\n' "${dynamic}" | grep -F 'Shared library: [libuv.so]' >/dev/null; then
    echo "Aki libuv dependency escaped the legacy rollback build" >&2
    exit 1
  fi
fi

if command -v sha256sum >/dev/null 2>&1; then
  sha="$(sha256sum "${library}" | awk '{print $1}')"
else
  sha="$(shasum -a 256 "${library}" | awk '{print $1}')"
fi
build_id="$(${READELF} -n "${library}" | awk '/Build ID:/ {print $3; exit}')"
needed="$(printf '%s\n' "${dynamic}" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p' | sort | paste -sd, -)"
echo "KNOI_ADDON_PASS mode=${mode} sha256=${sha} build_id=${build_id} needed=${needed}"

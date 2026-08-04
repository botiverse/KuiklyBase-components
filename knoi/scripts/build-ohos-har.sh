#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KNOI_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
OHOS_APP_ROOT="${KNOI_ROOT}/ohosApp"
HAR="${OHOS_APP_ROOT}/knoi/build/default/outputs/default/knoi.har"

if [[ -z "${DEVECO_SDK_HOME:-}" && -n "${OHOS_SDK_HOME:-}" ]]; then
  DEVECO_SDK_HOME="$(cd "${OHOS_SDK_HOME}/../.." && pwd)"
  export DEVECO_SDK_HOME
fi

OHPM="${OHPM:-$(command -v ohpm || true)}"
HVIGORW="${HVIGORW:-$(command -v hvigorw || true)}"
if [[ -z "${OHPM}" || -z "${HVIGORW}" ]]; then
  echo "ohpm and hvigorw are required to build the KNOI HAR" >&2
  exit 1
fi

(
  # The HAR target has only one file: dependency. Install from that module and
  # poison the registry endpoint so this gate cannot silently resolve the
  # unrelated sample entry's historical @kuiklybase/knoi@0.0.1 coordinate.
  cd "${OHOS_APP_ROOT}/knoi"
  "${OHPM}" install \
    --no-save \
    --registry http://127.0.0.1:9 \
    --strict_ssl true
)

(
  cd "${OHOS_APP_ROOT}"
  "${HVIGORW}" \
    --mode module \
    -p module=knoi@default \
    -p product=default \
    -p buildMode=release \
    assembleHar \
    --analyze=normal \
    --parallel \
    --no-daemon
)

if [[ ! -s "${HAR}" ]]; then
  echo "KNOI HAR was not produced: ${HAR}" >&2
  exit 1
fi
python3 "${SCRIPT_DIR}/verify-har-artifact.py" --har "${HAR}" --mode aki
printf '%s\n' "${HAR}"

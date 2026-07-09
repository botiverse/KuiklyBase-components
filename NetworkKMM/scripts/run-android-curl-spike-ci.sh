#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NETWORK_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
LOG_DIR="${NETWORK_ROOT}/build/android-curl-spike"

mkdir -p "$LOG_DIR"
cd "$NETWORK_ROOT"

ANDROID_ABI=x86_64 ./scripts/build-android-curl-spike.sh --run 2>&1 \
  | tee "$LOG_DIR/ci-output.log"
grep -q "SLOCK_ANDROID_CURL_SPIKE completed passed=true reused=true" \
  "$LOG_DIR/android-curl-spike.log"

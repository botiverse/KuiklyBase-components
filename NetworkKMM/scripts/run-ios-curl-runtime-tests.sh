#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NETWORK_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
RUNTIME_ROOT="${NETWORK_ROOT}/build/ios-curl-runtime"
CA_PATH="${RUNTIME_ROOT}/ca.pem"
KEY_PATH="${RUNTIME_ROOT}/server-key.pem"
OPENSSL_CONFIG="${RUNTIME_ROOT}/openssl.cnf"
PORT="${NETWORKKMM_IOS_CURL_RUNTIME_PORT:-18924}"

mkdir -p "$RUNTIME_ROOT"
if [[ ! -s "$CA_PATH" || ! -s "$KEY_PATH" ]] || \
    ! openssl x509 -checkend 60 -noout -in "$CA_PATH" >/dev/null 2>&1; then
  cat > "$OPENSSL_CONFIG" <<'OPENSSLCONFIG'
[req]
distinguished_name = subject
x509_extensions = extensions
prompt = no

[subject]
CN = 127.0.0.1

[extensions]
subjectAltName = IP:127.0.0.1
basicConstraints = critical,CA:TRUE
keyUsage = critical,digitalSignature,keyEncipherment,keyCertSign
extendedKeyUsage = serverAuth
OPENSSLCONFIG
  openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
    -config "$OPENSSL_CONFIG" \
    -keyout "$KEY_PATH" \
    -out "$CA_PATH" >/dev/null 2>&1
fi

python3 "${NETWORK_ROOT}/tests/wrapper/https_test_server.py" \
  --port "$PORT" \
  --cert "$CA_PATH" \
  --key "$KEY_PATH" &
SERVER_PID=$!
trap 'kill "$SERVER_PID" 2>/dev/null || true' EXIT

for _ in $(seq 1 50); do
  if curl --cacert "$CA_PATH" -fsS "https://127.0.0.1:${PORT}/ok" >/dev/null 2>&1; then
    break
  fi
  sleep 0.1
done
curl --cacert "$CA_PATH" -fsS "https://127.0.0.1:${PORT}/ok" >/dev/null

export NETWORKKMM_IOS_CURL_RUNTIME_CA_PATH="$CA_PATH"
export NETWORKKMM_IOS_CURL_RUNTIME_URL="${NETWORKKMM_IOS_CURL_RUNTIME_URL:-https://127.0.0.1:${PORT}/stream}"
export NETWORKKMM_IOS_CURL_RUNTIME_UPLOAD_URL="${NETWORKKMM_IOS_CURL_RUNTIME_UPLOAD_URL:-https://127.0.0.1:${PORT}/upload}"
export NETWORKKMM_IOS_CURL_RUNTIME_CANCEL_URL="${NETWORKKMM_IOS_CURL_RUNTIME_CANCEL_URL:-https://127.0.0.1:${PORT}/slow}"
# simctl only forwards variables carrying the SIMCTL_CHILD_ prefix.
export SIMCTL_CHILD_NETWORKKMM_IOS_CURL_RUNTIME_CA_PATH="$NETWORKKMM_IOS_CURL_RUNTIME_CA_PATH"
export SIMCTL_CHILD_NETWORKKMM_IOS_CURL_RUNTIME_URL="$NETWORKKMM_IOS_CURL_RUNTIME_URL"
export SIMCTL_CHILD_NETWORKKMM_IOS_CURL_RUNTIME_UPLOAD_URL="$NETWORKKMM_IOS_CURL_RUNTIME_UPLOAD_URL"
export SIMCTL_CHILD_NETWORKKMM_IOS_CURL_RUNTIME_CANCEL_URL="$NETWORKKMM_IOS_CURL_RUNTIME_CANCEL_URL"

cd "$NETWORK_ROOT"
./gradlew \
  :network:iosSimulatorArm64Test \
  --tests 'com.tencent.kmm.network.internal.platform.IosCurlRuntimeTest' \
  --rerun-tasks \
  --no-daemon \
  --console=plain \
  --stacktrace

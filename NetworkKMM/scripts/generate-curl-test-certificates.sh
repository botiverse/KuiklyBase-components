#!/usr/bin/env bash
# Generates deterministic-shape, ephemeral certificate material for the curl
# runtime acceptance matrix. Keys are test-only and recreated on every run.
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "usage: $0 OUTPUT_DIR [ANDROID_KOTLIN_OUTPUT]" >&2
  exit 2
fi

OUTPUT_DIR="$1"
ANDROID_KOTLIN_OUTPUT="${2:-}"
rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"

create_ca() {
  local name="$1" subject="$2"
  local dir="${OUTPUT_DIR}/${name}"
  mkdir -p "$dir/newcerts"
  : > "$dir/index.txt"
  printf '1000\n' > "$dir/serial"
  cat > "$dir/openssl.cnf" <<OPENSSLCONFIG
[req]
distinguished_name = ca_subject
x509_extensions = ca_extensions
prompt = no

[ca_subject]
CN = $subject

[ca_extensions]
basicConstraints = critical,CA:TRUE
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always,issuer

[ca]
default_ca = local_ca

[local_ca]
dir = $dir
database = \$dir/index.txt
new_certs_dir = \$dir/newcerts
serial = \$dir/serial
certificate = \$dir/ca.pem
private_key = \$dir/ca-key.pem
default_md = sha256
default_days = 3650
policy = signing_policy
unique_subject = no

[signing_policy]
commonName = supplied
OPENSSLCONFIG

  # Android commits the generated PKCS#12 fixtures, so positive/mismatch
  # certificates need a long lifetime instead of expiring after the CI run.
  # Keep extensions in the config instead of relying on req -addext so hosted
  # OpenSSL and LibreSSL apply the same issuer contract as `openssl ca`.
  openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -sha256 \
    -config "$dir/openssl.cnf" \
    -keyout "$dir/ca-key.pem" \
    -out "$dir/ca.pem" >/dev/null 2>&1
}

create_server() {
  local name="$1" ca_name="$2" common_name="$3" san="$4"
  local start_date="${5:-}" end_date="${6:-}"
  local ca_dir="${OUTPUT_DIR}/${ca_name}"
  local extensions_path="${OUTPUT_DIR}/${name}-extensions.cnf"
  openssl req -new -newkey rsa:2048 -nodes -sha256 \
    -subj "/CN=${common_name}" \
    -keyout "${OUTPUT_DIR}/${name}-key.pem" \
    -out "${OUTPUT_DIR}/${name}.csr" >/dev/null 2>&1

  cat > "$extensions_path" <<SERVEREXTENSIONS
[server_certificate]
subjectAltName = $san
basicConstraints = critical,CA:FALSE
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid,issuer
SERVEREXTENSIONS

  local date_args=()
  if [[ -n "$start_date" ]]; then
    date_args+=("-startdate" "$start_date" "-enddate" "$end_date")
  else
    date_args+=("-days" "3650")
  fi
  openssl ca -batch -config "$ca_dir/openssl.cnf" \
    -extfile "$extensions_path" \
    -extensions server_certificate \
    -in "${OUTPUT_DIR}/${name}.csr" \
    -out "${OUTPUT_DIR}/${name}.pem" \
    "${date_args[@]}" >/dev/null 2>&1
  rm -f "${OUTPUT_DIR}/${name}.csr" "$extensions_path"
}

create_ca trusted-ca "NetworkKMM Test Root"
create_ca unknown-ca "NetworkKMM Unknown Root"

create_server valid trusted-ca 127.0.0.1 "IP:127.0.0.1"
create_server unknown unknown-ca 127.0.0.1 "IP:127.0.0.1"
create_server mismatch trusted-ca mismatch.invalid "DNS:mismatch.invalid"
create_server expired trusted-ca 127.0.0.1 "IP:127.0.0.1" \
  20200101000000Z 20200102000000Z

cp "${OUTPUT_DIR}/trusted-ca/ca.pem" "${OUTPUT_DIR}/ca.pem"
cp "${OUTPUT_DIR}/unknown-ca/ca.pem" "${OUTPUT_DIR}/wrong-ca.pem"

openssl verify -CAfile "${OUTPUT_DIR}/ca.pem" "${OUTPUT_DIR}/valid.pem" >/dev/null
if openssl verify -CAfile "${OUTPUT_DIR}/ca.pem" "${OUTPUT_DIR}/unknown.pem" >/dev/null 2>&1; then
  echo "unknown-CA test certificate unexpectedly verified" >&2
  exit 2
fi
if openssl verify -CAfile "${OUTPUT_DIR}/ca.pem" "${OUTPUT_DIR}/expired.pem" >/dev/null 2>&1; then
  echo "expired test certificate unexpectedly verified" >&2
  exit 2
fi

if [[ -n "$ANDROID_KOTLIN_OUTPUT" ]]; then
  export_pkcs12() {
    local name="$1" ca_name="$2"
    openssl pkcs12 -export \
      -inkey "${OUTPUT_DIR}/${name}-key.pem" \
      -in "${OUTPUT_DIR}/${name}.pem" \
      -certfile "${OUTPUT_DIR}/${ca_name}/ca.pem" \
      -name server \
      -passout pass:networkkmm \
      -out "${OUTPUT_DIR}/${name}.p12" >/dev/null 2>&1
  }
  base64_one_line() {
    openssl base64 -A -in "$1"
  }

  export_pkcs12 valid trusted-ca
  export_pkcs12 unknown unknown-ca
  export_pkcs12 expired trusted-ca
  export_pkcs12 mismatch trusted-ca

  mkdir -p "$(dirname "$ANDROID_KOTLIN_OUTPUT")"
  kotlin_tmp="${ANDROID_KOTLIN_OUTPUT}.tmp.$$"
  trap 'rm -f "$kotlin_tmp"' EXIT
  cat > "$kotlin_tmp" <<KOTLIN
/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tencent.kmm.network.internal.platform

/** Test-only certificates and private keys generated by generate-curl-test-certificates.sh. */
internal object AndroidCurlTlsTestMaterial {
    const val PASSWORD = "networkkmm"
    const val TRUSTED_CA_PEM_BASE64 = "$(base64_one_line "${OUTPUT_DIR}/ca.pem")"
    const val WRONG_CA_PEM_BASE64 = "$(base64_one_line "${OUTPUT_DIR}/wrong-ca.pem")"
    const val VALID_PKCS12_BASE64 = "$(base64_one_line "${OUTPUT_DIR}/valid.p12")"
    const val UNKNOWN_PKCS12_BASE64 = "$(base64_one_line "${OUTPUT_DIR}/unknown.p12")"
    const val EXPIRED_PKCS12_BASE64 = "$(base64_one_line "${OUTPUT_DIR}/expired.p12")"
    const val MISMATCH_PKCS12_BASE64 = "$(base64_one_line "${OUTPUT_DIR}/mismatch.p12")"
}
KOTLIN
  mv "$kotlin_tmp" "$ANDROID_KOTLIN_OUTPUT"
  trap - EXIT
fi

echo "generated curl certificate matrix in $OUTPUT_DIR"

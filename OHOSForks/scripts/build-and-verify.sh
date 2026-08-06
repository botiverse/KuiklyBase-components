#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'build-and-verify: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "required command is missing: $1"
}

[[ $# -eq 1 ]] || die "usage: $0 <new-output-directory>"

for command in cp curl file git grep java python3 realpath sha256sum; do
  require_command "$command"
done

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
fork_root=$(realpath -- "$script_dir/..")
repository_root=$(realpath -- "$fork_root/..")
gradlew="$repository_root/NetworkKMM/gradlew"
release_spec="$fork_root/release-spec.json"
[[ -x "$gradlew" ]] || die "Gradle wrapper is missing or not executable: $gradlew"
[[ -f "$release_spec" ]] || die "release spec is missing: $release_spec"
[[ -x "$script_dir/canonicalize-klibs.py" ]] || die "KLIB canonicalizer is missing or not executable"
[[ -x "$script_dir/test-klib-canonicalizer.py" ]] || die "KLIB canonicalizer self-test is missing or not executable"

output_root=$(realpath -m -- "$1")
[[ ! -e "$output_root" ]] || die "output path already exists: $output_root"
mkdir -p -- "$output_root"

git_config_home=$(mktemp -d)
cleanup() {
  [[ -n "${git_config_home:-}" && -d "$git_config_home" ]] && rm -rf -- "$git_config_home"
}
trap cleanup EXIT
chmod 700 "$git_config_home"
HOME="$git_config_home" git config --global --add safe.directory "$repository_root"

carrier_git() {
  HOME="$git_config_home" git -C "$repository_root" "$@"
}

[[ -z "$(carrier_git status --porcelain=v1 --untracked-files=all)" ]] ||
  die "release carrier checkout must be clean before source preparation or build"
carrier_sha=$(carrier_git rev-parse HEAD)
[[ "$carrier_sha" =~ ^[0-9a-f]{40}$ ]] || die "git rev-parse HEAD did not return a 40-character lowercase SHA"

expected_image=$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["harmonyImage"])' "$release_spec") ||
  die "release spec lacks its pinned Harmony image"
[[ "$expected_image" == *@sha256:* ]] || die "Harmony image must be pinned by digest"
if [[ -n "${OHOS_FORKS_HARMONY_IMAGE:-}" && "$OHOS_FORKS_HARMONY_IMAGE" != "$expected_image" ]]; then
  die "runtime Harmony image declaration does not match release-spec.json"
fi

ohos_sdk_home="${OHOS_SDK_HOME:-${OHOS_BASE_SDK_HOME:-/opt/harmonyos-tools/command-line-tools/sdk/default/openharmony}}"
[[ -d "$ohos_sdk_home/native/sysroot" ]] || die "OpenHarmony native sysroot is missing: $ohos_sdk_home/native/sysroot"
export OHOS_SDK_HOME="$ohos_sdk_home"
export OHOS_BASE_SDK_HOME="$ohos_sdk_home"

"$script_dir/test-klib-canonicalizer.py"

# Kotlin/Native records source paths in files.knf. A carrier-derived absolute
# path makes those bytes stable across CI and production even when their output
# directories differ.
prepared_root="/tmp/kuiklybase-ohos-forks-$carrier_sha"
[[ ! -e "$prepared_root" ]] || die "deterministic source workspace already exists: $prepared_root"
HOME="$git_config_home" "$script_dir/prepare-sources.sh" "$prepared_root"
[[ -f "$prepared_root/prepared-sources.json" ]] || die "source preparation receipt is missing"
cp -- "$prepared_root/prepared-sources.json" "$output_root/prepared-sources.json"

staging_repository="$output_root/staging"
manifest_directory="$output_root/manifest"
mkdir -p -- "$manifest_directory"

gradle_args=(
  --no-daemon
  --console=plain
  --stacktrace
  -p "$fork_root"
  "-Dmaven.repo.local=$staging_repository"
  "-PforkCarrierSha=$carrier_sha"
  "-PatomicfuSourceDir=$prepared_root/atomicfu"
  "-PcoroutinesSourceDir=$prepared_root/coroutines"
)

"$gradlew" "${gradle_args[@]}" clean :atomicfu:publishToMavenLocal
"$gradlew" "${gradle_args[@]}" :kotlinx-coroutines-core:publishToMavenLocal

canonicalization_receipt="$output_root/klib-canonicalization.json"
"$script_dir/canonicalize-klibs.py" \
  --repository "$staging_repository" \
  --release-spec "$release_spec" \
  --receipt "$canonicalization_receipt"

release_manifest="$manifest_directory/release-manifest.json"
"$script_dir/verify-staging.py" \
  --repository "$staging_repository" \
  --carrier-sha "$carrier_sha" \
  --manifest "$release_manifest"
"$script_dir/test-staging-verifier.py" \
  --repository "$staging_repository" \
  --carrier-sha "$carrier_sha"

smoke_args=(
  --no-daemon
  --console=plain
  --stacktrace
  --refresh-dependencies
  -p "$fork_root/consumer-smoke"
  "-Dmaven.repo.local=$staging_repository"
)
"$gradlew" "${smoke_args[@]}" clean linkDebugExecutableOhosArm64

smoke_binary="$fork_root/consumer-smoke/build/bin/ohosArm64/debugExecutable/kuiklybase-ohos-forks-consumer-smoke.kexe"
[[ -f "$smoke_binary" && ! -L "$smoke_binary" && -s "$smoke_binary" ]] ||
  die "consumer smoke did not produce its OHOS ARM64 executable"
file "$smoke_binary" | grep -Fq 'ELF 64-bit LSB' || die "consumer smoke output is not a 64-bit ELF positive control"
file "$smoke_binary" | grep -Fq 'ARM aarch64' || die "consumer smoke output is not an AArch64 positive control"

missing_version='1.8.0-raft.missing-control'
missing_log="$output_root/missing-version-control.log"
set +e
"$gradlew" "${smoke_args[@]}" \
  "-PcoroutinesVersion=$missing_version" \
  linkDebugExecutableOhosArm64 >"$missing_log" 2>&1
missing_status=$?
set -e
[[ $missing_status -ne 0 ]] || die "missing-version consumer mutation unexpectedly resolved"
grep -Fq "org.jetbrains.kotlinx:kotlinx-coroutines-core:$missing_version" "$missing_log" ||
  die "missing-version consumer mutation did not fail on the intended coordinate"
grep -Fq "$staging_repository/org/jetbrains/kotlinx/kotlinx-coroutines-core/$missing_version" "$missing_log" ||
  die "missing-version consumer mutation lacks its staging-repository positive control"
if grep -E "https?://[^[:space:]]*${missing_version//./\\.}" "$missing_log" >/dev/null; then
  die "candidate coordinate escaped exclusive staging resolution to a remote repository"
fi

kotlin_version=$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["kotlinVersion"])' "$release_spec") ||
  die "release spec lacks kotlinVersion"
effective_user_home=$(python3 -c 'import os, pwd; print(pwd.getpwuid(os.geteuid()).pw_dir)') ||
  die "cannot resolve the effective OS user's home directory"
[[ "$effective_user_home" = /* ]] || die "effective OS user home is not absolute: $effective_user_home"
# GitHub Actions container jobs override shell HOME with /github/home while
# Kotlin/Native still uses the effective user's Java/OS home (/root). Follow
# Kotlin/Native's default instead of the runner's transient shell HOME.
konan_data_dir="${KONAN_DATA_DIR:-$effective_user_home/.konan}"
klib_command="$konan_data_dir/kotlin-native-prebuilt-linux-x86_64-$kotlin_version/bin/klib"
[[ -x "$klib_command" ]] || die "pinned KBA klib command is missing: $klib_command"
"$script_dir/verify-abi.py" \
  --repository "$staging_repository" \
  --klib "$klib_command" \
  --output "$output_root/abi"

release_manifest_sha256=$(sha256sum -- "$release_manifest" | awk '{print $1}')
abi_receipt_sha256=$(sha256sum -- "$output_root/abi/abi-receipt.json" | awk '{print $1}')
prepared_receipt_sha256=$(sha256sum -- "$output_root/prepared-sources.json" | awk '{print $1}')
canonicalization_receipt_sha256=$(sha256sum -- "$canonicalization_receipt" | awk '{print $1}')
smoke_sha256=$(sha256sum -- "$smoke_binary" | awk '{print $1}')
python3 - \
  "$output_root/build-receipt.json" \
  "$carrier_sha" \
  "$expected_image" \
  "$release_manifest_sha256" \
  "$prepared_receipt_sha256" \
  "$canonicalization_receipt_sha256" \
  "$abi_receipt_sha256" \
  "$smoke_sha256" <<'PY'
import json
import sys

output, carrier, image, manifest, prepared, canonicalization, abi, smoke = sys.argv[1:]
receipt = {
    "schema": 1,
    "carrierSha": carrier,
    "harmonyImage": image,
    "releaseManifestSha256": manifest,
    "preparedSourcesReceiptSha256": prepared,
    "klibCanonicalizationReceiptSha256": canonicalization,
    "abiReceiptSha256": abi,
    "smokeExecutableSha256": smoke,
}
with open(output, "w", encoding="utf-8") as stream:
    json.dump(receipt, stream, indent=2, sort_keys=True)
    stream.write("\n")
PY

printf 'build-and-verify: PASS carrier=%s manifest_sha256=%s staging=%s\n' \
  "$carrier_sha" "$release_manifest_sha256" "$staging_repository"

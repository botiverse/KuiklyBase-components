# Agent notes — KuiklyBase-components / NetworkKMM

## Building the OHOS (ohosArm64) target locally on Linux

The `metadata` and `android` targets build directly on the host
(`./gradlew :network:compileKotlinMetadata`, `:network:compileDebugKotlinAndroid`,
`:network:testDebugUnitTest`). The **OHOS** target needs the OpenHarmony SDK,
which is not on the host — but the CI image ships it, so on a Linux box with
Docker you can compile (and thus validate) `ohosArm64` locally instead of
waiting on CI:

```bash
docker pull ghcr.io/bytemain/harmony-next-pipeline-docker/harmonyos-ci-image:latest

# Persist gradle + konan caches across runs (first run extracts the konan
# toolchain + downloads deps; later runs are ~fast).
docker volume create kba-gradle
docker volume create kba-konan

docker run --rm \
  -v "$HOME/work/kuiklybase-components":/work \
  -v kba-gradle:/root/.gradle \
  -v kba-konan:/root/.konan \
  -w /work/NetworkKMM \
  ghcr.io/bytemain/harmony-next-pipeline-docker/harmonyos-ci-image:latest \
  bash -lc '
    git config --global --add safe.directory "*"
    export OHOS_SDK_HOME="$OHOS_BASE_SDK_HOME"   # konan reads OHOS_SDK_HOME, not OHOS_BASE_SDK_HOME
    ./gradlew :network:compileKotlinOhosArm64 --no-daemon --console=plain
  '
```

Key points:
- The image sets `OHOS_BASE_SDK_HOME` but **konan looks up `OHOS_SDK_HOME`** —
  export it or you get `OHOS SDK is not found in '/usr/local/lib/DevEco-Studio/...'`.
- Use `--no-daemon`; the container is single-use.
- Mount the repo read-write (the build writes `build/` dirs; they end up
  root-owned on the host — clean with `sudo rm -rf` if needed).
- This validates OHOS-side Kotlin **and** cinterop compiles. The native `.so`
  itself (pbcurlwrapper) is still rebuilt + committed via the
  `networkkmm-ohos-native.yml` workflow (`commit_binaries`), same as raft.3/4.

## Publishing a new raft.N

1. Merge the change to `master`; bump `NetworkKMM/gradle.properties`
   `mavenVersion` + add a `NetworkKMM/CHANGELOG-raft.md` entry.
2. If any `.cpp`/header changed, dispatch `networkkmm-ohos-native.yml` with
   `commit_binaries=true` first (rebuilds + commits the `.so`).
3. Dispatch `publish-network-github-packages.yml` with `version=0.1.0-raft.N`
   (builds all targets incl. iOS on mac, then publishes the original GAVs to
   both GitHub Packages and Raft Artifacts). The jobs require the protected
   `raft-artifacts-production` environment and its scope-limited
   `RAFT_ARTIFACTS_PUBLISH_TOKEN`; do not bypass the clean-checkout,
   source-SHA, immutable-state, or two-repository convergence gates.
4. To consume locally (the dev box cannot read GitHub Packages — 401 without
   `read:packages`), publish to mavenLocal from source:
   `./gradlew :network:publishKotlinMultiplatformPublicationToMavenLocal :network:publishAndroidPublicationToMavenLocal`
   (the Android publication is named `android`, not `androidRelease`).

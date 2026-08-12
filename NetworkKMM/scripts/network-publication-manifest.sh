#!/usr/bin/env bash

# Shared task-to-coordinate contract for NetworkKMM's immutable dual publish.
# This file is sourced by the planner, verifier, publisher, and mutation gate.

network_assert_known_publication_task() {
  local task="$1"
  case "$task" in
    :network:publishAndroidPublicationToGithubPackagesRepository | \
    :network-android-curl-runtime:publishAndroidCurlRuntimePublicationToGithubPackagesRepository | \
    :network:publishIosX64PublicationToGithubPackagesRepository | \
    :network:publishIosArm64PublicationToGithubPackagesRepository | \
    :network:publishIosSimulatorArm64PublicationToGithubPackagesRepository | \
    :network:publishOhosArm64PublicationToGithubPackagesRepository | \
    :network-ohos-runtime:publishAllPublicationsToGithubPackagesRepository | \
    :network-ohos-runtime-gradle-plugin:publishAllPublicationsToGithubPackagesRepository | \
    :network:publishKotlinMultiplatformPublicationToGithubPackagesRepository)
      return 0
      ;;
    *)
      echo "Unknown NetworkKMM publication task: $task" >&2
      return 1
      ;;
  esac
}

network_raft_task_for() {
  local github_task="$1"
  network_assert_known_publication_task "$github_task"
  printf '%s\n' "${github_task/GithubPackagesRepository/RaftArtifactsRepository}"
}

network_publication_version_for() {
  local github_task="$1"
  local base_version="$2"
  case "$github_task" in
    :network:publishOhosArm64PublicationToGithubPackagesRepository | \
    :network-ohos-runtime:publishAllPublicationsToGithubPackagesRepository | \
    :network-ohos-runtime-gradle-plugin:publishAllPublicationsToGithubPackagesRepository)
      printf '%s-ohos\n' "$base_version"
      ;;
    :network:publishKotlinMultiplatformPublicationToGithubPackagesRepository)
      if [[ "${NETWORK_SETTINGS_FILE:-}" == "settings.ohos.gradle.kts" ]]; then
        printf '%s-ohos\n' "$base_version"
      else
        printf '%s\n' "$base_version"
      fi
      ;;
    *)
      printf '%s\n' "$base_version"
      ;;
  esac
}

network_required_paths_for() {
  local github_task="$1"
  local base_version="$2"
  local version
  network_assert_known_publication_task "$github_task"
  version="$(network_publication_version_for "$github_task" "$base_version")"

  case "$github_task" in
    :network:publishAndroidPublicationToGithubPackagesRepository)
      printf '%s\n' \
        "com/tencent/kuiklybase/network-android/$version/network-android-$version.aar" \
        "com/tencent/kuiklybase/network-android/$version/network-android-$version-debug.aar" \
        "com/tencent/kuiklybase/network-android/$version/network-android-$version.pom" \
        "com/tencent/kuiklybase/network-android/$version/network-android-$version-sources.jar" \
        "com/tencent/kuiklybase/network-android/$version/network-android-$version.module"
      ;;
    :network-android-curl-runtime:publishAndroidCurlRuntimePublicationToGithubPackagesRepository)
      printf '%s\n' \
        "com/tencent/kuiklybase/network-android-curl-runtime/$version/network-android-curl-runtime-$version.aar" \
        "com/tencent/kuiklybase/network-android-curl-runtime/$version/network-android-curl-runtime-$version.pom" \
        "com/tencent/kuiklybase/network-android-curl-runtime/$version/network-android-curl-runtime-$version-sources.jar" \
        "com/tencent/kuiklybase/network-android-curl-runtime/$version/network-android-curl-runtime-$version.module"
      ;;
    :network:publishIosX64PublicationToGithubPackagesRepository)
      printf '%s\n' \
        "com/tencent/kuiklybase/network-iosx64/$version/network-iosx64-$version.klib" \
        "com/tencent/kuiklybase/network-iosx64/$version/network-iosx64-$version-cinterop-iosCurl.klib" \
        "com/tencent/kuiklybase/network-iosx64/$version/network-iosx64-$version.pom" \
        "com/tencent/kuiklybase/network-iosx64/$version/network-iosx64-$version-sources.jar" \
        "com/tencent/kuiklybase/network-iosx64/$version/network-iosx64-$version-metadata.jar" \
        "com/tencent/kuiklybase/network-iosx64/$version/network-iosx64-$version.module"
      ;;
    :network:publishIosArm64PublicationToGithubPackagesRepository)
      printf '%s\n' \
        "com/tencent/kuiklybase/network-iosarm64/$version/network-iosarm64-$version.klib" \
        "com/tencent/kuiklybase/network-iosarm64/$version/network-iosarm64-$version-cinterop-iosCurl.klib" \
        "com/tencent/kuiklybase/network-iosarm64/$version/network-iosarm64-$version.pom" \
        "com/tencent/kuiklybase/network-iosarm64/$version/network-iosarm64-$version-sources.jar" \
        "com/tencent/kuiklybase/network-iosarm64/$version/network-iosarm64-$version-metadata.jar" \
        "com/tencent/kuiklybase/network-iosarm64/$version/network-iosarm64-$version.module"
      ;;
    :network:publishIosSimulatorArm64PublicationToGithubPackagesRepository)
      printf '%s\n' \
        "com/tencent/kuiklybase/network-iossimulatorarm64/$version/network-iossimulatorarm64-$version.klib" \
        "com/tencent/kuiklybase/network-iossimulatorarm64/$version/network-iossimulatorarm64-$version-cinterop-iosCurl.klib" \
        "com/tencent/kuiklybase/network-iossimulatorarm64/$version/network-iossimulatorarm64-$version.pom" \
        "com/tencent/kuiklybase/network-iossimulatorarm64/$version/network-iossimulatorarm64-$version-sources.jar" \
        "com/tencent/kuiklybase/network-iossimulatorarm64/$version/network-iossimulatorarm64-$version-metadata.jar" \
        "com/tencent/kuiklybase/network-iossimulatorarm64/$version/network-iossimulatorarm64-$version.module"
      ;;
    :network:publishOhosArm64PublicationToGithubPackagesRepository)
      printf '%s\n' \
        "com/tencent/kuiklybase/network-ohosarm64/$version/network-ohosarm64-$version.klib" \
        "com/tencent/kuiklybase/network-ohosarm64/$version/network-ohosarm64-$version-cinterop-interop.klib" \
        "com/tencent/kuiklybase/network-ohosarm64/$version/network-ohosarm64-$version.pom" \
        "com/tencent/kuiklybase/network-ohosarm64/$version/network-ohosarm64-$version-sources.jar" \
        "com/tencent/kuiklybase/network-ohosarm64/$version/network-ohosarm64-$version.module"
      ;;
    :network-ohos-runtime:publishAllPublicationsToGithubPackagesRepository)
      printf '%s\n' \
        "com/tencent/kuiklybase/network-ohos-runtime/$version/network-ohos-runtime-$version.zip" \
        "com/tencent/kuiklybase/network-ohos-runtime/$version/network-ohos-runtime-$version.pom" \
        "com/tencent/kuiklybase/network-ohos-runtime/$version/network-ohos-runtime-$version-sources.jar"
      ;;
    :network-ohos-runtime-gradle-plugin:publishAllPublicationsToGithubPackagesRepository)
      local marker_group="com/tencent/kuiklybase/network/ohos-runtime"
      local marker_artifact="com.tencent.kuiklybase.network.ohos-runtime.gradle.plugin"
      printf '%s\n' \
        "com/tencent/kuiklybase/network-ohos-runtime-gradle-plugin/$version/network-ohos-runtime-gradle-plugin-$version.jar" \
        "com/tencent/kuiklybase/network-ohos-runtime-gradle-plugin/$version/network-ohos-runtime-gradle-plugin-$version.pom" \
        "com/tencent/kuiklybase/network-ohos-runtime-gradle-plugin/$version/network-ohos-runtime-gradle-plugin-$version-sources.jar" \
        "com/tencent/kuiklybase/network-ohos-runtime-gradle-plugin/$version/network-ohos-runtime-gradle-plugin-$version-javadoc.jar" \
        "com/tencent/kuiklybase/network-ohos-runtime-gradle-plugin/$version/network-ohos-runtime-gradle-plugin-$version.module" \
        "$marker_group/$marker_artifact/$version/$marker_artifact-$version.pom"
      ;;
    :network:publishKotlinMultiplatformPublicationToGithubPackagesRepository)
      printf '%s\n' \
        "com/tencent/kuiklybase/network/$version/network-$version.module" \
        "com/tencent/kuiklybase/network/$version/network-$version.pom" \
        "com/tencent/kuiklybase/network/$version/network-$version-sources.jar" \
        "com/tencent/kuiklybase/network/$version/network-$version.jar" \
        "com/tencent/kuiklybase/network/$version/network-$version-kotlin-tooling-metadata.json"
      ;;
  esac
}

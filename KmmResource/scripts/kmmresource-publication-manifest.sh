#!/usr/bin/env bash

# Shared task-to-coordinate contract for KmmResource's immutable Raft mirror.
# This file is sourced by the planner, verifier, publisher, and mutation gate.
# The file set must match the standard (non-Compose) KmmResource publication
# matrix — the same set published by publish-kmmresource-github-packages.yml
# and already mirrored for 0.1.0-raft.4.

kmmresource_assert_known_publication_task() {
  local task="$1"
  case "$task" in
    :resource-generator:publishAllPublicationsToGithubPackagesRepository | \
    :resource-core:publishAndroidPublicationToGithubPackagesRepository | \
    :resource-core:publishOhosArm64PublicationToGithubPackagesRepository | \
    :resource-core:publishIosX64PublicationToGithubPackagesRepository | \
    :resource-core:publishIosArm64PublicationToGithubPackagesRepository | \
    :resource-core:publishIosSimulatorArm64PublicationToGithubPackagesRepository | \
    :resource-core:publishKotlinMultiplatformPublicationToGithubPackagesRepository)
      return 0
      ;;
    *)
      echo "Unknown KmmResource publication task: $task" >&2
      return 1
      ;;
  esac
}

kmmresource_required_paths_for() {
  local github_task="$1"
  local version="$2"
  kmmresource_assert_known_publication_task "$github_task"

  case "$github_task" in
    :resource-generator:publishAllPublicationsToGithubPackagesRepository)
      printf '%s\n' \
        "com/tencent/kuiklybase/resource-generator/$version/resource-generator-$version.jar" \
        "com/tencent/kuiklybase/resource-generator/$version/resource-generator-$version.module" \
        "com/tencent/kuiklybase/resource-generator/$version/resource-generator-$version.pom" \
        "com/tencent/kuiklybase/resource/generator/com.tencent.kuiklybase.resource.generator.gradle.plugin/$version/com.tencent.kuiklybase.resource.generator.gradle.plugin-$version.pom"
      ;;
    :resource-core:publishAndroidPublicationToGithubPackagesRepository)
      printf '%s\n' \
        "com/tencent/kuiklybase/resource-core-android/$version/resource-core-android-$version.aar" \
        "com/tencent/kuiklybase/resource-core-android/$version/resource-core-android-$version-debug.aar" \
        "com/tencent/kuiklybase/resource-core-android/$version/resource-core-android-$version-debug-sources.jar" \
        "com/tencent/kuiklybase/resource-core-android/$version/resource-core-android-$version.pom" \
        "com/tencent/kuiklybase/resource-core-android/$version/resource-core-android-$version-sources.jar" \
        "com/tencent/kuiklybase/resource-core-android/$version/resource-core-android-$version.module"
      ;;
    :resource-core:publishOhosArm64PublicationToGithubPackagesRepository)
      printf '%s\n' \
        "com/tencent/kuiklybase/resource-core-ohosarm64/$version/resource-core-ohosarm64-$version-cinterop-interop.klib" \
        "com/tencent/kuiklybase/resource-core-ohosarm64/$version/resource-core-ohosarm64-$version.klib" \
        "com/tencent/kuiklybase/resource-core-ohosarm64/$version/resource-core-ohosarm64-$version.pom" \
        "com/tencent/kuiklybase/resource-core-ohosarm64/$version/resource-core-ohosarm64-$version-sources.jar" \
        "com/tencent/kuiklybase/resource-core-ohosarm64/$version/resource-core-ohosarm64-$version.module"
      ;;
    :resource-core:publishIosX64PublicationToGithubPackagesRepository)
      printf '%s\n' \
        "com/tencent/kuiklybase/resource-core-iosx64/$version/resource-core-iosx64-$version.klib" \
        "com/tencent/kuiklybase/resource-core-iosx64/$version/resource-core-iosx64-$version-metadata.jar" \
        "com/tencent/kuiklybase/resource-core-iosx64/$version/resource-core-iosx64-$version.pom" \
        "com/tencent/kuiklybase/resource-core-iosx64/$version/resource-core-iosx64-$version-sources.jar" \
        "com/tencent/kuiklybase/resource-core-iosx64/$version/resource-core-iosx64-$version.module"
      ;;
    :resource-core:publishIosArm64PublicationToGithubPackagesRepository)
      printf '%s\n' \
        "com/tencent/kuiklybase/resource-core-iosarm64/$version/resource-core-iosarm64-$version.klib" \
        "com/tencent/kuiklybase/resource-core-iosarm64/$version/resource-core-iosarm64-$version-metadata.jar" \
        "com/tencent/kuiklybase/resource-core-iosarm64/$version/resource-core-iosarm64-$version.pom" \
        "com/tencent/kuiklybase/resource-core-iosarm64/$version/resource-core-iosarm64-$version-sources.jar" \
        "com/tencent/kuiklybase/resource-core-iosarm64/$version/resource-core-iosarm64-$version.module"
      ;;
    :resource-core:publishIosSimulatorArm64PublicationToGithubPackagesRepository)
      printf '%s\n' \
        "com/tencent/kuiklybase/resource-core-iossimulatorarm64/$version/resource-core-iossimulatorarm64-$version.klib" \
        "com/tencent/kuiklybase/resource-core-iossimulatorarm64/$version/resource-core-iossimulatorarm64-$version-metadata.jar" \
        "com/tencent/kuiklybase/resource-core-iossimulatorarm64/$version/resource-core-iossimulatorarm64-$version.pom" \
        "com/tencent/kuiklybase/resource-core-iossimulatorarm64/$version/resource-core-iossimulatorarm64-$version-sources.jar" \
        "com/tencent/kuiklybase/resource-core-iossimulatorarm64/$version/resource-core-iossimulatorarm64-$version.module"
      ;;
    :resource-core:publishKotlinMultiplatformPublicationToGithubPackagesRepository)
      printf '%s\n' \
        "com/tencent/kuiklybase/resource-core/$version/resource-core-$version.jar" \
        "com/tencent/kuiklybase/resource-core/$version/resource-core-$version-kotlin-tooling-metadata.json" \
        "com/tencent/kuiklybase/resource-core/$version/resource-core-$version.pom" \
        "com/tencent/kuiklybase/resource-core/$version/resource-core-$version-sources.jar" \
        "com/tencent/kuiklybase/resource-core/$version/resource-core-$version.module"
      ;;
  esac
}

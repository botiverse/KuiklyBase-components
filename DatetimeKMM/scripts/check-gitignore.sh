#!/usr/bin/env bash
#
# Fail-closed guard for the DatetimeKMM .gitignore. The production source
# package is build.raft.kuiklybase.datetime, so source files live under
# .../kotlin/build/raft/... . A generic `build/` / `**/build/` ignore pattern
# would silently ignore the whole source tree (and hide it from rg/audits).
# This gate asserts that representative source paths under build/raft are NOT
# ignored, while real Gradle output directories still ARE ignored.
#
# Usage: check-gitignore.sh   (run from anywhere in the repo)

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
cd "$repo_root"

fail=0

# not_ignored <path>: must NOT be ignored (git check-ignore exits non-zero).
not_ignored() {
    if git check-ignore -q --no-index "$1"; then
        echo "  FAIL source path is ignored (must be tracked): $1" >&2
        fail=1
    else
        echo "  OK   source path not ignored: $1"
    fi
}

# ignored <path>: must be ignored (git check-ignore exits zero).
ignored() {
    if git check-ignore -q --no-index "$1"; then
        echo "  OK   gradle output ignored: $1"
    else
        echo "  FAIL gradle output not ignored (must be ignored): $1" >&2
        fail=1
    fi
}

echo "== DatetimeKMM gitignore fail-closed gate =="

# Representative production source paths under the build.raft package, including
# a not-yet-existing Future.kt to prove future files in the package are tracked.
not_ignored "DatetimeKMM/datetime/src/commonMain/kotlin/build/raft/kuiklybase/datetime/Clock.kt"
not_ignored "DatetimeKMM/datetime/src/commonMain/kotlin/build/raft/kuiklybase/datetime/Future.kt"
not_ignored "DatetimeKMM/datetime/src/androidMain/kotlin/build/raft/kuiklybase/datetime/PlatformDatetime.android.kt"
not_ignored "DatetimeKMM/datetime/src/iosMain/kotlin/build/raft/kuiklybase/datetime/PlatformDatetime.ios.kt"
not_ignored "DatetimeKMM/datetime/src/ohosArm64Main/kotlin/build/raft/kuiklybase/datetime/PlatformDatetime.ohos.kt"

# Real Gradle output and local config must remain ignored.
ignored "DatetimeKMM/build"
ignored "DatetimeKMM/build/libs/x.jar"
ignored "DatetimeKMM/datetime/build"
ignored "DatetimeKMM/datetime/build/classes/kotlin"
ignored "DatetimeKMM/.gradle"
ignored "DatetimeKMM/local.properties"

if [ "$fail" -ne 0 ]; then
    echo "GITIGNORE_GATE_FAIL" >&2
    exit 1
fi
echo "GITIGNORE_GATE_PASS"

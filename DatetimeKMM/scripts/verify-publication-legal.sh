#!/usr/bin/env bash
#
# Asserts that the Apache-2.0 LICENSE/NOTICE/PROVENANCE files are present,
# byte-identical to the checked-in source, in every published artifact that is
# defined to carry them. See legal/META-INF/PROVENANCE.md, "Publication delivery
# contract".
#
# Usage:
#   verify-publication-legal.sh <m2-repo-root> <normal|ohos>
#
# The source of truth is DatetimeKMM/legal/META-INF/. The gate computes each
# source file's SHA-256 at run time and compares it with the copy extracted from
# each carrying artifact, so the contract cannot drift from the checked-in
# source.

set -euo pipefail

m2_root="${1:?usage: verify-publication-legal.sh <m2-repo-root> <normal|ohos>}"
variant="${2:?usage: verify-publication-legal.sh <m2-repo-root> <normal|ohos>}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
legal_src="$script_dir/../legal/META-INF"

FILES=(LICENSE.txt NOTICE.txt PROVENANCE.md)

declare -A SRC_SHA
for f in "${FILES[@]}"; do
    test -s "$legal_src/$f" || { echo "missing source legal file: $legal_src/$f" >&2; exit 1; }
    SRC_SHA[$f]="$(sha256sum "$legal_src/$f" | cut -d' ' -f1)"
done

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# sha_in_archive <archive> <inner-archive-or-empty> <path-inside>
sha_in_archive() {
    local archive="$1" inner="$2" path="$3" target="$archive"
    test -s "$archive" || { echo "MISSING_ARTIFACT"; return; }
    if [ -n "$inner" ]; then
        rm -rf "$work/inner"; mkdir -p "$work/inner"
        (cd "$work/inner" && unzip -o -q "$archive" "$inner")
        target="$work/inner/$inner"
    fi
    rm -rf "$work/extract"; mkdir -p "$work/extract"
    (cd "$work/extract" && unzip -o -q "$target" "$path" 2>/dev/null) || { echo "ABSENT"; return; }
    if [ -s "$work/extract/$path" ]; then
        sha256sum "$work/extract/$path" | cut -d' ' -f1
    else
        echo "ABSENT"
    fi
}

# check_artifact <label> <archive> <inner-archive-or-empty>
check_artifact() {
    local label="$1" archive="$2" inner="$3" ok=1
    for f in "${FILES[@]}"; do
        local got
        got="$(sha_in_archive "$archive" "$inner" "META-INF/$f")"
        if [ "$got" = "${SRC_SHA[$f]}" ]; then
            echo "  OK   $label META-INF/$f"
        else
            echo "  FAIL $label META-INF/$f expected=${SRC_SHA[$f]} got=$got" >&2
            ok=0
        fi
    done
    [ "$ok" = 1 ] || exit 1
}

datetime_dir="$m2_root/com/tencent/kuiklybase/datetime"

if [ "$variant" = "normal" ]; then
    android_dir="$m2_root/com/tencent/kuiklybase/datetime-android"
    version="$(ls "$datetime_dir" | grep -v xml | head -1)"
    android_version="$(ls "$android_dir" | grep -v xml | head -1)"
    echo "== normal tree publication legal gate (version=$version android=$android_version) =="
    check_artifact "metadata-jar"        "$datetime_dir/$version/datetime-$version.jar" ""
    check_artifact "root-sources"        "$datetime_dir/$version/datetime-$version-sources.jar" ""
    check_artifact "android-sources"     "$android_dir/$android_version/datetime-android-$android_version-sources.jar" ""
    check_artifact "android-aar-classes" "$android_dir/$android_version/datetime-android-$android_version.aar" "classes.jar"
elif [ "$variant" = "ios" ]; then
    version="$(ls "$datetime_dir" | grep -v xml | head -1)"
    echo "== iOS tree publication legal gate (root=$version) =="
    check_artifact "ios-metadata-jar"          "$datetime_dir/$version/datetime-$version.jar" ""
    check_artifact "ios-root-sources"          "$datetime_dir/$version/datetime-$version-sources.jar" ""
    for tgt in iosx64 iosarm64 iossimulatorarm64; do
        tgt_dir="$m2_root/com/tencent/kuiklybase/datetime-$tgt"
        [ -d "$tgt_dir" ] || { echo "  SKIP $tgt (not published)"; continue; }
        tgt_version="$(ls "$tgt_dir" | grep -v xml | head -1)"
        check_artifact "$tgt-sources" "$tgt_dir/$tgt_version/datetime-$tgt-$tgt_version-sources.jar" ""
    done
elif [ "$variant" = "ohos" ]; then
    ohosarm64_dir="$m2_root/com/tencent/kuiklybase/datetime-ohosarm64"
    version="$(ls "$datetime_dir" | grep -v xml | head -1)"
    ohos_version="$(ls "$ohosarm64_dir" | grep -v xml | head -1)"
    echo "== OHOS tree publication legal gate (root=$version ohosarm64=$ohos_version) =="
    check_artifact "ohos-metadata-jar"      "$datetime_dir/$version/datetime-$version.jar" ""
    check_artifact "ohos-root-sources"      "$datetime_dir/$version/datetime-$version-sources.jar" ""
    check_artifact "ohos-ohosarm64-sources" "$ohosarm64_dir/$ohos_version/datetime-ohosarm64-$ohos_version-sources.jar" ""
else
    echo "unknown variant: $variant (expected normal|ohos)" >&2
    exit 2
fi

echo "PUBLICATION_LEGAL_GATE_PASS ($variant)"

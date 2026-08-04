#!/usr/bin/env python3
"""Static ABI/source gate for the KNOI Aki bootstrap boundary.

This is intentionally a scripts/ CI constraint, not a source-text unit test.
It protects names consumed by ArkTS and dlsym before a real addon probe runs.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import shutil
import tempfile


EXPECTED_NATIVE_EXPORTS = {
    "setup",
    "init",
    "create_function_waiter",
    "wait_on_function_waiter",
    "notify_function_waiter",
}
EXPECTED_BOOTSTRAP_SYMBOLS = {
    "com_tencent_tmm_knoi_initEnv",
    "com_tencent_tmm_knoi_initBridge",
}


class ContractFailure(RuntimeError):
    pass


def verify(root: Path) -> None:
    cpp_root = root / "ohosApp" / "knoi" / "src" / "main" / "cpp"
    sources = [cpp_root / "knoi_aki.cpp", cpp_root / "async_invoker_aki.cpp"]
    combined = "\n".join(path.read_text() for path in sources)
    actual_exports = set(
        re.findall(
            r"JSBIND_FUNCTION\(\s*([A-Za-z0-9_]+)\s*\)",
            combined,
        )
    )
    if actual_exports != EXPECTED_NATIVE_EXPORTS:
        raise ContractFailure(
            f"native export drift: expected={sorted(EXPECTED_NATIVE_EXPORTS)}, "
            f"actual={sorted(actual_exports)}"
        )
    if "JSBIND_SCOPED_FUNCTION(" in combined or "BindSymbols(kKnoiAkiModuleScope)" in combined:
        raise ContractFailure("undocumented scoped Aki binding must not be used")
    if "aki::JSBind::BindSymbols(env, exports)" not in combined:
        raise ContractFailure("documented Aki hybrid binding is missing")
    if 'napi_delete_property(env, exports, key, &deleted)' not in combined:
        raise ContractFailure("Aki's internal JSBind class is not removed from the public surface")

    declarations = (
        root / "ohosApp" / "knoi" / "src" / "main" / "types" / "libknoi" / "index.d.ts"
    ).read_text()
    declared = set(re.findall(r"export declare function\s+([A-Za-z0-9_]+)\s*\(", declarations))
    if declared != EXPECTED_NATIVE_EXPORTS:
        raise ContractFailure(
            f"TypeScript native declaration drift: expected={sorted(EXPECTED_NATIVE_EXPORTS)}, "
            f"actual={sorted(declared)}"
        )

    loader = (cpp_root / "native_bridge_loader.cpp").read_text()
    for symbol in EXPECTED_BOOTSTRAP_SYMBOLS:
        if loader.count(f'"{symbol}"') != 1:
            raise ContractFailure(f"bootstrap symbol drift: {symbol}")

    cmake = (cpp_root / "CMakeLists.txt").read_text()
    required_cmake = (
        'option(KNOI_USE_AKI "Build the KNOI addon on the pinned Aki runtime" ON)',
        "set(AKI_BUILDING_SHARED OFF",
        "CXX_VISIBILITY_PRESET hidden",
        "knoi_legacy.cpp async_invoker_legacy.cpp",
    )
    for fragment in required_cmake:
        if fragment not in cmake:
            raise ContractFailure(f"CMake contract missing: {fragment}")


def self_test(root: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="knoi-surface-self-test-") as temporary:
        copy = Path(temporary) / "knoi"
        shutil.copytree(root, copy)

        adapter = copy / "ohosApp" / "knoi" / "src" / "main" / "cpp" / "knoi_aki.cpp"
        adapter.write_text(adapter.read_text().replace(
            "JSBIND_FUNCTION(setup);", ""
        ))
        try:
            verify(copy)
        except ContractFailure:
            pass
        else:
            raise ContractFailure("self-test mutation survived: missing setup export")

        shutil.rmtree(copy)
        shutil.copytree(root, copy)
        loader = copy / "ohosApp" / "knoi" / "src" / "main" / "cpp" / "native_bridge_loader.cpp"
        loader.write_text(loader.read_text().replace(
            "com_tencent_tmm_knoi_initBridge", "com_tencent_tmm_knoi_initBridge_v2"
        ))
        try:
            verify(copy)
        except ContractFailure:
            pass
        else:
            raise ContractFailure("self-test mutation survived: bootstrap symbol rename")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parent.parent,
        help="KNOI project root",
    )
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    verify(args.root)
    if args.self_test:
        self_test(args.root)
    print("KNOI_SURFACE_PASS native_exports=5 bootstrap_symbols=2 documented_aki=true")


if __name__ == "__main__":
    try:
        main()
    except ContractFailure as error:
        raise SystemExit(f"KNOI_SURFACE_FAIL: {error}")

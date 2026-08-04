#!/usr/bin/env python3
"""Verify the architecture and exact dynamic-link contract of libknoi.so."""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess


COMMON_NEEDED = {
    "libace_napi.z.so",
    "libc++_shared.so",
    "libc.so",
    "libhilog_ndk.z.so",
}
EXPECTED_NEEDED = {
    "aki": COMMON_NEEDED | {"libuv.so"},
    "legacy": COMMON_NEEDED,
}


class ContractError(RuntimeError):
    """The addon ELF does not match the frozen runtime contract."""


def field(output: str, name: str) -> str:
    match = re.search(rf"^\s*{re.escape(name)}:\s*(.+?)\s*$", output, re.MULTILINE)
    if match is None:
        raise ContractError(f"ELF header field missing: {name}")
    return match.group(1)


def verify_contract(header: str, dynamic: str, mode: str) -> None:
    elf_class = field(header, "Class")
    if elf_class != "ELF64":
        raise ContractError(f"expected ELF64, got {elf_class}")

    machine = field(header, "Machine")
    if machine != "AArch64":
        raise ContractError(f"expected AArch64, got {machine}")

    search_paths = re.findall(r"\((RPATH|RUNPATH)\)", dynamic)
    if search_paths:
        raise ContractError(
            "runtime search path tags are forbidden: " + ",".join(sorted(set(search_paths)))
        )

    needed = re.findall(r"Shared library:\s*\[([^]]+)\]", dynamic)
    duplicates = sorted(name for name in set(needed) if needed.count(name) != 1)
    if duplicates:
        raise ContractError(f"duplicate DT_NEEDED entries: {duplicates}")
    actual = set(needed)
    expected = EXPECTED_NEEDED[mode]
    if actual != expected:
        missing = sorted(expected - actual)
        unexpected = sorted(actual - expected)
        raise ContractError(
            f"DT_NEEDED drift for {mode}: missing={missing} unexpected={unexpected}"
        )


def expect_red(header: str, dynamic: str, mode: str, mutation: str) -> None:
    try:
        verify_contract(header, dynamic, mode)
    except ContractError:
        return
    raise ContractError(f"{mutation} contract mutation survived")


def self_test() -> None:
    header = """ELF Header:
  Class:                             ELF64
  Machine:                           AArch64
"""
    dynamic = "\n".join(
        f" 0x0000000000000001 (NEEDED) Shared library: [{name}]"
        for name in sorted(EXPECTED_NEEDED["aki"])
    )
    verify_contract(header, dynamic, "aki")
    verify_contract(
        header,
        "\n".join(
            f" 0x0000000000000001 (NEEDED) Shared library: [{name}]"
            for name in sorted(EXPECTED_NEEDED["legacy"])
        ),
        "legacy",
    )

    expect_red(header.replace("ELF64", "ELF32"), dynamic, "aki", "ELF class")
    expect_red(header.replace("AArch64", "Advanced Micro Devices X86-64"), dynamic, "aki", "machine")
    expect_red(
        header,
        dynamic + "\n 0x0000000000000001 (NEEDED) Shared library: [libunexpected.so]",
        "aki",
        "extra DT_NEEDED",
    )
    expect_red(
        header,
        dynamic + "\n 0x000000000000001d (RUNPATH) Library runpath: [/tmp/knoi]",
        "aki",
        "DT_RUNPATH",
    )
    expect_red(
        header,
        dynamic + "\n 0x000000000000000f (RPATH) Library rpath: [/tmp/knoi]",
        "aki",
        "DT_RPATH",
    )


def readelf(readelf: str, option: str, library: Path) -> str:
    return subprocess.check_output([readelf, option, str(library)], text=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--library", type=Path)
    parser.add_argument("--mode", choices=("aki", "legacy"))
    parser.add_argument("--readelf", default="readelf")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    try:
        if args.self_test:
            self_test()
        if args.library is not None:
            if args.mode is None:
                parser.error("--mode is required with --library")
            verify_contract(
                readelf(args.readelf, "-h", args.library),
                readelf(args.readelf, "-d", args.library),
                args.mode,
            )
        elif not args.self_test:
            parser.error("provide --library or --self-test")
    except (ContractError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"KNOI_ADDON_ELF_FAIL: {error}") from error


if __name__ == "__main__":
    main()

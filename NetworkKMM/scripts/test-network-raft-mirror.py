#!/usr/bin/env python3
"""Offline mutation teeth for NetworkKMM's resumable primary mirror."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("network_raft_mirror", HERE / "network-raft-mirror.py")
mirror = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(mirror)


class FakeReader:
    def __init__(self, store: dict[str, bytes]) -> None:
        self.store = store

    def get(self, relative: str, attempts: int = 3):
        del attempts
        return (200, self.store[relative]) if relative in self.store else (404, b"")


class FakeWriter:
    def __init__(self, store: dict[str, bytes]) -> None:
        self.store = store
        self.puts: list[str] = []
        self.force_conflict: set[str] = set()

    def put(self, relative: str, body: bytes) -> int:
        self.puts.append(relative)
        if relative in self.force_conflict or relative in self.store:
            return 409
        self.store[relative] = body
        return 201


def digest(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def expect_error(name: str, fn, needle: str) -> None:
    try:
        fn()
    except mirror.MirrorError as error:
        assert needle in str(error), (name, error)
    else:
        raise AssertionError(f"{name}: no error")


def main() -> None:
    os.environ.update(
        GITHUB_SHA="a" * 40,
        NETWORK_ARTIFACT_SOURCE_SHA="b" * 40,
        GITHUB_RUN_ID="123",
        GITHUB_RUN_ATTEMPT="2",
    )
    files = {
        "com/tencent/kuiklybase/network-a/0.1.0-raft.33/a.pom": b"pom",
        "com/tencent/kuiklybase/network-a/0.1.0-raft.33/a.jar": b"jar",
        "com/tencent/kuiklybase/network-b/0.1.0-raft.33/b.module": b"module",
    }
    receipt = {
        "schema": 1,
        "status": "complete",
        "authority": "github-packages",
        "version": "0.1.0-raft.33",
        "tasks": ["task-a"],
        "fileCount": len(files),
        "provenance": mirror.current_tuple(),
        "files": [
            {"path": path, "size": len(body), "sha256": digest(body)}
            for path, body in files.items()
        ],
    }

    empty: dict[str, bytes] = {}
    state = mirror.classify(receipt, FakeReader(empty), [])
    assert state["decision"] == "publish-all-absent" and len(state["missing"]) == 3

    partial = {next(iter(files)): next(iter(files.values()))}
    state = mirror.classify(receipt, FakeReader(partial), list(partial))
    assert state["decision"] == "resume-partial-exact" and len(state["existing"]) == 1

    complete = dict(files)
    state = mirror.classify(receipt, FakeReader(complete), list(complete))
    assert state["decision"] == "noop-complete-identical"

    divergent = dict(partial)
    divergent[next(iter(divergent))] = b"different"
    state = mirror.classify(receipt, FakeReader(divergent), list(divergent))
    assert state["decision"] == "hold-conflict" and state["divergent"]

    extra = next(iter(files)).rsplit("/", 1)[0] + "/foreign.jar"
    state = mirror.classify(receipt, FakeReader(empty), [extra])
    assert state["decision"] == "hold-conflict" and state["unexpected"] == [extra]

    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        receipt_path = root / "receipt.json"
        bytes_dir = root / "bytes"
        plan_path = root / "plan.json"
        output_path = root / "writer.json"
        bytes_dir.mkdir()
        for path, body in files.items():
            destination = bytes_dir / path
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(body)
        receipt_path.write_text(json.dumps(receipt))
        plan = {
            "schema": 1,
            "receiptSha256": mirror.sha256_file(receipt_path),
            "fileCount": 3,
            "provenance": mirror.current_tuple(),
            "authentication": "none",
            "remote": mirror.classify(receipt, FakeReader(partial), list(partial)),
        }
        plan_path.write_text(json.dumps(plan))
        original_load_receipt = mirror.load_receipt
        original_required_paths = mirror.required_paths
        original_writer = mirror.Writer
        original_reader = mirror.Reader
        mirror.required_paths = lambda tasks, version: sorted(files)
        mirror.load_receipt = lambda path: receipt
        store = dict(partial)
        initial_existing = set(store)
        writer = FakeWriter(store)
        mirror.Writer = lambda username, token: writer
        mirror.Reader = lambda *args, **kwargs: FakeReader(store)
        os.environ["RAFT_ARTIFACTS_PUBLISH_TOKEN"] = "test"
        try:
            mirror.publish(receipt_path, bytes_dir, plan_path, output_path)
            assert set(writer.puts) == set(files) - initial_existing
            assert store == files

            tampered = bytes_dir / next(iter(files))
            tampered.write_bytes(b"tampered")
            expect_error(
                "tampered-staging",
                lambda: mirror.publish(receipt_path, bytes_dir, plan_path, root / "other.json"),
                "authority byte changed",
            )
        finally:
            mirror.load_receipt = original_load_receipt
            mirror.required_paths = original_required_paths
            mirror.Writer = original_writer
            mirror.Reader = original_reader

    race_store: dict[str, bytes] = {}
    race_writer = FakeWriter(race_store)
    victim = next(iter(files))
    race_store[victim] = files[victim]
    race_writer.force_conflict.add(victim)
    assert race_writer.put(victim, files[victim]) == 409
    status, body = FakeReader(race_store).get(victim)
    assert status == 200 and digest(body) == digest(files[victim])
    print("network Raft mirror teeth: 8/8 PASS")


if __name__ == "__main__":
    main()

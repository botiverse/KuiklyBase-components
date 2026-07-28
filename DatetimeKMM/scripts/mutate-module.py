#!/usr/bin/env python3
"""Apply a named fault mutation to a Gradle module metadata file, for the
terminal-verifier readback self-tests. Each mutation must be caught (fail
closed) by scripts/verify-coordinates.py.

Mutations:
  wrong-version     set the first available-at record's version to 9.9.9 (url too)
  wrong-url         set the first available-at record's url to a wrong path that
                    still contains the correct filename substring
  bad-duplicate     add an extra available-at record for the first referenced
                    target with a WRONG version (a bad duplicate alongside the
                    good records; collapsing would hide it behind a good one)
  unexpected-target add an available-at record for a foreign datetime-jvm target
  wrong-component   set component.module to datetime-WRONG

Usage: mutate-module.py <module-file> <mutation>
"""
import json
import sys

GROUP = "build.raft.kuiklybase"


def records(doc):
    return [v["available-at"] for v in doc["variants"] if "available-at" in v]


def main():
    path, mutation = sys.argv[1], sys.argv[2]
    with open(path) as f:
        doc = json.load(f)
    recs = records(doc)

    if mutation == "wrong-version":
        recs[0]["version"] = "9.9.9"
        m = recs[0]["module"]
        recs[0]["url"] = f"../../{m}/9.9.9/{m}-9.9.9.module"
    elif mutation == "wrong-url":
        m = recs[0]["module"]
        v = recs[0]["version"]
        # Keeps the correct filename substring but a wrong directory path.
        recs[0]["url"] = f"../../WRONGDIR/{m}/{v}/{m}-{v}.module"
    elif mutation == "bad-duplicate":
        m = recs[0]["module"]
        # A bad duplicate record for an already-present target, wrong version.
        doc["variants"].append({
            "name": f"{m}BadDuplicate",
            "attributes": {"org.gradle.category": "library"},
            "available-at": {
                "url": f"../../{m}/9.9.9/{m}-9.9.9.module",
                "group": GROUP, "module": m, "version": "9.9.9",
            },
        })
    elif mutation == "unexpected-target":
        doc["variants"].append({
            "name": "datetime-jvmApiElements",
            "attributes": {"org.gradle.category": "library"},
            "available-at": {
                "url": "../../datetime-jvm/0.1.0-raft.0/datetime-jvm-0.1.0-raft.0.module",
                "group": GROUP, "module": "datetime-jvm", "version": "0.1.0-raft.0",
            },
        })
    elif mutation == "wrong-component":
        doc["component"]["module"] = "datetime-WRONG"
    else:
        print(f"unknown mutation: {mutation}", file=sys.stderr)
        sys.exit(2)

    with open(path, "w") as f:
        json.dump(doc, f, indent=2)
    print(f"applied {mutation} to {path}")


if __name__ == "__main__":
    main()

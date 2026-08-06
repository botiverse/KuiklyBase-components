# Source-owned OHOS dependency forks

This directory rebuilds the two upstream libraries that the Kuikly OHOS graph
needs but cannot obtain as a maintained Maven publication:

| Upstream source | Published root | Published OHOS target | Version |
| --- | --- | --- | --- |
| `Kotlin/kotlinx.atomicfu` | `org.jetbrains.kotlinx:atomicfu` | `org.jetbrains.kotlinx:atomicfu-ohosarm64` | `0.23.2-raft.1` |
| `Kotlin/kotlinx.coroutines` | `org.jetbrains.kotlinx:kotlinx-coroutines-core` | `org.jetbrains.kotlinx:kotlinx-coroutines-core-ohosarm64` | `1.8.0-raft.1` |

The original Maven coordinates are preserved. The graph intentionally exposes
only common metadata plus `ohosArm64`; it does not claim to replace upstream
JVM, Android, Apple, JS, or Wasm variants.

## Source and ABI contract

`source-lock.json` pins each upstream commit, the checked-in public patch bytes,
the prepared Git tree, and a second SHA-256 digest over its complete tree
manifest. `scripts/prepare-sources.sh` fetches only those commits, applies only
those patches, and refuses any source-tree drift.

The build uses Kotlin/Native `2.0.21-KBA-010`. Coroutines applies the matching
AtomicFU Native IR compiler plugin; omitting it changes the exported Native ABI.
`release-spec.json` pins the live KBA KLIB controls and their normalized ABI
digests. `scripts/verify-abi.py` verifies the downloaded control checksum before
running `klib dump-abi`, removes only the CLI banner, and requires byte-for-byte
normalized ABI equality.

Every POM carries two unambiguous provenance pairs: the KuiklyBase-components
carrier repository plus clean carrier commit in Maven SCM, and the upstream
repository plus upstream commit and patch SHA-256 in explicit properties.
The root metadata redirects only to an existing OHOS target. Coroutines depends
only on the new AtomicFU root/target pair, so no retired KBA coordinate is part
of the release closure.

## Closed local build

Run the entire gate inside the image pinned in `release-spec.json`:

```bash
OHOS_FORKS_HARMONY_IMAGE="ghcr.io/bytemain/harmony-next-pipeline-docker/harmonyos-ci-image@sha256:cbe95055b155c4eb71d234f24b47d481a1b20b7e96defe3f24ab3219aff55347" \
  OHOSForks/scripts/build-and-verify.sh /tmp/ohos-forks-output
```

The command requires a clean checkout and a new output directory. It performs
fresh source preparation, publishes AtomicFU then coroutines into one isolated
local Maven repository, verifies the exact 20-file manifest, exercises all
staging mutations, compiles and links an AArch64 OHOS consumer, proves a missing
candidate cannot fall back to a remote repository, and checks both ABIs.
Before manifesting or consuming the publication, the driver canonicalizes the
four target KLIB ZIPs: entry names are UTF-8 byte-sorted and ZIP timestamps,
attributes, comments, extras, flags, versions, and compression are normalized.
It proves every entry payload is preserved, proves the rewrite is idempotent,
and then rebinds the two target Gradle module descriptors to the canonical KLIB
sizes and hashes. `klib-canonicalization.json` records the payload-manifest and
final archive digests and is itself bound by `build-receipt.json`; the staging
verifier independently rejects non-canonical order or metadata.
Prepared sources use a carrier-derived fixed `/tmp` path because Kotlin/Native
records source paths in `files.knf`; this keeps release KLIB bytes independent
of the CI job's chosen output directory. Both modules also replace filesystem
enumeration with prepared-tree-relative sorted positional, fragment-source, and
fragment-refinement inputs; each Native compile task asserts that the exact
lists and order reached its compiler input. Toolchain lookup follows the
effective OS user's home (or an explicit `KONAN_DATA_DIR`), rather than a CI
runner's transient shell `HOME` override.

The consumer uses AtomicFU plus `launch`, `async`, and `runBlocking`. Successful
completion produces a real AArch64 ELF rather than only resolving metadata.

## Publication boundary

`.github/workflows/ohos-forks-ci.yml` is read-only Hosted CI. It cannot publish.
Its immutable-evidence artifact uploads the single complete build output root;
the workflow contract rejects a narrowed artifact that silently drops staging,
manifest, source-preparation, build, or ABI receipts.

`.github/workflows/publish-ohos-forks.yml` is manual-only and protected by the
`raft-artifacts-production` environment. A dispatch must supply the exact
reviewed current-master SHA, the fresh Hosted release-manifest digest, and the
version-specific confirmation phrase. It rebuilds once, plans only missing
immutable paths, uploads the staged bytes only to Raft Artifacts, then downloads
and hashes all 20 files from Raft Artifacts.

All plan, positive-control, upload, and readback requests share one transport
bound to the reviewed `https://maven.artifacts.botiverse.dev` origin. It never
follows HTTP redirects, so credentials and publication bytes are sent only to
that origin.

An interrupted publication is recoverable only when every existing byte still
matches the frozen manifest. Any divergent existing byte, changed source,
changed manifest, incomplete plan, automatic trigger, or missing Raft readback
fails closed. There is no tag, package, or consumer-cutover side effect in the
ordinary CI workflow.

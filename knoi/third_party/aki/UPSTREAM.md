# Aki provenance

This directory vendors the runtime source needed by KNOI from:

- Repository: <https://gitcode.com/CPF-ApplicationTPC/aki>
- Upstream commit: `eed486c81bc3404df336d1fee94f989f827cbb57`
- Upstream tree: `5eb9196485033bfc37069aecf1418b62c60d16da`
- Release commit with the same tree: `c4e83ff`
- Project/package version: `1.3.1`
- License: Apache-2.0

The latest annotated upstream tag at import time was `1.3.0`; version 1.3.1
was therefore frozen by commit and tree rather than by inventing a tag.

Only `CMakeLists.txt`, `include/`, `src/`, `LICENSE`, and `NOTICE` are
vendored. `UPSTREAM_TREE.manifest` records the original Git mode and blob ID
for every imported file. Run:

```bash
python3 knoi/scripts/verify-aki-provenance.py
```

before accepting an update. Integration code must not edit these files in
place. Update the exact, replace the complete imported subset, refresh the
manifest, and review the upstream delta instead.

KNOI builds Aki once as a hidden static implementation detail of
`libknoi.so`. A second default-visible Aki runtime must not be linked into the
same addon.

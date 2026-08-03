# KNOI → Aki migration

The terminal contract is to remove KNOI's duplicated Node-API C++
implementation in favor of pinned Aki, while keeping KNOI's Kotlin/Native,
KSP, generated service, and consumer API semantics stable.

The migration is intentionally split into independently reviewable and
revertible exacts:

1. **Addon/bootstrap foundation**
   - pin Aki source, tree, license and notice;
   - freeze the published KNOI 0.0.4 HAR/ELF baseline and record its missing
     source provenance;
   - add an Aki-backed addon with the same five native JS exports and the same
     two Kotlin bootstrap symbols;
   - use a build-time Aki/legacy switch only—never two runtimes in one process;
   - close loader atomicity, UTF-8 waiter, timeout and exactly-once cleanup.
2. **Reference/function ownership and thread lifecycle**
   - migrate raw `napi_ref`, Kotlin `StableRef`, JS function and finalizer
     ownership through a stable C ABI adapter;
   - migrate TSFN dispatch with balanced acquire/release, bounded waits,
     cleanup hooks and stale-worker/TID rejection.
3. **JSValue, ArrayBuffer, Promise and async work**
   - migrate scalar, string, type, property, array, object and module helpers;
   - migrate copy/external buffers and every typed-array kind without changing
     element-count/byte-count or finalizer semantics;
   - migrate Promise resolution/rejection and async work ordering.
4. **Legacy removal**
   - delete the old native implementation and the build-time rollback path;
   - prove repository-wide zero residue, one Aki version and hidden symbols.

Each exact needs deterministic behavior tests, killed mutations, OHOS arm64
debug/release builds, ABI/export/dependency checks and runtime evidence before
the next phase starts. Publication, KNOI coordinate bump and consumer pins are
separate protected changes after the final migration exact. This work never
shares a PR with unrelated KuiklyUI/Mobile coordinate updates.

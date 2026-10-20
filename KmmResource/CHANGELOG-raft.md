# KmmResource Raft changelog

## 0.1.0-raft.6

- resource-generator builds and runs under Kotlin 2.4: drop the iOS klib
  resource copy's use of `KotlinLibraryLayoutImpl` / `zipDirAs` (removed in
  Kotlin 2.4, `NoClassDefFoundError` at framework link) and the references to
  the two Kotlin/Native targets Kotlin 2.4 no longer has. Needed by the mobile
  Compose Multiplatform 1.11.1 / Kotlin 2.4 upgrade.

## 0.1.0-raft.5

- Give the ohosArm64 `StringDesc.localeType` actual real storage (was a stub
  that dropped assignments and always reported `System`), aligning it with
  androidMain/appleMain and keeping the `Custom` locale tag readable for
  host-side override resource managers. Adds a commonTest storage contract.
  Fixes mobile task #190 (in-app language selection having no effect on OHOS).

## 0.1.0-raft.4

- Route OHOS zero-argument string reads through the non-formatting ArkTS
  `ResourceManager.getStringByNameSync(name)` service path. This prevents
  formatting templates such as `%1$s` from reaching a native variadic API
  without arguments.
- Keep non-empty formatted calls on the existing service formatter and add
  executable dispatch contracts for empty, ordinary, positional, multi-value,
  and escaped-percent inputs.

## 0.1.0-raft.3

- Deliver the owning module's MR bundle to iOS frameworks.

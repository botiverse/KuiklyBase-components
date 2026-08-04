# KmmResource Raft changelog

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

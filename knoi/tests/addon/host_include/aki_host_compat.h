#ifndef KNOI_AKI_HOST_COMPAT_H
#define KNOI_AKI_HOST_COMPAT_H

// Aki 1.3.1 relies on transitive standard-library includes supplied by the
// HarmonyOS libc++ toolchain. Make those dependencies explicit only for the
// Linux/macOS Node-API runtime probe without modifying the pinned vendor tree.
#include <cstring>
#include <memory>
#include <string>
#include <unordered_map>

#endif // KNOI_AKI_HOST_COMPAT_H

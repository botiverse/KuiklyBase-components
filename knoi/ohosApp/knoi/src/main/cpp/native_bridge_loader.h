#ifndef KNOI_NATIVE_BRIDGE_LOADER_H
#define KNOI_NATIVE_BRIDGE_LOADER_H

#include <mutex>
#include <string>

namespace knoi {

enum class BridgeStatus {
    kOk,
    kInvalidArgument,
    kLibraryOpenFailed,
    kMissingInitEnv,
    kMissingInitBridge,
    kConflictingSetup,
    kNotConfigured,
};

struct BridgeResult {
    BridgeStatus status = BridgeStatus::kOk;
    std::string detail;

    bool ok() const
    {
        return status == BridgeStatus::kOk;
    }
};

class NativeBridgeLoader {
public:
    using InitEnvFunction = void (*)(void* env, void* exports, bool debug);
    using InitBridgeFunction = void (*)();

    NativeBridgeLoader() = default;
    ~NativeBridgeLoader();

    NativeBridgeLoader(const NativeBridgeLoader&) = delete;
    NativeBridgeLoader& operator=(const NativeBridgeLoader&) = delete;

    BridgeResult Setup(const std::string& libraryName, bool debug);
    BridgeResult Initialize(void* env, void* exports);

    bool IsConfigured() const;
    std::string ConfiguredLibrary() const;

private:
    mutable std::mutex mutex_;
    void* libraryHandle_ = nullptr;
    std::string libraryName_;
    bool debug_ = false;
    InitEnvFunction initEnv_ = nullptr;
    InitBridgeFunction initBridge_ = nullptr;
    std::once_flag initBridgeOnce_;
};

const char* BridgeStatusCode(BridgeStatus status);

} // namespace knoi

#endif // KNOI_NATIVE_BRIDGE_LOADER_H

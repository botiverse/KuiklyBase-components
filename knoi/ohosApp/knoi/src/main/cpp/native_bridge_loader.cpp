#include "native_bridge_loader.h"

#include <dlfcn.h>
#include <utility>

namespace knoi {
namespace {

constexpr const char* kInitEnvSymbol = "com_tencent_tmm_knoi_initEnv";
constexpr const char* kInitBridgeSymbol = "com_tencent_tmm_knoi_initBridge";

BridgeResult Failure(BridgeStatus status, std::string detail)
{
    return BridgeResult{status, std::move(detail)};
}

} // namespace

NativeBridgeLoader::~NativeBridgeLoader()
{
    std::lock_guard<std::mutex> lock(mutex_);
    if (libraryHandle_ != nullptr) {
        dlclose(libraryHandle_);
        libraryHandle_ = nullptr;
    }
}

BridgeResult NativeBridgeLoader::Setup(const std::string& libraryName, bool debug)
{
    std::lock_guard<std::mutex> lock(mutex_);
    // Preserve KNOI's first-successful-owner contract. Environment creation
    // unconditionally calls setup("libkn.so", false) as a fallback, even when
    // the application already selected a custom library/debug mode. Once a
    // setup succeeds, every later setup is therefore an intentional no-op.
    if (libraryHandle_ != nullptr) {
        return {};
    }

    if (libraryName.empty()) {
        return Failure(BridgeStatus::kInvalidArgument, "KNOI library name must not be empty");
    }

    dlerror();
    // Kotlin/Native OHOS shared libraries intentionally retain lazy-only
    // runtime references such as `main`. The legacy KNOI loader used
    // RTLD_LAZY; resolving every function relocation up front rejects valid
    // production consumers before their bootstrap symbols can be called.
    void* candidateHandle = dlopen(libraryName.c_str(), RTLD_LAZY | RTLD_LOCAL);
    if (candidateHandle == nullptr) {
        const char* error = dlerror();
        return Failure(
            BridgeStatus::kLibraryOpenFailed,
            error == nullptr ? "dlopen failed without a diagnostic" : error);
    }

    dlerror();
    auto candidateInitEnv = reinterpret_cast<InitEnvFunction>(dlsym(candidateHandle, kInitEnvSymbol));
    const char* initEnvError = dlerror();
    if (candidateInitEnv == nullptr || initEnvError != nullptr) {
        dlclose(candidateHandle);
        return Failure(BridgeStatus::kMissingInitEnv, kInitEnvSymbol);
    }

    dlerror();
    auto candidateInitBridge =
        reinterpret_cast<InitBridgeFunction>(dlsym(candidateHandle, kInitBridgeSymbol));
    const char* initBridgeError = dlerror();
    if (candidateInitBridge == nullptr || initBridgeError != nullptr) {
        dlclose(candidateHandle);
        return Failure(BridgeStatus::kMissingInitBridge, kInitBridgeSymbol);
    }

    libraryHandle_ = candidateHandle;
    libraryName_ = libraryName;
    debug_ = debug;
    initEnv_ = candidateInitEnv;
    initBridge_ = candidateInitBridge;
    return {};
}

BridgeResult NativeBridgeLoader::Initialize(void* env, void* exports)
{
    if (env == nullptr || exports == nullptr) {
        return Failure(BridgeStatus::kInvalidArgument, "KNOI init requires non-null env and exports");
    }

    InitEnvFunction initEnv = nullptr;
    InitBridgeFunction initBridge = nullptr;
    bool debug = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (libraryHandle_ == nullptr || initEnv_ == nullptr || initBridge_ == nullptr) {
            return Failure(BridgeStatus::kNotConfigured, "call setup before init");
        }
        initEnv = initEnv_;
        initBridge = initBridge_;
        debug = debug_;
    }

    initEnv(env, exports, debug);
    std::call_once(initBridgeOnce_, [initBridge]() { initBridge(); });
    return {};
}

bool NativeBridgeLoader::IsConfigured() const
{
    std::lock_guard<std::mutex> lock(mutex_);
    return libraryHandle_ != nullptr;
}

std::string NativeBridgeLoader::ConfiguredLibrary() const
{
    std::lock_guard<std::mutex> lock(mutex_);
    return libraryName_;
}

const char* BridgeStatusCode(BridgeStatus status)
{
    switch (status) {
        case BridgeStatus::kOk:
            return "KNOI_OK";
        case BridgeStatus::kInvalidArgument:
            return "KNOI_INVALID_ARGUMENT";
        case BridgeStatus::kLibraryOpenFailed:
            return "KNOI_LIBRARY_OPEN_FAILED";
        case BridgeStatus::kMissingInitEnv:
            return "KNOI_MISSING_INIT_ENV";
        case BridgeStatus::kMissingInitBridge:
            return "KNOI_MISSING_INIT_BRIDGE";
        case BridgeStatus::kNotConfigured:
            return "KNOI_NOT_CONFIGURED";
    }
    return "KNOI_UNKNOWN_ERROR";
}

} // namespace knoi

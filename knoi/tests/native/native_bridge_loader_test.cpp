#include "native_bridge_loader.h"

#include <dlfcn.h>

#include <cstdlib>
#include <iostream>
#include <string>
#include <thread>
#include <vector>

namespace {

void Check(bool condition, const char* message)
{
    if (!condition) {
        std::cerr << "FAIL: " << message << std::endl;
        std::exit(1);
    }
}

template <typename Function>
Function Symbol(void* handle, const char* name)
{
    return reinterpret_cast<Function>(dlsym(handle, name));
}

} // namespace

int main(int argc, char** argv)
{
    Check(argc == 4, "expected valid, missing-env, and missing-bridge fixture paths");
    const std::string validPath = argv[1];
    const std::string missingEnvPath = argv[2];
    const std::string missingBridgePath = argv[3];

    {
        knoi::NativeBridgeLoader loader;
        Check(
            loader.Setup("", false).status == knoi::BridgeStatus::kInvalidArgument,
            "empty library must fail");
        Check(
            loader.Setup("/definitely/not/a/knoi/library.so", false).status ==
                knoi::BridgeStatus::kLibraryOpenFailed,
            "missing library must fail");
        Check(!loader.IsConfigured(), "failed setup must not partially configure loader");
    }

    {
        knoi::NativeBridgeLoader loader;
        Check(
            loader.Setup(missingEnvPath, false).status == knoi::BridgeStatus::kMissingInitEnv,
            "missing initEnv must fail");
        Check(!loader.IsConfigured(), "missing initEnv must leave loader unconfigured");
        Check(loader.Setup(validPath, false).ok(), "valid setup must recover after missing initEnv");
    }

    {
        knoi::NativeBridgeLoader loader;
        Check(
            loader.Setup(missingBridgePath, false).status == knoi::BridgeStatus::kMissingInitBridge,
            "missing initBridge must fail");
        Check(!loader.IsConfigured(), "missing initBridge must leave loader unconfigured");
    }

    void* fixtureHandle = dlopen(validPath.c_str(), RTLD_NOW | RTLD_LOCAL);
    Check(fixtureHandle != nullptr, "valid fixture must load");
    auto resetCalls = Symbol<void (*)()>(fixtureHandle, "knoi_test_reset_calls");
    auto envCalls = Symbol<int (*)()>(fixtureHandle, "knoi_test_env_calls");
    auto bridgeCalls = Symbol<int (*)()>(fixtureHandle, "knoi_test_bridge_calls");
    auto lastDebug = Symbol<int (*)()>(fixtureHandle, "knoi_test_last_debug");
    Check(
        resetCalls != nullptr && envCalls != nullptr && bridgeCalls != nullptr &&
            lastDebug != nullptr,
        "fixture probes missing");
    resetCalls();

    {
        knoi::NativeBridgeLoader loader;
        Check(
            loader.Initialize(reinterpret_cast<void*>(1), reinterpret_cast<void*>(2)).status ==
                knoi::BridgeStatus::kNotConfigured,
            "init before setup must fail");
        Check(loader.Setup(validPath, true).ok(), "valid setup failed");
        Check(loader.Setup(validPath, true).ok(), "same setup must be idempotent");
        Check(
            loader.Setup(validPath, false).ok(),
            "fallback setup with a different debug mode must be a no-op");
        Check(
            loader.Setup(missingEnvPath, false).ok(),
            "fallback setup with a different library must be a no-op");
        Check(loader.Setup("", false).ok(), "all setup calls after the first success must be no-ops");
        Check(loader.ConfiguredLibrary() == validPath, "configured library identity changed");

        constexpr int kThreadCount = 16;
        std::vector<std::thread> threads;
        threads.reserve(kThreadCount);
        for (int index = 0; index < kThreadCount; ++index) {
            threads.emplace_back([&loader, index]() {
                const auto result = loader.Initialize(
                    reinterpret_cast<void*>(static_cast<uintptr_t>(index + 1)),
                    reinterpret_cast<void*>(static_cast<uintptr_t>(index + 101)));
                Check(result.ok(), "concurrent initialize failed");
            });
        }
        for (auto& thread : threads) {
            thread.join();
        }
        Check(envCalls() == kThreadCount, "initEnv must run once per env initialization");
        Check(bridgeCalls() == 1, "initBridge must run exactly once");
        Check(lastDebug() == 1, "fallback setup must not replace the first debug mode");
    }

    resetCalls();
    {
        knoi::NativeBridgeLoader loader;
        Check(loader.Setup(validPath, true).ok(), "first setup for mixed concurrency failed");
        constexpr int kThreadCount = 12;
        std::vector<std::thread> threads;
        std::vector<knoi::BridgeStatus> statuses(kThreadCount, knoi::BridgeStatus::kInvalidArgument);
        for (int index = 0; index < kThreadCount; ++index) {
            threads.emplace_back([&loader, &statuses, &validPath, &missingEnvPath, index]() {
                const std::string& fallbackPath = index % 2 == 0 ? validPath : missingEnvPath;
                statuses[index] = loader.Setup(fallbackPath, false).status;
            });
        }
        for (auto& thread : threads) {
            thread.join();
        }
        for (auto status : statuses) {
            Check(status == knoi::BridgeStatus::kOk, "concurrent fallback setup must be a no-op");
        }
        Check(loader.ConfiguredLibrary() == validPath, "concurrent fallback replaced the first library");
        Check(
            loader.Initialize(reinterpret_cast<void*>(1), reinterpret_cast<void*>(2)).ok(),
            "initialize after concurrent fallback failed");
        Check(lastDebug() == 1, "concurrent fallback replaced the first debug mode");
    }

    dlclose(fixtureHandle);
    std::cout << "native_bridge_loader_test PASS" << std::endl;
    return 0;
}

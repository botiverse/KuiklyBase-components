#include "function_waiter_registry.h"

#include <atomic>
#include <chrono>
#include <cstdlib>
#include <iostream>
#include <string>
#include <thread>

namespace {

void Check(bool condition, const char* message)
{
    if (!condition) {
        std::cerr << "FAIL: " << message << std::endl;
        std::exit(1);
    }
}

} // namespace

int main()
{
    using namespace std::chrono_literals;
    knoi::FunctionWaiterRegistry registry;

    const auto unicodeId = registry.Create();
    const std::string unicode = u8R"({"message":"你好🙂","escaped":"\\n"})";
    Check(registry.Notify(unicodeId, unicode) == knoi::WaiterStatus::kOk, "unicode notify failed");
    Check(
        registry.Notify(unicodeId, "duplicate") == knoi::WaiterStatus::kAlreadyCompleted,
        "duplicate notify must fail closed");
    const knoi::WaiterResult unicodeResult = registry.Wait(unicodeId, 100ms);
    Check(unicodeResult.ok(), "unicode wait failed");
    Check(unicodeResult.value == unicode, "UTF-8 payload changed or truncated");
    Check(registry.Outstanding() == 0, "completed waiter leaked");
    Check(
        registry.Notify(unicodeId, "late") == knoi::WaiterStatus::kNotFound,
        "late notify must not access retired waiter");

    const auto timeoutId = registry.Create();
    const knoi::WaiterResult timeoutResult = registry.Wait(timeoutId, 5ms);
    Check(timeoutResult.status == knoi::WaiterStatus::kTimedOut, "waiter timeout not enforced");
    Check(registry.Outstanding() == 0, "timed-out waiter leaked");
    Check(
        registry.Notify(timeoutId, "late") == knoi::WaiterStatus::kNotFound,
        "late timeout notify must fail safely");

    const auto concurrentId = registry.Create();
    std::thread notifier([&registry, concurrentId]() {
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
        Check(
            registry.Notify(concurrentId, "ready") == knoi::WaiterStatus::kOk,
            "concurrent notify failed");
    });
    const knoi::WaiterResult concurrentResult = registry.Wait(concurrentId, 500ms);
    notifier.join();
    Check(concurrentResult.ok() && concurrentResult.value == "ready", "concurrent wait failed");

    const auto singleConsumerId = registry.Create();
    std::atomic<int> started{0};
    knoi::WaiterResult consumerResults[2];
    std::thread consumers[2];
    for (int index = 0; index < 2; ++index) {
        consumers[index] = std::thread([&registry, &started, &consumerResults, singleConsumerId, index]() {
            started.fetch_add(1, std::memory_order_release);
            consumerResults[index] = registry.Wait(singleConsumerId, 500ms);
        });
    }
    while (started.load(std::memory_order_acquire) != 2) {
        std::this_thread::yield();
    }
    std::this_thread::sleep_for(20ms);
    Check(
        registry.Notify(singleConsumerId, "once") == knoi::WaiterStatus::kOk,
        "single-consumer notify failed");
    for (auto& consumer : consumers) {
        consumer.join();
    }
    const int successCount = static_cast<int>(consumerResults[0].ok()) +
        static_cast<int>(consumerResults[1].ok());
    Check(successCount == 1, "a waiter result must be consumed exactly once");
    const knoi::WaiterResult& rejected =
        consumerResults[0].ok() ? consumerResults[1] : consumerResults[0];
    Check(
        rejected.status == knoi::WaiterStatus::kAlreadyConsumed ||
            rejected.status == knoi::WaiterStatus::kNotFound,
        "the second waiter consumer must fail closed");

    const auto largeId = registry.Create();
    const std::string large(1024 * 1024, 'x');
    Check(registry.Notify(largeId, large) == knoi::WaiterStatus::kOk, "large notify failed");
    const knoi::WaiterResult largeResult = registry.Wait(largeId, 100ms);
    Check(largeResult.ok() && largeResult.value == large, "large payload changed");
    Check(registry.Outstanding() == 0, "final registry state is not empty");

    std::cout << "function_waiter_registry_test PASS" << std::endl;
    return 0;
}

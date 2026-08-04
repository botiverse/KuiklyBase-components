#ifndef KNOI_FUNCTION_WAITER_REGISTRY_H
#define KNOI_FUNCTION_WAITER_REGISTRY_H

#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>

namespace knoi {

enum class WaiterStatus {
    kOk,
    kNotFound,
    kAlreadyCompleted,
    kAlreadyConsumed,
    kTimedOut,
};

struct WaiterResult {
    WaiterStatus status = WaiterStatus::kOk;
    std::string value;

    bool ok() const
    {
        return status == WaiterStatus::kOk;
    }
};

class FunctionWaiterRegistry {
public:
    using WaiterId = int64_t;

    WaiterId Create();
    WaiterStatus Notify(WaiterId id, std::string value);
    WaiterResult Wait(WaiterId id, std::chrono::milliseconds timeout);
    size_t Outstanding() const;

private:
    struct Waiter {
        std::mutex mutex;
        std::condition_variable condition;
        bool completed = false;
        bool consumed = false;
        std::string value;
    };

    std::shared_ptr<Waiter> Find(WaiterId id) const;
    void EraseIfSame(WaiterId id, const std::shared_ptr<Waiter>& waiter);

    mutable std::mutex registryMutex_;
    std::unordered_map<WaiterId, std::shared_ptr<Waiter>> waiters_;
    uint64_t nextId_ = 1;
};

const char* WaiterStatusCode(WaiterStatus status);

} // namespace knoi

#endif // KNOI_FUNCTION_WAITER_REGISTRY_H

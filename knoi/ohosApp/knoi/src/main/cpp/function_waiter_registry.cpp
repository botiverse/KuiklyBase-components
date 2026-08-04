#include "function_waiter_registry.h"

#include <utility>

namespace knoi {
namespace {

constexpr uint64_t kMaxJavaScriptSafeInteger = (uint64_t{1} << 53) - 1;

} // namespace

FunctionWaiterRegistry::WaiterId FunctionWaiterRegistry::Create()
{
    std::lock_guard<std::mutex> lock(registryMutex_);
    for (;;) {
        const uint64_t rawId = nextId_;
        nextId_ = rawId == kMaxJavaScriptSafeInteger ? 1 : rawId + 1;
        const WaiterId id = static_cast<WaiterId>(rawId);
        auto inserted = waiters_.emplace(id, std::make_shared<Waiter>());
        if (inserted.second) {
            return id;
        }
    }
}

WaiterStatus FunctionWaiterRegistry::Notify(WaiterId id, std::string value)
{
    std::shared_ptr<Waiter> waiter = Find(id);
    if (waiter == nullptr) {
        return WaiterStatus::kNotFound;
    }

    {
        std::lock_guard<std::mutex> lock(waiter->mutex);
        if (waiter->consumed) {
            return WaiterStatus::kAlreadyConsumed;
        }
        if (waiter->completed) {
            return WaiterStatus::kAlreadyCompleted;
        }
        waiter->value = std::move(value);
        waiter->completed = true;
    }
    waiter->condition.notify_all();
    return WaiterStatus::kOk;
}

WaiterResult FunctionWaiterRegistry::Wait(WaiterId id, std::chrono::milliseconds timeout)
{
    std::shared_ptr<Waiter> waiter = Find(id);
    if (waiter == nullptr) {
        return {WaiterStatus::kNotFound, {}};
    }

    WaiterResult result;
    {
        std::unique_lock<std::mutex> lock(waiter->mutex);
        if (waiter->consumed) {
            return {WaiterStatus::kAlreadyConsumed, {}};
        }
        const bool ready = waiter->condition.wait_for(lock, timeout, [&waiter]() {
            return waiter->completed || waiter->consumed;
        });
        if (waiter->consumed) {
            return {WaiterStatus::kAlreadyConsumed, {}};
        }
        waiter->consumed = true;
        if (!ready) {
            result.status = WaiterStatus::kTimedOut;
        } else {
            result.status = WaiterStatus::kOk;
            result.value = std::move(waiter->value);
        }
    }

    waiter->condition.notify_all();
    EraseIfSame(id, waiter);
    return result;
}

size_t FunctionWaiterRegistry::Outstanding() const
{
    std::lock_guard<std::mutex> lock(registryMutex_);
    return waiters_.size();
}

std::shared_ptr<FunctionWaiterRegistry::Waiter> FunctionWaiterRegistry::Find(WaiterId id) const
{
    std::lock_guard<std::mutex> lock(registryMutex_);
    auto it = waiters_.find(id);
    return it == waiters_.end() ? nullptr : it->second;
}

void FunctionWaiterRegistry::EraseIfSame(WaiterId id, const std::shared_ptr<Waiter>& waiter)
{
    std::lock_guard<std::mutex> lock(registryMutex_);
    auto it = waiters_.find(id);
    if (it != waiters_.end() && it->second == waiter) {
        waiters_.erase(it);
    }
}

const char* WaiterStatusCode(WaiterStatus status)
{
    switch (status) {
        case WaiterStatus::kOk:
            return "KNOI_WAITER_OK";
        case WaiterStatus::kNotFound:
            return "KNOI_WAITER_NOT_FOUND";
        case WaiterStatus::kAlreadyCompleted:
            return "KNOI_WAITER_ALREADY_COMPLETED";
        case WaiterStatus::kAlreadyConsumed:
            return "KNOI_WAITER_ALREADY_CONSUMED";
        case WaiterStatus::kTimedOut:
            return "KNOI_WAITER_TIMED_OUT";
    }
    return "KNOI_WAITER_UNKNOWN_ERROR";
}

} // namespace knoi

/*
 * Copyright (C) 2022 Huawei Device Co., Ltd.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef AKI_PROMISE_H
#define AKI_PROMISE_H

#include <future>
#include <node_api.h>
#include <atomic>
#include <memory>
#include <functional>
#include "aki/config.h"

namespace aki {
class AKI_EXPORT Promise {
public:
    Promise();

    explicit Promise(napi_value promise)
        : env_(nullptr), promise_(promise), deferred_(nullptr), promiseRef_(nullptr),
          settled_(std::make_shared<std::atomic<bool>>(false)) {}

    ~Promise();

    Promise(const Promise& other);

    Promise& operator=(const Promise& other);

    template<typename T>
    void Resolve(T&& t) const;

    template<typename T>
    void Reject(T&& t) const;

    napi_value GetHandle()
    {
        return promise_;
    }

    template<typename R, typename T>
    Promise Then(std::function<R(T)> func)
    {
        return PromiseHandle(func, "then");
    }

    template<typename R, typename T>
    Promise Catch(std::function<R(T)> func)
    {
        return PromiseHandle(func, "catch");
    }

    template<typename R1, typename T1, typename R2, typename T2>
    Promise Then(std::function<R1(T1)> func, std::function<R2(T2)> errorFunc)
    {
        return PromiseHandle(func, "then").PromiseHandle(errorFunc, "catch");
    }

private:
    napi_env env_ = nullptr;
    napi_value promise_ = nullptr;
    napi_deferred deferred_ = nullptr;
    mutable napi_ref promiseRef_ = nullptr;
    std::shared_ptr<std::atomic<bool>> settled_;

    void CleanupRef() const;

    template<typename R, typename T>
    Promise PromiseHandle(std::function<R(T)> func, const char* property);
};
} // namespace aki

#endif //AKI_PROMISE_H

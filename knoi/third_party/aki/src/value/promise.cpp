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

#include <node_api.h>

#include "aki/value/promise.h"
#include "aki/binding.h"
#include "aki/logging/logging.h"
#include "status/status.h"

namespace aki {
Promise::Promise()
    : settled_(std::make_shared<std::atomic<bool>>(false))
{
    env_ = aki::Binding::GetScopedEnv();
    if (env_ == nullptr) {
        AKI_LOG(ERROR) << "Promise::Promise: env is nullptr";
        deferred_ = nullptr;
        promise_ = nullptr;
        return;
    }
    napi_status status = napi_create_promise(env_, &deferred_, &promise_);
    AKI_DCHECK(status == napi_ok) << "status(" << status << "): " << GetStatusDesc(status);
    if (status != napi_ok) {
        AKI_LOG(ERROR) << "napi_create_promise failed: status(" << status << "): " << GetStatusDesc(status);
        deferred_ = nullptr;
        promise_ = nullptr;
        return;
    }

    status = napi_create_reference(env_, promise_, 1, &promiseRef_);
    if (status != napi_ok) {
        AKI_LOG(ERROR) << "napi_create_reference failed for promise: status(" << status << ")";
        promiseRef_ = nullptr;
    }
}

Promise::~Promise()
{
}

Promise::Promise(const Promise &other)
    : env_(other.env_), promise_(other.promise_), deferred_(other.deferred_), promiseRef_(other.promiseRef_),
      settled_(other.settled_) {}

Promise& Promise::operator=(const Promise& other)
{
    if (this != &other) {
        env_ = other.env_;
        promise_ = other.promise_;
        deferred_ = other.deferred_;
        promiseRef_ = other.promiseRef_;
        settled_ = other.settled_;
    }
    return *this;
}

void Promise::CleanupRef() const
{
    if (promiseRef_ != nullptr && env_ != nullptr) {
        napi_status status = napi_delete_reference(env_, promiseRef_);
        if (status != napi_ok) {
            AKI_LOG(ERROR) << "napi_delete_reference failed for promise ref: status(" << status << ")";
        }
        promiseRef_ = nullptr;
    }
}
} // namespace aki
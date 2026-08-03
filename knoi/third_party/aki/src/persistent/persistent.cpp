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

#include "aki/persistent/persistent.h"
#include "aki/jsbind.h"
#include "aki/logging/logging.h"
#include "status/status.h"

namespace aki {
Persistent::Persistent(napi_value value)
{
    napi_status status;
    napi_env env = JSBind::GetScopedEnv();
    if (env == nullptr) {
        return;
    }
    env_ = env;
    status = napi_create_reference(env, value, 1, &ref_);
    AKI_DCHECK(status == napi_ok) << "status(" << status << "): " << GetStatusDesc(status);
    if (status != napi_ok) {
        AKI_LOG(ERROR) << "napi_create_reference failed: status(" << status << "): " << GetStatusDesc(status);
        ref_ = nullptr;
        return;
    }
}

Persistent::Persistent(const Persistent& that)
{
    env_ = that.env_;
    if (that.ref_ != nullptr && env_ != nullptr) {
        ref_ = that.ref_;

        napi_status status;
        uint32_t count;
        status = napi_reference_ref(env_, ref_, &count);
        AKI_DCHECK(status == napi_ok) << "status(" << status << "): " << GetStatusDesc(status);
        if (status != napi_ok) {
            AKI_LOG(ERROR) << "napi_reference_ref failed: status(" << status << "): " << GetStatusDesc(status);
            ref_ = nullptr;
            return;
        }
    }
}

Persistent& Persistent::operator=(const Persistent& that)
{
    if (this == &that) {
        return *this;
    }

    if (ref_ != nullptr) {
        if (env_ != nullptr) {
            napi_status status;
            uint32_t count;
            status = napi_reference_unref(env_, ref_, &count);
            AKI_DCHECK(status == napi_ok) << "status(" << status << "): " << GetStatusDesc(status);
            if (status != napi_ok) {
                AKI_LOG(ERROR) << "napi_reference_unref failed: status(" << status << "): " << GetStatusDesc(status);
            } else if (count == 0) {
                status = napi_delete_reference(env_, ref_);
                AKI_DCHECK(status == napi_ok) << "status(" << status << "): " << GetStatusDesc(status);
                if (status != napi_ok) {
                    AKI_LOG(ERROR) << "napi_delete_reference failed: status(" << status
                                   << "): " << GetStatusDesc(status);
                }
            }
        }
        ref_ = nullptr;
    }

    env_ = that.env_;
    if (that.ref_ != nullptr && env_ != nullptr) {
        ref_ = that.ref_;

        napi_status status;
        uint32_t count;
        status = napi_reference_ref(env_, ref_, &count);
        AKI_DCHECK(status == napi_ok) << "status(" << status << "): " << GetStatusDesc(status);
        if (status != napi_ok) {
            AKI_LOG(ERROR) << "napi_reference_ref failed: status(" << status << "): " << GetStatusDesc(status);
            ref_ = nullptr;
        }
    }

    return *this;
}

Persistent::~Persistent()
{
    if (ref_ != nullptr) {
        if (env_ == nullptr) {
            AKI_LOG(ERROR) << "Persistent destructor: env_ is null, cannot delete reference";
            ref_ = nullptr;
            return;
        }
        napi_status status;
        uint32_t count;
        status = napi_reference_unref(env_, ref_, &count);
        AKI_DCHECK(status == napi_ok) << "status(" << status << "): " << GetStatusDesc(status);
        if (status != napi_ok) {
            AKI_LOG(ERROR) << "napi_reference_unref failed: status(" << status << "): " << GetStatusDesc(status);
        } else if (count == 0) {
            status = napi_delete_reference(env_, ref_);
            AKI_DCHECK(status == napi_ok) << "status(" << status << "): " << GetStatusDesc(status);
            if (status != napi_ok) {
                AKI_LOG(ERROR) << "napi_delete_reference failed: status(" << status << "): " << GetStatusDesc(status);
            }
        }
        ref_ = nullptr;
    }
}

napi_value Persistent::GetValue() const
{
    if (env_ == nullptr) {
        AKI_LOG(ERROR) << "Persistent GetValue: env_ is null!";
        return nullptr;
    }

    napi_status status;
    napi_value value;

    if (ref_ == nullptr) {
        AKI_LOG(ERROR) << "ref is nullptr!";
        return nullptr;
    }

    status = napi_get_reference_value(env_, ref_, &value);
    AKI_DCHECK(status == napi_ok) << "status(" << status << "): " << GetStatusDesc(status);
    if (status != napi_ok) {
        return nullptr;
    }

    return value;
}
} // namespace aki
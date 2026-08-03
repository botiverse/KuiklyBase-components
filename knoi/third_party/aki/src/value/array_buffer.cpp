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

#include "aki/value/array_buffer.h"
#include "aki/jsbind.h"
#include "aki/logging/logging.h"
#include "status/status.h"

namespace aki {
ArrayBuffer::ArrayBuffer(uint8_t* ptr, size_t len, Typed typed)
    : data_(ptr), len_(len), typed_(typed)
{
    napi_env env = JSBind::GetScopedEnv();
    if (env != nullptr) {
        napi_status status;
        napi_value arrayBuffer;
        uint8_t* outputBuff = nullptr;

        status = napi_create_arraybuffer(env, this->GetLength(), reinterpret_cast<void**>(&outputBuff), &arrayBuffer);
        AKI_DCHECK(status == napi_ok) << "status(" << status << "): " << GetStatusDesc(status);
        if (status != napi_ok) {
            AKI_LOG(ERROR) << "napi_create_arraybuffer failed: status(" << status << "): " << GetStatusDesc(status);
            data_ = nullptr;
            len_ = 0;
            return;
        }
        
        uint8_t* inputBytes = ptr;
        for (size_t i = 0; i < this->GetLength(); i++) {
            outputBuff[i] = inputBytes[i];
        }
        
        if (this->GetTyped() != ArrayBuffer::BUFF) {
            napi_value typedArray;
            napi_typedarray_type typed = static_cast<napi_typedarray_type>(this->GetTyped());

            status = napi_create_typedarray(env, typed, this->GetCount(), arrayBuffer, 0, &typedArray);
            AKI_DCHECK(status == napi_ok) << "status(" << status << "): " << GetStatusDesc(status);
            if (status != napi_ok) {
                AKI_LOG(ERROR) << "napi_create_typedarray failed: status(" << status << "): " << GetStatusDesc(status);
            } else {
                arrayBuffer = typedArray;
            }
        }

        data_ = outputBuff;
        persistent_ = Persistent(arrayBuffer);
    }
}

ArrayBuffer::ArrayBuffer(napi_value arrayBuffer,
                         uint8_t* ptr,
                         size_t len,
                         Typed typed)
    : data_(ptr), len_(len), typed_(typed), persistent_(arrayBuffer)
{}

ArrayBuffer::ArrayBuffer(const ArrayBuffer& that)
    : data_(that.data_), len_(that.len_), staging_(that.staging_), typed_(that.typed_), persistent_(that.persistent_)
{
    if (!staging_.empty()) {
        data_ = staging_.data();
    }
}

ArrayBuffer& ArrayBuffer::operator=(const ArrayBuffer& that)
{
    if (this == &that) {
        return *this;
    }

    data_ = that.data_;
    len_ = that.len_;
    staging_ = that.staging_;
    typed_ = that.typed_;
    persistent_ = that.persistent_;

    if (!staging_.empty()) {
        data_ = staging_.data();
    }

    return *this;
}

void ArrayBuffer::Commit()
{
    std::vector<uint8_t> data(data_, data_ + len_);
    staging_ = std::move(data);
    data_ = staging_.data();
}

napi_value ArrayBuffer::GetHandle()
{
    if (persistent_.IsValid()) {
        return persistent_.GetValue();
    } else {
        if (JSBind::GetScopedEnv() != nullptr) {
            ArrayBuffer temp(data(), len_, typed_);
            return temp.GetHandle();
        } else {
            return nullptr;
        }
    }
}
} // namespace aki

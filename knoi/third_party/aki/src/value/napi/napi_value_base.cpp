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
#include "aki/jsbind.h"
#include "aki/value/napi/napi_value_base.h"
#include "aki/logging/logging.h"
#include "status/status.h"
#include <climits>
#include <cfloat>
namespace aki {

// | static |
bool NapiValueBase::CheckUndefinedType(napi_env env, napi_value value)
{
    napi_valuetype type = napi_undefined;
    napi_status status = napi_typeof(env, value, &type);
    AKI_DCHECK(status == napi_ok);
    if (status != napi_ok) {
        return false;
    }
    return type == napi_undefined;
}

bool NapiValueBase::CheckNullType(napi_env env, napi_value value)
{
    napi_valuetype type = napi_null;
    napi_status status = napi_typeof(env, value, &type);
    AKI_DCHECK(status == napi_ok);
    if (status != napi_ok) {
        return false;
    }
    return type == napi_null;
}

bool NapiValueBase::CheckBoolType(napi_env env, napi_value value)
{
    napi_valuetype type = napi_boolean;
    napi_status status = napi_typeof(env, value, &type);
    AKI_DCHECK(status == napi_ok);
    if (status != napi_ok) {
        return false;
    }
    return type == napi_boolean;
}

bool NapiValueBase::CheckNumberType(napi_env env, napi_value value)
{
    napi_valuetype type = napi_number;
    napi_status status = napi_typeof(env, value, &type);
    AKI_DCHECK(status == napi_ok);
    if (status != napi_ok) {
        return false;
    }
    return type == napi_number;
}

bool NapiValueBase::CheckBigIntType(napi_env env, napi_value value)
{
    napi_valuetype type = napi_undefined;
    napi_status status = napi_typeof(env, value, &type);
    AKI_DCHECK(status == napi_ok);
    if (status != napi_ok) {
        return false;
    }
    return type == napi_bigint;
}

bool NapiValueBase::CheckStringType(napi_env env, napi_value value)
{
    napi_valuetype type = napi_string;
    napi_status status = napi_typeof(env, value, &type);
    AKI_DCHECK(status == napi_ok);
    if (status != napi_ok) {
        return false;
    }
    return type == napi_string;
}

bool NapiValueBase::CheckObjectType(napi_env env, napi_value value)
{
    napi_valuetype type = napi_object;
    napi_status status = napi_typeof(env, value, &type);
    AKI_DCHECK(status == napi_ok);
    if (status != napi_ok) {
        return false;
    }
    return type == napi_object;
}

bool NapiValueBase::CheckArrayType(napi_env env, napi_value value)
{
    bool result = false;
    napi_status status = napi_is_array(env, value, &result);
    AKI_DCHECK(status == napi_ok);
    if (status != napi_ok) {
        return false;
    }
    return result;
}

bool NapiValueBase::CheckFunctionType(napi_env env, napi_value value)
{
    napi_valuetype type = napi_function;
    napi_status status = napi_typeof(env, value, &type);
    AKI_DCHECK(status == napi_ok);
    if (status != napi_ok) {
        return false;
    }
    return type == napi_function;
}

bool NapiValueBase::CheckErrorType(napi_env env, napi_value value)
{
    napi_status status;
    bool isError = false;
    status = napi_is_error(env, value, &isError);
    AKI_DCHECK(status == napi_ok);
    if (status != napi_ok) {
        return false;
    }
    return isError;
}

// napi_create_external 3rd/4th params required, differs from Node.js spec
void NapiValueBase::FinalizeTest(napi_env env, void* nativeInstance, void* finalizeHint)
{
    const char* hint = reinterpret_cast<const char*>(finalizeHint);
    AKI_DLOG(DEBUG) << "FinalizeTest: " << (hint == nullptr ? "unknown" : hint);
}

bool NapiValueBase::GetBool() const
{
    bool result = false;
    napi_status status = napi_get_value_bool(env_, value_, &result);
    AKI_DCHECK(status == napi_ok) << "status(" << status << "): " << GetStatusDesc(status);
    if (status != napi_ok) {
        AKI_LOG(ERROR) << "napi_get_value_bool failed: status(" << status << "): " << GetStatusDesc(status);
        return false;
    }
    return result;
}

uint8_t NapiValueBase::GetUint8() const
{
    uint32_t result = 0;
    napi_status status = napi_get_value_uint32(env_, value_, &result);
    AKI_DCHECK(status == napi_ok) << "status(" << status << "): " << GetStatusDesc(status);
    if (status != napi_ok) {
        AKI_LOG(ERROR) << "napi_get_value_uint32 failed: status(" << status << "): " << GetStatusDesc(status);
        return 0;
    }
    if (result > UINT8_MAX) {
        napi_throw_range_error(env_, nullptr, "Value exceeds uint8 range [0, 255]");
        return 0;
    }
    return static_cast<uint8_t>(result);
}

int8_t NapiValueBase::GetInt8() const
{
    int32_t result = 0;
    napi_status status = napi_get_value_int32(env_, value_, &result);
    AKI_DCHECK(status == napi_ok) << "status(" << status << "): " << GetStatusDesc(status);
    if (status != napi_ok) {
        AKI_LOG(ERROR) << "napi_get_value_int32 failed: status(" << status << "): " << GetStatusDesc(status);
        return 0;
    }
    if (result < INT8_MIN || result > INT8_MAX) {
        napi_throw_range_error(env_, nullptr, "Value exceeds int8 range [-128, 127]");
        return 0;
    }
    return static_cast<int8_t>(result);
}

uint16_t NapiValueBase::GetUint16() const
{
    uint32_t result = 0;
    napi_status status = napi_get_value_uint32(env_, value_, &result);
    AKI_DCHECK(status == napi_ok) << "status(" << status << "): " << GetStatusDesc(status);
    if (status != napi_ok) {
        AKI_LOG(ERROR) << "napi_get_value_uint32 failed: status(" << status << "): " << GetStatusDesc(status);
        return 0;
    }
    if (result > UINT16_MAX) {
        napi_throw_range_error(env_, nullptr, "Value exceeds uint16 range [0, 65535]");
        return 0;
    }
    return static_cast<uint16_t>(result);
}

int16_t NapiValueBase::GetInt16() const
{
    int32_t result = 0;
    napi_status status = napi_get_value_int32(env_, value_, &result);
    AKI_DCHECK(status == napi_ok) << "status(" << status << "): " << GetStatusDesc(status);
    if (status != napi_ok) {
        AKI_LOG(ERROR) << "napi_get_value_int32 failed: status(" << status << "): " << GetStatusDesc(status);
        return 0;
    }
    if (result < INT16_MIN || result > INT16_MAX) {
        napi_throw_range_error(env_, nullptr, "Value exceeds int16 range [-32768, 32767]");
        return 0;
    }
    return static_cast<int16_t>(result);
}

int32_t NapiValueBase::GetInt() const
{
    int32_t result = 0;
    napi_status status = napi_get_value_int32(env_, value_, &result);
    AKI_DCHECK(status == napi_ok) << "status(" << status << "): " << GetStatusDesc(status);
    if (status != napi_ok) {
        AKI_LOG(ERROR) << "napi_get_value_int32 failed: status(" << status << "): " << GetStatusDesc(status);
        return 0;
    }
    return result;
}

uint32_t NapiValueBase::GetUInt() const
{
    uint32_t result = 0;
    napi_status status = napi_get_value_uint32(env_, value_, &result);
    AKI_DCHECK(status == napi_ok) << "status(" << status << "): " << GetStatusDesc(status);
    if (status != napi_ok) {
        AKI_LOG(ERROR) << "napi_get_value_uint32 failed: status(" << status << "): " << GetStatusDesc(status);
        return 0;
    }
    return result;
}

uint64_t NapiValueBase::GetUInt64() const
{
    napi_valuetype type = napi_undefined;
    napi_status status = napi_typeof(env_, value_, &type);
    if (status != napi_ok) {
        AKI_LOG(ERROR) << "napi_typeof failed: status(" << status << "): " << GetStatusDesc(status);
        return 0;
    }

    if (type == napi_bigint) {
        uint64_t result = 0;
        bool lossless = false;
        status = napi_get_value_bigint_uint64(env_, value_, &result, &lossless);
        AKI_DCHECK(status == napi_ok) << "status(" << status << "): " << GetStatusDesc(status);
        if (status != napi_ok) {
            AKI_LOG(ERROR) << "napi_get_value_bigint_uint64 failed: status(" << status << "): "
                           << GetStatusDesc(status);
            return 0;
        }
        if (!lossless) {
            napi_throw_range_error(env_, nullptr, "BigInt value exceeds uint64 range");
            return 0;
        }
        return result;
    }

    if (type == napi_number) {
        double num = 0.0;
        status = napi_get_value_double(env_, value_, &num);
        AKI_DCHECK(status == napi_ok) << "status(" << status << "): " << GetStatusDesc(status);
        if (status != napi_ok) {
            AKI_LOG(ERROR) << "napi_get_value_double failed: status(" << status << "): " << GetStatusDesc(status);
            return 0;
        }
        if (num < 0) {
            napi_throw_range_error(env_, nullptr, "Number value is negative, cannot convert to uint64");
            return 0;
        }
        const double maxSafeInteger  = 9007199254740991.0;
        // JS Number 安全整数上限为 2^53，超出此范围精度已丢失（JS引擎层面），提示用 BigInt
        if (num > maxSafeInteger) {
            AKI_LOG(WARNING) << "uint64_t: Number value " << num
                          << " exceeds Number.MAX_SAFE_INTEGER (2^53-1), precision may be lost. Use BigInt instead.";
        }
        return static_cast<uint64_t>(num);
    }
    AKI_LOG(ERROR) << "GetUInt64: value is neither number nor bigint";
    return 0;
}
int64_t NapiValueBase::GetInt64() const
{
    napi_valuetype type = napi_undefined;
    napi_status status = napi_typeof(env_, value_, &type);
    if (status != napi_ok) {
        AKI_LOG(ERROR) << "napi_typeof failed: status(" << status << "): " << GetStatusDesc(status);
        return 0;
    }

    if (type == napi_bigint) {
        int64_t result = 0;
        bool lossless = false;
        status = napi_get_value_bigint_int64(env_, value_, &result, &lossless);
        AKI_DCHECK(status == napi_ok) << "status(" << status << "): " << GetStatusDesc(status);
        if (status != napi_ok) {
            AKI_LOG(ERROR) << "napi_get_value_bigint_int64 failed: status(" << status << "): " << GetStatusDesc(status);
            return 0;
        }
        if (!lossless) {
            napi_throw_range_error(env_, nullptr, "BigInt value exceeds int64 range");
            return 0;
        }
        return result;
    }

    if (type == napi_number) {
        int64_t result = 0;
        status = napi_get_value_int64(env_, value_, &result);
        AKI_DCHECK(status == napi_ok) << "status(" << status << "): " << GetStatusDesc(status);
        if (status != napi_ok) {
            AKI_LOG(ERROR) << "napi_get_value_int64 failed: status(" << status << "): " << GetStatusDesc(status);
            return 0;
        }
        // JS Number 安全整数上限为 2^53，超出此范围精度已丢失（JS引擎层面），提示用 BigInt
        if (result > 9007199254740991LL || result < -9007199254740991LL) {
            AKI_LOG(WARNING) << "int64_t: Number value " << result
                << " exceeds Number.MAX_SAFE_INTEGER range (±2^53-1), precision may be lost. Use BigInt instead.";
        }
        return result;
    }

    AKI_LOG(ERROR) << "GetInt64: value is neither number nor bigint";
    return 0;
}

float NapiValueBase::GetFloat() const
{
    double result = 0.0;
    napi_status status = napi_get_value_double(env_, value_, &result);
    AKI_DCHECK(status == napi_ok) << "status(" << status << "): " << GetStatusDesc(status);
    if (status != napi_ok) {
        AKI_LOG(ERROR) << "napi_get_value_double failed: status(" << status << "): " << GetStatusDesc(status);
        return 0.0f;
    }
    if (result < -FLT_MAX || result > FLT_MAX) {
        napi_throw_range_error(env_, nullptr, "Value exceeds float range");
        return 0.0f;
    }
    return static_cast<float>(result);
}

double NapiValueBase::GetDouble() const
{
    double result = 0.0;
    napi_status status = napi_get_value_double(env_, value_, &result);
    AKI_DCHECK(status == napi_ok) << "status(" << status << "): " << GetStatusDesc(status);
    if (status != napi_ok) {
        AKI_LOG(ERROR) << "napi_get_value_double failed: status(" << status << "): " << GetStatusDesc(status);
        return 0.0;
    }
    return result;
}

std::string NapiValueBase::GetString() const
{
    FUNCTION_DTRACE();
    size_t length = 0;
    napi_status status = napi_get_value_string_utf8(env_, value_, nullptr, 0, &length);
    AKI_DCHECK(status == napi_ok) << "status(" << status << "): " << GetStatusDesc(status);
    if (status != napi_ok) {
        AKI_LOG(ERROR) << "napi_get_value_string_utf8 failed: status(" << status << "): " << GetStatusDesc(status);
        return "";
    }
    AKI_DCHECK((length+1) < std::numeric_limits<size_t>::max());
    std::string buf(length, '\0');
#if USING_CXX_STANDARD_11
    status = napi_get_value_string_utf8(env_, value_, (char*)buf.data(), length+1, &length);
#else
    status = napi_get_value_string_utf8(env_, value_, buf.data(), length+1, &length);
#endif
    AKI_DCHECK(status == napi_ok) << "status(" << status << "): " << GetStatusDesc(status);
    if (status != napi_ok) {
        AKI_LOG(ERROR) << "napi_get_value_string_utf8 failed: status(" << status << "): " << GetStatusDesc(status);
        return "";
    }

    return buf;
}

napi_value NapiValueBase::GetNapiValue() const
{
    return value_;
}

ArrayBuffer NapiValueBase::GetArrayBuffer() const
{
    napi_status status;
    napi_env env = env_;
    void* data = nullptr;
    size_t length = 0;
    ArrayBuffer::Typed typed = ArrayBuffer::BUFF;

    bool isArrayBuffer;
    status = napi_is_arraybuffer(env, value_, &isArrayBuffer);
    AKI_DCHECK(status == napi_ok) << "status: " << status;
    if (status != napi_ok) {
        AKI_LOG(ERROR) << "napi_is_arraybuffer failed: status(" << status << ")";
        return ArrayBuffer(value_, nullptr, 0, typed);
    }

    if (isArrayBuffer) {
        status = napi_get_arraybuffer_info(env, value_, &data, &length);
        AKI_DCHECK(status == napi_ok) << "status: " << status;
        if (status != napi_ok) {
            AKI_LOG(ERROR) << "napi_get_arraybuffer_info failed: status(" << status << ")";
            return ArrayBuffer(value_, nullptr, 0, typed);
        }
    } else {
        napi_typedarray_type type;
        napi_value buffer;
        size_t offset;
        status = napi_get_typedarray_info(env, value_, &type, &length, &data, &buffer, &offset);
        AKI_DCHECK(status == napi_ok) << "status: " << status;
        if (status != napi_ok) {
            AKI_LOG(ERROR) << "napi_get_typedarray_info failed: status(" << status << ")";
            return ArrayBuffer(value_, nullptr, 0, typed);
        }
        typed = static_cast<ArrayBuffer::Typed>(type);
    }

    return ArrayBuffer(value_, reinterpret_cast<uint8_t *>(data), length, typed);
}

// 获取对象引用
void* NapiValueBase::GetDataReference()
{
    AKI_DCHECK(false);
    return nullptr;
}

Promise NapiValueBase::GetPromise()
{
    return Promise(value_);
}

bool NapiValueBase::IsUndefined() const
{
    return CheckUndefinedType(env_, value_);
}

bool NapiValueBase::IsNull() const
{
    return CheckNullType(env_, value_);
}

bool NapiValueBase::IsBool() const
{
    return CheckBoolType(env_, value_);
}

bool NapiValueBase::IsNumber() const
{
    return CheckNumberType(env_, value_);
}

bool NapiValueBase::IsString() const
{
    return CheckStringType(env_, value_);
}

bool NapiValueBase::IsObject() const
{
    return CheckObjectType(env_, value_);
}

bool NapiValueBase::IsArray() const
{
    return CheckArrayType(env_, value_);
}

bool NapiValueBase::IsFunction() const
{
    return CheckFunctionType(env_, value_);
}

bool NapiValueBase::IsError() const
{
    return CheckErrorType(env_, value_);
}

} // namespace aki
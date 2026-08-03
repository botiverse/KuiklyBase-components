/*
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Aki-typed exports for the existing TaskPool synchronous adapter. The third
 * notify argument is retained for source compatibility but no longer controls
 * UTF-8 allocation or copying.
 */

#include "function_waiter_registry.h"
#include "knoi_aki_scope.h"

#include <aki/jsbind.h>
#include <node_api.h>

#include <chrono>
#include <cstdint>
#include <string>
#include <utility>

#ifndef KNOI_WAITER_TIMEOUT_MS
#define KNOI_WAITER_TIMEOUT_MS 10000
#endif

namespace {

knoi::FunctionWaiterRegistry gWaiters;

napi_value Undefined(napi_env env)
{
    napi_value value = nullptr;
    if (env != nullptr) {
        napi_get_undefined(env, &value);
    }
    return value;
}

napi_value ThrowWaiterError(napi_env env, knoi::WaiterStatus status)
{
    const char* code = knoi::WaiterStatusCode(status);
    if (env != nullptr) {
        napi_throw_error(env, code, code);
    }
    return nullptr;
}

int64_t create_function_waiter()
{
    return gWaiters.Create();
}

napi_value notify_function_waiter(int64_t id, std::string result, int64_t legacyLength)
{
    (void)legacyLength;
    napi_env env = aki::JSBind::GetScopedEnv();
    knoi::WaiterStatus status = gWaiters.Notify(id, std::move(result));
    return status == knoi::WaiterStatus::kOk ? Undefined(env) : ThrowWaiterError(env, status);
}

napi_value wait_on_function_waiter(int64_t id)
{
    napi_env env = aki::JSBind::GetScopedEnv();
    knoi::WaiterResult result =
        gWaiters.Wait(id, std::chrono::milliseconds(KNOI_WAITER_TIMEOUT_MS));
    if (!result.ok()) {
        return ThrowWaiterError(env, result.status);
    }

    napi_value value = nullptr;
    napi_status status =
        napi_create_string_utf8(env, result.value.data(), result.value.size(), &value);
    if (status != napi_ok) {
        napi_throw_error(env, "KNOI_WAITER_STRING_FAILED", "napi_create_string_utf8 failed");
        return nullptr;
    }
    return value;
}

JSBIND_SCOPED_FUNCTION(kKnoiAkiModuleScope, create_function_waiter);
JSBIND_SCOPED_FUNCTION(kKnoiAkiModuleScope, wait_on_function_waiter);
JSBIND_SCOPED_FUNCTION(kKnoiAkiModuleScope, notify_function_waiter);

} // namespace

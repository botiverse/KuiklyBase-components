/*
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Aki-backed KNOI addon bootstrap. The public ArkTS surface and the two
 * Kotlin/Native bootstrap symbols remain the KNOI compatibility contract.
 */

#include "knoi_aki_scope.h"
#include "native_bridge_loader.h"

#include <aki/jsbind.h>
#include <node_api.h>

#include <string>

const char kKnoiAkiModuleScope[] = "kuiklybase.knoi";

namespace {

knoi::NativeBridgeLoader gBridgeLoader;

napi_value Undefined(napi_env env)
{
    napi_value value = nullptr;
    if (env != nullptr) {
        napi_get_undefined(env, &value);
    }
    return value;
}

napi_value ThrowBridgeError(napi_env env, const knoi::BridgeResult& result)
{
    if (env != nullptr) {
        napi_throw_error(env, knoi::BridgeStatusCode(result.status), result.detail.c_str());
    }
    return nullptr;
}

napi_value setup(std::string libraryName, bool debug)
{
    napi_env env = aki::JSBind::GetScopedEnv();
    knoi::BridgeResult result = gBridgeLoader.Setup(libraryName, debug);
    return result.ok() ? Undefined(env) : ThrowBridgeError(env, result);
}

napi_value init()
{
    napi_env env = aki::JSBind::GetScopedEnv();
    if (env == nullptr) {
        return ThrowBridgeError(
            env,
            {knoi::BridgeStatus::kInvalidArgument, "Aki did not provide a scoped napi_env"});
    }

    napi_value global = nullptr;
    napi_status status = napi_get_global(env, &global);
    if (status != napi_ok || global == nullptr) {
        napi_throw_error(env, "KNOI_GET_GLOBAL_FAILED", "napi_get_global failed");
        return nullptr;
    }

    napi_value knoiObject = nullptr;
    status = napi_create_object(env, &knoiObject);
    if (status != napi_ok || knoiObject == nullptr) {
        napi_throw_error(env, "KNOI_CREATE_OBJECT_FAILED", "napi_create_object failed");
        return nullptr;
    }

    status = napi_set_named_property(env, global, "knoi", knoiObject);
    if (status != napi_ok) {
        napi_throw_error(env, "KNOI_SET_GLOBAL_FAILED", "failed to install globalThis.knoi");
        return nullptr;
    }

    knoi::BridgeResult result = gBridgeLoader.Initialize(env, knoiObject);
    if (!result.ok()) {
        napi_value key = nullptr;
        bool deleted = false;
        if (napi_create_string_utf8(env, "knoi", NAPI_AUTO_LENGTH, &key) == napi_ok) {
            napi_delete_property(env, global, key, &deleted);
        }
        return ThrowBridgeError(env, result);
    }

    bool exceptionPending = false;
    if (napi_is_exception_pending(env, &exceptionPending) == napi_ok && exceptionPending) {
        napi_value key = nullptr;
        bool deleted = false;
        if (napi_create_string_utf8(env, "knoi", NAPI_AUTO_LENGTH, &key) == napi_ok) {
            napi_delete_property(env, global, key, &deleted);
        }
        return nullptr;
    }

    return Undefined(env);
}

JSBIND_SCOPED_FUNCTION(kKnoiAkiModuleScope, setup);
JSBIND_SCOPED_FUNCTION(kKnoiAkiModuleScope, init);

} // namespace

extern "C" napi_value InitKnoiAkiModule(napi_env env, napi_value)
{
    aki::JSBind::SetScopedEnv(env);
    return aki::JSBind::BindSymbols(kKnoiAkiModuleScope);
}

static napi_module knoiModule = {
    1,
    0,
    nullptr,
    InitKnoiAkiModule,
    "knoi",
    nullptr,
    {0},
};

extern "C" __attribute__((constructor)) void RegisterKNOIModule(void)
{
    napi_module_register(&knoiModule);
}

/*
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Aki-backed KNOI addon bootstrap. The public ArkTS surface and the two
 * Kotlin/Native bootstrap symbols remain the KNOI compatibility contract.
 */

#include "native_bridge_loader.h"

#include <aki/jsbind.h>
#include <node_api.h>

#include <string>

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

napi_value BindDocumentedAkiSurface(napi_env env, napi_value exports)
{
    // Aki's hybrid Node-API contract binds its registered global functions
    // into the host-supplied exports object. Do not use the undocumented
    // scoped registry: it constructs a replacement object and is not the
    // addon integration path described by Aki 1.3.1.
    return aki::JSBind::BindSymbols(env, exports);
}

bool HideAkiInternalClass(napi_env env, napi_value exports)
{
    napi_value key = nullptr;
    bool deleted = false;
    return napi_create_string_utf8(env, "JSBind", NAPI_AUTO_LENGTH, &key) == napi_ok &&
        napi_delete_property(env, exports, key, &deleted) == napi_ok && deleted;
}

} // namespace

JSBIND_GLOBAL()
{
    JSBIND_FUNCTION(setup);
    JSBIND_FUNCTION(init);
}

extern "C" napi_value InitKnoiAkiModule(napi_env env, napi_value exports)
{
    napi_value boundExports = BindDocumentedAkiSurface(env, exports);
    if (boundExports == nullptr) {
        return nullptr;
    }
    if (!HideAkiInternalClass(env, boundExports)) {
        napi_throw_error(
            env,
            "KNOI_HIDE_AKI_INTERNAL_FAILED",
            "failed to remove Aki's internal JSBind class from the KNOI addon surface");
        return nullptr;
    }
    return boundExports;
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

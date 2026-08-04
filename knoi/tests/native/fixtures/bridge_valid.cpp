#include <atomic>

#ifdef KNOI_FIXTURE_USE_NAPI
#include <node_api.h>
#endif

// Kotlin/Native OHOS shared libraries contain lazy-only unresolved runtime
// references (notably `main`). Keep an equivalent never-called relocation in
// the valid fixture so RTLD_NOW is proven incompatible while RTLD_LAZY can
// still validate and invoke the two KNOI bootstrap symbols.
extern "C" void knoi_fixture_lazy_only_dependency();
extern "C" __attribute__((visibility("default"), used)) void knoi_fixture_never_called()
{
    knoi_fixture_lazy_only_dependency();
}

namespace {
std::atomic<int> gEnvCalls{0};
std::atomic<int> gBridgeCalls{0};
std::atomic<int> gLastDebug{-1};

#ifdef KNOI_FIXTURE_USE_NAPI
napi_value GetEnvCalls(napi_env env, napi_callback_info)
{
    napi_value value = nullptr;
    napi_create_int32(env, gEnvCalls.load(), &value);
    return value;
}

napi_value GetBridgeCalls(napi_env env, napi_callback_info)
{
    napi_value value = nullptr;
    napi_create_int32(env, gBridgeCalls.load(), &value);
    return value;
}

void SetInt(napi_env env, napi_value target, const char* name, int value)
{
    napi_value jsValue = nullptr;
    if (napi_create_int32(env, value, &jsValue) == napi_ok) {
        napi_set_named_property(env, target, name, jsValue);
    }
}

void SetBool(napi_env env, napi_value target, const char* name, bool value)
{
    napi_value jsValue = nullptr;
    if (napi_get_boolean(env, value, &jsValue) == napi_ok) {
        napi_set_named_property(env, target, name, jsValue);
    }
}

void SetFunction(
    napi_env env,
    napi_value target,
    const char* name,
    napi_callback callback)
{
    napi_value function = nullptr;
    if (napi_create_function(env, name, NAPI_AUTO_LENGTH, callback, nullptr, &function) == napi_ok) {
        napi_set_named_property(env, target, name, function);
    }
}
#endif
}

extern "C" void com_tencent_tmm_knoi_initEnv(void* rawEnv, void* rawExports, bool debug)
{
    gLastDebug.store(debug ? 1 : 0);
    const int call = gEnvCalls.fetch_add(1) + 1;
#ifdef KNOI_FIXTURE_USE_NAPI
    auto env = static_cast<napi_env>(rawEnv);
    auto exports = static_cast<napi_value>(rawExports);
    SetInt(env, exports, "__knoi_test_init_env_call", call);
    SetBool(env, exports, "__knoi_test_debug", debug);
    SetFunction(env, exports, "__knoi_test_get_init_env_calls", GetEnvCalls);
    SetFunction(env, exports, "__knoi_test_get_init_bridge_calls", GetBridgeCalls);
#else
    (void)rawEnv;
    (void)rawExports;
#endif
}

extern "C" void com_tencent_tmm_knoi_initBridge()
{
    gBridgeCalls.fetch_add(1);
}

extern "C" int knoi_test_env_calls()
{
    return gEnvCalls.load();
}

extern "C" int knoi_test_bridge_calls()
{
    return gBridgeCalls.load();
}

extern "C" int knoi_test_last_debug()
{
    return gLastDebug.load();
}

extern "C" void knoi_test_reset_calls()
{
    gEnvCalls.store(0);
    gBridgeCalls.store(0);
    gLastDebug.store(-1);
}

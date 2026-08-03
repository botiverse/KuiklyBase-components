#include <atomic>

namespace {
std::atomic<int> gEnvCalls{0};
std::atomic<int> gBridgeCalls{0};
std::atomic<int> gLastDebug{-1};
}

extern "C" void com_tencent_tmm_knoi_initEnv(void*, void*, bool debug)
{
    gLastDebug.store(debug ? 1 : 0);
    gEnvCalls.fetch_add(1);
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

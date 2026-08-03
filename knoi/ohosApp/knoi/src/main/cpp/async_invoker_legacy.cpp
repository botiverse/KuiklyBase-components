#include <future>
#include <js_native_api.h>
#include <js_native_api_types.h>

using namespace std;

struct Waiter {
    promise<const char *> *result;
};

EXTERN_C_START
napi_value create_function_waiter(napi_env env, napi_callback_info info) {
    Waiter *waiter = new Waiter();
    waiter->result = new std::promise<const char *>();
    napi_value result;
    napi_create_int64(env, (long)waiter, &result);
    return result;
}
EXTERN_C_END

EXTERN_C_START
napi_value notify_function_waiter(napi_env env, napi_callback_info info) {
    napi_value argv[3];
    size_t argc = 3;
    napi_get_cb_info(env, info, &argc, argv, NULL, NULL);
    int64_t size;
    napi_get_value_int64(env, argv[2], &size);
    char *buf = (char *)malloc(size + 1);
    size_t realSize;
    napi_get_value_string_utf8(env, argv[1], buf, (size_t)size + 1, &realSize);

    long addr;
    napi_get_value_int64(env, argv[0], &addr);
    Waiter *waiter = (Waiter *)addr;
    waiter->result->set_value(buf);
    delete waiter;
    return NULL;
}
EXTERN_C_END

EXTERN_C_START
napi_value wait_on_function_waiter(napi_env env, napi_callback_info info) {
    napi_value argv[1];
    size_t argc = 1;
    napi_get_cb_info(env, info, &argc, argv, NULL, NULL);
    long addr;
    napi_get_value_int64(env, argv[0], &addr);
    Waiter *waiter = (Waiter *)addr;

    const char *c = waiter->result->get_future().get();
    napi_value result;
    napi_create_string_utf8(env, c, strlen(c), &result);
    free((void *)c);
    return result;
}
EXTERN_C_END
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

#ifndef AKI_SAFETY_CALLBACK_H
#define AKI_SAFETY_CALLBACK_H

#include <node_api.h>
#include <future>
#include <chrono>
#include <stdexcept>
#include <thread>
#include <memory>
#include <atomic>
#include <mutex>

#ifndef AKI_INVOKE_TIMEOUT_MS
#define AKI_INVOKE_TIMEOUT_MS 10000
#endif

#include "aki/config.h"
#include "aki/callback/napi/callback.h"
#include "aki/logging/logging.h"

namespace aki {
struct ThreadSafeContext {
    template<typename R, typename... P>
    struct Data {
        std::tuple<typename ValueDefiner<P>::RawType...> params;

        std::shared_ptr<std::promise<R>> result;

        std::shared_ptr<ThreadSafeContext> ctx;
    };

    struct EnvGuard {
        std::atomic<bool> valid{true};
    };

    struct CleanupHookArg {
        std::shared_ptr<EnvGuard> guard;
        napi_env env;
        napi_ref cbRef = nullptr;
        mutable std::mutex mutex;
        std::atomic<bool> cleanupDone{false};
    };

    ThreadSafeContext(const napi_env env,
                      const napi_value cb)
        : env(env), mainId_(std::this_thread::get_id()), envGuard_(std::make_shared<EnvGuard>())
    {
        napi_status status;
        napi_value workName;
        cleanupHookArg_ = new CleanupHookArg();
        cleanupHookArg_->guard = envGuard_;
        cleanupHookArg_->env = env;

        status = napi_create_string_utf8(env,
                                         "JSBind Thread-safe Call from Async Work Item",
                                         NAPI_AUTO_LENGTH,
                                         &workName);
        AKI_DCHECK(status == napi_ok) << "status: " << status;

        status = napi_create_reference(env,
                                       cb,
                                       1,
                                       &cleanupHookArg_->cbRef);
        AKI_DCHECK(status == napi_ok) << "status: " << status;

        status = napi_create_threadsafe_function(env,
                                                 cb,
                                                 nullptr,
                                                 workName,
                                                 0,
                                                 1,
                                                 cleanupHookArg_,
                                                 FinalizeThreadsafeFunction,
                                                 this,
                                                 CallJs,
                                                 &(ts));
        AKI_DCHECK(status == napi_ok) << "status: " << status;

        status = napi_add_env_cleanup_hook(env, EnvCleanupHook, cleanupHookArg_);
        AKI_DCHECK(status == napi_ok) << "status: " << status;
    }

    ~ThreadSafeContext()
    {
        napi_status status = napi_release_threadsafe_function(ts,
                                                              napi_tsfn_release);
        AKI_DCHECK(status == napi_ok) << "status: " << status;
    }

    template<typename R, typename... P>
    R Invoke(std::shared_ptr<ThreadSafeContext> self, P&&... args) const
    {
        if (!envGuard_->valid.load()) {
            throw std::runtime_error("ThreadSafeContext::Invoke: env destroyed, JS thread no longer exists");
        }

        napi_status status;

        if (std::this_thread::get_id() == mainId_) {
            napi_ref localCbRef;
            {
                std::lock_guard<std::mutex> lock(cleanupHookArg_->mutex);
                localCbRef = cleanupHookArg_->cbRef;
            }
            if (localCbRef == nullptr) {
                throw std::runtime_error("ThreadSafeContext::Invoke: cbRef is nullptr");
            }
            napi_value cb;
            status = napi_get_reference_value(env, localCbRef, &cb);
            AKI_DCHECK(status == napi_ok) << "status: " << status;

            Callback<R (P...)> jsCallback(env, cb);
            return jsCallback(std::forward<P>(args)...);
        } else {
            Data<R, P...> *data = new  Data<R, P...>({
                .params = std::make_tuple(std::forward<P>(args)...),
                .result = std::make_shared<std::promise<R>>(),
                .ctx = std::move(self)
            });
            
            auto future = data->result->get_future();
            if (!Invoke(data)) {
                data->result->set_exception(
                    std::make_exception_ptr(std::runtime_error("napi_call_threadsafe_function failed")));
                delete data;
            }

            if (future.wait_for(std::chrono::milliseconds(AKI_INVOKE_TIMEOUT_MS)) == std::future_status::timeout) {
                throw std::runtime_error("SafetyCallback::Invoke timed out waiting for JS callback");
            }
            return future.get();
        }
    }

    bool Invoke(void* data) const
    {
        if (!envGuard_->valid.load()) {
            AKI_DLOG(ERROR) << "ThreadSafeContext::Invoke: env destroyed, cannot call threadsafe function";
            return false;
        }

        napi_status status;

        status = napi_acquire_threadsafe_function(ts);
        if (status != napi_ok) {
            AKI_DLOG(ERROR) << "napi_acquire_threadsafe_function failed, status: " << status;
            return false;
        }
        AKI_DLOG(DEBUG) << "begin to call threadsafe function.";
        status = napi_call_threadsafe_function(ts,
                                               data,
                                               napi_tsfn_blocking);
        if (status != napi_ok) {
            AKI_DLOG(ERROR) << "napi_call_threadsafe_function failed, status: " << status;
            napi_release_threadsafe_function(ts, napi_tsfn_release);
            return false;
        }
        napi_release_threadsafe_function(ts, napi_tsfn_release);
        return true;
    }

    template <typename R, typename... P>
    std::future<R> InvokeAsync(std::shared_ptr<ThreadSafeContext> self, P &&...args) const
    {
        Data<R, P...> *data = new  Data<R, P...> ({
            .params = std::make_tuple(std::forward<P>(args)...),
            .result = std::make_shared<std::promise<R>>(),
            .ctx = std::move(self)});
        
        auto future = data->result->get_future();
        if (!envGuard_->valid.load() || !Invoke(data)) {
            data->result->set_exception(
                std::make_exception_ptr(std::runtime_error("napi_call_threadsafe_function failed or env destroyed")));
            delete data;
        }
        return future;
    }

    const napi_env env;

    napi_threadsafe_function ts = nullptr;

    std::thread::id mainId_;

    void (*forwardCallJs)(napi_env env, napi_value cb, void* data) = nullptr;

    void (*cleanupData)(void* data) = nullptr;

    std::shared_ptr<EnvGuard> envGuard_;

    CleanupHookArg* cleanupHookArg_ = nullptr;

private:

    static void DoNapiCleanup(CleanupHookArg* hookArg)
    {
        if (hookArg == nullptr) {
            return;
        }
        bool expected = false;
        if (hookArg->cleanupDone.compare_exchange_strong(expected, true)) {
            if (hookArg->cbRef != nullptr) {
                napi_status status = napi_delete_reference(hookArg->env, hookArg->cbRef);
                AKI_DCHECK(status == napi_ok) << "status: " << status;
                hookArg->cbRef = nullptr;
            }
            hookArg->guard->valid.store(false);
        }
    }

    static void EnvCleanupHook(void* arg)
    {
        CleanupHookArg* hookArg = static_cast<CleanupHookArg*>(arg);
        if (hookArg == nullptr) {
            return;
        }
        DoNapiCleanup(hookArg);
    }

    static void FinalizeThreadsafeFunction(napi_env env, void* finalizeData, void* hint)
    {
        AKI_DLOG(DEBUG) << "FinalizeThreadsafeFunction";
        CleanupHookArg* hookArg = static_cast<CleanupHookArg*>(finalizeData);
        if (hookArg == nullptr) {
            return;
        }
        DoNapiCleanup(hookArg);
        if (hookArg->env != nullptr) {
            napi_remove_env_cleanup_hook(hookArg->env, EnvCleanupHook, hookArg);
        }
        delete hookArg;
    }

    static void CallJs(napi_env env, napi_value noUsed, void* context, void* data)
    {
        napi_status status;
        ThreadSafeContext* ctx = (ThreadSafeContext*)(context);

        if (ctx == nullptr || !ctx->envGuard_->valid.load()) {
            AKI_DLOG(ERROR) << "CallJs: ctx is null or env destroyed";
            if (ctx != nullptr && ctx->cleanupData != nullptr && data != nullptr) {
                ctx->cleanupData(data);
            }
            if (env != nullptr) {
                napi_throw_error(env, nullptr, "ThreadSafeContext is null or env destroyed in CallJs");
            }
            return;
        }

        napi_ref localCbRef;
        {
            std::lock_guard<std::mutex> lock(ctx->cleanupHookArg_->mutex);
            localCbRef = ctx->cleanupHookArg_->cbRef;
        }

        napi_value cb;
        if (localCbRef == nullptr) {
            if (ctx->cleanupData != nullptr && data != nullptr) {
                ctx->cleanupData(data);
            }
            napi_throw_error(env, nullptr, "cbRef is nullptr in ThreadSafeContext::CallJs");
            return;
        }
        status = napi_get_reference_value(env, localCbRef, &cb);
        AKI_DCHECK(status == napi_ok) << "status: " << status;
        if (status != napi_ok) {
            if (ctx->cleanupData != nullptr && data != nullptr) {
                ctx->cleanupData(data);
            }
            napi_throw_error(env, nullptr, "napi_get_reference_value failed in ThreadSafeContext::CallJs");
            return;
        }

        AKI_DCHECK(ctx->forwardCallJs != nullptr);
        if (ctx->forwardCallJs == nullptr) {
            if (ctx->cleanupData != nullptr && data != nullptr) {
                ctx->cleanupData(data);
            }
            napi_throw_error(env, nullptr, "forwardCallJs is nullptr in ThreadSafeContext::CallJs");
            return;
        }
        try {
            ctx->forwardCallJs(env, cb, data);
        } catch (...) {
            AKI_LOG(ERROR) << "forwardCallJs threw unhandled exception";
        }
    }
};

template<typename T>
class SafetyCallback;

template<typename R, typename... P>
class SafetyCallback<R (P...)> {
public:
    explicit SafetyCallback(const napi_env env, const napi_value cb)
        : ctx_(std::make_shared<ThreadSafeContext>(env, cb))
    {
        ctx_->forwardCallJs = CallJs;
        ctx_->cleanupData = CleanupData;
    }

    R operator() (P... args) const
    {
        return(ctx_->template Invoke<R, P...>(ctx_, std::move(args)...));
    }

    static void CallJs(napi_env env, napi_value cb, void* recvData)
    {
        AKI_DCHECK(env != nullptr);
        AKI_DCHECK(cb != nullptr);
        AKI_DCHECK(recvData != nullptr);
        if (env == nullptr || cb == nullptr || recvData == nullptr) {
            if (recvData != nullptr) {
                ThreadSafeContext::Data<R, P...>* data = (ThreadSafeContext::Data<R, P...>*)recvData;
                data->result->set_exception(
                    std::make_exception_ptr(std::runtime_error("invalid arguments in SafetyCallback::CallJs")));
                delete data;
            }
            if (env != nullptr) {
                napi_throw_error(env, nullptr, "invalid arguments in SafetyCallback::CallJs");
            }
            AKI_DLOG(ERROR) << "invalid arguments in SafetyCallback::CallJs";
            return;
        }

        ThreadSafeContext::Data<R, P...>* data = (ThreadSafeContext::Data<R, P...>*)recvData;
        return ForwardCallJs(env, cb, data, std::make_index_sequence<sizeof...(P)>());
    }

    static void CleanupData(void* rawData)
    {
        ThreadSafeContext::Data<R, P...>* data = (ThreadSafeContext::Data<R, P...>*)rawData;
        try {
            data->result->set_exception(
                std::make_exception_ptr(std::runtime_error("ThreadSafeContext::CallJs failed to forward data")));
        } catch (...) {
        }
        delete data;
    }

private:
    struct VoidDummy {};

    typedef typename std::conditional<std::is_void<R>::value, VoidDummy, R>::type ResultType;

#if USING_CXX_STANDARD_11
    template <size_t... I>
    static ResultType InvokeJsCallbackOnly(napi_env env, napi_value cb,
        ThreadSafeContext::Data<R, P...>* data, std::false_type, std::index_sequence<I...>)
    {
        Callback<R (P...)> jsCallback(env, cb);
        return jsCallback(std::get<I>(data->params)...);
    }

    template <size_t... I>
    static VoidDummy InvokeJsCallbackOnly(napi_env env, napi_value cb,
        ThreadSafeContext::Data<R, P...>* data, std::true_type, std::index_sequence<I...>)
    {
        Callback<R (P...)> jsCallback(env, cb);
        jsCallback(std::get<I>(data->params)...);
        return VoidDummy();
    }

    static void ResolvePromise(ThreadSafeContext::Data<R, P...>* data, ResultType& r, std::false_type)
    {
        data->result->set_value(std::move(r));
    }

    static void ResolvePromise(ThreadSafeContext::Data<R, P...>* data, ResultType&, std::true_type)
    {
        data->result->set_value();
    }
#endif

    static void SetExceptionFromNapi(ThreadSafeContext::Data<R, P...>* data, napi_env env)
    {
        napi_value exceptionResult;
        napi_status exceptionStatus = napi_get_and_clear_last_exception(env, &exceptionResult);
        if (exceptionStatus == napi_ok) {
            napi_value errorString;
            napi_status coerceStatus = napi_coerce_to_string(env, exceptionResult, &errorString);
            if (coerceStatus == napi_ok) {
                size_t length = 0;
                napi_status strStatus = napi_get_value_string_utf8(env, errorString, nullptr, 0, &length);
                if (strStatus == napi_ok) {
                    std::string buf(length, '\0');
#if USING_CXX_STANDARD_11
                    strStatus = napi_get_value_string_utf8(env, errorString, (char *)buf.data(), length + 1, &length);
#else
                    strStatus = napi_get_value_string_utf8(env, errorString, buf.data(), length + 1, &length);
#endif
                    if (strStatus == napi_ok) {
                        AKI_LOG(ERROR) << "JS callback threw exception: " << buf;
                        data->result->set_exception(std::make_exception_ptr(std::runtime_error(buf)));
                    } else {
                        data->result->set_exception(std::make_exception_ptr(
                                                    std::runtime_error("JS callback threw unknown exception")));
                    }
                } else {
                    data->result->set_exception(std::make_exception_ptr(
                                                std::runtime_error("JS callback threw unknown exception")));
                }
            } else {
                data->result->set_exception(std::make_exception_ptr(
                    std::runtime_error("JS callback threw unknown exception")));
            }
        } else {
            data->result->set_exception(std::make_exception_ptr(
                        std::runtime_error("JS callback threw exception (failed to retrieve)")));
        }
    }

    template<size_t... I>
    static void ForwardCallJs(napi_env env,
        napi_value cb,
        ThreadSafeContext::Data<R, P...>* data,
        std::index_sequence<I...>)
    {
        try {
            ResultType r;
#if USING_CXX_STANDARD_11
            r = InvokeJsCallbackOnly(env, cb, data, std::is_void<R>(), std::make_index_sequence<sizeof...(P)>());
#else
            if constexpr(std::is_void<R>::value) {
                Callback<R (P...)> jsCallback(env, cb);
                jsCallback(std::get<I>(data->params)...);
            } else {
                Callback<R (P...)> jsCallback(env, cb);
                r = jsCallback(std::get<I>(data->params)...);
            }
#endif

            bool isExceptionPending = false;
            napi_status exceptionStatus = napi_is_exception_pending(env, &isExceptionPending);
            if (exceptionStatus == napi_ok && isExceptionPending) {
                SetExceptionFromNapi(data, env);
            } else {
#if USING_CXX_STANDARD_11
                ResolvePromise(data, r, std::is_void<R>());
#else
                if constexpr(std::is_void<R>::value) {
                    data->result->set_value();
                } else {
                    data->result->set_value(std::move(r));
                }
#endif
            }
        } catch (...) {
            try {
                data->result->set_exception(std::current_exception());
            } catch (...) {
                // 这里无需处理
            }
        }

        delete data;

        return;
    }

    std::shared_ptr<ThreadSafeContext> ctx_;
};

} // namespace aki
#endif //AKI_SAFETY_CALLBACK_H

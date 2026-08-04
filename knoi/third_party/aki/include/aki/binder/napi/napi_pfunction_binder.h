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

#ifndef AKI_NAPI_PFUNCTION_BINDER_H
#define AKI_NAPI_PFUNCTION_BINDER_H
#include "aki/binder/function_binder.h"
#include "aki/config.h"

namespace aki {

template <typename F, typename R, typename... P>
class NapiPFunctionBinder : public FunctionBinder<NapiPFunctionBinder<F, R, P...>, F, R, P...> {
public:
    typedef FunctionBinder<NapiPFunctionBinder<F, R, P...>, F, R, P...> ParentType;

    static napi_value Wrapper(napi_env env, napi_callback_info info)
    {
        AKI_DLOG(DEBUG) << "NapiPFunctionBinder::Wrapper";
        if (!Checker<P...>::IsArityValid(env, info)) {
            std::string msg = std::string("JSBind: Wrong number of arguments, expected: ")
                .append(std::to_string(sizeof...(P)));
            napi_throw_error(env, nullptr, msg.c_str());
            return nullptr;
        }
        if (!Checker<typename ValueDefiner<P>::RawType...>::TypesAreValid(env, info)) {
            std::string msg = Checker<typename ValueDefiner<P>::RawType...>::GetTypeError(env, info);
            napi_throw_type_error(env, "JSBind: Wrong type of arguments", msg.c_str());
            return nullptr;
        }
        return SafetyWrapper(env, info, std::make_index_sequence<sizeof...(P)>());
    }

private:
    struct InvokeWorkInfo {
        // napi work
        napi_async_work worker;
        // napi deferred
        napi_deferred deferred;

        aki::BindInfo* bindInfo;

        std::tuple<typename ValueDefiner<P>::RawType...> params;
        bool invokeFailed = false; // 子线程执行是否发生异常
        virtual ~InvokeWorkInfo()
        {}
    };

    struct InvokeWorkWithReturnInfo : public InvokeWorkInfo {
        R result;
    };

#if USING_CXX_STANDARD_11
    static InvokeWorkInfo* SafetyWrapperHelper(std::true_type)
    {
        return new(std::nothrow) InvokeWorkInfo;
    }

    static InvokeWorkInfo* SafetyWrapperHelper(std::false_type)
    {
        return new(std::nothrow) InvokeWorkWithReturnInfo;
    }
#endif

    template<size_t... Index>
    static napi_value SafetyWrapper(napi_env env, napi_callback_info info, std::index_sequence<Index...>)
    {
        napi_status status;
        aki::BindInfo* bindInfo = nullptr;
        size_t argc = sizeof...(Index);

        std::array<napi_value, sizeof...(Index)> args;
        status = napi_get_cb_info(env, info, &argc, args.data(), nullptr, ((void **)&bindInfo));
        if (status != napi_ok) {
            napi_throw_error(env, nullptr, "napi_get_cb_info failed in NapiPFunctionBinder::SafetyWrapper");
            return nullptr;
        }
        if (bindInfo == nullptr) {
            napi_throw_error(env, nullptr, "bindInfo is nullptr in NapiPFunctionBinder::SafetyWrapper");
            return nullptr;
        }
        AKI_DCHECK(status == napi_ok);
        AKI_DCHECK(bindInfo != nullptr);
        auto tuple = std::make_tuple(NapiValueMaker<typename ValueDefiner<P>::RawType>(env, args[Index])...);
        auto valueTuple = std::make_tuple(
            NapiValueTrait<typename ValueDefiner<P>::RawType>::Cast(std::get<Index>(tuple))...);

        // 异步执行
        struct InvokeWorkInfo* workData = nullptr;

#if USING_CXX_STANDARD_11
        workData = SafetyWrapperHelper(std::is_void<R>());
#else
        if constexpr (std::is_void<R>::value) {
            workData = new(std::nothrow) InvokeWorkInfo;
        } else {
            workData = new(std::nothrow) InvokeWorkWithReturnInfo;
        }
#endif

        workData->bindInfo = bindInfo;
        workData->params = valueTuple;

        return AsyncForward(env, workData);
    }

    static napi_value AsyncForward(napi_env env, InvokeWorkInfo* workData)
    {
        napi_status status;
        napi_value promise;
        napi_value workName;
        status = napi_create_promise(env, &workData->deferred, &promise);
        AKI_DCHECK(status == napi_ok);
        if (status != napi_ok) {
            napi_throw_error(env, nullptr, "napi_create_promise failed in NapiPFunctionBinder::AsyncForward");
            delete workData;
            return nullptr;
        }
        status = napi_create_string_utf8(env,
                                         "JSBind Deferred Promise from Async Work Item",
                                         NAPI_AUTO_LENGTH,
                                         &workName);
        AKI_DCHECK(status == napi_ok);
        if (status != napi_ok) {
            napi_throw_error(env, nullptr, "napi_create_string_utf8 failed in NapiPFunctionBinder::AsyncForward");
            delete workData;
            return nullptr;
        }
        status = napi_create_async_work(env,
                                        nullptr,
                                        workName,
                                        AsyncInvokeWork,
                                        AsyncInvokeDone,
                                        workData,
                                        &workData->worker);
        AKI_DCHECK(status == napi_ok);
        if (status != napi_ok) {
            napi_throw_error(env, nullptr, "napi_create_async_work failed in NapiPFunctionBinder::AsyncForward");
            delete workData;
            return nullptr;
        }
        status = napi_queue_async_work(env, workData->worker);
        AKI_DCHECK(status == napi_ok);
        if (status != napi_ok) {
            napi_throw_error(env, nullptr, "napi_queue_async_work failed in NapiPFunctionBinder::AsyncForward");
            delete workData;
            return nullptr;
        }
        return promise;
    }

#if USING_CXX_STANDARD_11
    static void AsyncInvokeWorkHelper(napi_env env, std::true_type, void* data)
    {
        AKI_DCHECK(data != nullptr);
        struct InvokeWorkInfo *arg = (struct InvokeWorkInfo *)data;

        WrapperForward(arg, std::make_index_sequence<sizeof...(P)>());
    }

    static void AsyncInvokeWorkHelper(napi_env env, std::false_type, void* data)
    {
        AKI_DCHECK(data != nullptr);
        struct InvokeWorkInfo *arg = (struct InvokeWorkInfo *)data;

        InvokeWorkWithReturnInfo *info = static_cast<InvokeWorkWithReturnInfo *>(arg);
        info->result = WrapperForward(info, std::make_index_sequence<sizeof...(P)>());
    }
#endif
    // native方法调用 子线程任务
    static void AsyncInvokeWork(napi_env env, void* data)
    {
        AKI_DCHECK(data != nullptr);
        struct InvokeWorkInfo *arg = (struct InvokeWorkInfo *)data;

        try {
#if USING_CXX_STANDARD_11
            AsyncInvokeWorkHelper(env, std::is_void<R>(), data);
#else
            if constexpr (std::is_void<R>::value) {
                WrapperForward(arg, std::make_index_sequence<sizeof...(P)>());
            } else {
                InvokeWorkWithReturnInfo *info = static_cast<InvokeWorkWithReturnInfo *>(arg);
                info->result = WrapperForward(info, std::make_index_sequence<sizeof...(P)>());
            }
#endif
        } catch (...) {
            arg->invokeFailed = true;
            AKI_LOG(ERROR) << "Exception occurred in async invoke work";
        }
    }

#if USING_CXX_STANDARD_11
    static napi_value AsyncInvokeDoneHelper(napi_env env, napi_status status, std::true_type, void* data)
    {
        return nullptr;
    }

    static napi_value AsyncInvokeDoneHelper(napi_env env, napi_status status, std::false_type, void* data)
    {
        AKI_DCHECK(status == napi_ok);
        if (status != napi_ok) {
            napi_throw_error(env, nullptr, "async work failed");
            return nullptr;
        }
        AKI_DCHECK(data != nullptr);
        struct InvokeWorkInfo *arg = (struct InvokeWorkInfo *)data;
        InvokeWorkWithReturnInfo *info = static_cast<InvokeWorkWithReturnInfo *>(arg);

        return NapiValue<R>::ToNapiValue(env, std::forward<R>(info->result));
    }
#endif
    // native方法调用 结果回调 回到主线程
    static void AsyncInvokeDone(napi_env env, napi_status status, void* data)
    {
        AKI_DCHECK(data != nullptr);
        struct InvokeWorkInfo *arg = (struct InvokeWorkInfo *)data;

        if (status != napi_ok) {
            // async work 被取消（如 Env 退出），reject Promise 并清理
            napi_value error;
            napi_value errorMsg;
            napi_create_string_utf8(env, "Async work was cancelled", NAPI_AUTO_LENGTH, &errorMsg);
            napi_create_error(env, nullptr, errorMsg, &error);
            napi_reject_deferred(env, arg->deferred, error);
            napi_delete_async_work(env, arg->worker);
            arg->deferred = nullptr;
            delete arg;
            return;
        }

        // 子线程执行异常时，reject Promise
        if (arg->invokeFailed) {
            napi_value error;
            napi_value errorMsg;
            napi_create_string_utf8(env, "Exception occurred in async invoke work", NAPI_AUTO_LENGTH, &errorMsg);
            napi_create_error(env, nullptr, errorMsg, &error);
            napi_reject_deferred(env, arg->deferred, error);
            napi_delete_async_work(env, arg->worker);
            arg->deferred = nullptr;
            delete arg;
            return;
        }

        napi_value result = nullptr;
        // 失败调用返回错误码
#if USING_CXX_STANDARD_11
        result = AsyncInvokeDoneHelper(env, status, std::is_void<R>(), data);
#else
        if constexpr (!std::is_void<R>::value) {
            InvokeWorkWithReturnInfo *info = static_cast<InvokeWorkWithReturnInfo *>(arg);
            try {
                result = NapiValue<R>::ToNapiValue(env, std::forward<R>(info->result));
            } catch (...) {
                AKI_LOG(ERROR) << "Exception occurred in AsyncInvokeDone ToNapiValue";
                napi_get_undefined(env, &result);
            }
        }
#endif

        if (!result) {
            napi_get_undefined(env, &result);
        }

        status = napi_resolve_deferred(env, arg->deferred, result);
        if (status != napi_ok) {
            napi_reject_deferred(env, arg->deferred, result);
        }
        AKI_DCHECK(status == napi_ok);
        status = napi_delete_async_work(env, arg->worker);
        if (status != napi_ok) {
            AKI_DLOG(ERROR) << "napi_delete_async_work failed in NapiPFunctionBinder::AsyncInvokeDone";
        }
        AKI_DCHECK(status == napi_ok);
        arg->deferred = nullptr;
        delete arg;
    }

    template<size_t... Index>
    static R WrapperForward(struct InvokeWorkInfo *arg, std::index_sequence<Index...>)
    {
        aki::BindInfo* bindInfo = arg->bindInfo;
        std::tuple<typename ValueDefiner<P>::RawType...>& params = arg->params;
        return ParentType::InnerWrapper(bindInfo, std::get<Index>(params)...);
    }
};

} // namespace aki
#endif //AKI_NAPI_PFUNCTION_BINDER_H

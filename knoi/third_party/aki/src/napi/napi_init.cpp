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
#include <iostream>
#include <unordered_map>
#include <vector>
#include <unistd.h>
#include <mutex>
#include <thread>
#include <sys/syscall.h>

#include "aki/jsbind.h"
#include "aki/logging/logging.h"
#include "aki/version.h"
#include "aki/task_runner/task_runner.h"

using NapiWrapperFunctionInfo = napi_value (*)(napi_env env, napi_callback_info info);

#define DECLARE_NAPI_FUNCTION(name, func, info)                                        \
    { name, 0, func, 0, 0, 0, napi_default, info }

#define DECLARE_NAPI_METHOD(name, func, info)                                        \
    { name, 0, func, 0, 0, 0, napi_default, info }

#define DECLARE_NAPI_STATIC_METHOD(name, func, info)                                    \
    { name, 0, func, 0, 0, 0,                                                           \
        static_cast<napi_property_attributes>(napi_default | napi_static), info }

#define DECLARE_NAPI_ENUMERATION(name, obj)                                        \
    { name, 0, 0, 0, 0, obj, napi_default, 0 }

using FuncPtr = void(*)();
struct FieldInfo {
    FuncPtr wrapper = nullptr;
    int32_t id = -1;
};

struct EnvCleanupData {
    napi_env env;
    std::vector<napi_ref*> constructorRefs;
    std::vector<aki::BindInfo*> bindInfos;
};

static void ModuleCleanup(void* arg)
{
    EnvCleanupData* data = static_cast<EnvCleanupData*>(arg);
    for (auto refPtr : data->constructorRefs) {
        napi_delete_reference(data->env, *refPtr);
        delete refPtr;
    }
    for (auto info : data->bindInfos) {
        delete info;
    }
    delete data;
}

static void BindMethodAndField(aki::ClassBase *xlass, std::vector<napi_property_descriptor> &properties,
    std::unordered_map<std::string, std::pair<FieldInfo, FieldInfo>> &tempVector, EnvCleanupData* cleanupData)
{
    for (const auto &method : xlass->GetMethods()) {
        if (method.GetBinder()->GetType() == aki::Binder::Type::Method) {
            AKI_DLOG(DEBUG) << "binding method: " << method.GetName();
            aki::BindInfo *info = new aki::BindInfo();
            cleanupData->bindInfos.push_back(info);
            info->methodNumber = method.GetInvokerId();
            properties.push_back(DECLARE_NAPI_METHOD(
                method.GetName(), reinterpret_cast<NapiWrapperFunctionInfo>(method.GetBinder()->GetWrapper()), info));
        } else if (method.GetBinder()->GetType() == aki::Binder::Type::Getter) {
            auto getterInvoker = method.GetBinder()->GetWrapper();
            AKI_DLOG(DEBUG) << "binding getter: " << method.GetName();
            const std::string name = method.GetName();
            std::pair<FieldInfo, FieldInfo> &field = tempVector[name];
            field.first.wrapper = getterInvoker;
            field.first.id = method.GetInvokerId();
        } else if (method.GetBinder()->GetType() == aki::Binder::Type::Setter) {
            auto setterInvoker = method.GetBinder()->GetWrapper();
            AKI_DLOG(DEBUG) << "binding setter: " << method.GetName();
            const std::string name = method.GetName();
            std::pair<FieldInfo, FieldInfo> &field = tempVector[name];
            field.second.wrapper = setterInvoker;
            field.second.id = method.GetInvokerId();
        } else if (method.GetBinder()->GetType() == aki::Binder::Type::Func) {
            // 绑定类静态方法
            AKI_DLOG(DEBUG) << "binding static method: " << method.GetName();
            aki::BindInfo *info = new aki::BindInfo();
            cleanupData->bindInfos.push_back(info);
            info->functionNumber = method.GetInvokerId();
            auto wrapper = reinterpret_cast<NapiWrapperFunctionInfo>(method.GetBinder()->GetWrapper());
            properties.push_back(DECLARE_NAPI_STATIC_METHOD(method.GetName(), wrapper, info));
        }
    }

    for (auto &itr : tempVector) {
        aki::BindInfo *info = new aki::BindInfo();
        cleanupData->bindInfos.push_back(info);
        const char *name = itr.first.c_str();
        const std::pair<FieldInfo, FieldInfo> &field = itr.second;
        info->getterNumber = field.first.id;
        info->setterNumber = field.second.id;
        properties.push_back({name, 0, 0, reinterpret_cast<napi_callback>(field.first.wrapper),
                              reinterpret_cast<napi_callback>(field.second.wrapper), 0, napi_default, info});
    }
}

EXTERN_C_START
static napi_value Init(napi_env env, napi_value exports)
{
    EnvCleanupData* cleanupData = new EnvCleanupData();
    cleanupData->env = env;
    std::vector<napi_property_descriptor> properties;
    std::unordered_map<std::string, std::pair<FieldInfo, FieldInfo>> tempVector;
    AKI_LOG(INFO) << "begin to initial AKI with version: " << aki::Version::GetVersion();
    aki::JSBind::SetScopedEnv(env);
    for (auto& function : aki::Binding::GetFunctionList()) {
        auto binder = function.GetBinder();
        auto wrapper = reinterpret_cast<NapiWrapperFunctionInfo>(binder->GetWrapper());

        napi_status status;
        aki::BindInfo* info = new aki::BindInfo();
        cleanupData->bindInfos.push_back(info);
        info->functionNumber = function.GetInvokerId();
        napi_property_descriptor desc = DECLARE_NAPI_FUNCTION(function.GetName(), wrapper, info);
        status = napi_define_properties(env, exports, 1, &desc);
        AKI_DCHECK(status == napi_ok) <<
            "napi_define_properties failed when binding global function: " << function.GetName();
        if (status != napi_ok) {
            napi_throw_error(env, nullptr, "napi_define_properties failed when binding global function");
            return nullptr;
        }
        AKI_DLOG(DEBUG) << "binding global function: " << function.GetName();
    }

    for (auto& enumeration : aki::Binding::GetEnumerationList()) {
        napi_status status;
        napi_value enumObject;
        status = napi_create_object(env, &enumObject);
        AKI_DCHECK(status == napi_ok);
        if (status != napi_ok) {
            napi_throw_error(env, nullptr, "napi_create_object failed for enumeration");
            return nullptr;
        }
        AKI_DLOG(INFO) << "binding ENUM: " << enumeration->GetName();
        for (auto& value : enumeration->GetValue()) {
            AKI_DLOG(INFO) << "binding key: " << value.first;
            napi_value enumValue = nullptr;
            status = napi_create_int32(env, value.second, &enumValue);
            AKI_DCHECK(status == napi_ok);
            if (status != napi_ok) {
                napi_throw_error(env, nullptr, "napi_create_int32 failed for enumeration value");
                return nullptr;
            }

            napi_property_descriptor desc = DECLARE_NAPI_ENUMERATION(value.first.c_str(), enumValue);
            status = napi_define_properties(env, enumObject, 1, &desc);
            AKI_DCHECK(status == napi_ok);
            if (status != napi_ok) {
                napi_throw_error(env, nullptr, "napi_define_properties failed for enumeration property");
                return nullptr;
            }
        }
        napi_property_descriptor desc = DECLARE_NAPI_ENUMERATION(enumeration->GetName(), enumObject);
        status = napi_define_properties(env, exports, 1, &desc);
        AKI_DCHECK(status == napi_ok);
        if (status != napi_ok) {
            napi_throw_error(env, nullptr, "napi_define_properties failed for enumeration export");
            return nullptr;
        }
    }

    for (auto& xlass : aki::Binding::GetClassList()) {
        AKI_DLOG(DEBUG) << "begin to bind class: " << xlass->GetName();
        std::forward_list<const char *> xlassName = aki::Binding::GetAssociationClassExtend(xlass->GetName());
        for (auto extendClassName : xlassName) {
            AKI_DLOG(DEBUG) << "binding extendClass: " << extendClassName;
            // 查找继承类的方法
            for (auto &extendClass : aki::Binding::GetClassList()) {
                if (strcmp(extendClass->GetName(), extendClassName) == 0) {
                    BindMethodAndField(extendClass, properties, tempVector, cleanupData);
                }
            }
        }
        AKI_DLOG(DEBUG) << "begin to bind method";
        BindMethodAndField(xlass, properties, tempVector, cleanupData);
        // 存储类级别的cons对象的媒介
        aki::BindInfo *commonInfo = new aki::BindInfo();
        cleanupData->bindInfos.push_back(commonInfo);
        auto wrapper = aki::NapiOverloader::Wrapper;
        commonInfo->overloadData = xlass->GetWrapperConstructorGroupId();
        napi_value cons = nullptr;
        napi_status status = napi_define_class(env, xlass->GetName(), NAPI_AUTO_LENGTH, \
            wrapper, commonInfo, properties.size(), properties.data(), &cons);
        AKI_DCHECK(status == napi_ok);
        if (status != napi_ok) {
            napi_throw_error(env, nullptr, "napi_define_class failed");
            return nullptr;
        }
        napi_ref *constructor = new napi_ref;
        cleanupData->constructorRefs.push_back(constructor);
        status = napi_create_reference(env, cons, 1, constructor);
        AKI_DCHECK(status == napi_ok);
        if (status != napi_ok) {
            napi_throw_error(env, nullptr, "napi_create_reference failed for constructor");
            return nullptr;
        }

        commonInfo->classBase = xlass;
        reinterpret_cast<aki::ClassBase *>(commonInfo->classBase)->SetClassRefs(constructor);
        AKI_DLOG(DEBUG)<<"class : " << xlass->GetName() << " SetClassRefs : " <<constructor;
        status = napi_set_named_property(env, exports, xlass->GetName(), cons);
        AKI_DCHECK(status == napi_ok);
        if (status != napi_ok) {
            napi_throw_error(env, nullptr, "napi_set_named_property failed for class export");
            return nullptr;
        }
        properties.clear();
        tempVector.clear();
    }
    napi_add_env_cleanup_hook(env, ModuleCleanup, cleanupData);
    return exports;
}

EXTERN_C_END

static std::map<std::string, bool> moduleMap;
static std::mutex moduleMapMutex;

static bool IsMainThread()
{
    pid_t pid = getpid();
    pid_t tid = syscall(SYS_gettid);
    if (pid == tid) {
        return true;
    } else {
        return false;
    }
}

static bool IsInitialized(const std::string& moduleName)
{
    std::lock_guard<std::mutex> lock(moduleMapMutex);
    auto it = moduleMap.find(moduleName);
    if (it != moduleMap.end()) {
        return it->second;
    }
    return false;
}

static void SetInitialized(const std::string& moduleName)
{
    std::lock_guard<std::mutex> lock(moduleMapMutex);
    moduleMap[moduleName] = true;
}

napi_value aki::JSBind::BindSymbols(napi_env env, napi_value exports)
{
    return Init(env, exports);
}

napi_value aki::JSBind::BindSymbols(napi_env env, napi_value exports, std::string moduleName)
{
    if (moduleName.empty() || !IsMainThread()) {
        return Init(env, exports);
    }
    if (IsInitialized(moduleName)) {
        return exports;
    }
    SetInitialized(moduleName);
    return Init(env, exports);
}

napi_value aki::JSBind::BindSymbols(const char* module)
{
    napi_env env = aki::JSBind::GetScopedEnv();
    EnvCleanupData* cleanupData = new EnvCleanupData();
    cleanupData->env = env;
    napi_value exports;

    napi_status status = napi_create_object(env, &exports);
    AKI_DCHECK(status == napi_ok) << "napi_create_object failed in BindSymbols(const char* module)";
    if (status != napi_ok) {
        napi_throw_error(env, nullptr, "napi_create_object failed in BindSymbols");
        return nullptr;
    }
    for (auto& function : aki::Binding::GetFunctionList(module)) {
        auto binder = function.GetBinder();
        auto wrapper = reinterpret_cast<NapiWrapperFunctionInfo>(binder->GetWrapper());

        napi_status status;
        aki::BindInfo* info = new aki::BindInfo();
        cleanupData->bindInfos.push_back(info);
        info->functionNumber = function.GetInvokerId();
        napi_property_descriptor desc = DECLARE_NAPI_FUNCTION(function.GetName(), wrapper, info);
        status = napi_define_properties(env, exports, 1, &desc);
        AKI_DCHECK(status == napi_ok) <<
            "napi_define_properties failed when binding global function: " << function.GetName();
        if (status != napi_ok) {
            napi_throw_error(env, nullptr, "napi_define_properties failed when binding global function");
            return nullptr;
        }
        AKI_DLOG(DEBUG) << "binding global function: " << function.GetName();
    }

    napi_add_env_cleanup_hook(env, ModuleCleanup, cleanupData);
    return exports;
}

void aki::JSBind::InitTaskRunner(const std::string& name)
{
    const TaskRunner* runner = aki::TaskRunner::Create(name);
    if (runner == nullptr) {
        napi_env env = aki::JSBind::GetScopedEnv();
        napi_throw_error(env, nullptr, "TaskRunner::Create failed, env may be invalid or event loop unavailable");
    }
}

void aki::JSBind::DestroyTaskRunner(const std::string& name)
{
    if (!aki::TaskRunner::RemoveTaskRunner(name)) {
        napi_env env = aki::JSBind::GetScopedEnv();
        napi_throw_error(env, nullptr, "TaskRunner::DestroyTaskRunner failed, name not found");
    }
}

using namespace aki;
JSBIND_CLASS(JSBind)
{
    JSBIND_METHOD(bindFunction);
    JSBIND_METHOD(unbindFunction);
    JSBIND_METHOD(InitTaskRunner, "initTaskRunner");
    JSBIND_METHOD(DestroyTaskRunner, "destroyTaskRunner");

#if JSBIND_SUPPORT_DECLARATION
    JSBIND_METHOD(Reflect, "reflect");
    JSBIND_METHOD(QueryType, "queryType");
#endif
}

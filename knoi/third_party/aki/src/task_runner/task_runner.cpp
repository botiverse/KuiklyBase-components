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

#include "aki/task_runner/task_runner.h"
#include "aki/logging/logging.h"
#include "aki/binding.h"
#include "aki/scopehandle/ScopeHandle.h"

namespace aki {

std::unordered_map<std::string, std::unique_ptr<TaskRunner>> TaskRunner::runners;
std::mutex TaskRunner::runnersMutex_;

const TaskRunner* TaskRunner::Create(std::string taskName)
{
    napi_env env = Binding::GetScopedEnv();
    AKI_DCHECK(env != nullptr) << "should be created at js thread.";
    if (env == nullptr) {
        AKI_LOG(ERROR) << "TaskRunner::Create: env is nullptr";
        return nullptr;
    }
    std::unique_ptr<TaskRunner> taskRunner;
    taskRunner.reset(new TaskRunner(env));
    if (taskRunner->tsfn_ == nullptr) {
        AKI_LOG(ERROR) << "TaskRunner::Create: napi_create_threadsafe_function failed";
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(runnersMutex_);
    auto& runner = runners[taskName];
    runner = std::move(taskRunner);
    return runner.get();
}

bool TaskRunner::RemoveTaskRunner(const std::string& taskName)
{
    std::lock_guard<std::mutex> lock(runnersMutex_);
    auto it = runners.find(taskName);
    if (it == runners.end()) {
        AKI_LOG(ERROR) << "TaskRunner::RemoveTaskRunner: taskRunner not found for name: " << taskName;
        return false;
    }
    runners.erase(it);
    return true;
}

void TaskRunner::PostTask(const std::string& taskName, Closure task)
{
    std::lock_guard<std::mutex> lock(runnersMutex_);
    auto it = runners.find(taskName);
    if (it == runners.end() || it->second == nullptr) {
        AKI_LOG(ERROR) << "TaskRunner::PostTask: taskRunner not found for name: " << taskName;
        return;
    }
    return it->second.get()->PostTask(std::move(task));
}

TaskRunner::TaskRunner(napi_env env) : tsfn_(nullptr)
{
    napi_status status;
    napi_value workName;
    status = napi_create_string_utf8(env, "AKI TaskRunner",
        NAPI_AUTO_LENGTH, &workName);
    AKI_DCHECK(status == napi_ok) << "status: " << status;
    if (status != napi_ok) {
        AKI_LOG(ERROR) << "napi_create_string_utf8 failed in TaskRunner";
        return;
    }

    status = napi_create_threadsafe_function(env,
        nullptr,
        nullptr,
        workName,
        0,
        1,
        nullptr,
        Finalize,
        nullptr,
        CallJs,
        &tsfn_);
    AKI_DCHECK(status == napi_ok) << "status: " << status;
    if (status != napi_ok) {
        AKI_LOG(ERROR) << "napi_create_threadsafe_function failed in TaskRunner";
        tsfn_ = nullptr;
    }
}

TaskRunner::~TaskRunner()
{
    if (tsfn_ != nullptr) {
        napi_release_threadsafe_function(tsfn_, napi_tsfn_release);
    }
}

void TaskRunner::PostTask(Closure task) const
{
    if (tsfn_ == nullptr) {
        AKI_LOG(ERROR) << "TaskRunner::PostTask: tsfn_ is nullptr";
        return;
    }

    napi_status status = napi_acquire_threadsafe_function(tsfn_);
    if (status != napi_ok) {
        AKI_LOG(ERROR) << "napi_acquire_threadsafe_function failed, status: " << status;
        return;
    }

    Closure* taskPtr = new Closure(std::move(task));
    status = napi_call_threadsafe_function(tsfn_, taskPtr, napi_tsfn_blocking);
    if (status != napi_ok) {
        AKI_LOG(ERROR) << "napi_call_threadsafe_function failed, status: " << status;
        delete taskPtr;
    }

    napi_release_threadsafe_function(tsfn_, napi_tsfn_release);
}

void TaskRunner::CallJs(napi_env env, napi_value js_cb, void* context, void* data)
{
    if (data == nullptr) {
        return;
    }
    Closure* taskPtr = static_cast<Closure*>(data);
    if (env == nullptr) {
        delete taskPtr;
        return;
    }

    Binding::SetScopedEnv(env);

    ScopeHandle scopeHandle(env, false);
    try {
        (*taskPtr)();
    } catch (const std::exception& e) {
        AKI_LOG(ERROR) << "TaskRunner::CallJs: exception in task: " << e.what();
    } catch (...) {
        AKI_LOG(ERROR) << "TaskRunner::CallJs: unknown exception in task";
    }
    delete taskPtr;
}

void TaskRunner::Finalize(napi_env env, void* raw, void* hint)
{
    AKI_DLOG(DEBUG) << "TaskRunner::Finalize";
}

} // namespace aki

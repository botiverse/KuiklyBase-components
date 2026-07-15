/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tencent.kmm.network.internal

object VBTransportManager {

    // Cancellation-aware task ownership. A cancel may beat the coroutine that
    // publishes the task; the tombstone makes that race deterministic instead
    // of silently losing the cancellation.
    private val tasks = CancellationAwareRegistry<Int, VBTransportTask>()

    fun getTask(requestId: Int): VBTransportTask? = tasks.get(requestId)

    fun onTaskPrepared(requestId: Int): Boolean = tasks.begin(requestId)

    fun onTaskBegin(task: VBTransportTask) {
        logI("${task.logTag} onTaskBegin() requestId :${task.requestId}")
        if (!tasks.publish(task.requestId, task)) {
            logI("${task.logTag} onTaskBegin() consumed pre-cancel requestId:${task.requestId}")
            task.cancel()
        }
    }

    fun getState(requestId: Int): VBTransportState {
        val task = tasks.get(requestId)
        if (task == null) {
            logI("requestId:$requestId don't exist！")
            return VBTransportState.Unknown
        }
        return task.getState()
    }

    fun cancel(requestId: Int) {
        var taskToCancel: VBTransportTask? = null
        tasks.cancelOrRemember(requestId, removePublished = true) { task ->
            taskToCancel = task
        }
        taskToCancel?.cancel()
        logI("requestId:$requestId is cancelled!")
    }

    fun onTaskFinish(task: VBTransportTask) {
        tasks.removeIfSame(task.requestId, task)
        logI("requestId:${task.requestId} is removed!")
    }

    private fun logI(content: String) {
        VBPBLog.i(VBPBLog.HMTRANSPORTIMPL, content)
    }
}

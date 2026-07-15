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

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

object VBTransportManager {

    // 任务状态管理Map
    private val lock = SynchronizedObject()
    private val taskMap = mutableMapOf<Int, VBTransportTask>()

    fun getTask(requestId: Int): VBTransportTask? = synchronized(lock) { taskMap[requestId] }

    fun onTaskBegin(task: VBTransportTask) {
        logI("${task.logTag} onTaskBegin() requestId :${task.requestId}")
        synchronized(lock) { taskMap[task.requestId] = task }
    }

    fun getState(requestId: Int): VBTransportState {
        val task = synchronized(lock) { taskMap[requestId] }
        if (task == null) {
            logI("requestId:$requestId don't exist！")
            return VBTransportState.Unknown
        }
        return task.getState()
    }

    fun cancel(requestId: Int) {
        val task = synchronized(lock) { taskMap.remove(requestId) }
        task?.cancel()
        logI("requestId:$requestId is cancelled!")
    }

    fun onTaskFinish(requestId: Int) {
        synchronized(lock) { taskMap.remove(requestId) }
        logI("requestId:$requestId is removed!")
    }

    private fun logI(content: String) {
        VBPBLog.i(VBPBLog.HMTRANSPORTIMPL, content)
    }
}

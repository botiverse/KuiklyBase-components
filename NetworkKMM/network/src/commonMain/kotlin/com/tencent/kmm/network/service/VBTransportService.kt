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
package com.tencent.kmm.network.service

import com.tencent.kmm.network.export.VBTransportBaseRequest
import com.tencent.kmm.network.export.VBTransportBytesCompletionHandler
import com.tencent.kmm.network.export.VBTransportBytesRequest
import com.tencent.kmm.network.export.VBTransportBytesResponse
import com.tencent.kmm.network.export.VBTransportGetHandler
import com.tencent.kmm.network.export.VBTransportGetRequest
import com.tencent.kmm.network.export.VBTransportGetResponse
import com.tencent.kmm.network.export.VBTransportPostHandler
import com.tencent.kmm.network.export.VBTransportPostRequest
import com.tencent.kmm.network.export.VBTransportPostResponse
import com.tencent.kmm.network.export.VBTransportHandler
import com.tencent.kmm.network.export.VBTransportRequest
import com.tencent.kmm.network.export.VBTransportResponse
import com.tencent.kmm.network.export.VBTransportResultCode
import com.tencent.kmm.network.export.VBTransportStringCompletionHandler
import com.tencent.kmm.network.export.VBTransportStringRequest
import com.tencent.kmm.network.export.VBTransportStringResponse
import com.tencent.kmm.network.internal.VBPBLog
import com.tencent.kmm.network.internal.transportLaunch
import com.tencent.kmm.network.internal.VBPBRequestIdGenerator
import com.tencent.kmm.network.internal.VBTransportManager
import com.tencent.kmm.network.internal.VBTransportState
import com.tencent.kmm.network.internal.VBTransportTask
import com.tencent.kmm.network.internal.platform.platformOwnsRequestHardDeadline
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlin.time.TimeSource

object VBTransportService {

    // SupervisorJob: one request's uncaught failure must not cancel the scope
    // (which would silently kill every in-flight AND future request). The
    // handler turns escaped exceptions into logs instead of process crashes on
    // Kotlin/Native (upstream issue #31: app crashed outright when offline).
    private val networkScope: CoroutineScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.IO +
                CoroutineExceptionHandler { _, throwable ->
                    VBPBLog.e("VBTransportService", "uncaught transport exception: ${throwable.message ?: throwable::class.simpleName}")
                }
        )
    private val taskManager = VBTransportManager

    private fun beginRequestTask(
        request: VBTransportBaseRequest,
        send: (VBTransportTask) -> Unit,
    ) {
        val task = VBTransportTask(request.requestId, request.useCurl, request.logTag, taskManager)
        val begin = {
            taskManager.onTaskBegin(task)
            send(task)
        }
        if (platformOwnsRequestHardDeadline) {
            // Android must arm its hard deadline before an IO dispatcher or
            // common coroutine handoff can delay the request. The platform
            // transport remains asynchronous after this synchronous entry.
            begin()
        } else {
            networkScope.transportLaunch { begin() }
        }
    }

    private fun prepareRequest(request: VBTransportBaseRequest) {
        request.serviceRequestStartMark = TimeSource.Monotonic.markNow()
        do {
            request.requestId = VBPBRequestIdGenerator.getRequestId()
        } while (!taskManager.onTaskPrepared(request.requestId))
    }

    // 发送字节数组Post类型网络请求
    fun sendBytesRequest(
        request: VBTransportBytesRequest,
        handler: VBTransportBytesCompletionHandler?,
    ) {
        prepareRequest(request)
        beginRequestTask(request) { task ->
            task.sendBytesRequest(request) { response ->
                if (task.trySetDone()) {
                    handler?.let { it(response) }
                }
            }
        }
        startTimeoutCheckTask(request.totalTimeout, request.requestId, request) {
            val timeoutResponse = VBTransportBytesResponse().apply {
                this.request = request
                this.errorCode = VBTransportResultCode.CODE_FORCE_TIMEOUT
                this.errorMessage = "Request timed out"
            }
            handler?.invoke(timeoutResponse)
        }
    }

    // 发送字符类型Get类型网络请求
    fun sendStringRequest(
        request: VBTransportStringRequest,
        handler: VBTransportStringCompletionHandler?,
    ) {
        prepareRequest(request)
        beginRequestTask(request) { task ->
            task.sendStringRequest(request) { response ->
                if (task.trySetDone()) {
                    handler?.let { it(response) }
                }
            }
        }
        startTimeoutCheckTask(request.totalTimeout, request.requestId, request) {
            val timeoutResponse = VBTransportStringResponse().apply {
                this.request = request
                this.errorCode = VBTransportResultCode.CODE_FORCE_TIMEOUT
                this.errorMessage = "Request timed out"
            }
            handler?.invoke(timeoutResponse)
        }
    }

    fun sendPostRequest(
        request: VBTransportPostRequest,
        handler: VBTransportPostHandler?
    ) {
        prepareRequest(request)
        beginRequestTask(request) { task ->
            task.sendPostRequest(request) { response ->
                if (task.trySetDone()) {
                    handler?.let { it(response) }
                }
            }
        }
        startTimeoutCheckTask(request.totalTimeout, request.requestId, request) {
            val timeoutResponse = VBTransportPostResponse().apply {
                this.request = request
                this.errorCode = VBTransportResultCode.CODE_FORCE_TIMEOUT
                this.errorMessage = "Request timed out"
            }
            handler?.invoke(timeoutResponse)
        }
    }

    fun sendGetRequest(
        request: VBTransportGetRequest,
        handler: VBTransportGetHandler?
    ) {
        prepareRequest(request)
        beginRequestTask(request) { task ->
            task.sendGetRequest(request) { response ->
                if (task.trySetDone()) {
                    handler?.let { it(response) }
                }
            }
        }
        startTimeoutCheckTask(request.totalTimeout, request.requestId, request) {
            val timeoutResponse = VBTransportGetResponse().apply {
                this.request = request
                this.errorCode = VBTransportResultCode.CODE_FORCE_TIMEOUT
                this.errorMessage = "Request timed out"
            }
            handler?.invoke(timeoutResponse)
        }
    }

    fun sendRequest(
        request: VBTransportRequest,
        handler: VBTransportHandler?
    ) {
        prepareRequest(request)
        beginRequestTask(request) { task ->
            task.sendRequest(request) { response ->
                if (task.trySetDone()) {
                    handler?.let { it(response) }
                }
            }
        }
        startTimeoutCheckTask(request.totalTimeout, request.requestId, request) {
            val timeoutResponse = VBTransportResponse().apply {
                this.request = request
                this.errorCode = VBTransportResultCode.CODE_FORCE_TIMEOUT
                this.errorMessage = "Request timed out"
            }
            handler?.invoke(timeoutResponse)
        }
    }

    // issue #8: streaming upload. The body is pushed by [writeBody]; like
    // streamRequest, no force-timeout task is scheduled — a large upload is
    // expected to outlive totalTimeout.
    fun uploadStream(
        request: VBTransportRequest,
        contentLength: Long?,
        writeBody: suspend (com.tencent.kmm.network.export.NetworkByteStreamSink) -> Unit,
        handler: VBTransportHandler?
    ) {
        prepareRequest(request)
        networkScope.transportLaunch {
            val task = VBTransportTask(request.requestId, request.useCurl, request.logTag, taskManager)
            taskManager.onTaskBegin(task)
            task.uploadStreamRequest(request, contentLength, writeBody) { response ->
                if (task.trySetDone()) {
                    handler?.let { it(response) }
                }
            }
        }
    }

    // fork #8: streaming download. Chunks are delivered via [onChunk] as they
    // arrive; [handler] receives the body-less completion. No force-timeout
    // task is scheduled — a long stream is expected to outlive totalTimeout,
    // whose semantics ("whole request done") do not fit an open download.
    fun streamRequest(
        request: VBTransportRequest,
        onResponseStart: (statusCode: Int, headers: Map<String, List<String>>) -> Unit,
        onChunk: (chunk: ByteArray) -> Unit,
        handler: VBTransportHandler?
    ) {
        prepareRequest(request)
        networkScope.transportLaunch {
            val task = VBTransportTask(request.requestId, request.useCurl, request.logTag, taskManager)
            taskManager.onTaskBegin(task)
            task.streamRequest(request, onResponseStart, onChunk) { response ->
                if (task.trySetDone()) {
                    handler?.let { it(response) }
                }
            }
        }
    }

    private fun startTimeoutCheckTask(
        timeout: Long,
        requestId: Int,
        request: VBTransportBaseRequest,
        handlerBlock: () -> Unit
    ) {
        if (platformOwnsRequestHardDeadline) return
        networkScope.transportLaunch {
            VBPBLog.i(
                VBPBLog.TASK_MANAGER, "${request.logTag} execute() coroutine " +
                    "totalTimeout: ${timeout}, requestId: $requestId")
            if (timeout <= 0) return@transportLaunch
            delay(timeout)
            taskManager.getTask(requestId)?.let { task ->
                if (task.trySetDone()) {
                    task.cancelTransport()
                    taskManager.onTaskFinish(task)
                    handlerBlock()
                }
            }
        }
    }

    /**
     * 获取http请求状态
     * @param requestId 请求编号
     * @return 请求状态
     *         Create - 已创建
     *         Running - 正在执行
     *         Canceled - 已取消
     *         Done - 已完成
     *         Unknown - 已取消或已完成后删除
     */
    fun getState(requestId: Int): VBTransportState = taskManager.getState(requestId)

    fun cancel(requestId: Int) {
        taskManager.cancel(requestId)
    }
}

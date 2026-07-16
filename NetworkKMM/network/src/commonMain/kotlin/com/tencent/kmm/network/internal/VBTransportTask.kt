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

import com.tencent.kmm.network.export.VBTransportBaseRequest
import com.tencent.kmm.network.export.VBTransportBaseResponse
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
import com.tencent.kmm.network.internal.platform.getIVBTransportService
import kotlinx.atomicfu.atomic

class VBTransportTask(
    val requestId: Int,
    val useCurl: Boolean,
    val logTag: String,
    private val taskManager: VBTransportManager
) {

    private val state = atomic(VBTransportState.Create)
    private val streamGate = atomic<StreamCallbackGate<VBTransportResponse>?>(null)
    private val platformCallbackHandoff = atomic<PlatformTerminalHandoff<VBTransportResponse>?>(null)
    private val platformEntryPhase = atomic(PLATFORM_PHASE_NONE)
    private val platformPrepareInProgress = atomic(false)
    private val bufferedTerminalLock = kotlinx.atomicfu.locks.SynchronizedObject()
    private var bufferedCompletionClaimed = false
    internal var platformPrepare: (Int) -> Boolean = { requestId ->
        getIVBTransportService().prepareRequest(requestId)
    }
    internal var platformAbortPrepared: (Int) -> Unit = { requestId ->
        getIVBTransportService().abortPreparedRequest(requestId)
    }
    internal var platformRequestStream: (
        VBTransportRequest,
        (Int, Map<String, List<String>>) -> Unit,
        (ByteArray) -> Unit,
        (VBTransportResponse) -> Unit,
    ) -> Unit = { request, onStart, onChunk, onComplete ->
        getIVBTransportService().requestStream(request, onStart, onChunk, onComplete)
    }
    internal var platformRequestUpload: (
        VBTransportRequest,
        Long?,
        suspend (com.tencent.kmm.network.export.NetworkByteStreamSink) -> Unit,
        (VBTransportResponse) -> Unit,
    ) -> Unit = { request, contentLength, writeBody, onComplete ->
        getIVBTransportService().requestUploadStream(request, contentLength, writeBody, onComplete)
    }
    internal var platformRequest: (VBTransportRequest, (VBTransportResponse) -> Unit) -> Unit =
        { request, onComplete -> getIVBTransportService().request(request, onComplete) }
    internal var platformCancel: (Int) -> Unit = { requestId ->
        getIVBTransportService().cancel(requestId)
    }
    internal var afterRunningPreparedForTest: (() -> Unit)? = null
    internal var afterPlatformPreparedForTest: (() -> Unit)? = null
    internal var afterStreamGateInstalledForTest: (() -> Unit)? = null

    private fun finishCanceledBeforeStart(
        response: VBTransportBaseResponse,
        handler: ((VBTransportBaseResponse) -> Unit)?,
    ) {
        response.errorCode = VBTransportResultCode.CODE_CANCELED
        response.errorMessage = "Request has been canceled"
        taskManager.onTaskFinish(this)
        handler?.invoke(response)
    }

    private fun wrapGetResponse(
        getCallback: ((getResponse: VBTransportGetResponse) -> Unit)?
    ): ((baseResponse: VBTransportBaseResponse) -> Unit)? {
        getCallback ?: return null
        return { response ->
            val res = response as? VBTransportGetResponse
            res?.let { getCallback(it) }
        }
    }

    private fun wrapPostResponse(
        postCallback: ((postResponse: VBTransportPostResponse) -> Unit)?
    ): ((baseResponse: VBTransportBaseResponse) -> Unit)? {
        postCallback ?: return null
        return { response ->
            val res = response as? VBTransportPostResponse
            res?.let { postCallback(it) }
        }
    }

    private fun wrapStringResponse(
        stringCallback: ((stringResponse: VBTransportStringResponse) -> Unit)?
    ): ((baseResponse: VBTransportBaseResponse) -> Unit)? {
        stringCallback ?: return null
        return { response ->
            val res = response as? VBTransportStringResponse
            res?.let { stringCallback(it) }
        }
    }

    private fun wrapBytesResponse(
        bytesCallback: ((bytesResponse: VBTransportBytesResponse) -> Unit)?
    ): ((baseResponse: VBTransportBaseResponse) -> Unit)? {
        bytesCallback ?: return null
        return { response ->
            val res = response as? VBTransportBytesResponse
            res?.let { bytesCallback(it) }
        }
    }

    private fun wrapResponse(
        callback: ((response: VBTransportResponse) -> Unit)?
    ): ((baseResponse: VBTransportBaseResponse) -> Unit)? {
        callback ?: return null
        return { response ->
            val res = response as? VBTransportResponse
            res?.let { callback(it) }
        }
    }

    private fun handleResponse(
        request: VBTransportBaseRequest,
        response: VBTransportBaseResponse,
        handler: ((response: VBTransportBaseResponse) -> Unit)?
    ) {
        if (streamGate.value == null) {
            kotlinx.atomicfu.locks.synchronized(bufferedTerminalLock) {
                bufferedCompletionClaimed = true
            }
        }
        if (isCanceledOrRemoved()) {
            logI("execute() request task is canceled")
            response.errorCode = VBTransportResultCode.CODE_CANCELED
            response.errorMessage = "Request has been canceled"
            logI("execute() invoke failHandler，task has been canceled")
        }
        // Platform handoff/cancel owns native cleanup. Release the common
        // identity before making terminal visible so same-id replacement in a
        // terminal handler cannot collide with the old common task.
        taskManager.onTaskFinish(this)
        handler?.invoke(response) ?: logI("handler is null!")
    }

    fun sendBytesRequest(
        request: VBTransportBytesRequest,
        handler: VBTransportBytesCompletionHandler?,
    ) {
        if (!trySetRunning()) {
            val response = VBTransportBytesResponse()
            logI("execute() request task is canceled")
            finishCanceledBeforeStart(response, wrapBytesResponse(handler))
            return
        }
        enterBufferedPlatform(
            start = { completion -> getIVBTransportService().sendBytesRequest(request, completion) },
            onComplete = { response ->
                handleResponse(request, response, wrapBytesResponse(handler))
            },
        )
    }

    private fun isCanceledOrRemoved(): Boolean =
        state.value == VBTransportState.Canceled || state.value == VBTransportState.Unknown

    // 发送字符类型Get类型网络请求
    fun sendStringRequest(
        request: VBTransportStringRequest,
        handler: VBTransportStringCompletionHandler?,
    ) {
        if (!trySetRunning()) {
            val response = VBTransportStringResponse()
            logI("execute() request task is canceled")
            finishCanceledBeforeStart(response, wrapStringResponse(handler))
            return
        }
        enterBufferedPlatform(
            start = { completion -> getIVBTransportService().sendStringRequest(request, completion) },
            onComplete = { response ->
                handleResponse(request, response, wrapStringResponse(handler))
            },
        )
    }

    fun sendPostRequest(
        request: VBTransportPostRequest,
        handler: VBTransportPostHandler?
    ) {
        if (!trySetRunning()) {
            val response = VBTransportPostResponse()
            logI("execute() request task is canceled before")
            finishCanceledBeforeStart(response, wrapPostResponse(handler))
            return
        }
        enterBufferedPlatform(
            start = { completion -> getIVBTransportService().post(request, completion) },
            onComplete = { response ->
                handleResponse(request, response, wrapPostResponse(handler))
            },
        )
    }

    fun sendGetRequest(
        request: VBTransportGetRequest,
        handler: VBTransportGetHandler?
    ) {
        if (!trySetRunning()) {
            val response = VBTransportGetResponse()
            logI("execute() request task is canceled before")
            finishCanceledBeforeStart(response, wrapGetResponse(handler))
            return
        }
        enterBufferedPlatform(
            start = { completion -> getIVBTransportService().get(request, completion) },
            onComplete = { response ->
                handleResponse(request, response, wrapGetResponse(handler))
            },
        )
    }

    fun sendRequest(
        request: VBTransportRequest,
        handler: VBTransportHandler?
    ) {
        if (!trySetRunning()) {
            val response = VBTransportResponse()
            logI("execute() request task is canceled before")
            finishCanceledBeforeStart(response, wrapResponse(handler))
            return
        }
        enterBufferedPlatform(
            start = { completion -> platformRequest(request, completion) },
            onComplete = { response ->
                handleResponse(request, response, wrapResponse(handler))
            },
        )
    }

    // fork #8: streaming download — response headers via [onResponseStart] as
    // soon as they are ready, body chunk-by-chunk via [onChunk], [handler]
    // receives the body-less completion (status/headers/error).
    fun streamRequest(
        request: VBTransportRequest,
        onResponseStart: (statusCode: Int, headers: Map<String, List<String>>) -> Unit,
        onChunk: (chunk: ByteArray) -> Unit,
        handler: VBTransportHandler?
    ) {
        if (!trySetRunning()) {
            val response = VBTransportResponse()
            logI("streamRequest() task is canceled before")
            finishCanceledBeforeStart(response, wrapResponse(handler))
            return
        }
        afterRunningPreparedForTest?.invoke()
        val terminalHandoff = PlatformTerminalHandoff<VBTransportResponse>()
        lateinit var gate: StreamCallbackGate<VBTransportResponse>
        gate = StreamCallbackGate(
            onStart = onResponseStart,
            onChunk = onChunk,
            onComplete = { response ->
                response.request = request
                state.compareAndSet(VBTransportState.Running, VBTransportState.Done)
                handleResponse(request, response, wrapResponse(handler))
            },
            failureCompletion = { throwable ->
                VBTransportResponse().apply {
                    this.request = request
                    this.errorCode = VBTransportResultCode.CODE_NETWORK_ERROR
                    this.errorMessage = "stream callback failed: ${throwable.message ?: throwable::class.simpleName}"
                }
            },
            cancelTransport = { cancelTransport() },
            callbackFailureCompletion = { response ->
                terminalHandoff.platformComplete(response, gate::complete)
            },
            onCallbackFailure = { throwable ->
                logI("stream callback failed: ${throwable.message ?: throwable::class.simpleName}")
            }
        )
        platformCallbackHandoff.value = terminalHandoff
        streamGate.value = gate
        afterStreamGateInstalledForTest?.invoke()
        if (state.value == VBTransportState.Canceled) {
            // cancel() recorded a pre-publish platform cancellation, but this
            // request will never enter platform code to consume it.
            abortUnusedPlatformReservation()
            taskManager.onTaskFinish(this)
            gate.complete(cancelledStreamResponse(request))
            return
        }
        if (!platformEntryPhase.compareAndSet(PLATFORM_PHASE_RESERVED, PLATFORM_PHASE_ENTERING)) {
            return
        }
        platformRequestStream(
            request,
            { status, headers -> terminalHandoff.businessCallback { gate.responseStart(status, headers) } },
            { chunk -> terminalHandoff.businessCallback { gate.chunk(chunk) } },
        ) { response ->
            terminalHandoff.platformComplete(response, gate::complete)
        }
        completePlatformEntryHandoff(gate, request, terminalHandoff)
    }

    // issue #8: streaming upload — body pushed by [writeBody] into the
    // transport sink; buffering (or true streaming) is the transport's choice.
    fun uploadStreamRequest(
        request: VBTransportRequest,
        contentLength: Long?,
        writeBody: suspend (com.tencent.kmm.network.export.NetworkByteStreamSink) -> Unit,
        handler: VBTransportHandler?
    ) {
        if (!trySetRunning()) {
            val response = VBTransportResponse()
            logI("uploadStreamRequest() task is canceled before")
            finishCanceledBeforeStart(response, wrapResponse(handler))
            return
        }
        afterRunningPreparedForTest?.invoke()
        val gate = StreamCallbackGate(
            onStart = { _, _ -> },
            onChunk = {},
            onComplete = { response ->
                response.request = request
                state.compareAndSet(VBTransportState.Running, VBTransportState.Done)
                handleResponse(request, response, wrapResponse(handler))
            },
            failureCompletion = { throwable ->
                VBTransportResponse().apply {
                    this.request = request
                    this.errorCode = VBTransportResultCode.CODE_NETWORK_ERROR
                    this.errorMessage = "upload callback failed: ${throwable.message ?: throwable::class.simpleName}"
                }
            },
            cancelTransport = { cancelTransport() },
        )
        val terminalHandoff = PlatformTerminalHandoff<VBTransportResponse>()
        platformCallbackHandoff.value = terminalHandoff
        streamGate.value = gate
        afterStreamGateInstalledForTest?.invoke()
        if (state.value == VBTransportState.Canceled) {
            abortUnusedPlatformReservation()
            taskManager.onTaskFinish(this)
            gate.complete(cancelledStreamResponse(request))
            return
        }
        if (!platformEntryPhase.compareAndSet(PLATFORM_PHASE_RESERVED, PLATFORM_PHASE_ENTERING)) {
            return
        }
        platformRequestUpload(request, contentLength, writeBody) { response ->
            terminalHandoff.platformComplete(response, gate::complete)
        }
        completePlatformEntryHandoff(gate, request, terminalHandoff)
    }

    fun getState(): VBTransportState = state.value

    private fun trySetRunning(): Boolean {
        if (!state.compareAndSet(VBTransportState.Create, VBTransportState.Running)) {
            return false
        }
        platformPrepareInProgress.value = true
        if (!platformPrepare(requestId)) {
            platformPrepareInProgress.value = false
            state.compareAndSet(VBTransportState.Running, VBTransportState.Canceled)
            return false
        }
        afterPlatformPreparedForTest?.invoke()
        platformEntryPhase.value = PLATFORM_PHASE_RESERVED
        platformPrepareInProgress.value = false
        if (state.value != VBTransportState.Running) {
            abortUnusedPlatformReservation()
            return false
        }
        return true
    }

    fun setState(state: VBTransportState) {
        this.state.value = state
    }

    fun trySetDone(): Boolean {
        while (true) {
            val current = state.value
            if (
                current == VBTransportState.Done ||
                current == VBTransportState.Canceled ||
                current == VBTransportState.Unknown
            ) {
                return false
            }
            if (state.compareAndSet(current, VBTransportState.Done)) {
                return true
            }
        }
    }

    fun cancel() {
        while (true) {
            val current = state.value
            if (
                current == VBTransportState.Done ||
                current == VBTransportState.Canceled ||
                current == VBTransportState.Unknown
            ) {
                return
            }
            val gate = streamGate.value
            if (current == VBTransportState.Running && gate != null) {
                val abortedBeforeEntry = abortUnusedPlatformReservation()
                if (!abortedBeforeEntry && platformEntryPhase.value == PLATFORM_PHASE_ABORTING) {
                    return
                }
                if (platformEntryPhase.value == PLATFORM_PHASE_CANCEL_PENDING) return
                if (platformEntryPhase.compareAndSet(PLATFORM_PHASE_ENTERING, PLATFORM_PHASE_CANCEL_PENDING)) {
                    // Publish cancellation to the platform reservation now so
                    // register/handoff cannot start work. Terminal delivery
                    // waits until the synchronous handoff returns, preventing
                    // same-id reuse from being consumed by the old request.
                    platformCallbackHandoff.value?.cancelBusinessCallbacks()
                    cancelPlatformContained()
                    return
                }
                gate.complete(cancelledStreamResponse()) {
                    state.compareAndSet(VBTransportState.Running, VBTransportState.Canceled)
                    if (platformEntryPhase.value == PLATFORM_PHASE_ENTERED) {
                        cancelPlatformContained()
                    }
                    taskManager.onTaskFinish(this)
                }
                return
            }
            if (gate == null) {
                kotlinx.atomicfu.locks.synchronized(bufferedTerminalLock) {
                    if (bufferedCompletionClaimed) return
                    cancelWithoutStreamGate()
                }
                return
            }
        }
    }

    private fun cancelWithoutStreamGate() {
        val current = state.value
        if (
            current == VBTransportState.Done ||
            current == VBTransportState.Canceled ||
            current == VBTransportState.Unknown
        ) return
        if (current == VBTransportState.Running && abortUnusedPlatformReservation()) {
            state.compareAndSet(VBTransportState.Running, VBTransportState.Canceled)
            taskManager.onTaskFinish(this)
            return
        }
        if (current == VBTransportState.Running && platformEntryPhase.value == PLATFORM_PHASE_CANCEL_PENDING) return
        if (
            current == VBTransportState.Running &&
            platformEntryPhase.compareAndSet(PLATFORM_PHASE_ENTERING, PLATFORM_PHASE_CANCEL_PENDING)
        ) {
            state.compareAndSet(VBTransportState.Running, VBTransportState.Canceled)
            cancelPlatformContained()
            return
        }
        if (state.compareAndSet(current, VBTransportState.Canceled) && current == VBTransportState.Running) {
            cancelPlatformContained()
            if (!platformPrepareInProgress.value) taskManager.onTaskFinish(this)
        }
    }

    private fun cancelledStreamResponse(request: VBTransportRequest? = null) =
        VBTransportResponse().apply {
            if (request != null) {
                this.request = request
            }
            this.errorCode = VBTransportResultCode.CODE_CANCELED
            this.errorMessage = "Request has been canceled"
        }

    fun cancelTransport() {
        cancelPlatformContained()
    }

    private fun cancelPlatformContained() {
        runCatching { platformCancel(requestId) }.onFailure { throwable ->
            logI("platform cancel failed: ${throwable.message ?: throwable::class.simpleName}")
        }
    }

    private fun abortUnusedPlatformReservation(): Boolean {
        if (!platformEntryPhase.compareAndSet(PLATFORM_PHASE_RESERVED, PLATFORM_PHASE_ABORTING)) {
            return false
        }
        try {
            platformAbortPrepared(requestId)
            platformEntryPhase.value = PLATFORM_PHASE_ABORTED
        } catch (throwable: Throwable) {
            val retryFailure = runCatching { platformAbortPrepared(requestId) }.exceptionOrNull()
            platformEntryPhase.value = if (retryFailure == null) {
                PLATFORM_PHASE_ABORTED
            } else {
                PLATFORM_PHASE_ABORT_FAILED
            }
            logI(
                "abort prepared request failed: ${throwable.message ?: throwable::class.simpleName}" +
                    (retryFailure?.let { "; retry failed: ${it.message ?: it::class.simpleName}" } ?: "; retry succeeded")
            )
        }
        return true
    }

    private fun completePlatformEntryHandoff(
        gate: StreamCallbackGate<VBTransportResponse>,
        request: VBTransportRequest,
        terminalHandoff: PlatformTerminalHandoff<VBTransportResponse>,
    ) {
        if (platformEntryPhase.compareAndSet(PLATFORM_PHASE_ENTERING, PLATFORM_PHASE_ENTERED)) {
            terminalHandoff.finish(cancelled = false, gate::complete)
            return
        }
        if (platformEntryPhase.compareAndSet(PLATFORM_PHASE_CANCEL_PENDING, PLATFORM_PHASE_ENTERED)) {
            terminalHandoff.finish(cancelled = true, gate::complete)
            gate.complete(cancelledStreamResponse(request)) {
                state.compareAndSet(VBTransportState.Running, VBTransportState.Canceled)
                taskManager.onTaskFinish(this)
            }
        }
    }

    private fun <C : VBTransportBaseResponse> enterBufferedPlatform(
        start: ((C) -> Unit) -> Unit,
        onComplete: (C) -> Unit,
    ) {
        if (!platformEntryPhase.compareAndSet(PLATFORM_PHASE_RESERVED, PLATFORM_PHASE_ENTERING)) return
        val terminalHandoff = PlatformTerminalHandoff<C>()
        start { response -> terminalHandoff.platformComplete(response, onComplete) }
        if (platformEntryPhase.compareAndSet(PLATFORM_PHASE_ENTERING, PLATFORM_PHASE_ENTERED)) {
            terminalHandoff.finish(cancelled = false, onComplete)
            return
        }
        if (platformEntryPhase.compareAndSet(PLATFORM_PHASE_CANCEL_PENDING, PLATFORM_PHASE_ENTERED)) {
            terminalHandoff.finish(cancelled = true, onComplete)
            taskManager.onTaskFinish(this)
        }
    }

    private fun logI(content: String) {
        VBPBLog.i(VBPBLog.TASK, "$logTag $content")
    }

}

private class PlatformTerminalHandoff<C> : kotlinx.atomicfu.locks.SynchronizedObject() {
    private val businessGate = InflightCallbackGate()
    private var finished = false
    private var cancelled = false
    private var pending: C? = null

    fun businessCallback(action: () -> Unit) {
        businessGate.runIfOpen(action)
    }

    fun cancelBusinessCallbacks() {
        businessGate.closeAndRun {}
    }

    fun platformComplete(completion: C, deliver: (C) -> Unit) {
        val deliverNow = kotlinx.atomicfu.locks.synchronized(this) {
            if (!finished) {
                if (pending == null) pending = completion
                false
            } else {
                !cancelled
            }
        }
        if (deliverNow) deliver(completion)
    }

    fun finish(cancelled: Boolean, deliver: (C) -> Unit) {
        if (cancelled) cancelBusinessCallbacks()
        val pendingCompletion = kotlinx.atomicfu.locks.synchronized(this) {
            this.finished = true
            this.cancelled = cancelled
            pending.also { pending = null }.takeUnless { cancelled }
        }
        pendingCompletion?.let(deliver)
    }
}

private const val PLATFORM_PHASE_NONE = 0
private const val PLATFORM_PHASE_RESERVED = 1
private const val PLATFORM_PHASE_ENTERED = 2
private const val PLATFORM_PHASE_ABORTING = 3
private const val PLATFORM_PHASE_ABORTED = 4
private const val PLATFORM_PHASE_ABORT_FAILED = 5
private const val PLATFORM_PHASE_ENTERING = 6
private const val PLATFORM_PHASE_CANCEL_PENDING = 7

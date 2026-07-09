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

import com.tencent.kmm.network.export.NetworkEngineCapabilities
import com.tencent.kmm.network.export.NetworkErrorKind
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.VBTransportElapseStatistics
import com.tencent.kmm.network.internal.platform.platformDefaultNetworkTransportEngine
import com.tencent.kmm.network.internal.platform.resolvePlatformNetworkEngine

/** Transport implementation selected below the public request API. */
enum class NetworkTransportEngine(val externalValue: String) {
    KTOR("ktor"),
    CURL("curl");

    companion object {
        fun fromExternalValue(value: String?): NetworkTransportEngine? {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.externalValue == normalized }
        }
    }
}

/**
 * Typed engine request consumed by [NetworkClient].
 *
 * A host can return this from [NetworkClientConfig.engineSelector] on every
 * request. This keeps remote config parsing at the host boundary while making
 * gray rollout and rollback take effect without leaking raw strings into
 * business call sites.
 */
data class NetworkEngineSelection(
    val requestedEngine: NetworkTransportEngine? = null,
    val allowedEngines: Set<NetworkTransportEngine> = NetworkTransportEngine.entries.toSet(),
    val forcePlatformDefault: Boolean = false,
    val externalValueValid: Boolean = true
) {
    companion object {
        /**
         * Maps external/remote values once at the integration boundary.
         * Unknown values fail closed to the current platform default.
         */
        fun fromExternalConfig(
            engine: String?,
            curlEnabled: Boolean = false,
            forcePlatformDefault: Boolean = false
        ): NetworkEngineSelection {
            val requested = NetworkTransportEngine.fromExternalValue(engine)
            val hasExternalValue = !engine.isNullOrBlank()
            val allowed = if (curlEnabled) {
                NetworkTransportEngine.entries.toSet()
            } else {
                setOf(NetworkTransportEngine.KTOR)
            }
            return NetworkEngineSelection(
                requestedEngine = requested,
                allowedEngines = allowed,
                forcePlatformDefault = forcePlatformDefault,
                externalValueValid = !hasExternalValue || requested != null
            )
        }
    }
}

enum class NetworkEngineSelectionReason {
    PLATFORM_DEFAULT,
    REQUESTED,
    REMOTE_ROLLBACK,
    DISALLOWED,
    UNAVAILABLE,
    INVALID_EXTERNAL_VALUE,
    SELECTOR_ERROR
}

data class NetworkEngineSelectionDiagnostics(
    val requestedEngine: NetworkTransportEngine?,
    val selectedEngine: NetworkTransportEngine,
    val platformDefaultEngine: NetworkTransportEngine,
    val reason: NetworkEngineSelectionReason,
    val capabilities: NetworkEngineCapabilities
)

data class NetworkEngineExecutionDiagnostics(
    val selection: NetworkEngineSelectionDiagnostics,
    val statusCode: Int?,
    val errorKind: NetworkErrorKind?,
    val timing: VBTransportElapseStatistics
)

/** Diagnostics callbacks run on the request dispatcher and must stay non-blocking. */
interface NetworkEngineDiagnosticsListener {
    fun onEngineSelected(diagnostics: NetworkEngineSelectionDiagnostics) = Unit

    fun onEngineCompleted(diagnostics: NetworkEngineExecutionDiagnostics) = Unit
}

internal data class ResolvedNetworkEngine(
    val engine: NetworkEngine,
    val diagnostics: NetworkEngineSelectionDiagnostics
)

internal class RoutingNetworkEngine(
    private val selector: ((NetworkRequest) -> NetworkEngineSelection)?,
    private val diagnosticsListener: NetworkEngineDiagnosticsListener?,
    private val platformDefault: NetworkTransportEngine = platformDefaultNetworkTransportEngine,
    private val resolver: (NetworkTransportEngine) -> NetworkEngine? = ::resolvePlatformNetworkEngine
) : NetworkEngine {
    // Selection may vary by request. Expose the current platform default here;
    // the exact selected engine capabilities are emitted for every request.
    override val capabilities: NetworkEngineCapabilities
        get() = resolver(platformDefault)?.capabilities ?: NetworkEngineCapabilities()

    override suspend fun execute(request: NetworkRequest, call: NetworkCall): NetworkResponse {
        val resolved = resolve(request, call)
        emitSelectedOnce(call, resolved.diagnostics)
        val response = resolved.engine.execute(request, call)
        emitCompleted(resolved.diagnostics, response)
        return response
    }

    override suspend fun downloadStream(
        request: NetworkRequest,
        call: NetworkCall,
        onResponseStart: (statusCode: Int, contentLength: Long?, headers: Map<String, List<String>>) -> Unit,
        onChunk: (ByteArray) -> Unit
    ): NetworkResponse {
        val resolved = resolve(request, call)
        emitSelectedOnce(call, resolved.diagnostics)
        val response = resolved.engine.downloadStream(request, call, onResponseStart, onChunk)
        emitCompleted(resolved.diagnostics, response)
        return response
    }

    private fun resolve(request: NetworkRequest, call: NetworkCall): ResolvedNetworkEngine {
        return call.getOrResolveEngine {
            val selectionResult = runCatching { selector?.invoke(request) ?: NetworkEngineSelection() }
            val selection = selectionResult.getOrElse { NetworkEngineSelection() }
            resolveNetworkEngine(
                selection = selection,
                platformDefault = platformDefault,
                resolver = resolver,
                selectorFailed = selectionResult.isFailure
            )
        }
    }

    private fun emitSelectedOnce(call: NetworkCall, diagnostics: NetworkEngineSelectionDiagnostics) {
        if (call.markEngineSelectionReported()) {
            runCatching { diagnosticsListener?.onEngineSelected(diagnostics) }
        }
    }

    private fun emitCompleted(
        selection: NetworkEngineSelectionDiagnostics,
        response: NetworkResponse
    ) {
        runCatching {
            diagnosticsListener?.onEngineCompleted(
                NetworkEngineExecutionDiagnostics(
                    selection = selection,
                    statusCode = response.statusCode,
                    errorKind = response.error?.kind,
                    timing = response.timing.copy()
                )
            )
        }
    }
}

internal fun resolveNetworkEngine(
    selection: NetworkEngineSelection,
    platformDefault: NetworkTransportEngine,
    resolver: (NetworkTransportEngine) -> NetworkEngine?,
    selectorFailed: Boolean = false
): ResolvedNetworkEngine {
    val defaultEngine = checkNotNull(resolver(platformDefault)) {
        "Platform default engine $platformDefault is not registered"
    }
    val requested = selection.requestedEngine
    val reason: NetworkEngineSelectionReason
    val selected: NetworkTransportEngine
    val delegate: NetworkEngine

    when {
        selectorFailed -> {
            selected = platformDefault
            delegate = defaultEngine
            reason = NetworkEngineSelectionReason.SELECTOR_ERROR
        }
        !selection.externalValueValid -> {
            selected = platformDefault
            delegate = defaultEngine
            reason = NetworkEngineSelectionReason.INVALID_EXTERNAL_VALUE
        }
        selection.forcePlatformDefault -> {
            selected = platformDefault
            delegate = defaultEngine
            reason = NetworkEngineSelectionReason.REMOTE_ROLLBACK
        }
        requested == null || requested == platformDefault -> {
            selected = platformDefault
            delegate = defaultEngine
            reason = NetworkEngineSelectionReason.PLATFORM_DEFAULT
        }
        requested !in selection.allowedEngines -> {
            selected = platformDefault
            delegate = defaultEngine
            reason = NetworkEngineSelectionReason.DISALLOWED
        }
        else -> {
            val requestedEngine = resolver(requested)
            if (requestedEngine == null) {
                selected = platformDefault
                delegate = defaultEngine
                reason = NetworkEngineSelectionReason.UNAVAILABLE
            } else {
                selected = requested
                delegate = requestedEngine
                reason = NetworkEngineSelectionReason.REQUESTED
            }
        }
    }

    return ResolvedNetworkEngine(
        engine = delegate,
        diagnostics = NetworkEngineSelectionDiagnostics(
            requestedEngine = requested,
            selectedEngine = selected,
            platformDefaultEngine = platformDefault,
            reason = reason,
            capabilities = delegate.capabilities
        )
    )
}

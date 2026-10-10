/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.tencent.kmm.network.socketio

import com.tencent.kmm.network.export.VBTransportCurl
import com.tencent.kmm.network.internal.platform.AndroidCurlJniBridge
import com.tencent.kmm.network.internal.platform.AndroidCurlSocketIoCallback

actual object NetworkSocketIoFactory {
    actual val isSupported: Boolean
        get() = AndroidCurlJniBridge.isAvailable

    actual fun create(
        config: NetworkSocketIoConfig,
        listener: NetworkSocketIoListener,
    ): NetworkSocketIoClient =
        if (AndroidCurlJniBridge.isAvailable) {
            AndroidCurlSocketIoClient(config, listener)
        } else {
            UnsupportedNetworkSocketIoClient(listener)
        }
}

/**
 * Raft task #154: the wrapper's curl Socket.IO client on Android, through the JNI bridge. Same
 * contract as the iOS/OHOS clients, including the DoH providers ([VBTransportCurl]'s switch) the
 * native client tries only when the system resolver cannot resolve the host.
 */
private class AndroidCurlSocketIoClient(
    config: NetworkSocketIoConfig,
    listener: NetworkSocketIoListener,
) : NetworkSocketIoClient {
    private val lock = Any()
    private var handle: Long =
        AndroidCurlJniBridge.socketIoCreate(
            serverUrl = config.serverUrl,
            authJson = config.authJson,
            headers = config.headers,
            caInfoPath = config.caInfoPath,
            proxyUrl = config.proxyUrl,
            connectTimeoutMillis = config.connectTimeoutMillis,
            receivePollMillis = config.receivePollMillis,
            reconnectInitialDelayMillis = config.reconnectInitialDelayMillis,
            reconnectMaxDelayMillis = config.reconnectMaxDelayMillis,
            dohProviderIds = VBTransportCurl.dohFallbackProviders.map { it.nativeId }.toIntArray(),
            callback =
                AndroidCurlSocketIoCallback(
                    onStateBlock = { state, code, detail -> listener.onState(state.toSocketIoState(), code, detail) },
                    onEventBlock = { eventName, payloadJson -> listener.onEvent(eventName, payloadJson) },
                ),
        )

    override fun start(): Boolean {
        val nativeHandle = synchronized(lock) { handle }
        return AndroidCurlJniBridge.socketIoStart(nativeHandle)
    }

    override fun emit(eventName: String, payloadJson: String): Boolean {
        if (eventName.isBlank()) return false
        val nativeHandle = synchronized(lock) { handle }
        return AndroidCurlJniBridge.socketIoEmit(nativeHandle, eventName, payloadJson)
    }

    override fun close() {
        val nativeHandle =
            synchronized(lock) {
                val current = handle
                handle = 0L
                current
            }
        AndroidCurlJniBridge.socketIoClose(nativeHandle)
    }
}

private fun Int.toSocketIoState(): NetworkSocketIoState =
    when (this) {
        1 -> NetworkSocketIoState.CONNECTING
        2 -> NetworkSocketIoState.ENGINE_OPEN
        3 -> NetworkSocketIoState.CONNECTED
        4 -> NetworkSocketIoState.DISCONNECTED
        5 -> NetworkSocketIoState.RECONNECTING
        else -> NetworkSocketIoState.ERROR
    }

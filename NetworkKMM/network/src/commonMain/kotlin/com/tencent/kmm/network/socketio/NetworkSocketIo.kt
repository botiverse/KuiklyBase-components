/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.tencent.kmm.network.socketio

/** Default-namespace Engine.IO v4 / Socket.IO v4 client configuration. */
data class NetworkSocketIoConfig(
    val serverUrl: String,
    val authJson: String = "{}",
    val headers: Map<String, String> = emptyMap(),
    val caInfoPath: String? = null,
    val proxyUrl: String = "",
    val connectTimeoutMillis: Long = 10_000,
    val receivePollMillis: Long = 100,
    val reconnectInitialDelayMillis: Long = 500,
    val reconnectMaxDelayMillis: Long = 10_000,
)

enum class NetworkSocketIoState {
    CONNECTING,
    ENGINE_OPEN,
    CONNECTED,
    DISCONNECTED,
    RECONNECTING,
    ERROR,
}

interface NetworkSocketIoListener {
    fun onState(state: NetworkSocketIoState, code: Int, detail: String)

    /** Payload is the exact JSON value from the Socket.IO event array. */
    fun onEvent(eventName: String, payloadJson: String)
}

interface NetworkSocketIoClient {
    fun start(): Boolean

    /** V1 accepts text JSON payloads and fails closed for blank event names. */
    fun emit(eventName: String, payloadJson: String): Boolean

    fun close()
}

/**
 * Platform factory. V1 is implemented by the C++/libcurl runtime on OHOS.
 * Other targets report unsupported instead of silently selecting another
 * protocol stack.
 */
expect object NetworkSocketIoFactory {
    val isSupported: Boolean

    fun create(
        config: NetworkSocketIoConfig,
        listener: NetworkSocketIoListener,
    ): NetworkSocketIoClient
}

internal class UnsupportedNetworkSocketIoClient(
    private val listener: NetworkSocketIoListener,
) : NetworkSocketIoClient {
    override fun start(): Boolean {
        listener.onState(NetworkSocketIoState.ERROR, 0, "curl_socket_io_unsupported")
        return false
    }

    override fun emit(eventName: String, payloadJson: String): Boolean = false

    override fun close() = Unit
}

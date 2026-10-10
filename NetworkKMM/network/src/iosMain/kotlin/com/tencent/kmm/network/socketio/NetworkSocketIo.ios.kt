/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.tencent.kmm.network.socketio

import com.tencent.kmm.network.export.VBTransportCurl
import com.tencent.kmm.network.curl.native.CURL_SOCKET_IO_ABI_VERSION
import com.tencent.kmm.network.curl.native.CURL_SOCKET_IO_CONNECTED
import com.tencent.kmm.network.curl.native.CURL_SOCKET_IO_CONNECTING
import com.tencent.kmm.network.curl.native.CURL_SOCKET_IO_DISCONNECTED
import com.tencent.kmm.network.curl.native.CURL_SOCKET_IO_ENGINE_OPEN
import com.tencent.kmm.network.curl.native.CURL_SOCKET_IO_ERROR
import com.tencent.kmm.network.curl.native.CURL_SOCKET_IO_RECONNECTING
import com.tencent.kmm.network.curl.native.CloseCurlSocketIoClientV1
import com.tencent.kmm.network.curl.native.CreateCurlSocketIoClientV1
import com.tencent.kmm.network.curl.native.CurlSocketIoCallbackV1
import com.tencent.kmm.network.curl.native.CurlSocketIoConfigV1
import com.tencent.kmm.network.curl.native.DeleteCurlSocketIoClientV1
import com.tencent.kmm.network.curl.native.EmitCurlSocketIoEventV1
import com.tencent.kmm.network.curl.native.SetCurlSocketIoDohFallbackProviders
import com.tencent.kmm.network.curl.native.StartCurlSocketIoClientV1
import com.tencent.kmm.network.curl.native.StringDic
import com.tencent.kmm.network.curl.native.StringPair
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.ByteVar

@OptIn(ExperimentalForeignApi::class)
actual object NetworkSocketIoFactory {
    actual val isSupported: Boolean = true

    actual fun create(
        config: NetworkSocketIoConfig,
        listener: NetworkSocketIoListener,
    ): NetworkSocketIoClient = IosCurlSocketIoClient(config, listener)
}

@OptIn(ExperimentalForeignApi::class)
private class IosCurlSocketIoClient(
    config: NetworkSocketIoConfig,
    listener: NetworkSocketIoListener,
) : NetworkSocketIoClient {
    private val callbacks = StableRef.create(IosSocketIoCallbacks(listener))
    private var handle: COpaquePointer? = createNative(config)?.also(::applyDohFallbackProviders)
    private var closed = false

    override fun start(): Boolean =
        !closed && handle != null &&
            StartCurlSocketIoClientV1(handle, CURL_SOCKET_IO_ABI_VERSION) != 0

    override fun emit(eventName: String, payloadJson: String): Boolean {
        if (closed || eventName.isBlank()) return false
        val nativeHandle = handle ?: return false
        return EmitCurlSocketIoEventV1(
            nativeHandle,
            eventName,
            payloadJson,
            CURL_SOCKET_IO_ABI_VERSION,
        ) != 0
    }

    override fun close() {
        if (closed) return
        closed = true
        handle?.let { nativeHandle ->
            CloseCurlSocketIoClientV1(nativeHandle, CURL_SOCKET_IO_ABI_VERSION)
            DeleteCurlSocketIoClientV1(nativeHandle, CURL_SOCKET_IO_ABI_VERSION)
        }
        handle = null
        callbacks.dispose()
    }

    /**
     * Raft task #153: the same DoH switch as HTTP requests ([VBTransportCurl.setDohFallbackProviders]);
     * the native client tries these providers only when the system resolver cannot resolve the host.
     */
    private fun applyDohFallbackProviders(nativeHandle: COpaquePointer) {
        val providerIds = VBTransportCurl.dohFallbackProviders.map { it.nativeId }
        if (providerIds.isEmpty()) return
        memScoped {
            val ids = allocArray<IntVar>(providerIds.size)
            providerIds.forEachIndexed { index, id -> ids[index] = id }
            SetCurlSocketIoDohFallbackProviders(nativeHandle, ids, providerIds.size, CURL_SOCKET_IO_ABI_VERSION)
        }
    }

    private fun createNative(config: NetworkSocketIoConfig): COpaquePointer? = memScoped {
        val nativePairs = allocArray<StringPair>(config.headers.size)
        config.headers.entries.forEachIndexed { index, entry ->
            nativePairs[index].first = entry.key.cstr.ptr
            nativePairs[index].second = entry.value.cstr.ptr
        }
        val dictionary = alloc<StringDic> {
            stringPairs = nativePairs
            size = config.headers.size
        }
        val nativeConfig = alloc<CurlSocketIoConfigV1> {
            abiVersion = CURL_SOCKET_IO_ABI_VERSION
            structSize = sizeOf<CurlSocketIoConfigV1>().toUInt()
            serverUrl = config.serverUrl.cstr.ptr
            authJson = config.authJson.cstr.ptr
            headers = dictionary.ptr
            caInfoPath = config.caInfoPath?.cstr?.ptr
            proxyUrl = config.proxyUrl.cstr.ptr
            connectTimeoutMs = config.connectTimeoutMillis
            receivePollMs = config.receivePollMillis
            reconnectInitialDelayMs = config.reconnectInitialDelayMillis
            reconnectMaxDelayMs = config.reconnectMaxDelayMillis
        }
        val nativeCallback = alloc<CurlSocketIoCallbackV1> {
            callbackRef = callbacks.asCPointer()
            onState = staticCFunction(::onNativeState)
            onEvent = staticCFunction(::onNativeEvent)
        }
        CreateCurlSocketIoClientV1(
            nativeConfig.ptr,
            sizeOf<CurlSocketIoConfigV1>().toULong(),
            CURL_SOCKET_IO_ABI_VERSION,
            nativeCallback.ptr,
        )
    }
}

private class IosSocketIoCallbacks(val listener: NetworkSocketIoListener)

@OptIn(ExperimentalForeignApi::class)
private fun onNativeState(
    callbackRef: COpaquePointer?,
    state: Int,
    code: Int,
    detail: CPointer<ByteVar>?,
) {
    val callbacks = callbackRef?.asStableRef<IosSocketIoCallbacks>()?.get() ?: return
    val mapped = when (state) {
        CURL_SOCKET_IO_CONNECTING.toInt() -> NetworkSocketIoState.CONNECTING
        CURL_SOCKET_IO_ENGINE_OPEN.toInt() -> NetworkSocketIoState.ENGINE_OPEN
        CURL_SOCKET_IO_CONNECTED.toInt() -> NetworkSocketIoState.CONNECTED
        CURL_SOCKET_IO_DISCONNECTED.toInt() -> NetworkSocketIoState.DISCONNECTED
        CURL_SOCKET_IO_RECONNECTING.toInt() -> NetworkSocketIoState.RECONNECTING
        CURL_SOCKET_IO_ERROR.toInt() -> NetworkSocketIoState.ERROR
        else -> NetworkSocketIoState.ERROR
    }
    callbacks.listener.onState(mapped, code, detail?.toKString().orEmpty())
}

@OptIn(ExperimentalForeignApi::class)
private fun onNativeEvent(
    callbackRef: COpaquePointer?,
    eventName: CPointer<ByteVar>?,
    payloadJson: CPointer<ByteVar>?,
) {
    val callbacks = callbackRef?.asStableRef<IosSocketIoCallbacks>()?.get() ?: return
    callbacks.listener.onEvent(eventName?.toKString().orEmpty(), payloadJson?.toKString().orEmpty())
}

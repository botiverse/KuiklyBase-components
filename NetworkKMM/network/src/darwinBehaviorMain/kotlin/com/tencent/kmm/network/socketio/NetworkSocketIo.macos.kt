/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.tencent.kmm.network.socketio

// Darwin behavior-test lane (macosArm64): no curl cinterop, so no curl Socket.IO client.
actual object NetworkSocketIoFactory {
    actual val isSupported: Boolean = false

    actual fun create(
        config: NetworkSocketIoConfig,
        listener: NetworkSocketIoListener,
    ): NetworkSocketIoClient = UnsupportedNetworkSocketIoClient(listener)
}

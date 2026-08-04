/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.tencent.kmm.network.socketio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NetworkSocketIoFactoryTest {
    @Test
    fun unsupportedPlatformFailsClosedWithoutSelectingAnotherStack() {
        val states = mutableListOf<NetworkSocketIoState>()
        val client = NetworkSocketIoFactory.create(
            NetworkSocketIoConfig(serverUrl = "https://example.test"),
            object : NetworkSocketIoListener {
                override fun onState(state: NetworkSocketIoState, code: Int, detail: String) {
                    states += state
                }

                override fun onEvent(eventName: String, payloadJson: String) = Unit
            },
        )

        assertFalse(NetworkSocketIoFactory.isSupported)
        assertFalse(client.start())
        assertFalse(client.emit("message:new", "{}"))
        assertEquals(listOf(NetworkSocketIoState.ERROR), states)
    }
}

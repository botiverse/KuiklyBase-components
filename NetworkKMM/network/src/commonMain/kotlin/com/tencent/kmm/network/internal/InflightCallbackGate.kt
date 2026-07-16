/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.tencent.kmm.network.internal

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Serializes callback admission against terminal delivery without locking user code. */
internal class InflightCallbackGate {
    private val lock = SynchronizedObject()
    private var closed = false
    private var inFlight = 0
    private var deliveryStarted = false
    private var delivered = false
    private var terminalAction: (() -> Unit)? = null
    private val afterTerminal = mutableListOf<() -> Unit>()

    fun runIfOpen(action: () -> Unit) {
        val admitted = synchronized(lock) {
            if (closed) false else { inFlight++; true }
        }
        if (!admitted) return
        try {
            action()
        } finally {
            val delivery = synchronized(lock) {
                inFlight--
                takeDeliveryIfReady()
            }
            delivery?.invoke()
        }
    }

    fun closeAndRun(action: () -> Unit) {
        val delivery = synchronized(lock) {
            if (!closed) {
                closed = true
                terminalAction = action
            }
            takeDeliveryIfReady()
        }
        delivery?.invoke()
    }

    fun enqueueAfterTerminal(action: () -> Unit) {
        val invokeNow = synchronized(lock) {
            if (delivered) true else { afterTerminal += action; false }
        }
        if (invokeNow) action()
    }

    private fun takeDeliveryIfReady(): (() -> Unit)? {
        if (!closed || inFlight != 0 || deliveryStarted) return null
        val terminal = terminalAction ?: return null
        deliveryStarted = true
        terminalAction = null
        return {
            terminal()
            while (true) {
                val observers = synchronized(lock) {
                    if (afterTerminal.isEmpty()) {
                        delivered = true
                        emptyList()
                    } else {
                        afterTerminal.toList().also { afterTerminal.clear() }
                    }
                }
                if (observers.isEmpty()) break
                observers.forEach { it() }
            }
        }
    }
}

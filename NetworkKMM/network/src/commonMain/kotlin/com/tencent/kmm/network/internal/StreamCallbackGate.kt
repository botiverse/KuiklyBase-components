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

/**
 * Exactly-once, exception-containing callback state for native downloads.
 * No user callback exception may cross a C/Objective-C/JNI callback boundary.
 */
internal class StreamCallbackGate<C>(
    private val onStart: (Int, Map<String, List<String>>) -> Unit,
    private val onChunk: (ByteArray) -> Unit,
    private val onComplete: (C) -> Unit,
    private val failureCompletion: (Throwable) -> C,
    private val cancelTransport: () -> Unit,
    private val onCallbackFailure: (Throwable) -> Unit = {}
) {
    private enum class Phase { Queued, Started, Terminal }

    private val lock = SynchronizedObject()
    private val deliveryGate = InflightCallbackGate()
    private var phase = Phase.Queued
    private var callbackFailure: Throwable? = null
    internal var beforeUserCallbackForTest: (() -> Unit)? = null

    fun responseStart(statusCode: Int, headers: Map<String, List<String>>) {
        val shouldInvoke = synchronized(lock) {
            if (phase != Phase.Queued || callbackFailure != null) false
            else {
                phase = Phase.Started
                true
            }
        }
        if (!shouldInvoke) return
        deliveryGate.runIfOpen {
            beforeUserCallbackForTest?.invoke()
            invokeUserCallback { onStart(statusCode, headers) }
        }
    }

    fun chunk(bytes: ByteArray) {
        val shouldInvoke = synchronized(lock) { phase == Phase.Started && callbackFailure == null }
        if (!shouldInvoke || bytes.isEmpty()) return
        deliveryGate.runIfOpen {
            beforeUserCallbackForTest?.invoke()
            invokeUserCallback { onChunk(bytes) }
        }
    }

    fun complete(completion: C, onWinner: () -> Unit = {}): Boolean {
        val shouldComplete = synchronized(lock) {
            if (phase == Phase.Terminal) {
                false
            } else {
                phase = Phase.Terminal
                true
            }
        }
        if (!shouldComplete) return false
        onWinner()
        deliveryGate.closeAndRun {
            val delivered = synchronized(lock) {
                callbackFailure?.let(failureCompletion) ?: completion
            }
            // Terminal callback failures are contained and diagnosed, but there is
            // no later callback to convert them into. Exactly-once remains intact.
            try {
                onComplete(delivered)
            } catch (throwable: Throwable) {
                onCallbackFailure(throwable)
            }
        }
        return true
    }

    private fun invokeUserCallback(block: () -> Unit) {
        try {
            block()
        } catch (throwable: Throwable) {
            val firstFailure = synchronized(lock) {
                if (callbackFailure == null) {
                    callbackFailure = throwable
                    true
                } else {
                    false
                }
            }
            if (firstFailure) {
                onCallbackFailure(throwable)
                // Close the business callback surface first. Transport cancel
                // is a cleanup side effect and must not be the only route to a
                // terminal response (some platform jobs rethrow cancellation).
                complete(failureCompletion(throwable))
                try {
                    cancelTransport()
                } catch (cancelFailure: Throwable) {
                    onCallbackFailure(cancelFailure)
                }
            }
        }
    }
}

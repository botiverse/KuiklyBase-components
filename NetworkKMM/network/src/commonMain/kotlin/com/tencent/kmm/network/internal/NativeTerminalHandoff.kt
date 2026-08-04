/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.tencent.kmm.network.internal

import kotlinx.coroutines.Job

/**
 * Detaches native ownership synchronously, then delivers business terminal work
 * through a caller-owned asynchronous launcher.
 *
 * Native owner callbacks must return after [cleanup] without executing
 * [terminal]. Removing the registry value before cleanup/handoff also makes the
 * same logical key immediately reusable, including from the terminal callback.
 */
internal class NativeTerminalHandoff<K, V>(
    private val registry: CancellationAwareRegistry<K, V>,
    private val launch: (suspend () -> Unit) -> Job
) {
    fun detachCleanupAndDispatch(
        key: K,
        value: V,
        cleanup: () -> Unit,
        onCleanupFailure: (Throwable) -> Unit = {},
        terminal: suspend () -> Unit
    ): Job {
        registry.removeIfSame(key, value)
        try {
            cleanup()
        } catch (throwable: Throwable) {
            runCatching { onCleanupFailure(throwable) }
        }
        return launch(terminal)
    }
}

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

/** Identity-safe ownership for direct native engines sharing an id-keyed bridge. */
internal class RequestIdOwnerRegistry {
    private val lock = SynchronizedObject()
    private val owners = mutableMapOf<Int, Any>()

    fun reserve(owner: Any): Int = VBPBRequestIdGenerator.reserveRequestId { requestId ->
        synchronized(lock) {
            if (owners.containsKey(requestId)) false
            else {
                owners[requestId] = owner
                true
            }
        }
    }

    fun isOwner(requestId: Int, owner: Any): Boolean =
        synchronized(lock) { owners[requestId] === owner }

    fun cancelIfOwner(requestId: Int, owner: Any, cancel: () -> Unit): Boolean =
        synchronized(lock) {
            if (owners[requestId] !== owner) false
            else {
                cancel()
                true
            }
        }

    fun release(requestId: Int, owner: Any) {
        synchronized(lock) {
            if (owners[requestId] === owner) owners.remove(requestId)
        }
    }
}

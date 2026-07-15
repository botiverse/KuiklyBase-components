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
 * A small ownership registry whose cancellation signal survives publication races.
 *
 * A caller may cancel before the task/native handle exists. That cancellation is
 * remembered as a one-shot tombstone and consumed by the next [publish] for the
 * same key. Published-value callbacks run while the registry lock is held, so a
 * native owner can pair [cancelOrRemember] with [removeIfSame] and guarantee that
 * cancellation never dereferences a value concurrently with its deletion.
 */
internal class CancellationAwareRegistry<K, V>(
    private val rememberCancellationWithoutBegin: Boolean = false
) {
    private val lock = SynchronizedObject()
    private val values = mutableMapOf<K, V>()
    private val cancellationTombstones = mutableSetOf<K>()
    private val activeKeys = mutableSetOf<K>()

    /** Marks a new logical owner before its value is published. */
    fun begin(key: K) = synchronized(lock) {
        activeKeys += key
        if (!rememberCancellationWithoutBegin) {
            cancellationTombstones.remove(key)
        }
    }

    /** Returns false when a pre-publication cancellation tombstone was consumed. */
    fun publish(key: K, value: V): Boolean = synchronized(lock) {
        activeKeys += key
        if (values.containsKey(key)) {
            // A logical owner remains active. Never silently orphan it by
            // replacing the registry entry; the new publisher must fail
            // closed and release/cancel its own value.
            false
        } else if (cancellationTombstones.remove(key)) {
            activeKeys.remove(key)
            false
        } else {
            values[key] = value
            true
        }
    }

    fun get(key: K): V? = synchronized(lock) { values[key] }

    /**
     * Cancels a published value or records a one-shot cancellation for a value
     * that has not been published yet.
     *
     * [onPublished] executes under the ownership lock. Keep it bounded and
     * non-reentrant; this is intentional for native Cancel-vs-delete safety.
     */
    fun cancelOrRemember(
        key: K,
        removePublished: Boolean,
        onPublished: (V) -> Unit
    ): Boolean = synchronized(lock) {
        val value = values[key]
        if (value == null && (key in activeKeys || rememberCancellationWithoutBegin)) {
            cancellationTombstones += key
            false
        } else if (value == null) {
            // A late/duplicate cancel after terminal removal is not a future
            // owner's cancellation. Only begin() opens a pre-publish window.
            false
        } else {
            try {
                onPublished(value)
            } finally {
                if (removePublished && values[key] == value) {
                    values.remove(key)
                    activeKeys.remove(key)
                    cancellationTombstones.remove(key)
                }
            }
            true
        }
    }

    /**
     * Removes exactly [value]. [onRemoved] executes under the same ownership
     * lock used by [cancelOrRemember], so release/delete cannot race cancel.
     */
    fun removeIfSame(key: K, value: V, onRemoved: (V) -> Unit = {}): Boolean = synchronized(lock) {
        if (values[key] != value) {
            false
        } else {
            values.remove(key)
            activeKeys.remove(key)
            cancellationTombstones.remove(key)
            onRemoved(value)
            true
        }
    }

    fun remove(key: K): V? = synchronized(lock) {
        activeKeys.remove(key)
        cancellationTombstones.remove(key)
        values.remove(key)
    }
}

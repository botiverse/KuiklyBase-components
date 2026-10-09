/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.tencent.kmm.network.curl

import com.tencent.kmm.network.export.NetworkCurlBufferedResponsePolicy
import com.tencent.kmm.network.export.VBTransportMethod

internal fun shouldFreshRetryCurlBufferedStall(
    method: VBTransportMethod,
    bodyRepeatable: Boolean,
    policy: NetworkCurlBufferedResponsePolicy,
    cancelled: Boolean,
    remainingTimeoutMillis: Long?,
): Boolean = policy.freshRetryEnabled && bodyRepeatable && !cancelled &&
    remainingTimeoutMillis != 0L &&
    (method == VBTransportMethod.GET || method == VBTransportMethod.HEAD)

/** libcurl CURLE_COULDNT_RESOLVE_HOST. */
internal const val CURL_CODE_COULDNT_RESOLVE_HOST: Int = 6

/**
 * Raft task #153: move on to the next resolution attempt (DoH provider, or the system resolver
 * again after a preferred DoH provider failed) only when the host could not be resolved. Nothing
 * was sent to the server (no connection was made), so any method may be retried.
 */
internal fun shouldRetryCurlWithDohFallback(
    curlCode: Int,
    hasNextAttempt: Boolean,
    cancelled: Boolean,
    remainingTimeoutMillis: Long?,
): Boolean = curlCode == CURL_CODE_COULDNT_RESOLVE_HOST && hasNextAttempt && !cancelled &&
    remainingTimeoutMillis != 0L

/**
 * The resolution attempts after a first attempt that used [firstProvider] (0 = system resolver):
 * after the system, the configured DoH providers in order; after a preferred DoH provider, the
 * system resolver and then the other providers.
 */
internal fun curlDohFallbackAttempts(firstProvider: Int, configured: List<Int>): List<Int> =
    if (firstProvider == 0) configured.distinct() else listOf(0) + configured.distinct().filter { it != firstProvider }

/** How long requests go to DoH first after a system resolve failure that DoH fixed. */
internal const val CURL_DOH_PREFERRED_WINDOW_MILLIS: Long = 60_000L

/**
 * Process-wide memory of a recent system resolve failure that a DoH provider fixed (raft task
 * #153). Within [CURL_DOH_PREFERRED_WINDOW_MILLIS] requests resolve through that provider first
 * instead of failing on the system resolver again; any DoH failure clears it, and the window
 * ending (or a provider no longer configured) returns to system-first.
 */
internal class CurlDohPreference(
    private val nowMillis: () -> Long,
    private val windowMillis: Long = CURL_DOH_PREFERRED_WINDOW_MILLIS,
) {
    private val lock = kotlinx.atomicfu.locks.SynchronizedObject()
    private var provider = 0
    private var until = 0L

    /** The provider to try first, or 0 to use the system resolver first. */
    fun preferredProvider(configured: List<Int>): Int =
        kotlinx.atomicfu.locks.synchronized(lock) {
            if (provider != 0 && nowMillis() < until && provider in configured) provider else 0
        }

    fun onDohResolved(providerId: Int) {
        kotlinx.atomicfu.locks.synchronized(lock) {
            provider = providerId
            until = nowMillis() + windowMillis
        }
    }

    fun clear() {
        kotlinx.atomicfu.locks.synchronized(lock) {
            provider = 0
            until = 0L
        }
    }
}

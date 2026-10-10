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
import com.tencent.kmm.network.export.VBTransportElapseStatistics
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
 * Native pseudo provider CURL_DOH_PROVIDER_STALE_ADDRESS (raft task #150 follow-up): the wrapper
 * pins the request to the address it last reached for the URL's host:port (remembered from any
 * transfer that got an HTTP status in the last 10 minutes) instead of resolving at all. With
 * nothing cached the attempt fails at once as CURLE_COULDNT_RESOLVE_HOST ("stale_address_miss")
 * and the walk continues with the DoH providers. Not a DoH provider: it never becomes the
 * preferred first attempt and does not touch [CurlDohPreference].
 */
internal const val CURL_DOH_PROVIDER_STALE_ADDRESS: Int = 3

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
 * after the system, the stale address (no resolver round trip, works where DoH is blocked) and
 * then the configured DoH providers in order; after a preferred DoH provider, the system
 * resolver, the stale address and then the other providers.
 */
internal fun curlDohFallbackAttempts(firstProvider: Int, configured: List<Int>): List<Int> {
    val providers = configured.distinct().filter { it != CURL_DOH_PROVIDER_STALE_ADDRESS }
    // One gate: with DoH fallback off (no providers) nothing is retried, stale address included.
    if (providers.isEmpty()) return emptyList()
    return if (firstProvider == 0) {
        listOf(CURL_DOH_PROVIDER_STALE_ADDRESS) + providers
    } else {
        listOf(0, CURL_DOH_PROVIDER_STALE_ADDRESS) + providers.filter { it != firstProvider }
    }
}

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
    // raft.45: providers whose last attempt failed, until when they go to the back of the order.
    private val failedUntil = mutableMapOf<Int, Long>()

    /**
     * [configured] with providers that failed within the window moved to the back (order otherwise
     * kept), so the next request does not spend its budget on a provider this network just failed.
     */
    fun orderByRecentFailures(configured: List<Int>): List<Int> =
        kotlinx.atomicfu.locks.synchronized(lock) {
            val now = nowMillis()
            failedUntil.entries.removeAll { it.value <= now }
            val (failed, fine) = configured.partition { it in failedUntil }
            fine + failed
        }

    fun onDohFailed(providerId: Int) {
        kotlinx.atomicfu.locks.synchronized(lock) {
            failedUntil[providerId] = nowMillis() + windowMillis
            if (provider == providerId) {
                provider = 0
                until = 0L
            }
        }
    }

    /** The provider to try first, or 0 to use the system resolver first. */
    fun preferredProvider(configured: List<Int>): Int =
        kotlinx.atomicfu.locks.synchronized(lock) {
            if (provider != 0 && nowMillis() < until && provider in configured) provider else 0
        }

    fun onDohResolved(providerId: Int) {
        kotlinx.atomicfu.locks.synchronized(lock) {
            provider = providerId
            until = nowMillis() + windowMillis
            failedUntil.remove(providerId)
        }
    }

    /** Forgets the preferred provider (a preferred-provider failure). Failure memory stays. */
    fun clear() {
        kotlinx.atomicfu.locks.synchronized(lock) {
            provider = 0
            until = 0L
        }
    }

    /** A new network: forget everything learned on the old one. */
    fun reset() {
        kotlinx.atomicfu.locks.synchronized(lock) {
            provider = 0
            until = 0L
            failedUntil.clear()
        }
    }
}

private val curlDohPreferenceClock = kotlin.time.TimeSource.Monotonic.markNow()

/** Process-wide DoH preference shared by every curl engine (Android, iOS, OHOS). */
internal val curlDohPreference: CurlDohPreference =
    CurlDohPreference(nowMillis = { curlDohPreferenceClock.elapsedNow().inWholeMilliseconds })

/**
 * Raft task #153, shared by the curl engines: after a first attempt that used [firstProvider]
 * (0 = system resolver) could not resolve the host, walk the remaining resolution attempts until
 * one resolves it, updating [preference]. Returns null when no retry applies (the first response
 * stands). The returned response carries freshRetryResult = doh_fallback_success |
 * system_after_doh_failure | doh_fallback_failure; a first attempt that used the preferred
 * provider and resolved is marked doh_preferred.
 */
internal suspend fun <R> runDohFallback(
    first: R,
    firstProvider: Int,
    configuredProviders: List<Int>,
    isUnresolved: (R) -> Boolean,
    timing: (R) -> VBTransportElapseStatistics,
    isCancelled: () -> Boolean,
    remainingTimeoutMillis: () -> Long?,
    preference: CurlDohPreference = curlDohPreference,
    attempt: suspend (provider: Int, timeoutMillis: Long) -> R,
): R? {
    if (firstProvider != 0 && !isUnresolved(first)) {
        timing(first).freshRetryResult = "doh_preferred"
        return null
    }
    val attempts = curlDohFallbackAttempts(firstProvider, preference.orderByRecentFailures(configuredProviders))
    if (firstProvider != 0) {
        preference.clear()
        if (isUnresolved(first)) preference.onDohFailed(firstProvider)
    }
    var last = first
    var lastProvider = firstProvider
    var retried = false
    for (provider in attempts) {
        val remainingTimeout = remainingTimeoutMillis()
        val canRetry = isUnresolved(last) && !isCancelled() && remainingTimeout != 0L
        if (!canRetry) break
        val next = attempt(provider, remainingTimeout ?: 0L)
        timing(next).retainFirstAttemptCurlFacts(timing(first))
        if (provider != 0 && provider != CURL_DOH_PROVIDER_STALE_ADDRESS && isUnresolved(next)) {
            preference.onDohFailed(provider)
        }
        last = next
        lastProvider = provider
        retried = true
    }
    if (!retried) return null
    val unresolved = isUnresolved(last)
    val lastIsDoh = lastProvider != 0 && lastProvider != CURL_DOH_PROVIDER_STALE_ADDRESS
    if (!unresolved && lastIsDoh) {
        preference.onDohResolved(lastProvider)
    } else if (lastIsDoh) {
        preference.clear()
    }
    timing(last).freshRetry = true
    timing(last).freshRetryResult =
        when {
            unresolved -> "doh_fallback_failure"
            lastProvider == 0 -> "system_after_doh_failure"
            lastProvider == CURL_DOH_PROVIDER_STALE_ADDRESS -> "stale_address_success"
            else -> "doh_fallback_success"
        }
    return last
}

/** [runDohFallback] for engines that see the raw curl result (Android, iOS). */
internal suspend fun runCurlDohFallback(
    first: CurlNativeResponse,
    firstProvider: Int,
    configuredProviders: List<Int>,
    isCancelled: () -> Boolean,
    remainingTimeoutMillis: () -> Long?,
    preference: CurlDohPreference = curlDohPreference,
    attempt: suspend (provider: Int, timeoutMillis: Long) -> CurlNativeResponse,
): CurlNativeResponse? =
    runDohFallback(
        first = first,
        firstProvider = firstProvider,
        configuredProviders = configuredProviders,
        isUnresolved = { it.code == CURL_CODE_COULDNT_RESOLVE_HOST },
        timing = { it.elapse },
        isCancelled = isCancelled,
        remainingTimeoutMillis = remainingTimeoutMillis,
        preference = preference,
        attempt = attempt,
    )

/** How often a response-headers stall may retire the pooled curl engine (raft.46). */
internal const val CURL_POOLED_ENGINE_RETIRE_INTERVAL_MILLIS: Long = 10_000L

/**
 * raft.46: rate limit for retiring the pooled curl engine after a response-headers stall. Several
 * requests on the same dead connection stall together; the first one retires the engine and the
 * others (already on the retired engine) must not retire the fresh one that replaced it.
 */
internal class CurlPooledEngineRetirement(
    private val nowMillis: () -> Long,
    private val intervalMillis: Long = CURL_POOLED_ENGINE_RETIRE_INTERVAL_MILLIS,
) {
    private val lock = kotlinx.atomicfu.locks.SynchronizedObject()
    private val lastRetiredAt = mutableMapOf<Boolean, Long>()

    /** Runs [retire] for the http3/default engine unless one was retired within the interval. */
    fun retireIfDue(http3Enabled: Boolean, retire: (Boolean) -> Unit): Boolean {
        val due = kotlinx.atomicfu.locks.synchronized(lock) {
            val now = nowMillis()
            val last = lastRetiredAt[http3Enabled]
            if (last != null && now - last < intervalMillis) {
                false
            } else {
                lastRetiredAt[http3Enabled] = now
                true
            }
        }
        if (due) retire(http3Enabled)
        return due
    }
}

private val curlPooledEngineRetirementClock = kotlin.time.TimeSource.Monotonic.markNow()

/** Process-wide; shared by the Android and iOS curl engines. */
internal val curlPooledEngineRetirement: CurlPooledEngineRetirement =
    CurlPooledEngineRetirement(nowMillis = { curlPooledEngineRetirementClock.elapsedNow().inWholeMilliseconds })

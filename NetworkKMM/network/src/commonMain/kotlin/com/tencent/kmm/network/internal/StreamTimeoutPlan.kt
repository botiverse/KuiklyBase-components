/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.tencent.kmm.network.internal

internal fun streamHeadersUpperBoundMillis(connectMillis: Long, headersMillis: Long): Long? {
    if (headersMillis <= 0) return null
    val connect = connectMillis.coerceAtLeast(0)
    return if (Long.MAX_VALUE - connect < headersMillis) Long.MAX_VALUE
    else connect + headersMillis
}

internal fun remainingStreamWholeTimeoutMillis(wholeMillis: Long, elapsedMillis: Long): Long? {
    if (wholeMillis <= 0) return null
    return (wholeMillis - elapsedMillis.coerceAtLeast(0)).coerceAtLeast(0)
}

internal fun streamPhaseTimeoutMillis(phaseMillis: Long?, remainingWholeMillis: Long?): Long? =
    when {
        phaseMillis == null -> remainingWholeMillis
        remainingWholeMillis == null -> phaseMillis
        else -> minOf(phaseMillis, remainingWholeMillis)
    }

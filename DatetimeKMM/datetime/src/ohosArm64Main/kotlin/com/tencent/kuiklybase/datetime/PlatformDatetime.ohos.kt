/*
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.tencent.kuiklybase.datetime

import com.tencent.kuiklybase.datetime.internal.ohos.kuikly_datetime_local_offset_seconds
import com.tencent.kuiklybase.datetime.internal.ohos.kuikly_datetime_now_millis
import com.tencent.kuiklybase.datetime.internal.ohos.kuikly_datetime_read_zone_id
import kotlinx.cinterop.*

internal actual fun platformEpochMilliseconds(): Long = memScoped {
    val result = alloc<LongVar>()
    check(kuikly_datetime_now_millis(result.ptr) == 0) {
        "Unable to read the OHOS wall clock"
    }
    result.value
}

internal actual fun platformSystemTimeZoneReading(
    epochMilliseconds: Long,
): PlatformSystemTimeZoneReading {
    repeat(MAX_STABLE_READ_ATTEMPTS) {
        val zoneIdBefore = readSystemTimeZoneId()
        val offsetSeconds = readSystemOffsetSeconds(epochMilliseconds.floorEpochSeconds())
        val zoneIdAfter = readSystemTimeZoneId()
        if (zoneIdBefore == zoneIdAfter) {
            return PlatformSystemTimeZoneReading(zoneIdBefore, offsetSeconds)
        }
    }
    error("OHOS system timezone changed during $MAX_STABLE_READ_ATTEMPTS consecutive reads")
}

private fun readSystemTimeZoneId(): String = memScoped {
    val buffer = allocArray<ByteVar>(TIME_ZONE_BUFFER_BYTES)
    val result = kuikly_datetime_read_zone_id(buffer, TIME_ZONE_BUFFER_BYTES.toUInt())
    check(result == TIME_SERVICE_OK) {
        "OH_TimeService_GetTimeZone failed with code $result"
    }
    buffer.toKString().also {
        check(it.isNotBlank()) { "OHOS returned an empty system timezone ID" }
    }
}

private fun readSystemOffsetSeconds(epochSeconds: Long): Int = memScoped {
    val result = alloc<IntVar>()
    check(kuikly_datetime_local_offset_seconds(epochSeconds, result.ptr) == 0) {
        "Unable to evaluate the OHOS system timezone offset at epoch $epochSeconds"
    }
    result.value
}

private fun Long.floorEpochSeconds(): Long {
    val quotient = this / MILLIS_PER_SECOND
    return if (this % MILLIS_PER_SECOND < 0) quotient - 1 else quotient
}

private const val MILLIS_PER_SECOND: Long = 1_000L
private const val TIME_ZONE_BUFFER_BYTES: Int = 128
private const val MAX_STABLE_READ_ATTEMPTS: Int = 3
private const val TIME_SERVICE_OK: Int = 0

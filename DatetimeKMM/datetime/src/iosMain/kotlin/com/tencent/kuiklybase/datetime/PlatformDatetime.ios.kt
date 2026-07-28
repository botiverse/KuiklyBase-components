/*
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.tencent.kuiklybase.datetime

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.Foundation.*
import platform.posix.gettimeofday
import platform.posix.timeval

internal actual fun platformEpochMilliseconds(): Long = memScoped {
    val now = alloc<timeval>()
    gettimeofday(now.ptr, null)
    now.tv_sec.toLong() * MILLIS_PER_SECOND + now.tv_usec.toLong() / MICROS_PER_MILLI
}

internal actual fun platformSystemTimeZoneReading(
    epochMilliseconds: Long,
): PlatformSystemTimeZoneReading {
    // Foundation caches systemTimeZone. Reset before every snapshot so a
    // timezone change made while the process is alive becomes observable.
    NSTimeZone.resetSystemTimeZone()
    val timeZone = NSTimeZone.systemTimeZone
    val date = NSDate.dateWithTimeIntervalSince1970(epochMilliseconds / MILLIS_PER_SECOND_DOUBLE)
    return PlatformSystemTimeZoneReading(
        zoneId = timeZone.name,
        offsetSeconds = timeZone.secondsFromGMTForDate(date).toInt(),
    )
}

private const val MILLIS_PER_SECOND: Long = 1_000L
private const val MICROS_PER_MILLI: Long = 1_000L
private const val MILLIS_PER_SECOND_DOUBLE: Double = 1_000.0

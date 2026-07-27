/*
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.tencent.kuiklybase.datetime

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970

internal actual fun platformEpochMilliseconds(): Long =
    (NSDate().timeIntervalSince1970 * MILLIS_PER_SECOND).toLong()

internal actual fun platformSystemTimeZoneReading(
    epochMilliseconds: Long,
): PlatformSystemTimeZoneReading {
    // Foundation caches systemTimeZone. Reset before every snapshot so a
    // timezone change made while the process is alive becomes observable.
    NSTimeZone.resetSystemTimeZone()
    val timeZone = NSTimeZone.systemTimeZone
    val date = NSDate.dateWithTimeIntervalSince1970(epochMilliseconds / MILLIS_PER_SECOND)
    return PlatformSystemTimeZoneReading(
        zoneId = timeZone.name,
        offsetSeconds = timeZone.secondsFromGMTForDate(date).toInt(),
    )
}

private const val MILLIS_PER_SECOND: Double = 1_000.0

/*
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */

package com.tencent.kuiklybase.datetime

import java.util.TimeZone

internal actual fun platformEpochMilliseconds(): Long = System.currentTimeMillis()

internal actual fun platformSystemTimeZoneReading(
    epochMilliseconds: Long,
): PlatformSystemTimeZoneReading {
    val timeZone = TimeZone.getDefault()
    return PlatformSystemTimeZoneReading(
        zoneId = timeZone.id,
        offsetSeconds = timeZone.getOffset(epochMilliseconds) / 1_000,
    )
}

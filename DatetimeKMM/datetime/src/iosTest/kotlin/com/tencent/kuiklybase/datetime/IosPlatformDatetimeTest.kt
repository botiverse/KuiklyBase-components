/*
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.tencent.kuiklybase.datetime

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosPlatformDatetimeTest {
    @Test
    fun systemClockUsesUnixEpochMilliseconds() {
        val before = (NSDate().timeIntervalSince1970 * 1_000.0).toLong()
        val observed = Clock.System.now().toEpochMilliseconds()
        val after = (NSDate().timeIntervalSince1970 * 1_000.0).toLong()
        assertTrue(observed in before..after)
    }

    @Test
    fun systemTimezoneSnapshotMatchesFreshFoundationReading() {
        val instant = Instant.fromEpochMilliseconds(1_704_067_200_000)
        val snapshot = SystemTimeZone.snapshot(instant)

        NSTimeZone.resetSystemTimeZone()
        val foundation = NSTimeZone.systemTimeZone
        val date = NSDate.dateWithTimeIntervalSince1970(
            instant.toEpochMilliseconds() / 1_000.0,
        )
        assertEquals(foundation.name, snapshot.zoneId)
        assertEquals(foundation.secondsFromGMTForDate(date).toInt(), snapshot.offset.totalSeconds)
    }
}

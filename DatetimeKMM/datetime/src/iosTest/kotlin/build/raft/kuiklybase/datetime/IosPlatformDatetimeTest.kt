/*
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.kuiklybase.datetime

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.Foundation.*
import platform.posix.gettimeofday
import platform.posix.timeval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosPlatformDatetimeTest {
    @Test
    fun systemClockUsesUnixEpochMilliseconds() {
        val before = posixNowMillis()
        val observed = Clock.System.now().toEpochMilliseconds()
        val after = posixNowMillis()
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

private fun posixNowMillis(): Long = memScoped {
    val now = alloc<timeval>()
    gettimeofday(now.ptr, null)
    now.tv_sec.toLong() * 1_000L + now.tv_usec.toLong() / 1_000L
}

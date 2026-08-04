/*
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */

package build.raft.kuiklybase.datetime

import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidPlatformDatetimeTest {
    @Test
    fun systemClockUsesUnixEpochMilliseconds() {
        val before = System.currentTimeMillis()
        val observed = Clock.System.now().toEpochMilliseconds()
        val after = System.currentTimeMillis()
        assertTrue(observed in before..after)
    }

    @Test
    fun systemTimezoneReadsDstAndAChangeMadeWhileTheProcessIsAlive() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val beforeTransition = SystemTimeZone.snapshot(
                Instant.fromEpochMilliseconds(1_710_053_999_000),
            )
            val afterTransition = SystemTimeZone.snapshot(
                Instant.fromEpochMilliseconds(1_710_054_000_000),
            )
            assertEquals("America/New_York", beforeTransition.zoneId)
            assertEquals(-18_000, beforeTransition.offset.totalSeconds)
            assertEquals(-14_400, afterTransition.offset.totalSeconds)

            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertFalse(SystemTimeZone.isCurrent(beforeTransition))
            val fresh = SystemTimeZone.snapshot(beforeTransition.instant)
            assertEquals("Asia/Tokyo", fresh.zoneId)
            assertEquals(32_400, fresh.offset.totalSeconds)
        } finally {
            TimeZone.setDefault(original)
        }
    }
}

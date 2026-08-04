/*
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */

package build.raft.kuiklybase.datetime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DatetimeContractTest {
    @Test
    fun instantKeepsTheExactEpochAcrossTheFullLongRange() {
        val epochs = listOf(Long.MIN_VALUE, -1L, 0L, 1L, EPOCH_2024_01_01, Long.MAX_VALUE)
        for (epoch in epochs) {
            assertEquals(epoch, Instant.fromEpochMilliseconds(epoch).toEpochMilliseconds())
        }
        assertTrue(Instant.fromEpochMilliseconds(-1) < Instant.fromEpochMilliseconds(0))
        assertTrue(Instant.fromEpochMilliseconds(Long.MIN_VALUE) < Instant.fromEpochMilliseconds(Long.MAX_VALUE))
    }

    @Test
    fun injectedClockReturnsTheSameEpochWithoutPlatformTime() {
        val expected = Instant.fromEpochMilliseconds(EPOCH_2024_01_01 + 123)
        val clock = object : Clock {
            override fun now(): Instant = expected
        }
        assertSame(expected, clock.now())
    }

    @Test
    fun utcOffsetPreservesSecondsAndRequiresExplicitMinuteConversion() {
        assertSame(UtcOffset.ZERO, UtcOffset.fromTotalSeconds(0))
        assertEquals("Z", UtcOffset.ZERO.toString())
        assertEquals("+05:30", UtcOffset.fromTotalSeconds(19_800).toString())
        assertEquals("-03:30:15", UtcOffset.fromTotalSeconds(-12_615).toString())
        assertEquals(330, UtcOffset.fromTotalSeconds(19_800).toWholeMinutesExact())
        assertFailsWith<IllegalStateException> {
            UtcOffset.fromTotalSeconds(19_801).toWholeMinutesExact()
        }
        assertFailsWith<IllegalArgumentException> {
            UtcOffset.fromTotalSeconds(18 * 60 * 60 + 1)
        }
    }

    @Test
    fun snapshotForwardsExactEpochAcrossLocalMidnight() {
        val reader = RecordingReader(zoneId = "Test/UTC+02") { 2 * 60 * 60 }
        val resolver = SystemTimeZoneResolver(reader)

        // UTC 21:59:59.500 and 22:00:00.500 straddle local midnight at UTC+02.
        val before = Instant.fromEpochMilliseconds(1_704_146_399_500)
        val after = Instant.fromEpochMilliseconds(1_704_146_400_500)
        assertEquals(7_200, resolver.snapshot(before).offset.totalSeconds)
        assertEquals(7_200, resolver.snapshot(after).offset.totalSeconds)
        assertEquals(
            listOf(before.toEpochMilliseconds(), after.toEpochMilliseconds()),
            reader.seenEpochs,
        )
    }

    @Test
    fun snapshotEvaluatesBothSidesOfADstTransition() {
        val transition = 1_710_054_000_000L // 2024-03-10T07:00:00Z, New York transition shape.
        val reader = RecordingReader(zoneId = "Test/DST") { epoch ->
            if (epoch < transition) -5 * 60 * 60 else -4 * 60 * 60
        }
        val resolver = SystemTimeZoneResolver(reader)

        assertEquals(-18_000, resolver.snapshot(Instant.fromEpochMilliseconds(transition - 1)).offset.totalSeconds)
        assertEquals(-14_400, resolver.snapshot(Instant.fromEpochMilliseconds(transition)).offset.totalSeconds)
    }

    @Test
    fun runtimeTimezoneChangeInvalidatesOldSnapshotAndFreshReadWins() {
        val reader = MutableReader("America/New_York", -18_000)
        val resolver = SystemTimeZoneResolver(reader)
        val instant = Instant.fromEpochMilliseconds(EPOCH_2024_01_01)
        val old = resolver.snapshot(instant)

        reader.zoneId = "Asia/Tokyo"
        reader.offsetSeconds = 32_400

        assertFalse(resolver.isCurrent(old))
        val fresh = resolver.snapshot(instant)
        assertEquals("Asia/Tokyo", fresh.zoneId)
        assertEquals(32_400, fresh.offset.totalSeconds)
        assertTrue(resolver.isCurrent(fresh))
    }

    @Test
    fun sameZoneIdRuleChangeAlsoInvalidatesOldSnapshot() {
        val reader = MutableReader("Test/Zone", 3_600)
        val resolver = SystemTimeZoneResolver(reader)
        val instant = Instant.fromEpochMilliseconds(EPOCH_2024_01_01)
        val old = resolver.snapshot(instant)

        reader.offsetSeconds = 7_200

        assertFalse(resolver.isCurrent(old))
    }

    @Test
    fun invalidPlatformReadingFailsClosed() {
        val instant = Instant.fromEpochMilliseconds(0)
        assertFailsWith<IllegalArgumentException> {
            SystemTimeZoneResolver { PlatformSystemTimeZoneReading("", 0) }.snapshot(instant)
        }
        assertFailsWith<IllegalArgumentException> {
            SystemTimeZoneResolver {
                PlatformSystemTimeZoneReading("Test/Invalid", 18 * 60 * 60 + 1)
            }.snapshot(instant)
        }
    }
}

private class RecordingReader(
    private val zoneId: String,
    private val offsetAt: (Long) -> Int,
) : SystemTimeZoneReader {
    val seenEpochs = mutableListOf<Long>()

    override fun read(epochMilliseconds: Long): PlatformSystemTimeZoneReading {
        seenEpochs += epochMilliseconds
        return PlatformSystemTimeZoneReading(zoneId, offsetAt(epochMilliseconds))
    }
}

private class MutableReader(
    var zoneId: String,
    var offsetSeconds: Int,
) : SystemTimeZoneReader {
    override fun read(epochMilliseconds: Long): PlatformSystemTimeZoneReading =
        PlatformSystemTimeZoneReading(zoneId, offsetSeconds)
}

private const val EPOCH_2024_01_01: Long = 1_704_067_200_000L

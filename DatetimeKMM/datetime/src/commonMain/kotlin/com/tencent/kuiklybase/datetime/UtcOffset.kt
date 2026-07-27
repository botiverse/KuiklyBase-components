/*
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */

package com.tencent.kuiklybase.datetime

import kotlin.math.abs

/** A signed offset from UTC with second precision. */
public class UtcOffset private constructor(
    public val totalSeconds: Int,
) {
    /**
     * Converts to minutes without silently losing historical second offsets.
     *
     * Mobile compatibility code may use this while its existing payload is
     * minute-based. Callers must choose an explicit fallback if this throws.
     */
    public fun toWholeMinutesExact(): Int {
        check(totalSeconds % SECONDS_PER_MINUTE == 0) {
            "UTC offset $this is not an exact number of minutes"
        }
        return totalSeconds / SECONDS_PER_MINUTE
    }

    override fun equals(other: Any?): Boolean =
        other is UtcOffset && totalSeconds == other.totalSeconds

    override fun hashCode(): Int = totalSeconds

    override fun toString(): String {
        if (totalSeconds == 0) return "Z"
        val magnitude = abs(totalSeconds)
        val hours = magnitude / SECONDS_PER_HOUR
        val minutes = (magnitude / SECONDS_PER_MINUTE) % MINUTES_PER_HOUR
        val seconds = magnitude % SECONDS_PER_MINUTE
        val base = buildString {
            append(if (totalSeconds < 0) '-' else '+')
            append(hours.twoDigits())
            append(':')
            append(minutes.twoDigits())
        }
        return if (seconds == 0) base else "$base:${seconds.twoDigits()}"
    }

    public companion object {
        public val ZERO: UtcOffset = UtcOffset(0)

        /**
         * Creates an offset in the ISO-8601 range from -18:00 through +18:00.
         */
        public fun fromTotalSeconds(totalSeconds: Int): UtcOffset {
            require(totalSeconds in -MAX_OFFSET_SECONDS..MAX_OFFSET_SECONDS) {
                "UTC offset must be between -18:00 and +18:00, got $totalSeconds seconds"
            }
            return if (totalSeconds == 0) ZERO else UtcOffset(totalSeconds)
        }
    }
}

private const val SECONDS_PER_MINUTE: Int = 60
private const val MINUTES_PER_HOUR: Int = 60
private const val SECONDS_PER_HOUR: Int = SECONDS_PER_MINUTE * MINUTES_PER_HOUR
private const val MAX_OFFSET_SECONDS: Int = 18 * SECONDS_PER_HOUR

private fun Int.twoDigits(): String = toString().padStart(2, '0')

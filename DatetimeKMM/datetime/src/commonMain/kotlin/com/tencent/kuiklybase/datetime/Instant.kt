/*
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */

package com.tencent.kuiklybase.datetime

/**
 * A point on the Unix time-line with millisecond precision.
 *
 * This deliberately small type is not a replacement for the calendar,
 * parsing, formatting, or arithmetic APIs in `kotlinx-datetime`. Its stable
 * contract is the round-trip to Unix epoch milliseconds needed by Kuikly
 * hosts on Android, iOS, and OHOS.
 */
public class Instant private constructor(
    private val epochMilliseconds: Long,
) : Comparable<Instant> {

    /** Returns the signed number of milliseconds since 1970-01-01T00:00:00Z. */
    public fun toEpochMilliseconds(): Long = epochMilliseconds

    override fun compareTo(other: Instant): Int = epochMilliseconds.compareTo(other.epochMilliseconds)

    override fun equals(other: Any?): Boolean =
        other is Instant && epochMilliseconds == other.epochMilliseconds

    override fun hashCode(): Int = epochMilliseconds.hashCode()

    override fun toString(): String = "Instant(epochMilliseconds=$epochMilliseconds)"

    public companion object {
        /** Creates an [Instant] without changing or normalizing the supplied epoch. */
        public fun fromEpochMilliseconds(epochMilliseconds: Long): Instant =
            Instant(epochMilliseconds)
    }
}

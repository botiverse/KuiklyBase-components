/*
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */

package com.tencent.kuiklybase.datetime

/** A source of wall-clock [Instant] values. */
public interface Clock {
    /** Reads the current wall-clock time. It is not a monotonic duration source. */
    public fun now(): Instant

    public companion object {
        /**
         * The platform wall clock.
         *
         * Prefer injecting a [Clock] into business logic so tests do not depend
         * on the device clock.
         */
        public val System: Clock = PlatformWallClock
    }
}

private object PlatformWallClock : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(platformEpochMilliseconds())
}

internal expect fun platformEpochMilliseconds(): Long

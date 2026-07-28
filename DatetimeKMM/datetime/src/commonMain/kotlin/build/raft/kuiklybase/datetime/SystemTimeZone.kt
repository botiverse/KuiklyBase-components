/*
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */

package build.raft.kuiklybase.datetime

/**
 * One immutable observation of the device's current system timezone.
 *
 * [instant] is the epoch at which [offset] was evaluated. The observation is
 * intentionally not a live object: call [SystemTimeZone.snapshot] again when
 * formatting another instant or after a platform timezone-change signal.
 */
public class SystemTimeZoneSnapshot internal constructor(
    public val zoneId: String,
    public val offset: UtcOffset,
    public val instant: Instant,
) {
    override fun equals(other: Any?): Boolean =
        other is SystemTimeZoneSnapshot &&
            zoneId == other.zoneId &&
            offset == other.offset &&
            instant == other.instant

    override fun hashCode(): Int {
        var result = zoneId.hashCode()
        result = 31 * result + offset.hashCode()
        result = 31 * result + instant.hashCode()
        return result
    }

    override fun toString(): String =
        "SystemTimeZoneSnapshot(zoneId=$zoneId, offset=$offset, instant=$instant)"
}

/** Fresh access to the device's system timezone and its offset. */
public object SystemTimeZone {
    private val resolver: SystemTimeZoneResolver =
        SystemTimeZoneResolver(PlatformSystemTimeZoneReader)

    /**
     * Reads the current system timezone and evaluates its offset at [instant].
     *
     * No timezone ID, rules object, or offset is cached across calls. Android
     * and iOS use one platform timezone object for both fields. OHOS verifies
     * the ID before and after its POSIX offset read and retries if it changed.
     */
    public fun snapshot(instant: Instant = Clock.System.now()): SystemTimeZoneSnapshot =
        resolver.snapshot(instant)

    /**
     * Active invalidation probe for a cached observation.
     *
     * The current system zone is read again and evaluated at the original
     * [SystemTimeZoneSnapshot.instant]. This returns false for either a zone-ID
     * change or a rule/offset change at that epoch. Callers responding to an OS
     * timezone-change event can use this as the refresh signal.
     */
    public fun isCurrent(snapshot: SystemTimeZoneSnapshot): Boolean =
        resolver.isCurrent(snapshot)
}

internal data class PlatformSystemTimeZoneReading(
    val zoneId: String,
    val offsetSeconds: Int,
)

internal fun interface SystemTimeZoneReader {
    fun read(epochMilliseconds: Long): PlatformSystemTimeZoneReading
}

internal class SystemTimeZoneResolver(
    private val reader: SystemTimeZoneReader,
) {
    fun snapshot(instant: Instant): SystemTimeZoneSnapshot {
        val reading = reader.read(instant.toEpochMilliseconds())
        require(reading.zoneId.isNotBlank()) { "System timezone ID must not be blank" }
        return SystemTimeZoneSnapshot(
            zoneId = reading.zoneId,
            offset = UtcOffset.fromTotalSeconds(reading.offsetSeconds),
            instant = instant,
        )
    }

    fun isCurrent(snapshot: SystemTimeZoneSnapshot): Boolean {
        val current = snapshot(snapshot.instant)
        return current.zoneId == snapshot.zoneId && current.offset == snapshot.offset
    }
}

private object PlatformSystemTimeZoneReader : SystemTimeZoneReader {
    override fun read(epochMilliseconds: Long): PlatformSystemTimeZoneReading =
        platformSystemTimeZoneReading(epochMilliseconds)
}

internal expect fun platformSystemTimeZoneReading(
    epochMilliseconds: Long,
): PlatformSystemTimeZoneReading

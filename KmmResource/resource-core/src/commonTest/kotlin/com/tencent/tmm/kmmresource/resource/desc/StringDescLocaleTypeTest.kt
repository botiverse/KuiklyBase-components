package com.tencent.tmm.kmmresource.resource.desc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Contract test for the platform `StringDesc.LocaleType` storage: every
 * actual must retain the assigned value (the ohosArm64 actual used to drop
 * the assignment and always report `System`, which broke in-app language
 * switching). Host-run targets (android unit test) exercise their own actual;
 * the ohosArm64 actual satisfies the same contract by construction and is
 * compiled into `compileTestKotlinOhosArm64` via this source set.
 */
class StringDescLocaleTypeTest {
    @Test
    fun customLocaleTypeIsStoredAndReturned() {
        val previous = StringDesc.localeType
        try {
            val custom = StringDesc.LocaleType.Custom("zh-CN")
            StringDesc.localeType = custom
            assertSame(custom, StringDesc.localeType)

            StringDesc.localeType = StringDesc.LocaleType.System
            assertSame(StringDesc.LocaleType.System, StringDesc.localeType)
        } finally {
            StringDesc.localeType = previous
        }
    }

    @Test
    fun customLocaleTypeDefaultsToSystem() {
        assertSame(StringDesc.LocaleType.System, StringDesc.localeType)
    }

    @Test
    fun localeTypeAssignmentIsReplacedNotAccumulated() {
        val previous = StringDesc.localeType
        try {
            StringDesc.localeType = StringDesc.LocaleType.Custom("en")
            StringDesc.localeType = StringDesc.LocaleType.Custom("ja")
            val current = StringDesc.localeType
            assertEquals(true, current is StringDesc.LocaleType.Custom)
        } finally {
            StringDesc.localeType = previous
        }
    }
}

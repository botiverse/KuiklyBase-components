package com.tencent.tmm.kmmresource.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StringLookupDispatcherTest {
    @Test
    fun zeroArgumentTemplateUsesRawLookup() {
        var formattedLookupCalled = false
        val dispatcher = StringLookupDispatcher(
            rawLookup = { "Failed to save tab order: %1\$s" },
            formattedLookup = { _, _ ->
                formattedLookupCalled = true
                error("zero-argument lookup must not enter the formatter")
            }
        )

        assertEquals(
            "Failed to save tab order: %1\$s",
            dispatcher.getString("member_error_tab_order_save_detail")
        )
        assertFalse(formattedLookupCalled)
    }

    @Test
    fun explicitlyEmptyVarargStillUsesRawLookup() {
        var rawLookupCalled = false
        val dispatcher = StringLookupDispatcher(
            rawLookup = {
                rawLookupCalled = true
                "100%% ready"
            },
            formattedLookup = { _, _ -> error("empty vararg must not enter the formatter") }
        )

        assertEquals("100%% ready", dispatcher.getString("escaped_percent", *emptyArray()))
        assertTrue(rawLookupCalled)
    }

    @Test
    fun nonEmptyArgumentsUseFormattedLookupInOrder() {
        var rawLookupCalled = false
        var receivedName: String? = null
        var receivedArguments: Array<out Any>? = null
        val dispatcher = StringLookupDispatcher(
            rawLookup = {
                rawLookupCalled = true
                error("formatted lookup must not use the raw path")
            },
            formattedLookup = { resourceName, arguments ->
                receivedName = resourceName
                receivedArguments = arguments
                "Pair alpha/7/100%"
            }
        )

        assertEquals(
            "Pair alpha/7/100%",
            dispatcher.getString("multi_and_escaped_percent", "alpha", 7)
        )
        assertFalse(rawLookupCalled)
        assertEquals("multi_and_escaped_percent", receivedName)
        assertEquals(listOf("alpha", 7), receivedArguments?.toList())
    }

    @Test
    fun ordinaryStringUsesRawLookupUnchanged() {
        val dispatcher = StringLookupDispatcher(
            rawLookup = { "Status OK" },
            formattedLookup = { _, _ -> error("ordinary raw lookup must not enter the formatter") }
        )

        assertEquals("Status OK", dispatcher.getString("ordinary_status"))
    }
}

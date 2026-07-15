/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.tencent.kmm.network.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CancellationAwareRegistryTest {
    @Test
    fun cancellationBeforePublishIsConsumedExactlyOnce() {
        val registry = CancellationAwareRegistry<Int, String>()

        assertFalse(registry.cancelOrRemember(7, removePublished = false) {})
        assertFalse(registry.publish(7, "first"))
        assertNull(registry.get(7))
        assertTrue(registry.publish(7, "second"))
        assertEquals("second", registry.get(7))
    }

    @Test
    fun cancelAndReleaseShareOneOwnershipBoundary() {
        val registry = CancellationAwareRegistry<Int, String>()
        val events = mutableListOf<String>()
        assertTrue(registry.publish(9, "handle"))

        assertTrue(
            registry.cancelOrRemember(9, removePublished = false) { value -> events += "cancel:$value" }
        )
        assertTrue(registry.removeIfSame(9, "handle") { value -> events += "delete:$value" })

        assertEquals(listOf("cancel:handle", "delete:handle"), events)
        assertNull(registry.get(9))
    }

    @Test
    fun staleOwnerCannotDeleteReplacement() {
        val registry = CancellationAwareRegistry<Int, String>()
        assertTrue(registry.publish(11, "old"))
        assertTrue(registry.publish(11, "new"))

        assertFalse(registry.removeIfSame(11, "old"))
        assertEquals("new", registry.get(11))
    }
}

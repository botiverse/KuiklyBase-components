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
import kotlin.test.assertFailsWith

class CancellationAwareRegistryTest {
    @Test
    fun cancellationBeforePublishIsConsumedExactlyOnce() {
        val registry = CancellationAwareRegistry<Int, String>()

        registry.begin(7)
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

    @Test
    fun duplicateCancelAfterRemovalDoesNotPoisonReusedKey() {
        val registry = CancellationAwareRegistry<Int, String>()
        var cancels = 0
        registry.begin(13)
        assertTrue(registry.publish(13, "first"))

        assertTrue(registry.cancelOrRemember(13, removePublished = true) { cancels++ })
        assertFalse(registry.cancelOrRemember(13, removePublished = true) { cancels++ })
        registry.begin(13)
        assertTrue(registry.publish(13, "second"))

        assertEquals(1, cancels)
        assertEquals("second", registry.get(13))
    }

    @Test
    fun lateCancelAfterSuccessDoesNotPoisonReusedKey() {
        val registry = CancellationAwareRegistry<Int, String>()
        registry.begin(17)
        assertTrue(registry.publish(17, "first"))
        assertEquals("first", registry.remove(17))

        assertFalse(registry.cancelOrRemember(17, removePublished = true) {})
        registry.begin(17)
        assertTrue(registry.publish(17, "second"))

        assertEquals("second", registry.get(17))
    }

    @Test
    fun throwingPublishedCancellationStillRemovesOwnerInFinally() {
        val registry = CancellationAwareRegistry<Int, String>()
        registry.begin(19)
        assertTrue(registry.publish(19, "first"))

        assertFailsWith<IllegalStateException> {
            registry.cancelOrRemember(19, removePublished = true) {
                error("platform cancel failed")
            }
        }

        assertNull(registry.get(19))
        registry.begin(19)
        assertTrue(registry.publish(19, "replacement"))
        assertEquals("replacement", registry.get(19))
    }
}

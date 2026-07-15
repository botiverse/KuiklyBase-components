/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.tencent.kmm.network.internal

import com.tencent.kmm.network.export.IVBPBRequestIdGenerator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RequestIdOwnerRegistryTest {
    @AfterTest
    fun reset() {
        VBPBRequestIdGenerator.requestIdGenerator = null
    }

    @Test
    fun constantIdReplacementGetsDistinctOwnerAndCannotCancelIncumbent() {
        VBPBRequestIdGenerator.requestIdGenerator = object : IVBPBRequestIdGenerator {
            override fun getRequestId(): Int = 909
        }
        val registry = RequestIdOwnerRegistry()
        val incumbent = Any()
        val replacement = Any()

        val incumbentId = registry.reserve(incumbent)
        val replacementId = registry.reserve(replacement)

        assertNotEquals(incumbentId, replacementId)
        assertTrue(registry.isOwner(incumbentId, incumbent))
        assertFalse(registry.isOwner(incumbentId, replacement))
        registry.release(replacementId, replacement)
        assertTrue(registry.isOwner(incumbentId, incumbent))
        registry.release(incumbentId, incumbent)
    }
}

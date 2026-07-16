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
import kotlin.test.assertNotEquals

class VBPBRequestIdGeneratorTest {
    @AfterTest
    fun reset() {
        VBPBRequestIdGenerator.requestIdGenerator = null
    }

    @Test
    fun constantCustomGeneratorFallsBackInsteadOfSpinningForever() {
        val occupied = 777
        VBPBRequestIdGenerator.requestIdGenerator = object : IVBPBRequestIdGenerator {
            override fun getRequestId(): Int = occupied
        }

        val reserved = VBPBRequestIdGenerator.reserveRequestId { it != occupied }

        assertNotEquals(occupied, reserved)
    }
}

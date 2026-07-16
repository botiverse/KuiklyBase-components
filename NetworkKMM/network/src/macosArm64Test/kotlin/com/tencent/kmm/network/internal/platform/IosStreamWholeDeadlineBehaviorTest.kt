/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.tencent.kmm.network.internal.platform

import com.tencent.kmm.network.export.VBTransportMethod
import com.tencent.kmm.network.export.VBTransportRequest
import com.tencent.kmm.network.export.VBTransportResponse
import com.tencent.kmm.network.export.VBTransportResultCode
import com.tencent.kmm.network.service.VBTransportService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosStreamWholeDeadlineBehaviorTest {
    @Test
    fun publicStreamWholeDeadlineIncludesBlockedCommonToPlatformHandoff() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val terminal = CompletableDeferred<VBTransportResponse>()
        var starts = 0
        var chunks = 0
        var terminals = 0
        var transportOpens = 0
        IosTransportTestHooks.beforeStreamTransportCoroutineStart = {
            entered.complete(Unit)
            release.await()
        }
        IosTransportTestHooks.beforeStreamTransportOpen = { transportOpens++ }
        try {
            val request = VBTransportRequest().apply {
                method = VBTransportMethod.GET
                url = "https://127.0.0.1/never-opened"
                useCurl = false
                streamWholeTimeoutMillis = 25L
                streamResponseHeadersTimeoutMillis = 0L
            }

            VBTransportService.streamRequest(
                request,
                onResponseStart = { _, _ -> starts++ },
                onChunk = { chunks++ },
            ) {
                terminals++
                terminal.complete(it)
            }
            entered.await()
            delay(50L)
            release.complete(Unit)

            val response = withTimeout(2_000L) { terminal.await() }
            assertEquals(VBTransportResultCode.CODE_FORCE_TIMEOUT, response.errorCode)
            assertEquals(0, starts)
            assertEquals(0, chunks)
            assertEquals(0, transportOpens)
            assertEquals(1, terminals)
            val transport = IOSTransportImpl()
            assertTrue(transport.prepareRequest(request.requestId))
            transport.abortPreparedRequest(request.requestId)
        } finally {
            release.complete(Unit)
            IosTransportTestHooks.reset()
        }
    }
}

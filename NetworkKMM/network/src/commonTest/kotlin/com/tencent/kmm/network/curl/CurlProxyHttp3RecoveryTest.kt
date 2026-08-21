/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.tencent.kmm.network.curl

import com.tencent.kmm.network.export.VBTransportMethod
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CurlProxyHttp3RecoveryTest {
    @Test
    fun classifierRequiresBothObservedCodeAndExplicitProxyHttp3Message() {
        val message = "HTTP/3 is not supported over an HTTP proxy"
        assertTrue(isCurlProxyHttp3Incompatibility(28, message))
        assertTrue(isCurlProxyHttp3Incompatibility(35, message))
        assertTrue(isCurlProxyHttp3Incompatibility(56, "[connection_lost] CURLcode:56 $message"))

        assertFalse(isCurlProxyHttp3Incompatibility(7, message))
        assertFalse(isCurlProxyHttp3Incompatibility(28, "Operation timed out after 30000 milliseconds"))
        assertFalse(isCurlProxyHttp3Incompatibility(35, "TLS certificate verify failed"))
        assertFalse(isCurlProxyHttp3Incompatibility(56, "HTTP/3 peer reset"))
        assertFalse(isCurlProxyHttp3Incompatibility(35, "proxy authentication required"))
    }

    @Test
    fun retryEligibilityIsOnlyGetOrHeadWithBudgetAndActiveCall() {
        assertTrue(shouldFreshRetryCurlProxyHttp3Failure(VBTransportMethod.GET, false, null))
        assertTrue(shouldFreshRetryCurlProxyHttp3Failure(VBTransportMethod.HEAD, false, 1L))
        assertFalse(shouldFreshRetryCurlProxyHttp3Failure(VBTransportMethod.POST, false, 1L))
        assertFalse(shouldFreshRetryCurlProxyHttp3Failure(VBTransportMethod.PUT, false, 1L))
        assertFalse(shouldFreshRetryCurlProxyHttp3Failure(VBTransportMethod.PATCH, false, 1L))
        assertFalse(shouldFreshRetryCurlProxyHttp3Failure(VBTransportMethod.DELETE, false, 1L))
        assertFalse(shouldFreshRetryCurlProxyHttp3Failure(VBTransportMethod.GET, true, 1L))
        assertFalse(shouldFreshRetryCurlProxyHttp3Failure(VBTransportMethod.GET, false, 0L))
    }
}

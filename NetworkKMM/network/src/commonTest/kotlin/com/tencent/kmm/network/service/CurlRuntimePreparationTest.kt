/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tencent.kmm.network.service

import com.tencent.kmm.network.export.NetworkCurlProxyConfiguration
import com.tencent.kmm.network.export.NetworkCurlRuntimeConfiguration
import com.tencent.kmm.network.export.NetworkCurlTrustMode
import com.tencent.kmm.network.export.NetworkCurlTrustStore
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.VBTransportCurl
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two-layer curl trust contract (task #252 root fix):
 * - never configured + verified platform default -> requests proceed on the
 *   compiled CA with the proxy pinned to explicit direct;
 * - never configured + no verified default -> gated, typed unavailability;
 * - failed explicit configure -> fail-closed, NEVER falls back to the
 *   platform default;
 * - clear() restores the platform-default (pre-configure) state.
 */
class CurlRuntimePreparationTest {

    private val verifiedDefault = CurlPlatformDefaultTrust(
        available = true,
        detail = "test compiled default"
    )
    private val noDefault = CurlPlatformDefaultTrust(
        available = false,
        detail = "no verified default"
    )

    @BeforeTest
    fun resetRuntime() {
        VBTransportCurl.clear()
    }

    @AfterTest
    fun restoreRuntime() {
        VBTransportCurl.clear()
    }

    @Test
    fun unconfiguredRunsOnVerifiedPlatformDefaultWithExplicitDirectProxy() {
        val request = NetworkRequest(url = "https://example.com/api")

        val availability = prepareCurlRuntime(request, verifiedDefault)

        assertTrue(availability.available)
        assertEquals(NetworkCurlTrustMode.PLATFORM_DEFAULT, VBTransportCurl.trustMode)
        // No CA path: the wrapper must leave the compiled CURL_CA_BUNDLE active.
        assertNull(preparedCurlCaInfoPath(request))
        // Proxy pinned to explicit direct — "" disables environment proxies.
        assertEquals("", preparedCurlProxyUrl(request))
        assertEquals(CURL_RUNTIME_TRUST_PLATFORM_DEFAULT, preparedCurlTrustSource(request))
    }

    @Test
    fun unconfiguredStaysGatedWithoutVerifiedPlatformDefault() {
        val request = NetworkRequest(url = "https://example.com/api")

        val availability = prepareCurlRuntime(request, noDefault)

        assertFalse(availability.available)
        assertEquals(NetworkEngineUnavailableReason.TRUST_STORE_NOT_CONFIGURED, availability.reason)
        assertNull(preparedCurlTrustSource(request))
    }

    @Test
    fun failedExplicitConfigureFailsClosedAndNeverFallsBackToDefault() {
        val status = VBTransportCurl.configure(
            NetworkCurlRuntimeConfiguration(
                trustStore = NetworkCurlTrustStore(
                    path = "/nonexistent/trust-store.pem",
                    sha256 = "0".repeat(64)
                ),
                proxy = NetworkCurlProxyConfiguration.direct()
            )
        )
        assertFalse(status.configured)
        assertEquals(NetworkCurlTrustMode.FAILED_CLOSED, VBTransportCurl.trustMode)

        val request = NetworkRequest(url = "https://example.com/api")
        // Even with a verified platform default available, a failed explicit
        // configure must not silently fall back to it.
        val availability = prepareCurlRuntime(request, verifiedDefault)

        assertFalse(availability.available)
        assertNull(preparedCurlTrustSource(request))
    }

    @Test
    fun clearRestoresPlatformDefaultTrustAfterFailedConfigure() {
        VBTransportCurl.configure(
            NetworkCurlRuntimeConfiguration(
                trustStore = NetworkCurlTrustStore(
                    path = "/nonexistent/trust-store.pem",
                    sha256 = "0".repeat(64)
                ),
                proxy = NetworkCurlProxyConfiguration.direct()
            )
        )
        assertEquals(NetworkCurlTrustMode.FAILED_CLOSED, VBTransportCurl.trustMode)

        VBTransportCurl.clear()
        assertEquals(NetworkCurlTrustMode.PLATFORM_DEFAULT, VBTransportCurl.trustMode)

        val request = NetworkRequest(url = "https://example.com/api")
        assertTrue(prepareCurlRuntime(request, verifiedDefault).available)
        assertEquals(CURL_RUNTIME_TRUST_PLATFORM_DEFAULT, preparedCurlTrustSource(request))
    }
}

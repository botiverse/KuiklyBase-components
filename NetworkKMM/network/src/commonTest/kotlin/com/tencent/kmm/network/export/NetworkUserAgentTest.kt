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
package com.tencent.kmm.network.export

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkUserAgentTest {

    @BeforeTest
    fun reset() {
        NetworkUserAgent.appIdentity = null
    }

    @AfterTest
    fun restore() {
        NetworkUserAgent.appIdentity = null
    }

    @Test
    fun degradesToUnknownRatherThanOmittingTheHeader() {
        // No host identity is the state every consumer starts in. The header must
        // still exist: "no User-Agent at all" is what left servers unable to
        // identify a client build in the first place.
        val value = NetworkUserAgent.headerValue()

        assertTrue(value.startsWith("unknown/unknown "), value)
        assertTrue(value.contains("NetworkKMM/${NetworkUserAgent.LIBRARY_VERSION}"), value)
    }

    @Test
    fun carriesApplicationVersionAndBuildNumber() {
        NetworkUserAgent.appIdentity = NetworkAppIdentity(
            name = "Raft",
            version = "1.10.0",
            buildNumber = "1100004",
            buildType = "production"
        )

        val value = NetworkUserAgent.headerValue()

        assertTrue(value.startsWith("Raft/1.10.0+1100004 ("), value)
        assertTrue(value.contains("; production)"), value)
        assertTrue(value.endsWith("NetworkKMM/${NetworkUserAgent.LIBRARY_VERSION}"), value)
    }

    @Test
    fun omitsBlankOptionalSegments() {
        NetworkUserAgent.appIdentity = NetworkAppIdentity(name = "Raft", version = "1.10.0")

        val value = NetworkUserAgent.headerValue()

        assertTrue(value.startsWith("Raft/1.10.0 ("), value)
        assertFalse(value.contains("+"), value)
        assertFalse(value.contains("; )"), value)
    }

    @Test
    fun stripsControlCharactersSoHostValuesCannotInjectHeaders() {
        // A CR/LF reaching the wire would let a host-supplied string append
        // arbitrary headers. Replaced, not dropped, so it stays visible in logs.
        NetworkUserAgent.appIdentity = NetworkAppIdentity(
            name = "Raft\r\nX-Injected: 1",
            version = "1.10.0",
            buildType = "prod\nuction"
        )

        val value = NetworkUserAgent.headerValue()

        assertFalse(value.contains('\r'), value)
        assertFalse(value.contains('\n'), value)
        assertTrue(value.contains("X-Injected"), "sanitised text should remain readable: $value")
    }

    @Test
    fun fillsTheHeaderWhenAbsent() {
        val headers = mutableMapOf<String, String>()

        val written = NetworkUserAgent.applyTo(headers)

        assertTrue(written)
        assertEquals(1, headers.size)
        assertTrue(headers.containsKey("User-Agent"))
    }

    @Test
    fun neverOverwritesACallerSuppliedValue() {
        val headers = mutableMapOf("User-Agent" to "caller/1.0")

        val written = NetworkUserAgent.applyTo(headers)

        assertFalse(written)
        assertEquals("caller/1.0", headers["User-Agent"])
        assertEquals(1, headers.size)
    }

    @Test
    fun respectsLowercaseHeaderNamesSoTheWireNeverCarriesTwo() {
        // HTTP header names are case-insensitive. Matching only the canonical
        // spelling would append a second User-Agent alongside the caller's.
        val headers = mutableMapOf("user-agent" to "caller/1.0")

        val written = NetworkUserAgent.applyTo(headers)

        assertFalse(written)
        assertEquals(1, headers.size)
        assertEquals("caller/1.0", headers["user-agent"])
    }

    @Test
    fun respectsMixedCaseHeaderNames() {
        val headers = mutableMapOf("USER-AGENT" to "caller/1.0")

        assertFalse(NetworkUserAgent.applyTo(headers))
        assertEquals(1, headers.size)
    }

    @Test
    fun headerNameIsTheCanonicalSpelling() {
        assertEquals("User-Agent", NetworkUserAgent.HEADER_NAME)
    }
}

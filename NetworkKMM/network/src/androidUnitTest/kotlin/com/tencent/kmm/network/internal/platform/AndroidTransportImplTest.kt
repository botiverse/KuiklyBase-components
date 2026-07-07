package com.tencent.kmm.network.internal.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidTransportImplTest {
    @Test
    fun explicitContentTypeHeaderPreventsDefaultContentType() {
        assertTrue(
            hasExplicitContentType(
                mapOf("Content-Type" to "multipart/form-data; boundary=BoundaryForTest")
            )
        )
    }

    @Test
    fun explicitContentTypeHeaderMatchIsCaseInsensitive() {
        assertTrue(hasExplicitContentType(mapOf("content-type" to "application/json")))
    }

    @Test
    fun blankOrMissingContentTypeRequiresDefaultContentType() {
        assertFalse(hasExplicitContentType(mapOf("Content-Type" to "")))
        assertFalse(hasExplicitContentType(mapOf("Accept" to "application/json")))
    }
}

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

import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkHttpProtocolMappingTest {
    @Test
    fun mapsNativeAndPlatformProtocolLabels() {
        assertEquals(NetworkHttpProtocol.HTTP_1_0, "http/1.0".toNetworkHttpProtocol())
        assertEquals(NetworkHttpProtocol.HTTP_1_1, "http/1.1".toNetworkHttpProtocol())
        assertEquals(NetworkHttpProtocol.HTTP_2, "h2".toNetworkHttpProtocol())
        assertEquals(NetworkHttpProtocol.HTTP_3, "h3".toNetworkHttpProtocol())
        assertEquals(NetworkHttpProtocol.HTTP_3, "HTTP/3".toNetworkHttpProtocol())
        assertEquals(NetworkHttpProtocol.UNKNOWN, "unknown".toNetworkHttpProtocol())
        assertEquals(NetworkHttpProtocol.UNKNOWN, null.toNetworkHttpProtocol())
    }
}

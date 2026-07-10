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

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

internal actual val curlSystemProxySupport: CurlSystemProxySupport = CurlSystemProxySupport(
    available = true,
    detail = "Android direct/manual/PAC proxy decisions are read per request; PAC uses Android's localhost forwarding proxy."
)

internal actual fun resolveCurlSystemProxy(url: String): CurlSystemProxyResolution =
    AndroidCurlSystemProxyResolver.resolve(url)

internal object AndroidCurlSystemProxyResolver {
    @Volatile
    internal var testResolver: ((String) -> CurlSystemProxyResolution)? = null

    fun resolve(url: String): CurlSystemProxyResolution =
        testResolver?.invoke(url) ?: resolveAndroidCurlSystemProxy(url)
}

internal fun resolveAndroidCurlSystemProxy(
    url: String,
    proxySelector: ProxySelector? = runCatching { ProxySelector.getDefault() }.getOrNull(),
    isPacSelector: Boolean = proxySelector?.javaClass?.name?.contains("PacProxySelector") == true,
    property: (String) -> String? = { key -> runCatching { System.getProperty(key) }.getOrNull() }
): CurlSystemProxyResolution {
    val uri = runCatching { URI(url) }.getOrNull()
        ?: return CurlSystemProxyResolution.unavailable(
            NetworkEngineUnavailableReason.PROXY_SYSTEM_UNAVAILABLE,
            "Android system proxy cannot resolve an invalid request URL."
        )
    if (!uri.isAbsolute || uri.host.isNullOrBlank()) {
        return CurlSystemProxyResolution.unavailable(
            NetworkEngineUnavailableReason.PROXY_SYSTEM_UNAVAILABLE,
            "Android system proxy requires an absolute request URL with a host."
        )
    }

    if (isPacSelector) {
        // ActivityThread mirrors ProxyInfo into these JVM properties. For PAC,
        // ProxyInfo contains the localhost forwarding port bound by Android.
        return resolveAndroidPacForwarder(uri, property)
    }

    val proxies = runCatching { proxySelector?.select(uri) }.getOrNull()
        ?: return CurlSystemProxyResolution.unavailable(
            NetworkEngineUnavailableReason.PROXY_SYSTEM_UNAVAILABLE,
            "Android default ProxySelector did not return a proxy decision for this request."
        )
    if (proxies.size != 1) {
        return CurlSystemProxyResolution.unavailable(
            NetworkEngineUnavailableReason.PROXY_SYSTEM_UNAVAILABLE,
            "Android default ProxySelector returned ${proxies.size} ordered choices; curl system mode requires one effective decision."
        )
    }
    return proxyToCurlUrl(proxies.single())
}

private fun resolveAndroidPacForwarder(
    uri: URI,
    property: (String) -> String?
): CurlSystemProxyResolution {
    val scheme = if (uri.scheme.equals("https", ignoreCase = true)) "https" else "http"
    val host = property("$scheme.proxyHost")?.trim().orEmpty()
        .ifEmpty { property("http.proxyHost")?.trim().orEmpty() }
    val port = property("$scheme.proxyPort")?.trim()?.toIntOrNull()
        ?: property("http.proxyPort")?.trim()?.toIntOrNull()
    val isLoopback = host.equals("localhost", ignoreCase = true) ||
        host == "127.0.0.1" || host == "::1" || host == "[::1]"
    if (!isLoopback || port == null || port !in 1..65535) {
        return CurlSystemProxyResolution.unavailable(
            NetworkEngineUnavailableReason.PROXY_SYSTEM_UNAVAILABLE,
            "Android PAC is configured, but its localhost forwarding proxy is not ready."
        )
    }
    return CurlSystemProxyResolution.resolved("http://${formatProxyHost(host)}:$port")
}

private fun proxyToCurlUrl(proxy: Proxy): CurlSystemProxyResolution {
    if (proxy == Proxy.NO_PROXY || proxy.type() == Proxy.Type.DIRECT) {
        return CurlSystemProxyResolution.resolved("")
    }
    val address = proxy.address() as? InetSocketAddress
        ?: return CurlSystemProxyResolution.unavailable(
            NetworkEngineUnavailableReason.PROXY_SYSTEM_UNAVAILABLE,
            "Android system proxy address is not an InetSocketAddress."
        )
    if (address.port !in 1..65535) {
        return CurlSystemProxyResolution.unavailable(
            NetworkEngineUnavailableReason.PROXY_SYSTEM_UNAVAILABLE,
            "Android system proxy port is invalid: ${address.port}"
        )
    }
    val scheme = when (proxy.type()) {
        Proxy.Type.HTTP -> "http"
        Proxy.Type.SOCKS -> "socks5h"
        Proxy.Type.DIRECT -> return CurlSystemProxyResolution.resolved("")
    }
    return CurlSystemProxyResolution.resolved(
        "$scheme://${formatProxyHost(address.hostString)}:${address.port}"
    )
}

private fun formatProxyHost(host: String): String =
    if (host.contains(':') && !host.startsWith('[')) "[$host]" else host

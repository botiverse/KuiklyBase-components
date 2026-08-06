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

import com.tencent.kmm.network.export.NetworkCurlConfigurationFailureReason
import com.tencent.kmm.network.export.NetworkCurlProxyMode
import com.tencent.kmm.network.export.NetworkCurlProxyHttp3Environment
import com.tencent.kmm.network.export.NetworkCurlTrustMode
import com.tencent.kmm.network.export.NetworkEngineCapabilities
import com.tencent.kmm.network.export.NetworkEngineFeatureReason
import com.tencent.kmm.network.export.NetworkEngineFeatureStatus
import com.tencent.kmm.network.export.NetworkError
import com.tencent.kmm.network.export.NetworkErrorKind
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkResponseBody
import com.tencent.kmm.network.export.VBTransportCurl
import com.tencent.kmm.network.internal.platform.platformCurlSupportsHttp3

private const val CURL_RUNTIME_READY = "com.tencent.kmm.network.curl.runtime.ready"
private const val CURL_RUNTIME_CA_PATH = "com.tencent.kmm.network.curl.runtime.ca_path"
private const val CURL_RUNTIME_PROXY_URL = "com.tencent.kmm.network.curl.runtime.proxy_url"
private const val CURL_RUNTIME_TRUST = "com.tencent.kmm.network.curl.runtime.trust"
private const val CURL_RUNTIME_HTTP3 = "com.tencent.kmm.network.curl.runtime.http3"
private const val CURL_RUNTIME_HTTP3_REQUESTED =
    "com.tencent.kmm.network.curl.runtime.http3_requested"
private const val CURL_RUNTIME_PROXY_HTTP3_GENERATION =
    "com.tencent.kmm.network.curl.runtime.proxy_http3_generation"
private const val CURL_RUNTIME_PROXY_HTTP3_FINGERPRINT =
    "com.tencent.kmm.network.curl.runtime.proxy_http3_fingerprint"
private const val CURL_RUNTIME_PROXY_FINGERPRINT =
    "com.tencent.kmm.network.curl.runtime.proxy_fingerprint"

internal const val CURL_RUNTIME_TRUST_PLATFORM_DEFAULT = "platform_default"
internal const val CURL_RUNTIME_TRUST_APP_OWNED = "app_owned"

/**
 * Whether this platform's compiled libcurl default CA is a verified trust
 * source. Where it is (OHOS), unconfigured curl runs on the platform default;
 * where it is not, curl stays gated until the app configures explicitly.
 */
internal data class CurlPlatformDefaultTrust(
    val available: Boolean,
    val detail: String
)

internal expect val curlPlatformDefaultTrust: CurlPlatformDefaultTrust

internal data class CurlSystemProxySupport(
    val available: Boolean,
    val detail: String
)

internal data class CurlSystemProxyResolution(
    val available: Boolean,
    val proxyUrl: String? = null,
    val reason: NetworkEngineUnavailableReason? = null,
    val detail: String? = null
) {
    companion object {
        fun resolved(proxyUrl: String): CurlSystemProxyResolution =
            CurlSystemProxyResolution(available = true, proxyUrl = proxyUrl)

        fun unavailable(
            reason: NetworkEngineUnavailableReason,
            detail: String
        ): CurlSystemProxyResolution = CurlSystemProxyResolution(
            available = false,
            reason = reason,
            detail = detail
        )
    }
}

internal expect val curlSystemProxySupport: CurlSystemProxySupport

internal expect fun resolveCurlSystemProxy(url: String): CurlSystemProxyResolution

internal fun prepareCurlRuntime(
    request: NetworkRequest,
    platformDefaultTrust: CurlPlatformDefaultTrust = curlPlatformDefaultTrust,
    nativeHttp3Supported: Boolean = platformCurlSupportsHttp3()
): NetworkEngineAvailability {
    if (request.metadata[CURL_RUNTIME_READY] == "true") {
        return NetworkEngineAvailability.Available
    }
    val configuration = VBTransportCurl.snapshot()
    val http3Requested = request.curlHttp3EnabledOverride
        ?: configuration?.http3Enabled
        ?: false
    // Preserve intent separately from the effective mode. A proxy/H3 latch may
    // force this request onto H2 while diagnostics still need to show that H3
    // was requested by policy.
    request.metadata[CURL_RUNTIME_HTTP3_REQUESTED] = http3Requested.toString()
    if (configuration == null) {
        if (VBTransportCurl.trustMode == NetworkCurlTrustMode.PLATFORM_DEFAULT &&
            platformDefaultTrust.available
        ) {
            if (http3Requested && !nativeHttp3Supported) {
                return NetworkEngineAvailability.unavailable(
                    reason = NetworkEngineUnavailableReason.HTTP3_UNSUPPORTED,
                    detail = "HTTP/3 is not eligible: the linked curl artifact does not report CURL_VERSION_HTTP3."
                )
            }
            // Never explicitly configured: run on the platform's verified
            // compiled CA default. No CA path is handed to the wrapper (the
            // compiled CURL_CA_BUNDLE applies) and the proxy is pinned to
            // explicit direct ("" disables libcurl's environment proxies),
            // so the default's semantics are deterministic, not ambient.
            prepareCurlProxyAndHttp3(request, proxyUrl = "", http3Requested = http3Requested)
            request.metadata[CURL_RUNTIME_TRUST] = CURL_RUNTIME_TRUST_PLATFORM_DEFAULT
            request.metadata[CURL_RUNTIME_READY] = "true"
            return NetworkEngineAvailability.Available
        }
        val status = VBTransportCurl.configurationStatus
        val reason = when (status.failureReason) {
            NetworkCurlConfigurationFailureReason.TRUST_STORE_PATH_BLANK ->
                NetworkEngineUnavailableReason.TRUST_STORE_NOT_CONFIGURED
            NetworkCurlConfigurationFailureReason.TRUST_STORE_HASH_INVALID,
            NetworkCurlConfigurationFailureReason.TRUST_STORE_UNREADABLE,
            NetworkCurlConfigurationFailureReason.TRUST_STORE_HASH_MISMATCH ->
                NetworkEngineUnavailableReason.TRUST_STORE_INVALID
            NetworkCurlConfigurationFailureReason.PROXY_URL_MISSING ->
                NetworkEngineUnavailableReason.PROXY_RESOLUTION_REQUIRED
            NetworkCurlConfigurationFailureReason.PROXY_URL_INVALID ->
                NetworkEngineUnavailableReason.PROXY_INVALID
            NetworkCurlConfigurationFailureReason.NONE ->
                NetworkEngineUnavailableReason.TRUST_STORE_NOT_CONFIGURED
        }
        return NetworkEngineAvailability.unavailable(
            reason = reason,
            detail = status.detail ?: "Curl runtime configuration is unavailable."
        )
    }
    if (configuration.httpDnsEnabled) {
        return NetworkEngineAvailability.unavailable(
            reason = NetworkEngineUnavailableReason.HTTPDNS_UNSUPPORTED,
            detail = "Custom HTTPDNS is not eligible: no resolver-to-IP injection contract preserves SNI, Host, and certificate verification yet."
        )
    }
    if (http3Requested) {
        if (!nativeHttp3Supported) {
            return NetworkEngineAvailability.unavailable(
                reason = NetworkEngineUnavailableReason.HTTP3_UNSUPPORTED,
                detail = "HTTP/3 is not eligible: the linked curl artifact does not report CURL_VERSION_HTTP3."
            )
        }
    }
    val proxyUrl = when (configuration.proxy.mode) {
        NetworkCurlProxyMode.DIRECT -> ""
        NetworkCurlProxyMode.MANUAL -> configuration.proxy.url.orEmpty()
        NetworkCurlProxyMode.ANDROID_SYSTEM -> {
            val resolution = resolveCurlSystemProxy(request.resolvedUrl())
            if (!resolution.available) {
                return NetworkEngineAvailability.unavailable(
                    reason = resolution.reason ?: NetworkEngineUnavailableReason.PROXY_SYSTEM_UNAVAILABLE,
                    detail = resolution.detail ?: "Android system proxy resolution is unavailable."
                )
            }
            resolution.proxyUrl.orEmpty()
        }
        NetworkCurlProxyMode.PAC_UNRESOLVED -> {
            return NetworkEngineAvailability.unavailable(
                reason = NetworkEngineUnavailableReason.PROXY_PAC_UNSUPPORTED,
                detail = "PAC/system proxy must be resolved by the platform bridge before curl can be selected."
            )
        }
    }
    request.metadata[CURL_RUNTIME_CA_PATH] = configuration.trustStore.path
    prepareCurlProxyAndHttp3(request, proxyUrl = proxyUrl, http3Requested = http3Requested)
    request.metadata[CURL_RUNTIME_TRUST] = CURL_RUNTIME_TRUST_APP_OWNED
    request.metadata[CURL_RUNTIME_READY] = "true"
    return NetworkEngineAvailability.Available
}

internal fun preparedCurlTrustSource(request: NetworkRequest): String? =
    request.metadata[CURL_RUNTIME_TRUST]

internal fun preparedCurlCaInfoPath(request: NetworkRequest): String? =
    request.metadata[CURL_RUNTIME_CA_PATH]?.takeIf(String::isNotBlank)

/** Empty means explicit direct mode and is still passed to CURLOPT_PROXY. */
internal fun preparedCurlProxyUrl(request: NetworkRequest): String? =
    request.metadata[CURL_RUNTIME_PROXY_URL]

internal fun preparedCurlHttp3Enabled(request: NetworkRequest): Boolean =
    request.metadata[CURL_RUNTIME_HTTP3] == "true"

internal fun preparedCurlHttp3Requested(request: NetworkRequest): Boolean? =
    request.metadata[CURL_RUNTIME_HTTP3_REQUESTED]?.let { value ->
        when (value) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

internal fun preparedCurlProxyHttp3Environment(
    request: NetworkRequest
): NetworkCurlProxyHttp3Environment? {
    val generation = request.metadata[CURL_RUNTIME_PROXY_HTTP3_GENERATION]?.toLongOrNull()
        ?: return null
    val proxyFingerprint = request.metadata[CURL_RUNTIME_PROXY_FINGERPRINT] ?: return null
    val fingerprint = request.metadata[CURL_RUNTIME_PROXY_HTTP3_FINGERPRINT] ?: return null
    return NetworkCurlProxyHttp3Environment(
        generation = generation,
        proxyFingerprint = proxyFingerprint,
        fingerprint = fingerprint,
        h2Latched = !preparedCurlHttp3Enabled(request) && preparedCurlHttp3Requested(request) == true
    )
}

internal fun latchPreparedCurlProxyHttp3Fallback(request: NetworkRequest): Boolean {
    val environment = preparedCurlProxyHttp3Environment(request) ?: return false
    return VBTransportCurl.latchProxyHttp3Fallback(environment)
}

internal fun forcePreparedCurlHttp2(request: NetworkRequest) {
    request.metadata[CURL_RUNTIME_HTTP3] = "false"
}

private fun prepareCurlProxyAndHttp3(
    request: NetworkRequest,
    proxyUrl: String,
    http3Requested: Boolean
) {
    val environment = VBTransportCurl.proxyHttp3Environment(proxyUrl)
    request.metadata[CURL_RUNTIME_PROXY_URL] = proxyUrl
    request.metadata[CURL_RUNTIME_HTTP3] =
        (http3Requested && !environment.h2Latched).toString()
    request.metadata[CURL_RUNTIME_PROXY_HTTP3_GENERATION] = environment.generation.toString()
    request.metadata[CURL_RUNTIME_PROXY_HTTP3_FINGERPRINT] = environment.fingerprint
    request.metadata[CURL_RUNTIME_PROXY_FINGERPRINT] = environment.proxyFingerprint
}

internal fun curlRuntimeFailureResponse(
    request: NetworkRequest,
    availability: NetworkEngineAvailability
): NetworkResponse {
    val kind = when (availability.reason) {
        NetworkEngineUnavailableReason.TRUST_STORE_NOT_CONFIGURED,
        NetworkEngineUnavailableReason.TRUST_STORE_INVALID -> NetworkErrorKind.TLS
        NetworkEngineUnavailableReason.PROXY_RESOLUTION_REQUIRED,
        NetworkEngineUnavailableReason.PROXY_PAC_UNSUPPORTED,
        NetworkEngineUnavailableReason.PROXY_SYSTEM_UNSUPPORTED,
        NetworkEngineUnavailableReason.PROXY_SYSTEM_UNAVAILABLE,
        NetworkEngineUnavailableReason.PROXY_INVALID -> NetworkErrorKind.CONNECT
        NetworkEngineUnavailableReason.HTTPDNS_UNSUPPORTED -> NetworkErrorKind.DNS
        NetworkEngineUnavailableReason.HTTP3_UNSUPPORTED,
        NetworkEngineUnavailableReason.NATIVE_UNAVAILABLE,
        null -> NetworkErrorKind.UNKNOWN
    }
    return NetworkResponse(
        request = request,
        statusCode = null,
        headers = emptyMap(),
        body = NetworkResponseBody(),
        error = NetworkError(
            kind = kind,
            message = availability.detail ?: "Curl runtime is not eligible for this request."
        )
    )
}

internal fun curlNetworkEngineCapabilities(
    nativeHttp3Supported: Boolean = platformCurlSupportsHttp3()
): NetworkEngineCapabilities {
    val trustStatus = when {
        VBTransportCurl.configured ->
            NetworkEngineFeatureStatus.available("App-owned CA bundle verified by SHA-256 before activation.")
        VBTransportCurl.trustMode == NetworkCurlTrustMode.PLATFORM_DEFAULT &&
            curlPlatformDefaultTrust.available ->
            NetworkEngineFeatureStatus.available(
                "Platform default trust store: ${curlPlatformDefaultTrust.detail} " +
                    "Call VBTransportCurl.configure() to install an app-owned override."
            )
        else ->
            NetworkEngineFeatureStatus.unavailable(
                reason = NetworkEngineFeatureReason.CONFIGURATION_REQUIRED,
                compiledIn = true,
                detail = VBTransportCurl.configurationStatus.detail
            )
    }
    return NetworkEngineCapabilities(
        requestBodyStreaming = true,
        responseBodyStreaming = true,
        multipartStreaming = true,
        uploadProgress = true,
        downloadProgress = true,
        appOwnedTrustStore = trustStatus,
        manualProxy = NetworkEngineFeatureStatus.available(
            "Direct/manual proxy is passed explicitly through CURLOPT_PROXY."
        ),
        pacProxy = if (curlSystemProxySupport.available) {
            NetworkEngineFeatureStatus.available(curlSystemProxySupport.detail)
        } else {
            NetworkEngineFeatureStatus.unavailable(
                reason = NetworkEngineFeatureReason.PAC_UNSUPPORTED,
                compiledIn = true,
                detail = curlSystemProxySupport.detail
            )
        },
        httpDns = NetworkEngineFeatureStatus.unavailable(
            reason = NetworkEngineFeatureReason.NOT_IMPLEMENTED,
            detail = "No custom resolver contract currently preserves original-host SNI and certificate verification."
        ),
        http3 = if (nativeHttp3Supported) {
            NetworkEngineFeatureStatus.available(
                "Linked curl artifact reports CURL_VERSION_HTTP3; rollout remains explicit through http3Enabled."
            )
        } else {
            NetworkEngineFeatureStatus.unavailable(
                reason = NetworkEngineFeatureReason.NATIVE_BACKEND_NOT_COMPILED,
                detail = "Linked curl artifact does not report CURL_VERSION_HTTP3."
            )
        }
    )
}

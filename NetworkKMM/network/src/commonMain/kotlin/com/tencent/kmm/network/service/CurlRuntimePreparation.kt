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
import com.tencent.kmm.network.export.NetworkEngineCapabilities
import com.tencent.kmm.network.export.NetworkEngineFeatureReason
import com.tencent.kmm.network.export.NetworkEngineFeatureStatus
import com.tencent.kmm.network.export.NetworkError
import com.tencent.kmm.network.export.NetworkErrorKind
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkResponseBody
import com.tencent.kmm.network.export.VBTransportCurl

private const val CURL_RUNTIME_READY = "com.tencent.kmm.network.curl.runtime.ready"
private const val CURL_RUNTIME_CA_PATH = "com.tencent.kmm.network.curl.runtime.ca_path"
private const val CURL_RUNTIME_PROXY_URL = "com.tencent.kmm.network.curl.runtime.proxy_url"

internal fun prepareCurlRuntime(request: NetworkRequest): NetworkEngineAvailability {
    if (request.metadata[CURL_RUNTIME_READY] == "true") {
        return NetworkEngineAvailability.Available
    }
    val configuration = VBTransportCurl.snapshot()
    if (configuration == null) {
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
    if (configuration.http3Enabled) {
        return NetworkEngineAvailability.unavailable(
            reason = NetworkEngineUnavailableReason.HTTP3_UNSUPPORTED,
            detail = "HTTP/3 is not eligible: current curl artifacts contain no QUIC backend and runtime feature probe."
        )
    }
    val proxyUrl = when (configuration.proxy.mode) {
        NetworkCurlProxyMode.DIRECT -> ""
        NetworkCurlProxyMode.MANUAL -> configuration.proxy.url.orEmpty()
        NetworkCurlProxyMode.PAC_UNRESOLVED -> {
            return NetworkEngineAvailability.unavailable(
                reason = NetworkEngineUnavailableReason.PROXY_PAC_UNSUPPORTED,
                detail = "PAC/system proxy must be resolved by the platform bridge before curl can be selected."
            )
        }
    }
    request.metadata[CURL_RUNTIME_CA_PATH] = configuration.trustStore.path
    request.metadata[CURL_RUNTIME_PROXY_URL] = proxyUrl
    request.metadata[CURL_RUNTIME_READY] = "true"
    return NetworkEngineAvailability.Available
}

internal fun preparedCurlCaInfoPath(request: NetworkRequest): String? =
    request.metadata[CURL_RUNTIME_CA_PATH]?.takeIf(String::isNotBlank)

/** Empty means explicit direct mode and is still passed to CURLOPT_PROXY. */
internal fun preparedCurlProxyUrl(request: NetworkRequest): String? =
    request.metadata[CURL_RUNTIME_PROXY_URL]

internal fun curlRuntimeFailureResponse(
    request: NetworkRequest,
    availability: NetworkEngineAvailability
): NetworkResponse {
    val kind = when (availability.reason) {
        NetworkEngineUnavailableReason.TRUST_STORE_NOT_CONFIGURED,
        NetworkEngineUnavailableReason.TRUST_STORE_INVALID -> NetworkErrorKind.TLS
        NetworkEngineUnavailableReason.PROXY_RESOLUTION_REQUIRED,
        NetworkEngineUnavailableReason.PROXY_PAC_UNSUPPORTED,
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

internal fun curlNetworkEngineCapabilities(): NetworkEngineCapabilities {
    val trustStatus = if (VBTransportCurl.configured) {
        NetworkEngineFeatureStatus.available("App-owned CA bundle verified by SHA-256 before activation.")
    } else {
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
        pacProxy = NetworkEngineFeatureStatus.unavailable(
            reason = NetworkEngineFeatureReason.PAC_UNSUPPORTED,
            compiledIn = true,
            detail = "The host must resolve PAC/system proxy rules to a fixed URL before curl rollout."
        ),
        httpDns = NetworkEngineFeatureStatus.unavailable(
            reason = NetworkEngineFeatureReason.NOT_IMPLEMENTED,
            detail = "No custom resolver contract currently preserves original-host SNI and certificate verification."
        ),
        http3 = NetworkEngineFeatureStatus.unavailable(
            reason = NetworkEngineFeatureReason.NATIVE_BACKEND_NOT_COMPILED,
            detail = "Native artifacts ship without ngtcp2/quiche/msh3 and therefore cannot negotiate HTTP/3."
        )
    )
}

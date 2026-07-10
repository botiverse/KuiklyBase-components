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

import kotlinx.atomicfu.atomic

/** Pinned Mozilla CA snapshot used by the production staging helper. */
object NetworkCurlCaBundleManifest {
    const val VERSION: String = "2026-05-14"
    const val SOURCE_URL: String = "https://curl.se/ca/cacert-2026-05-14.pem"
    const val SHA256: String = "86a1f3366afac7c6f8ae9f3c779ac221129328c43f0ab2b8817eb2f362a5025c"
}

data class NetworkCurlTrustStore(
    /** Absolute path to the app-owned PEM bundle staged outside the network library. */
    val path: String,
    /** Lower- or upper-case SHA-256 expected for the exact bytes at [path]. */
    val sha256: String
)

enum class NetworkCurlProxyMode {
    /** Explicitly bypass every proxy, including libcurl environment defaults. */
    DIRECT,

    /** A fixed proxy URL already resolved by the host platform bridge. */
    MANUAL,

    /** A PAC/system proxy exists but has not been resolved to a fixed URL. */
    PAC_UNRESOLVED
}

data class NetworkCurlProxyConfiguration(
    val mode: NetworkCurlProxyMode,
    val url: String? = null
) {
    companion object {
        fun direct(): NetworkCurlProxyConfiguration =
            NetworkCurlProxyConfiguration(NetworkCurlProxyMode.DIRECT)

        fun manual(url: String): NetworkCurlProxyConfiguration =
            NetworkCurlProxyConfiguration(NetworkCurlProxyMode.MANUAL, url)

        fun pacUnresolved(): NetworkCurlProxyConfiguration =
            NetworkCurlProxyConfiguration(NetworkCurlProxyMode.PAC_UNRESOLVED)
    }
}

/**
 * Process-wide curl inputs supplied by the app before curl rollout is enabled.
 *
 * The proxy choice is intentionally explicit. libcurl does not inherit the
 * Android/iOS/OHOS platform proxy/PAC contract, so a host must either declare
 * direct access, pass a fixed platform-resolved proxy URL, or mark PAC as
 * unresolved so routing can fail closed to the platform engine.
 */
data class NetworkCurlRuntimeConfiguration(
    val trustStore: NetworkCurlTrustStore,
    val proxy: NetworkCurlProxyConfiguration,
    /** Reserved gate. Custom DNS is rejected until a SNI-safe resolver contract lands. */
    val httpDnsEnabled: Boolean = false,
    /** Reserved gate. Current native artifacts contain no QUIC/HTTP3 backend. */
    val http3Enabled: Boolean = false
)

enum class NetworkCurlConfigurationFailureReason {
    NONE,
    TRUST_STORE_PATH_BLANK,
    TRUST_STORE_HASH_INVALID,
    TRUST_STORE_UNREADABLE,
    TRUST_STORE_HASH_MISMATCH,
    PROXY_URL_MISSING,
    PROXY_URL_INVALID
}

data class NetworkCurlConfigurationStatus(
    val configured: Boolean,
    val failureReason: NetworkCurlConfigurationFailureReason,
    val detail: String? = null
)

/** Shared, verified curl runtime configuration used by every platform delegate. */
object VBTransportCurl {
    private val configurationState = atomic<NetworkCurlRuntimeConfiguration?>(null)
    private val statusState = atomic(missingConfigurationStatus())

    val configurationStatus: NetworkCurlConfigurationStatus
        get() = statusState.value

    val configured: Boolean
        get() = configurationState.value != null

    /**
     * Verifies the declared SHA-256 against the staged file before making the
     * configuration visible to any curl request. A failed update clears the
     * previous configuration so stale trust/proxy inputs cannot survive a bad
     * rollout refresh.
     */
    fun configure(configuration: NetworkCurlRuntimeConfiguration): NetworkCurlConfigurationStatus {
        val validation = validate(configuration)
        if (validation.configured) {
            configurationState.value = configuration.copy(
                trustStore = configuration.trustStore.copy(
                    path = configuration.trustStore.path.trim(),
                    sha256 = configuration.trustStore.sha256.trim().lowercase()
                ),
                proxy = configuration.proxy.copy(url = configuration.proxy.url?.trim())
            )
        } else {
            configurationState.value = null
        }
        statusState.value = validation
        return validation
    }

    fun clear() {
        configurationState.value = null
        statusState.value = missingConfigurationStatus()
    }

    internal fun snapshot(): NetworkCurlRuntimeConfiguration? = configurationState.value

    /** Compatibility bridge for the old path-only Android/iOS API. */
    internal fun configureLegacyPath(path: String?): NetworkCurlConfigurationStatus {
        val normalized = path?.trim().orEmpty()
        if (normalized.isEmpty()) {
            clear()
            return configurationStatus
        }
        val bytes = readNetworkCurlFile(normalized)
        if (bytes == null || bytes.isEmpty()) {
            val status = NetworkCurlConfigurationStatus(
                configured = false,
                failureReason = NetworkCurlConfigurationFailureReason.TRUST_STORE_UNREADABLE,
                detail = "Curl trust store is missing or unreadable: $normalized"
            )
            configurationState.value = null
            statusState.value = status
            return status
        }
        return configure(
            NetworkCurlRuntimeConfiguration(
                trustStore = NetworkCurlTrustStore(normalized, networkCurlSha256Hex(bytes)),
                proxy = NetworkCurlProxyConfiguration.direct()
            )
        )
    }

    private fun validate(configuration: NetworkCurlRuntimeConfiguration): NetworkCurlConfigurationStatus {
        val path = configuration.trustStore.path.trim()
        if (path.isEmpty()) {
            return failure(
                NetworkCurlConfigurationFailureReason.TRUST_STORE_PATH_BLANK,
                "Curl requires an app-owned CA bundle path."
            )
        }
        val expectedHash = configuration.trustStore.sha256.trim().lowercase()
        if (!expectedHash.matches(Regex("[0-9a-f]{64}"))) {
            return failure(
                NetworkCurlConfigurationFailureReason.TRUST_STORE_HASH_INVALID,
                "Curl CA bundle SHA-256 must contain exactly 64 hexadecimal characters."
            )
        }
        val bytes = readNetworkCurlFile(path)
        if (bytes == null || bytes.isEmpty()) {
            return failure(
                NetworkCurlConfigurationFailureReason.TRUST_STORE_UNREADABLE,
                "Curl trust store is missing or unreadable: $path"
            )
        }
        val actualHash = networkCurlSha256Hex(bytes)
        if (actualHash != expectedHash) {
            return failure(
                NetworkCurlConfigurationFailureReason.TRUST_STORE_HASH_MISMATCH,
                "Curl trust store SHA-256 mismatch: expected $expectedHash, actual $actualHash"
            )
        }
        if (configuration.proxy.mode == NetworkCurlProxyMode.MANUAL) {
            val proxyUrl = configuration.proxy.url?.trim().orEmpty()
            if (proxyUrl.isEmpty()) {
                return failure(
                    NetworkCurlConfigurationFailureReason.PROXY_URL_MISSING,
                    "Manual curl proxy mode requires a fixed proxy URL."
                )
            }
            if (!isSupportedProxyUrl(proxyUrl)) {
                return failure(
                    NetworkCurlConfigurationFailureReason.PROXY_URL_INVALID,
                    "Unsupported curl proxy URL scheme: $proxyUrl"
                )
            }
        }
        return NetworkCurlConfigurationStatus(
            configured = true,
            failureReason = NetworkCurlConfigurationFailureReason.NONE
        )
    }

    private fun failure(
        reason: NetworkCurlConfigurationFailureReason,
        detail: String
    ): NetworkCurlConfigurationStatus = NetworkCurlConfigurationStatus(
        configured = false,
        failureReason = reason,
        detail = detail
    )

    private fun isSupportedProxyUrl(url: String): Boolean {
        val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
        return scheme in setOf("http", "https", "socks4", "socks4a", "socks5", "socks5h")
    }

    private fun missingConfigurationStatus(): NetworkCurlConfigurationStatus =
        NetworkCurlConfigurationStatus(
            configured = false,
            failureReason = NetworkCurlConfigurationFailureReason.TRUST_STORE_PATH_BLANK,
            detail = "Curl runtime configuration has not been installed by the app."
        )
}

internal expect fun readNetworkCurlFile(path: String): ByteArray?

internal fun networkCurlSha256Hex(bytes: ByteArray): String =
    NetworkCurlSha256.digest(bytes).joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

private object NetworkCurlSha256 {
    private val roundConstants = intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcfu.toInt(), 0xe9b5dba5u.toInt(),
        0x3956c25b, 0x59f111f1, 0x923f82a4u.toInt(), 0xab1c5ed5u.toInt(),
        0xd807aa98u.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1feu.toInt(), 0x9bdc06a7u.toInt(), 0xc19bf174u.toInt(),
        0xe49b69c1u.toInt(), 0xefbe4786u.toInt(), 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152u.toInt(), 0xa831c66du.toInt(), 0xb00327c8u.toInt(), 0xbf597fc7u.toInt(),
        0xc6e00bf3u.toInt(), 0xd5a79147u.toInt(), 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92eu.toInt(), 0x92722c85u.toInt(),
        0xa2bfe8a1u.toInt(), 0xa81a664bu.toInt(), 0xc24b8b70u.toInt(), 0xc76c51a3u.toInt(),
        0xd192e819u.toInt(), 0xd6990624u.toInt(), 0xf40e3585u.toInt(), 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
        0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814u.toInt(), 0x8cc70208u.toInt(),
        0x90befffau.toInt(), 0xa4506cebu.toInt(), 0xbef9a3f7u.toInt(), 0xc67178f2u.toInt()
    )

    fun digest(input: ByteArray): ByteArray {
        val paddedLength = ((input.size + 9 + 63) / 64) * 64
        val padded = ByteArray(paddedLength)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        val bitLength = input.size.toLong() * 8L
        repeat(8) { index ->
            padded[padded.lastIndex - index] = (bitLength ushr (index * 8)).toByte()
        }

        var h0 = 0x6a09e667
        var h1 = 0xbb67ae85u.toInt()
        var h2 = 0x3c6ef372
        var h3 = 0xa54ff53au.toInt()
        var h4 = 0x510e527f
        var h5 = 0x9b05688cu.toInt()
        var h6 = 0x1f83d9ab
        var h7 = 0x5be0cd19
        val words = IntArray(64)

        var blockOffset = 0
        while (blockOffset < padded.size) {
            for (index in 0 until 16) {
                val offset = blockOffset + index * 4
                words[index] =
                    ((padded[offset].toInt() and 0xff) shl 24) or
                        ((padded[offset + 1].toInt() and 0xff) shl 16) or
                        ((padded[offset + 2].toInt() and 0xff) shl 8) or
                        (padded[offset + 3].toInt() and 0xff)
            }
            for (index in 16 until 64) {
                val s0 = words[index - 15].rotateRight(7) xor
                    words[index - 15].rotateRight(18) xor
                    (words[index - 15] ushr 3)
                val s1 = words[index - 2].rotateRight(17) xor
                    words[index - 2].rotateRight(19) xor
                    (words[index - 2] ushr 10)
                words[index] = words[index - 16] + s0 + words[index - 7] + s1
            }

            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4
            var f = h5
            var g = h6
            var h = h7
            for (index in 0 until 64) {
                val sum1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val choose = (e and f) xor (e.inv() and g)
                val temp1 = h + sum1 + choose + roundConstants[index] + words[index]
                val sum0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val majority = (a and b) xor (a and c) xor (b and c)
                val temp2 = sum0 + majority
                h = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }
            h0 += a
            h1 += b
            h2 += c
            h3 += d
            h4 += e
            h5 += f
            h6 += g
            h7 += h
            blockOffset += 64
        }

        val output = ByteArray(32)
        intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7).forEachIndexed { index, value ->
            val offset = index * 4
            output[offset] = (value ushr 24).toByte()
            output[offset + 1] = (value ushr 16).toByte()
            output[offset + 2] = (value ushr 8).toByte()
            output[offset + 3] = value.toByte()
        }
        return output
    }
}

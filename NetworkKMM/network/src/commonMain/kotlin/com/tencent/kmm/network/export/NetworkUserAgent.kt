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

import com.tencent.kmm.network.getPlatform

/**
 * Identity of the application embedding this library.
 *
 * The library cannot know these values on its own, so the host supplies them
 * through [NetworkUserAgent.appIdentity]. When it does not, requests still
 * carry a User-Agent — the application fields degrade to `unknown` rather than
 * the header being omitted, because a missing header and an unidentified
 * client are different facts and server logs should be able to tell them apart.
 */
class NetworkAppIdentity(
    /** Product name, e.g. `Raft`. */
    val name: String,
    /** User-visible version, e.g. `1.10.0`. */
    val version: String,
    /** Build number / versionCode, e.g. `1100004`. Omitted from the header when blank. */
    val buildNumber: String = "",
    /** Distribution channel, e.g. `production`, `alpha`. Omitted when blank. */
    val buildType: String = ""
)

/**
 * Default `User-Agent` for requests that do not carry one.
 *
 * Before this existed the library set no User-Agent at any layer, and the
 * default transport is curl, which sends none unless asked. Servers therefore
 * received no User-Agent at all and could not tell which client build produced
 * a request — which is exactly what an incident investigation needs first.
 *
 * A caller that sets its own `User-Agent` always wins; this only fills a gap.
 */
object NetworkUserAgent {

    /**
     * Version of this library, reported so a server can tell transports apart.
     *
     * Generated from the published `mavenVersion` at build time rather than
     * hand-maintained: a release bump that updated only the property would
     * otherwise ship a constant reporting the previous version, and nothing
     * would fail. Both build trees derive from the same property, so this
     * value is identical on all platforms.
     */
    const val LIBRARY_VERSION: String = NETWORK_KMM_VERSION

    /** Header this object populates. HTTP header names are case-insensitive. */
    const val HEADER_NAME: String = "User-Agent"

    /**
     * Host-supplied application identity. Set once during application start-up,
     * before the first request. `null` yields an `unknown` application segment.
     */
    var appIdentity: NetworkAppIdentity? = null

    /**
     * Builds the default header value, e.g.
     * `Raft/1.10.0+1100004 (Android 34; production) NetworkKMM/0.1.0-raft.35`.
     */
    fun headerValue(): String {
        val identity = appIdentity
        val application = if (identity == null) {
            "unknown/unknown"
        } else {
            val version = if (identity.buildNumber.isBlank()) {
                identity.version
            } else {
                "${identity.version}+${identity.buildNumber}"
            }
            "${identity.name.sanitized()}/${version.sanitized()}"
        }
        val platform = getPlatform().name.sanitized()
        val buildType = identity?.buildType?.takeIf { it.isNotBlank() }?.sanitized()
        val context = if (buildType == null) platform else "$platform; $buildType"
        return "$application ($context) NetworkKMM/$LIBRARY_VERSION"
    }

    /**
     * Fills in the default only when the caller did not set one.
     *
     * The lookup is case-insensitive because HTTP header names are: a caller
     * that set `user-agent` must not end up with two of them on the wire.
     * Returns true when a value was written, so callers can assert on it.
     */
    fun applyTo(headers: MutableMap<String, String>): Boolean {
        val alreadySet = headers.keys.any { it.equals(HEADER_NAME, ignoreCase = true) }
        if (alreadySet) {
            return false
        }
        headers[HEADER_NAME] = headerValue()
        return true
    }

    /**
     * Header values may not contain control characters.
     *
     * CR/LF is the dangerous case — a newline reaching the wire would let a
     * host-supplied string inject additional headers — but C1 (0x80..0x9F) is
     * excluded too: intermediaries and log pipelines handle it inconsistently,
     * and an earlier version of this method covered only C0 and DEL.
     *
     * Characters are replaced rather than dropped so the value stays
     * recognisable in logs instead of silently losing content.
     */
    private fun String.sanitized(): String = map { character ->
        val code = character.code
        val isC0OrDelete = code < 0x20 || code == 0x7F
        val isC1 = code in 0x80..0x9F
        if (isC0OrDelete || isC1) '_' else character
    }.joinToString("")
}

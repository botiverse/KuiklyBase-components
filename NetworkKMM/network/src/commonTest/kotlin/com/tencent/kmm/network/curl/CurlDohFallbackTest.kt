package com.tencent.kmm.network.curl

import com.tencent.kmm.network.export.NetworkError
import com.tencent.kmm.network.export.NetworkErrorKind
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkResponseBody
import com.tencent.kmm.network.export.VBTransportCurl
import com.tencent.kmm.network.service.isCurlUnresolvedHost
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Raft task #153: the DoH fallback loop shared by the Android, iOS and OHOS curl engines. */
class CurlDohFallbackTest {
    private var now = 0L
    private val preference = CurlDohPreference(nowMillis = { now }, windowMillis = 60_000)

    @AfterTest
    fun resetProcessPreference() {
        curlDohPreference.clear()
    }

    private fun unresolved() = CurlNativeResponse(code = CURL_CODE_COULDNT_RESOLVE_HOST, errorMsg = "Could not resolve host")
    private fun ok() = CurlNativeResponse(code = 0, httpCode = 200)
    private fun staleMiss() = CurlNativeResponse(code = CURL_CODE_COULDNT_RESOLVE_HOST, errorMsg = "stale_address_miss")

    private fun run(
        first: CurlNativeResponse,
        firstProvider: Int,
        configured: List<Int>,
        results: Map<Int, CurlNativeResponse>,
        attempts: MutableList<Int>,
        remaining: Long? = null,
        cancelled: Boolean = false,
    ): CurlNativeResponse? = runBlocking {
        runCurlDohFallback(
            first = first,
            firstProvider = firstProvider,
            configuredProviders = configured,
            isCancelled = { cancelled },
            remainingTimeoutMillis = { remaining },
            preference = preference,
        ) { provider, _ ->
            attempts += provider
            results.getValue(provider)
        }
    }

    @Test
    fun aResolvedFirstAttemptIsNeverRetried() {
        val attempts = mutableListOf<Int>()
        assertNull(run(ok(), firstProvider = 0, configured = listOf(1, 2), results = emptyMap(), attempts = attempts))
        assertEquals(emptyList(), attempts)
    }

    @Test
    fun providersAreTriedInOrderAndTheWinnerBecomesPreferred() {
        val attempts = mutableListOf<Int>()
        val result = run(unresolved(), 0, listOf(1, 2), mapOf(CURL_DOH_PROVIDER_STALE_ADDRESS to staleMiss(), 1 to unresolved(), 2 to ok()), attempts)
        assertEquals(listOf(CURL_DOH_PROVIDER_STALE_ADDRESS, 1, 2), attempts, "the stale address is tried before any DoH provider")
        assertEquals("doh_fallback_success", result?.elapse?.freshRetryResult)
        assertTrue(result?.elapse?.freshRetry == true)
        assertEquals(2, preference.preferredProvider(listOf(1, 2)))
    }

    @Test
    fun allProvidersFailingIsReportedAndLeavesNoPreference() {
        val attempts = mutableListOf<Int>()
        val result = run(unresolved(), 0, listOf(1, 2), mapOf(CURL_DOH_PROVIDER_STALE_ADDRESS to staleMiss(), 1 to unresolved(), 2 to unresolved()), attempts)
        assertEquals(listOf(CURL_DOH_PROVIDER_STALE_ADDRESS, 1, 2), attempts)
        assertEquals("doh_fallback_failure", result?.elapse?.freshRetryResult)
        assertEquals(0, preference.preferredProvider(listOf(1, 2)))
    }

    @Test
    fun aPreferredProviderThatResolvesIsMarkedAndAFailingOneFallsBackToTheSystem() {
        val attempts = mutableListOf<Int>()
        val preferredOk = ok()
        assertNull(run(preferredOk, firstProvider = 2, configured = listOf(1, 2), results = emptyMap(), attempts = attempts))
        assertEquals("doh_preferred", preferredOk.elapse.freshRetryResult)

        preference.onDohResolved(2)
        val result = run(unresolved(), 2, listOf(1, 2), mapOf(0 to ok()), attempts)
        assertEquals(listOf(0), attempts, "the system resolver comes first after a failed preferred provider")
        assertEquals("system_after_doh_failure", result?.elapse?.freshRetryResult)
        assertEquals(0, preference.preferredProvider(listOf(1, 2)), "a failed preferred provider is dropped")
    }

    @Test
    fun aStaleAddressHitEndsTheWalkWithoutTouchingTheDohPreference() {
        val attempts = mutableListOf<Int>()
        val result = run(unresolved(), 0, listOf(1, 2), mapOf(CURL_DOH_PROVIDER_STALE_ADDRESS to ok()), attempts)
        assertEquals(listOf(CURL_DOH_PROVIDER_STALE_ADDRESS), attempts, "no DoH provider is asked once the stale address connected")
        assertEquals("stale_address_success", result?.elapse?.freshRetryResult)
        assertTrue(result?.elapse?.freshRetry == true)
        assertEquals(0, preference.preferredProvider(listOf(1, 2)), "a stale hit is not a DoH preference")
    }

    @Test
    fun aStaleAddressMissDoesNotClearAnExistingDohPreferenceWindow() {
        preference.onDohResolved(1)
        val attempts = mutableListOf<Int>()
        // Preferred provider 1 failed → system → stale miss → provider 2 resolves.
        val result = run(unresolved(), 1, listOf(1, 2), mapOf(0 to unresolved(), CURL_DOH_PROVIDER_STALE_ADDRESS to staleMiss(), 2 to ok()), attempts)
        assertEquals(listOf(0, CURL_DOH_PROVIDER_STALE_ADDRESS, 2), attempts)
        assertEquals("doh_fallback_success", result?.elapse?.freshRetryResult)
        assertEquals(2, preference.preferredProvider(listOf(1, 2)))
    }

    @Test
    fun theStaleAddressIsNeverListedTwiceEvenIfAHostConfiguresIt() {
        assertEquals(listOf(CURL_DOH_PROVIDER_STALE_ADDRESS, 1), curlDohFallbackAttempts(0, listOf(CURL_DOH_PROVIDER_STALE_ADDRESS, 1, CURL_DOH_PROVIDER_STALE_ADDRESS)))
        assertEquals(listOf(0, CURL_DOH_PROVIDER_STALE_ADDRESS, 2), curlDohFallbackAttempts(1, listOf(1, 2)))
        assertEquals(emptyList(), curlDohFallbackAttempts(0, emptyList()), "DoH off also turns the stale attempt off")
        assertEquals(emptyList(), curlDohFallbackAttempts(0, listOf(CURL_DOH_PROVIDER_STALE_ADDRESS)))
    }

    @Test
    fun noRetryWhenCancelledOrOutOfTime() {
        val attempts = mutableListOf<Int>()
        assertNull(run(unresolved(), 0, listOf(1), mapOf(1 to ok()), attempts, cancelled = true))
        assertNull(run(unresolved(), 0, listOf(1), mapOf(1 to ok()), attempts, remaining = 0L))
        assertEquals(emptyList(), attempts)
    }

    @Test
    fun aNetworkChangeDropsTheProcessWideWindow() {
        curlDohPreference.onDohResolved(1)
        assertEquals(1, curlDohPreference.preferredProvider(listOf(1)))
        VBTransportCurl.onNetworkChanged()
        assertEquals(0, curlDohPreference.preferredProvider(listOf(1)))
    }

    @Test
    fun ohosTriggerIsTheCurlCodeNotTheDnsTextMatch() {
        fun failed(rawCode: Int, statusCode: Int? = null) = NetworkResponse(
            request = NetworkRequest(),
            statusCode = statusCode,
            headers = emptyMap(),
            body = NetworkResponseBody(),
            error = NetworkError(NetworkErrorKind.DNS, "[dns] could not resolve", statusCode, rawCode = rawCode),
        )
        assertTrue(failed(CURL_CODE_COULDNT_RESOLVE_HOST).isCurlUnresolvedHost())
        // CURLE_COULDNT_RESOLVE_PROXY is classified DNS by text, but DoH cannot fix a proxy name.
        assertFalse(failed(5).isCurlUnresolvedHost())
        assertFalse(failed(CURL_CODE_COULDNT_RESOLVE_HOST, statusCode = 502).isCurlUnresolvedHost())
    }
}

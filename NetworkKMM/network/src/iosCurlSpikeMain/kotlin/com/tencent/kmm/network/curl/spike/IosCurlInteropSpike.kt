@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.tencent.kmm.network.curl.spike

import com.tencent.kmm.network.curl.spike.native.CreateCurlClient
import com.tencent.kmm.network.curl.spike.native.DeleteCurlClient
import com.tencent.kmm.network.curl.spike.native.SetCurlCaInfo

/** Compile-only proof that the existing C wrapper is consumable from Kotlin/Native on iOS. */
internal fun compileIosCurlInteropSpike(caInfoPath: String?): Boolean {
    val handle = CreateCurlClient("ios-kotlin-cinterop-spike") ?: return false
    SetCurlCaInfo(handle, caInfoPath)
    DeleteCurlClient(handle)
    return true
}

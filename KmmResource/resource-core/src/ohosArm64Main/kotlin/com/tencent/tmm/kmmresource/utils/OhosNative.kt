package com.tencent.tmm.kmmresource.utils

import com.tencent.tmm.kmmresource.ohos_get_color_by_name
import com.tencent.tmm.kmmresource.ohos_get_image_base64_by_name
import com.tencent.tmm.kmmresource.ohos_get_media_by_name
import com.tencent.tmm.kmmresource.ohos_get_string_plural_by_name
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.free
import platform.posix.memcpy
import platform.posix.uint32_tVar
import platform.posix.uint64_tVar
import platform.posix.uint8_tVar

/**
 * Process media info
 *  测试通过
 * @param mediaName
 * @return
 */
@OptIn(ExperimentalForeignApi::class)
internal fun processMediaInfo(mediaName: String): ByteArray? {
    memScoped {
        val mediaLenVar = alloc<uint64_tVar>()
        val mediaDataVar =
            alloc<CPointerVar<uint8_tVar>>() // Assuming a maximum size of 1024 bytes for media data
        val density: UInt = 0u // Assuming a default value for density

        val resultCode =
            ohos_get_media_by_name(mediaName, mediaLenVar.ptr, mediaDataVar.ptr, density)
        return if (resultCode == 0) {
            val result = ByteArray(mediaLenVar.value.toInt())
            result.usePinned {
                memcpy(it.addressOf(0), mediaDataVar.value, mediaLenVar.value)
            }
            result
        } else {
            null
        }

    }
}

/**
 * Get color by name
 *  测试通过
 * @param name
 * @return
 */
@OptIn(ExperimentalForeignApi::class)
internal fun getColorByName(name: String): Int {
    memScoped {
        val resValue = alloc<uint32_tVar>()
        val resultCode = ohos_get_color_by_name(name, resValue.ptr)
        return if (resultCode == 0) {
            resValue.value.toInt()
        } else {
            throw RuntimeException("getColorByName $name failed $resultCode")
        }
    }
}

/**
 * Get plural string by name
 *  测试通过
 * @param name
 * @param number
 * @return
 */
@OptIn(ExperimentalForeignApi::class)
internal fun getPluralStringByName(name: String, number: UInt): String? {
    memScoped {
        val resValue = alloc<CPointerVar<ByteVar>>()
        val resultCode = ohos_get_string_plural_by_name(name, number, resValue.ptr)
        return if (resultCode == 0) {
            val res = resValue.value?.toKString()
            /**
             * 手动释放native内存
             */
            free(resValue.value)
            res
        } else {
            throw RuntimeException("getPluralStringByName $name number $number failed $resultCode")
        }
    }
}

/**
 * Get image base64by name
 *  测试通过
 * @param name
 * @return
 */
@OptIn(ExperimentalForeignApi::class)
internal fun getImageBase64ByName(name: String): String? {
    memScoped {
        val resValue = alloc<CPointerVar<ByteVar>>()
        val mediaLenVar = alloc<uint64_tVar>()
        val resultCode = ohos_get_image_base64_by_name(name, resValue.ptr, mediaLenVar.ptr, 0u)
        return if (resultCode == 0) {
            val res = resValue.value?.toKString()
            /**
             * 手动释放native内存
             */
            free(resValue.value)
            res
        } else {
            throw RuntimeException("getImageBase64ByName $name failed $resultCode")
        }
    }
}

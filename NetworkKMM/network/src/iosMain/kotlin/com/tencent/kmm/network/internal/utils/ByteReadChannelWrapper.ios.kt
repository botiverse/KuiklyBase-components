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
package com.tencent.kmm.network.internal.utils

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.isEmpty
import io.ktor.utils.io.core.readBytes

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual class ByteReadChannelWrapper(private val byteReadChannel: Any) {
    actual suspend fun readBytes(limit: Long): ByteArray {
        if (byteReadChannel is ByteReadChannel) {
            val chunks = mutableListOf<ByteArray>()
            var totalSize = 0

            while (true) {
                val packet = byteReadChannel.readRemaining(limit)
                if (packet.isEmpty) {
                    break
                }

                val bytes = packet.readBytes()
                chunks.add(bytes)
                totalSize += bytes.size
            }

            // 使用平台特定的合并方法
            return ByteArray(totalSize).apply {
                mergeFromChunks(chunks)
            }
        }
        return ByteArray(0)
    }

    actual suspend fun readAvailable(size: Long): ByteArray {
        if (byteReadChannel is ByteReadChannel) {
            // raft.9: `size` comes from the Content-Length header, which is a
            // hint, not a guarantee — the delivered byte count can fall short
            // (early close) or overshoot (engine transparently decompressed
            // the body while the header still carries the compressed length).
            // ktor's readAvailable returns -1 at EOF; the previous loop never
            // handled it and spun until the request timeout, surfacing as an
            // opaque transport error ("HTTP 0").
            val expected = size.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            val head = ByteArray(expected)
            var readTotal = 0
            var reachedEof = false
            while (readTotal < expected) {
                val count = byteReadChannel.readAvailable(head, readTotal, expected - readTotal)
                if (count < 0) {
                    reachedEof = true
                    break
                }
                readTotal += count
            }
            return if (reachedEof) {
                head.copyOf(readTotal)
            } else {
                val tail = readBytes(READ_TO_EOF_CHUNK_BYTES)
                if (tail.isEmpty()) head else head + tail
            }
        }
        return ByteArray(0)
    }

    private companion object {
        // Chunk size for draining any bytes delivered beyond Content-Length.
        private const val READ_TO_EOF_CHUNK_BYTES = 8L * 1024L
    }
}
#ifndef NETWORKKMM_CURL_FACTS_COMPAT_H
#define NETWORKKMM_CURL_FACTS_COMPAT_H

#include "curl_wrapper.h"

// Native apps can be rebuilt before their packaged wrapper artifact is. Keep
// the additive facts symbol weak so an older artifact remains request-capable:
// callers receive unavailable facts instead of a load/link failure.
#if defined(__APPLE__)
extern int GetCurlTransferInfoV1(CurClientHandle handle, CurlTransferInfoV1 *info,
                                 size_t infoSize, int abiVersion)
    __attribute__((weak_import));
#elif defined(__GNUC__)
#pragma weak GetCurlTransferInfoV1
#endif

#if defined(__APPLE__)
extern void SetCurlMaxBufferedResponseBytes(CurClientHandle handle, int64_t maxBytes)
    __attribute__((weak_import));
#elif defined(__GNUC__)
#pragma weak SetCurlMaxBufferedResponseBytes
#endif

#if defined(__APPLE__)
extern void SetCurlBufferedBodyIdleTimeoutMs(CurClientHandle handle, int64_t timeoutMs)
    __attribute__((weak_import));
#elif defined(__GNUC__)
#pragma weak SetCurlBufferedBodyIdleTimeoutMs
#endif

static inline int NetworkKmmGetCurlTransferInfoV1IfAvailable(
    CurClientHandle handle,
    CurlTransferInfoV1 *info,
    size_t infoSize,
    int abiVersion
) {
    if (GetCurlTransferInfoV1 == 0) {
        return 0;
    }
    return GetCurlTransferInfoV1(handle, info, infoSize, abiVersion);
}

static inline int NetworkKmmSetCurlMaxBufferedResponseBytesIfAvailable(
    CurClientHandle handle,
    int64_t maxBytes
) {
    if (SetCurlMaxBufferedResponseBytes == 0) {
        return 0;
    }
    SetCurlMaxBufferedResponseBytes(handle, maxBytes);
    return 1;
}

static inline int NetworkKmmSetCurlBufferedBodyIdleTimeoutMsIfAvailable(
    CurClientHandle handle,
    int64_t timeoutMs
) {
    if (SetCurlBufferedBodyIdleTimeoutMs == 0) {
        return 0;
    }
    SetCurlBufferedBodyIdleTimeoutMs(handle, timeoutMs);
    return 1;
}

#endif  // NETWORKKMM_CURL_FACTS_COMPAT_H

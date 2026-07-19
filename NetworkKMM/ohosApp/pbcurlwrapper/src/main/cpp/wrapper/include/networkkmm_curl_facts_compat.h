#ifndef NETWORKKMM_CURL_FACTS_COMPAT_H
#define NETWORKKMM_CURL_FACTS_COMPAT_H

#include "curl_wrapper.h"

#if defined(__APPLE__)
#include <dlfcn.h>
#endif

// Native apps can be rebuilt before their packaged wrapper artifact is. Keep
// the additive facts symbol weak so an older artifact remains request-capable:
// callers receive unavailable facts instead of a load/link failure.
#if !defined(__APPLE__) && defined(__GNUC__)
#pragma weak GetCurlTransferInfoV1
#endif

#if !defined(__APPLE__) && defined(__GNUC__)
#pragma weak SetCurlMaxBufferedResponseBytes
#endif

#if !defined(__APPLE__) && defined(__GNUC__)
#pragma weak SetCurlBufferedBodyIdleTimeoutMs
#endif

static inline int NetworkKmmGetCurlTransferInfoV1IfAvailable(
    CurClientHandle handle,
    CurlTransferInfoV1 *info,
    size_t infoSize,
    int abiVersion
) {
#if defined(__APPLE__)
    typedef int (*GetTransferInfoFn)(
        CurClientHandle,
        CurlTransferInfoV1 *,
        size_t,
        int);
    GetTransferInfoFn function = (GetTransferInfoFn)dlsym(
        RTLD_DEFAULT,
        "GetCurlTransferInfoV1");
    if (function == 0) {
        return 0;
    }
    return function(handle, info, infoSize, abiVersion);
#else
    if (GetCurlTransferInfoV1 == 0) {
        return 0;
    }
    return GetCurlTransferInfoV1(handle, info, infoSize, abiVersion);
#endif
}

static inline int NetworkKmmSetCurlMaxBufferedResponseBytesIfAvailable(
    CurClientHandle handle,
    int64_t maxBytes
) {
#if defined(__APPLE__)
    typedef void (*SetMaxBufferedResponseBytesFn)(CurClientHandle, int64_t);
    SetMaxBufferedResponseBytesFn function = (SetMaxBufferedResponseBytesFn)dlsym(
        RTLD_DEFAULT,
        "SetCurlMaxBufferedResponseBytes");
    if (function == 0) {
        return 0;
    }
    function(handle, maxBytes);
    return 1;
#else
    if (SetCurlMaxBufferedResponseBytes == 0) {
        return 0;
    }
    SetCurlMaxBufferedResponseBytes(handle, maxBytes);
    return 1;
#endif
}

static inline int NetworkKmmSetCurlBufferedBodyIdleTimeoutMsIfAvailable(
    CurClientHandle handle,
    int64_t timeoutMs
) {
#if defined(__APPLE__)
    typedef void (*SetBufferedBodyIdleTimeoutMsFn)(CurClientHandle, int64_t);
    SetBufferedBodyIdleTimeoutMsFn function = (SetBufferedBodyIdleTimeoutMsFn)dlsym(
        RTLD_DEFAULT,
        "SetCurlBufferedBodyIdleTimeoutMs");
    if (function == 0) {
        return 0;
    }
    function(handle, timeoutMs);
    return 1;
#else
    if (SetCurlBufferedBodyIdleTimeoutMs == 0) {
        return 0;
    }
    SetCurlBufferedBodyIdleTimeoutMs(handle, timeoutMs);
    return 1;
#endif
}

#endif  // NETWORKKMM_CURL_FACTS_COMPAT_H

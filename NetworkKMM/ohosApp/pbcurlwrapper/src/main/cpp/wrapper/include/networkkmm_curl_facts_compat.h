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
#pragma weak CreateCurlMultiEngine
#pragma weak SubmitBufferedRequestV27
#pragma weak CancelCurlMultiRequest
#pragma weak GetCurlMultiInfoV1
#endif

static inline int NetworkKmmCurlMultiApiAvailable(void) {
#if defined(__APPLE__)
    return dlsym(RTLD_DEFAULT, "CreateCurlMultiEngine") != 0 &&
        dlsym(RTLD_DEFAULT, "SubmitBufferedRequestV27") != 0 &&
        dlsym(RTLD_DEFAULT, "CancelCurlMultiRequest") != 0 &&
        dlsym(RTLD_DEFAULT, "GetCurlMultiInfoV1") != 0;
#else
    return CreateCurlMultiEngine != 0 &&
        SubmitBufferedRequestV27 != 0 &&
        CancelCurlMultiRequest != 0 &&
        GetCurlMultiInfoV1 != 0;
#endif
}

static inline CurlMultiEngineHandle NetworkKmmCreateCurlMultiEngineIfAvailable(
    const char *logTag
) {
#if defined(__APPLE__)
    typedef CurlMultiEngineHandle (*CreateFn)(const char *);
    CreateFn function = (CreateFn)dlsym(RTLD_DEFAULT, "CreateCurlMultiEngine");
    return function == 0 ? 0 : function(logTag);
#else
    return CreateCurlMultiEngine == 0 ? 0 : CreateCurlMultiEngine(logTag);
#endif
}

static inline int NetworkKmmSubmitBufferedRequestV27IfAvailable(
    CurlMultiEngineHandle engine,
    int64_t requestId,
    CurClientHandle handle,
    const CurlRequest *request,
    size_t requestSize,
    int abiVersion,
    const CurlCallback *callback
) {
#if defined(__APPLE__)
    typedef int (*SubmitFn)(CurlMultiEngineHandle, int64_t, CurClientHandle,
        const CurlRequest *, size_t, int, const CurlCallback *);
    SubmitFn function = (SubmitFn)dlsym(RTLD_DEFAULT, "SubmitBufferedRequestV27");
    return function == 0 ? 0 : function(
        engine, requestId, handle, request, requestSize, abiVersion, callback);
#else
    return SubmitBufferedRequestV27 == 0 ? 0 : SubmitBufferedRequestV27(
        engine, requestId, handle, request, requestSize, abiVersion, callback);
#endif
}

static inline void NetworkKmmCancelCurlMultiRequestIfAvailable(
    CurlMultiEngineHandle engine,
    int64_t requestId
) {
#if defined(__APPLE__)
    typedef void (*CancelFn)(CurlMultiEngineHandle, int64_t);
    CancelFn function = (CancelFn)dlsym(RTLD_DEFAULT, "CancelCurlMultiRequest");
    if (function != 0) function(engine, requestId);
#else
    if (CancelCurlMultiRequest != 0) CancelCurlMultiRequest(engine, requestId);
#endif
}

static inline int NetworkKmmGetCurlMultiInfoV1IfAvailable(
    CurClientHandle handle,
    CurlMultiInfoV1 *info,
    size_t infoSize,
    int abiVersion
) {
#if defined(__APPLE__)
    typedef int (*GetInfoFn)(CurClientHandle, CurlMultiInfoV1 *, size_t, int);
    GetInfoFn function = (GetInfoFn)dlsym(RTLD_DEFAULT, "GetCurlMultiInfoV1");
    return function == 0 ? 0 : function(handle, info, infoSize, abiVersion);
#else
    return GetCurlMultiInfoV1 == 0 ? 0 :
        GetCurlMultiInfoV1(handle, info, infoSize, abiVersion);
#endif
}

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

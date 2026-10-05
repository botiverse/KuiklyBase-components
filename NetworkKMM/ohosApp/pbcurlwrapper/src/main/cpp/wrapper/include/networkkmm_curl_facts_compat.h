#ifndef NETWORKKMM_CURL_FACTS_COMPAT_H
#define NETWORKKMM_CURL_FACTS_COMPAT_H

#include "curl_wrapper.h"

// New cinterop clients must still link against older wrapper archives. On
// Apple, bind optional functions weakly at link time: dlsym(RTLD_DEFAULT)
// cannot find static wrapper functions hidden by a Release app's export table.
// The required wrapper calls pull in the same archive object; optional symbols
// resolve when present and remain null when linking an older wrapper.
// ios_curl.def permits only these eight weak imports to remain unresolved.
#if defined(__APPLE__)
extern __typeof__(GetCurlTransferInfoV1) GetCurlTransferInfoV1 __attribute__((weak_import));
extern __typeof__(GetCurlCompletionInfoV1) GetCurlCompletionInfoV1 __attribute__((weak_import));
extern __typeof__(SetCurlMaxBufferedResponseBytes) SetCurlMaxBufferedResponseBytes __attribute__((weak_import));
extern __typeof__(SetCurlBufferedBodyIdleTimeoutMs) SetCurlBufferedBodyIdleTimeoutMs __attribute__((weak_import));
extern __typeof__(CreateCurlMultiEngine) CreateCurlMultiEngine __attribute__((weak_import));
extern __typeof__(SubmitBufferedRequestV27) SubmitBufferedRequestV27 __attribute__((weak_import));
extern __typeof__(CancelCurlMultiRequest) CancelCurlMultiRequest __attribute__((weak_import));
extern __typeof__(GetCurlMultiInfoV1) GetCurlMultiInfoV1 __attribute__((weak_import));
#elif defined(__GNUC__)
#pragma weak GetCurlTransferInfoV1
#pragma weak GetCurlCompletionInfoV1
#pragma weak SetCurlMaxBufferedResponseBytes
#pragma weak SetCurlBufferedBodyIdleTimeoutMs
#pragma weak CreateCurlMultiEngine
#pragma weak SubmitBufferedRequestV27
#pragma weak CancelCurlMultiRequest
#pragma weak GetCurlMultiInfoV1
#endif

static inline int NetworkKmmCurlMultiApiAvailable(void) {
    return CreateCurlMultiEngine != 0 &&
        SubmitBufferedRequestV27 != 0 &&
        CancelCurlMultiRequest != 0 &&
        GetCurlMultiInfoV1 != 0;
}

static inline CurlMultiEngineHandle NetworkKmmCreateCurlMultiEngineIfAvailable(
    const char *logTag
) {
    if (!NetworkKmmCurlMultiApiAvailable()) return 0;
    return CreateCurlMultiEngine(logTag);
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
    if (!NetworkKmmCurlMultiApiAvailable()) return 0;
    return SubmitBufferedRequestV27(
        engine, requestId, handle, request, requestSize, abiVersion, callback);
}

static inline void NetworkKmmCancelCurlMultiRequestIfAvailable(
    CurlMultiEngineHandle engine,
    int64_t requestId
) {
    if (!NetworkKmmCurlMultiApiAvailable()) return;
    CancelCurlMultiRequest(engine, requestId);
}

static inline int NetworkKmmGetCurlMultiInfoV1IfAvailable(
    CurClientHandle handle,
    CurlMultiInfoV1 *info,
    size_t infoSize,
    int abiVersion
) {
    if (!NetworkKmmCurlMultiApiAvailable()) return 0;
    return GetCurlMultiInfoV1(handle, info, infoSize, abiVersion);
}

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

static inline int NetworkKmmGetCurlCompletionInfoV1IfAvailable(
    CurClientHandle handle,
    CurlCompletionInfoV1 *info,
    size_t infoSize,
    int abiVersion
) {
    if (GetCurlCompletionInfoV1 == 0) {
        return 0;
    }
    return GetCurlCompletionInfoV1(handle, info, infoSize, abiVersion);
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

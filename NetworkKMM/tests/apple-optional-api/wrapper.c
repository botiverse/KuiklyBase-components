#include "curl_wrapper.h"
#include <assert.h>
#include <stdint.h>

// One archive member, like curl_wrapper.cpp. Required ABI pulls it in.
int CurlWrapperAbiVersion(void) { return 27; }
#ifdef OPTIONAL_API
static int calls;
int OptionalApiCalls(void) { return calls; }
CurlMultiEngineHandle CreateCurlMultiEngine(const char *tag) {
    assert(tag && tag[0] == 't'); ++calls;
    return (CurlMultiEngineHandle)(uintptr_t)0x1234;
}
int SubmitBufferedRequestV27(CurlMultiEngineHandle engine, int64_t id,
    CurClientHandle handle, const CurlRequest *request, size_t size, int abi,
    const CurlCallback *callback) {
    assert((uintptr_t)engine == 0x1234 && id == 42 && !handle && !request &&
           size == 17 && abi == 27 && !callback); ++calls; return 81;
}
void CancelCurlMultiRequest(CurlMultiEngineHandle engine, int64_t id) {
    assert((uintptr_t)engine == 0x1234 && id == 42); ++calls;
}
int GetCurlMultiInfoV1(CurClientHandle h, CurlMultiInfoV1 *i, size_t s, int a) {
    assert(!h && !i && s == 19 && a == 27); ++calls; return 82;
}
int GetCurlTransferInfoV1(CurClientHandle h, CurlTransferInfoV1 *i, size_t s, int a) {
    assert(!h && !i && s == 23 && a == 27); ++calls; return 83;
}
int GetCurlCompletionInfoV1(CurClientHandle h, CurlCompletionInfoV1 *i, size_t s, int a) {
    assert(!h && !i && s == 29 && a == 27); ++calls; return 84;
}
void SetCurlMaxBufferedResponseBytes(CurClientHandle h, int64_t n) {
    assert(!h && n == 123); ++calls;
}
void SetCurlBufferedBodyIdleTimeoutMs(CurClientHandle h, int64_t n) {
    assert(!h && n == 456); ++calls;
}
#endif

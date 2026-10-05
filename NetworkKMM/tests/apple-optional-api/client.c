#include "networkkmm_curl_facts_compat.h"
#include <assert.h>
#include <stdio.h>
#if EXPECT_AVAILABLE
int OptionalApiCalls(void);
#endif
int main(void) {
    assert(CurlWrapperAbiVersion() == 27);
    assert(NetworkKmmCurlMultiApiAvailable() == EXPECT_AVAILABLE);
    CurlMultiEngineHandle engine = NetworkKmmCreateCurlMultiEngineIfAvailable("test");
    assert((engine != 0) == EXPECT_AVAILABLE);
    assert(NetworkKmmSubmitBufferedRequestV27IfAvailable(engine, 42, 0, 0, 17, 27, 0)
           == (EXPECT_AVAILABLE ? 81 : 0));
    NetworkKmmCancelCurlMultiRequestIfAvailable(engine, 42);
    assert(NetworkKmmGetCurlMultiInfoV1IfAvailable(0, 0, 19, 27)
           == (EXPECT_AVAILABLE ? 82 : 0));
    assert(NetworkKmmGetCurlTransferInfoV1IfAvailable(0, 0, 23, 27)
           == (EXPECT_AVAILABLE ? 83 : 0));
    assert(NetworkKmmGetCurlCompletionInfoV1IfAvailable(0, 0, 29, 27)
           == (EXPECT_AVAILABLE ? 84 : 0));
    assert(NetworkKmmSetCurlMaxBufferedResponseBytesIfAvailable(0, 123) == EXPECT_AVAILABLE);
    assert(NetworkKmmSetCurlBufferedBodyIdleTimeoutMsIfAvailable(0, 456) == EXPECT_AVAILABLE);
#if EXPECT_AVAILABLE
    assert(OptionalApiCalls() == 8);
#endif
    printf("hidden static optional API: available=%d PASS\n", EXPECT_AVAILABLE);
    return 0;
}

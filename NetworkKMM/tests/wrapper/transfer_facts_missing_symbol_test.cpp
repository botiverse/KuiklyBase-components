#include "networkkmm_curl_facts_compat.h"

int main() {
    CurlTransferInfoV1 facts{};
    if (NetworkKmmGetCurlTransferInfoV1IfAvailable(
            nullptr,
            &facts,
            sizeof(facts),
            CURL_TRANSFER_INFO_ABI_VERSION) != 0) {
        return 1;
    }
    if (NetworkKmmSetCurlMaxBufferedResponseBytesIfAvailable(nullptr, 1024) != 0) {
        return 2;
    }
    if (NetworkKmmSetCurlBufferedBodyIdleTimeoutMsIfAvailable(nullptr, 7000) != 0) {
        return 3;
    }
    return 0;
}

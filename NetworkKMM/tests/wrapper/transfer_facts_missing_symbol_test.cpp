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
    CurlCompletionInfoV1 completion{};
    if (NetworkKmmGetCurlCompletionInfoV1IfAvailable(
            nullptr,
            &completion,
            sizeof(completion),
            CURL_COMPLETION_INFO_ABI_VERSION) != 0) {
        return 8;
    }
    if (NetworkKmmSetCurlMaxBufferedResponseBytesIfAvailable(nullptr, 1024) != 0) {
        return 2;
    }
    if (NetworkKmmSetCurlBufferedBodyIdleTimeoutMsIfAvailable(nullptr, 7000) != 0) {
        return 3;
    }
    if (NetworkKmmCurlMultiApiAvailable() != 0) {
        return 4;
    }
    if (NetworkKmmCreateCurlMultiEngineIfAvailable("missing") != nullptr) {
        return 5;
    }
    CurlRequest request{};
    CurlCallback callback{};
    if (NetworkKmmSubmitBufferedRequestV27IfAvailable(
            nullptr, 1, nullptr, &request, sizeof(request),
            CURL_WRAPPER_ABI_VERSION, &callback) != 0) {
        return 6;
    }
    NetworkKmmCancelCurlMultiRequestIfAvailable(nullptr, 1);
    CurlMultiInfoV1 multi{};
    if (NetworkKmmGetCurlMultiInfoV1IfAvailable(
            nullptr, &multi, sizeof(multi), CURL_MULTI_INFO_ABI_VERSION) != 0) {
        return 7;
    }
    return 0;
}

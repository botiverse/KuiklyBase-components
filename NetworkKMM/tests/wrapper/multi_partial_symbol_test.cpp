#include "networkkmm_curl_facts_compat.h"

static int createCalls = 0;

CurlMultiEngineHandle CreateCurlMultiEngine(const char *) {
    ++createCalls;
    return reinterpret_cast<CurlMultiEngineHandle>(1);
}

int main() {
    if (NetworkKmmCurlMultiApiAvailable() != 0) return 1;
    if (NetworkKmmCreateCurlMultiEngineIfAvailable("partial") != nullptr) return 2;
    if (createCalls != 0) return 3;
    CurlRequest request{};
    CurlCallback callback{};
    if (NetworkKmmSubmitBufferedRequestV27IfAvailable(
            nullptr, 1, nullptr, &request, sizeof(request),
            CURL_WRAPPER_ABI_VERSION, &callback) != 0) return 4;
    NetworkKmmCancelCurlMultiRequestIfAvailable(nullptr, 1);
    CurlMultiInfoV1 info{};
    if (NetworkKmmGetCurlMultiInfoV1IfAvailable(
            nullptr, &info, sizeof(info), CURL_MULTI_INFO_ABI_VERSION) != 0) return 5;
    return 0;
}

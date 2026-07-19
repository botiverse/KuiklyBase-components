#include "networkkmm_curl_facts_compat.h"

int main() {
    CurlTransferInfoV1 facts{};
    return NetworkKmmGetCurlTransferInfoV1IfAvailable(
               nullptr,
               &facts,
               sizeof(facts),
               CURL_TRANSFER_INFO_ABI_VERSION) == 0
        ? 0
        : 1;
}

#include "curl_wrapper.h"

int main() {
    CurlRequest request{};
    CurlCallback callback{};
    CurClientHandle handle = CreateCurlClient("v27-caller");
    const int started = StartRequestV27(
        handle, &request, sizeof(request), CURL_WRAPPER_ABI_VERSION, &callback);
    DeleteCurlClient(handle);
    return started;
}

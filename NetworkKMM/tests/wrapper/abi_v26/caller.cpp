#include "curl_wrapper_v26.h"

int main() {
    CurlRequest request{};
    CurlCallback callback{};
    CurClientHandle handle = CreateCurlClient("frozen-v26-caller");
    StartRequest(handle, request, &callback);
    DeleteCurlClient(handle);
    return 0;
}

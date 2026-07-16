#include "curl_wrapper_v26.h"

extern "C" CurClientHandle CreateCurlClient(const char *) { return nullptr; }
extern "C" void DeleteCurlClient(CurClientHandle) {}
extern "C" void StartRequest(CurClientHandle, CurlRequest, CurlCallback *) {}

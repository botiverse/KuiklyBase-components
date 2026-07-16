#pragma once

#include <cstdint>

typedef void *CurClientHandle;

typedef struct {
    void *stringPairs;
    int size;
} StringDic;

typedef struct {
    const char *url;
    const char *method;
    StringDic *headers;
    int64_t timeout;
    int postBodyLen;
    const char *postBody;
} CurlRequest;

typedef struct CurlResponse CurlResponse;

typedef struct {
    void *callbackRef;
    void (*callback)(void *callbackRef, CurlResponse *response);
} CurlCallback;

extern "C" CurClientHandle CreateCurlClient(const char *logTag);
extern "C" void DeleteCurlClient(CurClientHandle handle);
extern "C" void StartRequest(CurClientHandle handle, CurlRequest request, CurlCallback *callback);

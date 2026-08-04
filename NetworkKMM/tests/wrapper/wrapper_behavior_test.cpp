// Behavior-contract tests for pbcurlwrapper, run on the host (linux) against
// tests/wrapper/test_server.py. These lock down the wrapper's observable
// contract — most importantly that HTTP statuses pass through untouched
// (the raft.3 production bug: 401s surfaced as transfer-OK and Slock's auth
// refresh never fired).
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <sys/mman.h>
#include <unistd.h>
#include <curl/curl.h>
#include "curl_wrapper.h"

static int gFailures = 0;

#define CHECK(cond, msg)                                                     \
    do {                                                                     \
        if (!(cond)) {                                                       \
            std::fprintf(stderr, "FAIL: %s (%s:%d)\n", msg, __FILE__, __LINE__); \
            gFailures++;                                                     \
        } else {                                                             \
            std::fprintf(stderr, "ok:   %s\n", msg);                         \
        }                                                                    \
    } while (0)

struct Captured {
    int code = -1;
    long httpCode = -1;
    std::string data;
    std::string errorMsg;
    long long connectTimeMs = -1;
    bool invoked = false;
};

struct MultiCaptured {
    std::mutex mutex;
    std::condition_variable condition;
    int expected = 0;
    int completed = 0;
    std::vector<int> callbackCounts;
    std::vector<int> codes;
    std::vector<long> httpCodes;
    std::thread::id ownerThread;
    bool ownerThreadSet = false;
    bool ownerThreadMismatch = false;
};

struct MultiCallbackRef {
    MultiCaptured *batch = nullptr;
    int index = 0;
};

static void OnMultiResponse(void *ref, CurlResponse *response) {
    auto *item = static_cast<MultiCallbackRef *>(ref);
    std::lock_guard<std::mutex> lock(item->batch->mutex);
    item->batch->callbackCounts[item->index]++;
    item->batch->codes[item->index] = response->code;
    item->batch->httpCodes[item->index] = response->httpCode;
    if (!item->batch->ownerThreadSet) {
        item->batch->ownerThread = std::this_thread::get_id();
        item->batch->ownerThreadSet = true;
    } else if (item->batch->ownerThread != std::this_thread::get_id()) {
        item->batch->ownerThreadMismatch = true;
    }
    item->batch->completed++;
    item->batch->condition.notify_all();
}

static bool AwaitMulti(MultiCaptured &batch, int timeoutMs) {
    std::unique_lock<std::mutex> lock(batch.mutex);
    return batch.condition.wait_for(
        lock,
        std::chrono::milliseconds(timeoutMs),
        [&batch]() { return batch.completed >= batch.expected; });
}

struct UploadBuffer {
    std::string data;
    size_t offset = 0;
};

static int ReadUploadBuffer(void *ref, char *buffer, int maxLen) {
    auto *source = static_cast<UploadBuffer *>(ref);
    if (source == nullptr || buffer == nullptr || maxLen <= 0) return -1;
    const size_t remaining = source->data.size() - source->offset;
    if (remaining == 0) return 0;
    const size_t count = std::min(remaining, static_cast<size_t>(maxLen));
    std::memcpy(buffer, source->data.data() + source->offset, count);
    source->offset += count;
    return static_cast<int>(count);
}

struct StreamCaptured {
    int starts = 0;
    long startHttpCode = -1;
    std::string startHeaders;
    int chunks = 0;
    std::string data;
    int completes = 0;
    int code = -1;
    long httpCode = -1;
    std::string errorMsg;
    int chunkDelayMs = 0;
};

static void OnStreamStart(void *ref, long httpCode, const char *headers, int headerLen) {
    auto *out = static_cast<StreamCaptured *>(ref);
    out->starts++;
    out->startHttpCode = httpCode;
    if (headers != nullptr && headerLen > 0) {
        out->startHeaders.assign(headers, headerLen);
    }
}

static void OnStreamChunk(void *ref, const char *data, int len) {
    auto *out = static_cast<StreamCaptured *>(ref);
    out->chunks++;
    if (out->chunkDelayMs > 0) {
        std::this_thread::sleep_for(std::chrono::milliseconds(out->chunkDelayMs));
    }
    if (data != nullptr && len > 0) {
        out->data.append(data, len);
    }
}

static void OnStreamComplete(void *ref, CurlResponse *response) {
    auto *out = static_cast<StreamCaptured *>(ref);
    out->completes++;
    out->code = response->code;
    out->httpCode = response->httpCode;
    if (response->errorMsg != nullptr && response->errorMsgLen > 0) {
        out->errorMsg.assign(response->errorMsg, response->errorMsgLen);
    }
}

static StreamCaptured FetchStream(const std::string &url,
                                  const char *method = "GET",
                                  int64_t headerTimeoutMs = 1000,
                                  int64_t idleTimeoutMs = 1000,
                                  int64_t wholeTimeoutMs = 0,
                                  int chunkDelayMs = 0,
                                  int64_t connectTimeoutMs = 1000,
                                  const char *proxyUrl = "",
                                  const char *caInfoPath = "") {
    StreamCaptured captured;
    captured.chunkDelayMs = chunkDelayMs;
    StringDic headers{};
    CurlRequest request{};
    request.url = url.c_str();
    request.method = method;
    request.headers = &headers;
    request.streamConnectTimeoutMs = connectTimeoutMs;
    request.streamResponseHeadersTimeoutMs = headerTimeoutMs;
    request.streamIdleTimeoutMs = idleTimeoutMs;
    request.streamWholeTimeoutMs = wholeTimeoutMs;

    CurlStreamCallback callback{};
    callback.callbackRef = &captured;
    callback.onResponseStart = OnStreamStart;
    callback.onChunk = OnStreamChunk;
    callback.onComplete = OnStreamComplete;
    CurClientHandle handle = CreateCurlClient("wrapper-stream-test");
    SetCurlProxy(handle, proxyUrl);
    SetCurlCaInfo(handle, caInfoPath);
    StartStreamRequestV27(handle, &request, sizeof(request), CURL_WRAPPER_ABI_VERSION, &callback);
    DeleteCurlClient(handle);
    return captured;
}

static void OnResponse(void *ref, CurlResponse *response) {
    Captured *out = static_cast<Captured *>(ref);
    out->invoked = true;
    out->code = response->code;
    out->httpCode = response->httpCode;
    if (response->data != nullptr && response->dataLen > 0) {
        out->data.assign(response->data, response->dataLen);
    }
    if (response->errorMsg != nullptr && response->errorMsgLen > 0) {
        out->errorMsg.assign(response->errorMsg, response->errorMsgLen);
    }
    out->connectTimeMs = response->elapse.connectTimeMs;
}

static Captured Fetch(const std::string &url, int64_t timeoutMs = 5000,
                      const char *method = "GET", const char *body = nullptr,
                      int64_t bufferedBodyIdleTimeoutMs = 0) {
    Captured captured;
    StringDic headers{};
    headers.size = 0;
    headers.stringPairs = nullptr;

    CurlRequest request{};
    request.url = url.c_str();
    request.method = method;
    request.headers = &headers;
    request.timeout = timeoutMs;
    request.postBodyLen = body ? static_cast<int>(std::strlen(body)) : 0;
    request.postBody = body;

    // Caller owns the callback (wrapper only borrows it) — a stack struct is
    // legal and pins the contract; the old take-ownership-and-delete
    // semantics crashed exactly this pattern.
    CurlCallback callback{&captured, OnResponse};
    CurClientHandle handle = CreateCurlClient("wrapper-test");
    SetCurlBufferedBodyIdleTimeoutMs(handle, bufferedBodyIdleTimeoutMs);
    StartRequestV27(handle, &request, sizeof(request), CURL_WRAPPER_ABI_VERSION, &callback);
    DeleteCurlClient(handle);
    return captured;
}

static Captured FetchCapped(const std::string &url, int64_t maxBytes) {
    Captured captured;
    StringDic headers{};
    CurlRequest request{};
    request.url = url.c_str();
    request.method = "GET";
    request.headers = &headers;
    request.timeout = 5000;

    CurlCallback callback{&captured, OnResponse};
    CurClientHandle handle = CreateCurlClient("wrapper-response-cap-test");
    SetCurlMaxBufferedResponseBytes(handle, maxBytes);
    StartRequestV27(handle, &request, sizeof(request), CURL_WRAPPER_ABI_VERSION, &callback);
    DeleteCurlClient(handle);
    return captured;
}

static Captured FetchWithResolve(const std::string &url, const std::string &resolveEntry) {
    Captured captured;
    StringDic headers{};
    CurlRequest request{};
    request.url = url.c_str();
    request.method = "GET";
    request.headers = &headers;
    request.timeout = 5000;

    CurlCallback callback{&captured, OnResponse};
    CurClientHandle handle = CreateCurlClient("wrapper-resolve-test");
    SetCurlProxy(handle, "");
    CHECK(SetCurlResolve(handle, resolveEntry.c_str()) == 1,
          "per-client resolve entry is accepted");
    StartRequestV27(handle, &request, sizeof(request), CURL_WRAPPER_ABI_VERSION, &callback);
    DeleteCurlClient(handle);
    return captured;
}

static bool HasCurlFeature(long feature) {
    curl_version_info_data *info = curl_version_info(CURLVERSION_NOW);
    return info != nullptr && (info->features & feature) != 0;
}

static bool RequireAllContentCodecs() {
    const char *value = std::getenv("WRAPPER_REQUIRE_ALL_CODECS");
    return value != nullptr && std::strcmp(value, "0") != 0 && std::strcmp(value, "") != 0;
}

struct LegacyCurlRequestV26 {
    const char *url;
    const char *method;
    StringDic *headers;
    int64_t timeout;
    int postBodyLen;
    const char *postBody;
};

static void CheckGuardedLegacyRequestRejection() {
    static_assert(sizeof(LegacyCurlRequestV26) == 48,
                  "frozen LP64 raft.26 request must remain 48 bytes");
    const long pageSize = sysconf(_SC_PAGESIZE);
    void *mapping = mmap(nullptr, static_cast<size_t>(pageSize) * 2,
                         PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    CHECK(mapping != MAP_FAILED, "guarded legacy ABI allocation succeeds");
    if (mapping == MAP_FAILED) return;
    auto *guard = static_cast<char *>(mapping) + pageSize;
    CHECK(mprotect(guard, static_cast<size_t>(pageSize), PROT_NONE) == 0,
          "legacy ABI allocation has an unreadable guard page");
    auto *legacy = reinterpret_cast<LegacyCurlRequestV26 *>(guard - sizeof(LegacyCurlRequestV26));
    std::memset(legacy, 0, sizeof(*legacy));
    const auto *request = reinterpret_cast<const CurlRequest *>(legacy);
    CurClientHandle handle = CreateCurlClient("wrapper-guarded-abi26");
    CurlCallback buffered{};
    CurlStreamCallback stream{};
    CurlUploadSource upload{};
    CHECK(StartRequestV27(handle, request, sizeof(*legacy), 26, &buffered) == 0,
          "buffered V27 entry rejects a real guarded 48-byte v26 request pre-read");
    CHECK(StartStreamRequestV27(handle, request, sizeof(*legacy), 26, &stream) == 0,
          "stream V27 entry rejects a real guarded 48-byte v26 request pre-read");
    CHECK(StartUploadRequestV27(handle, request, sizeof(*legacy), 26, &upload, &buffered) == 0,
          "upload V27 entry rejects a real guarded 48-byte v26 request pre-read");
    DeleteCurlClient(handle);
    munmap(mapping, static_cast<size_t>(pageSize) * 2);
}

static void CheckDecodedContentEncoding(const std::string &base,
                                        const char *path,
                                        const char *label,
                                        bool supported) {
    if (!supported) {
        std::fprintf(stderr, "skip: %s decode (host libcurl lacks codec)\n", label);
        return;
    }
    Captured encoded = Fetch(base + path);
    std::string prefix = std::string(label) + " response";
    CHECK(encoded.code == 0, (prefix + " CURLcode is 0").c_str());
    CHECK(encoded.httpCode == 200, (prefix + " httpCode is 200").c_str());
    CHECK(encoded.data.find("\"encoded\":true") != std::string::npos,
          (prefix + " decoded body delivered").c_str());
}

int main(int argc, char **argv) {
    std::string base = argc > 1 ? argv[1] : "http://127.0.0.1:18923";
    std::string stalledProxy = argc > 2 ? argv[2] : "";
    std::string delayedProxy = argc > 3 ? argv[3] : "";
    std::string phaseHttpsBase = argc > 4 ? argv[4] : "";
    std::string phaseCaPath = argc > 5 ? argv[5] : "";

    CHECK(CurlWrapperAbiVersion() == CURL_WRAPPER_ABI_VERSION,
          "wrapper exports the exact CurlRequest ABI version");
    CheckGuardedLegacyRequestRejection();
    {
        CurClientHandle handle = CreateCurlClient("wrapper-abi-skew");
        CurlRequest request{};
        CurlCallback callback{};
        CHECK(StartRequestV27(handle, &request, 48, 26, &callback) == 0,
              "v27 entry rejects an old 48-byte/ABI-26 request before reading it");
        DeleteCurlClient(handle);
    }

    // HTTP/3 control surface is additive and must fail closed against a host
    // libcurl without the feature while leaving the default path available.
    {
        CurClientHandle handle = CreateCurlClient("wrapper-http3-contract");
        CHECK(handle != nullptr, "HTTP/3 contract probe creates a client");
        CHECK(SetCurlHttp3Enabled(handle, 0) == 1,
              "default h2/h1 mode is available regardless of HTTP/3 support");
        const int supportsHttp3 = CurlSupportsHttp3();
        CHECK(SetCurlHttp3Enabled(handle, 1) == (supportsHttp3 != 0 ? 1 : 0),
              "HTTP/3 enable result matches the linked feature bit");
        CHECK(std::strcmp(GetCurlNegotiatedProtocol(handle), "unknown") == 0,
              "protocol is unknown before a request completes");
        DeleteCurlClient(handle);
    }

    {
        constexpr int requestCount = 4;
        CurlMultiEngineHandle engine = CreateCurlMultiEngine("wrapper-multi-owner");
        CHECK(engine != nullptr, "single-owner multi engine starts");

        MultiCaptured batch;
        batch.expected = requestCount;
        batch.callbackCounts.assign(requestCount, 0);
        batch.codes.assign(requestCount, -1);
        batch.httpCodes.assign(requestCount, -1);
        std::vector<MultiCallbackRef> refs(requestCount);
        std::vector<CurClientHandle> handles(requestCount, nullptr);
        const std::string multiUrl = base + "/multi-delay";
        StringDic headers{};
        const auto started = std::chrono::steady_clock::now();
        for (int index = 0; index < requestCount; ++index) {
            refs[index] = MultiCallbackRef{&batch, index};
            handles[index] = CreateCurlClient("wrapper-multi-request");
            CurlRequest request{};
            request.url = multiUrl.c_str();
            request.method = "GET";
            request.headers = &headers;
            request.timeout = 5000;
            CurlCallback callback{&refs[index], OnMultiResponse};
            CHECK(
                SubmitBufferedRequestV27(
                    engine,
                    10'000 + index,
                    handles[index],
                    &request,
                    sizeof(request),
                    CURL_WRAPPER_ABI_VERSION,
                    &callback) == 1,
                "multi engine accepts buffered request");
        }
        CHECK(AwaitMulti(batch, 5000), "multi owner completes every concurrent request");
        CHECK(batch.ownerThreadSet && !batch.ownerThreadMismatch,
              "every multi terminal callback runs on one owner thread");
        const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - started).count();
        CHECK(elapsed < 2200, "single owner advances four delayed requests concurrently");
        for (int index = 0; index < requestCount; ++index) {
            CHECK(batch.callbackCounts[index] == 1, "multi request completes exactly once");
            CHECK(batch.codes[index] == 0, "multi request CURLcode is 0");
            CHECK(batch.httpCodes[index] == 200, "multi request preserves HTTP 200");
            CurlMultiInfoV1 info{};
            CHECK(
                GetCurlMultiInfoV1(
                    handles[index],
                    &info,
                    sizeof(info),
                    CURL_MULTI_INFO_ABI_VERSION) == 1,
                "multi request exposes queue-delay facts");
            CHECK(info.ownerThreadObserved == 1, "multi request ran on owner thread");
            CHECK(info.enqueueToNativeStartElapsedMs >= 0, "multi queue delay is non-negative");
            CurlMultiInfoV1 untouched{};
            untouched.reserved = 77;
            CHECK(
                GetCurlMultiInfoV1(handles[index], &untouched, sizeof(untouched) - 1,
                                   CURL_MULTI_INFO_ABI_VERSION) == 0,
                "multi facts reject mismatched struct size");
            CHECK(untouched.reserved == 77, "multi facts mismatch does not write caller memory");
        }

        MultiCaptured cancelled;
        cancelled.expected = 1;
        cancelled.callbackCounts.assign(1, 0);
        cancelled.codes.assign(1, -1);
        cancelled.httpCodes.assign(1, -1);
        MultiCallbackRef cancelRef{&cancelled, 0};
        CurClientHandle cancelHandle = CreateCurlClient("wrapper-multi-cancel");
        const std::string slowUrl = base + "/slow";
        CurlRequest cancelRequest{};
        cancelRequest.url = slowUrl.c_str();
        cancelRequest.method = "GET";
        cancelRequest.headers = &headers;
        cancelRequest.timeout = 30'000;
        CurlCallback cancelCallback{&cancelRef, OnMultiResponse};
        CHECK(
            SubmitBufferedRequestV27(
                engine,
                20'000,
                cancelHandle,
                &cancelRequest,
                sizeof(cancelRequest),
                CURL_WRAPPER_ABI_VERSION,
                &cancelCallback) == 1,
            "multi engine accepts cancellable request");
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        CancelCurlMultiRequest(engine, 20'000);
        CHECK(AwaitMulti(cancelled, 3000), "multi cancel wakes owner without worker starvation");
        CHECK(cancelled.callbackCounts[0] == 1, "multi cancel completes exactly once");
        CHECK(cancelled.codes[0] == 42, "multi cancel preserves CURLE_ABORTED_BY_CALLBACK");

        DeleteCurlMultiEngine(engine);
        for (CurClientHandle handle : handles) {
            DeleteCurlClient(handle);
        }
        DeleteCurlClient(cancelHandle);
    }

    // CURLINFO_CONN_ID is unique only within one connection cache. Prove the
    // exported physical identity preserves reuse, changes for a new physical
    // connection, and remains collision-free across engine/cache instances.
    {
        CurlMultiEngineHandle engine = CreateCurlMultiEngine("wrapper-connection-identity");
        CHECK(engine != nullptr, "connection-identity multi engine starts");
        StringDic headers{};
        const size_t portSeparator = base.rfind(':');
        CHECK(portSeparator != std::string::npos,
              "connection-identity test server base has a port");
        const std::string alternateOrigin =
            "http://localhost" + base.substr(portSeparator) + "/ok";
        const std::string primaryUrl = base + "/ok";

        auto performOne = [&](CurlMultiEngineHandle targetEngine,
                              int64_t requestId,
                              const std::string &url,
                              const char *caInfoPath,
                              CurClientHandle *outHandle) {
            MultiCaptured captured;
            captured.expected = 1;
            captured.callbackCounts.assign(1, 0);
            captured.codes.assign(1, -1);
            captured.httpCodes.assign(1, -1);
            MultiCallbackRef ref{&captured, 0};
            CurClientHandle handle = CreateCurlClient("wrapper-connection-identity-request");
            SetCurlCaInfo(handle, caInfoPath);
            CurlRequest request{};
            request.url = url.c_str();
            request.method = "GET";
            request.headers = &headers;
            request.timeout = 5000;
            CurlCallback callback{&ref, OnMultiResponse};
            CHECK(SubmitBufferedRequestV27(
                      targetEngine, requestId, handle, &request, sizeof(request),
                      CURL_WRAPPER_ABI_VERSION, &callback) == 1,
                  "connection-identity request is accepted");
            CHECK(AwaitMulti(captured, 5000),
                  "connection-identity request reaches terminal");
            CHECK(captured.codes[0] == 0 && captured.httpCodes[0] == 200,
                  "connection-identity request succeeds");
            CurlCompletionInfoV1 info{};
            CHECK(GetCurlCompletionInfoV1(
                      handle, &info, sizeof(info), CURL_COMPLETION_INFO_ABI_VERSION) == 1,
                  "completion diagnostics snapshot succeeds");
            CHECK(info.abiVersion == CURL_COMPLETION_INFO_ABI_VERSION &&
                      info.structSize == sizeof(CurlCompletionInfoV1),
                  "completion diagnostics report the exact V1 ABI shape");
            CHECK(info.connectionIdAvailable == 1 && info.connectionCacheId > 0 &&
                      info.connectionId >= 0,
                  "successful request exposes a cache-scoped connection id");
            CHECK(info.nameLookupTimingAvailable == 1 &&
                      info.connectTimingAvailable == 1 &&
                      info.preTransferTimingAvailable == 1 &&
                      info.startTransferTimingAvailable == 1 &&
                      info.totalTimingAvailable == 1,
                  "successful request exposes all completed phase timings");
            CHECK(info.nameLookupTimeUs >= 0 && info.connectTimeUs >= 0 &&
                      info.tlsTimeUs >= 0 && info.preTransferTimeUs >= 0 &&
                      info.startTransferTimeUs >= 0 && info.totalTimeUs >= 0,
                  "normalized completion phase durations are non-negative");
            const int64_t normalizedToFirstByteUs =
                info.nameLookupTimeUs + info.connectTimeUs + info.tlsTimeUs +
                info.preTransferTimeUs + info.startTransferTimeUs;
            CHECK(normalizedToFirstByteUs <= info.totalTimeUs,
                  "normalized phase durations fit inside total transfer time");
            *outHandle = handle;
            return info;
        };

        CurClientHandle firstHandle = nullptr;
        CurClientHandle reusedHandle = nullptr;
        CurClientHandle newConnectionHandle = nullptr;
        const CurlCompletionInfoV1 first =
            performOne(engine, 30'000, primaryUrl, "", &firstHandle);
        const CurlCompletionInfoV1 reused =
            performOne(engine, 30'001, primaryUrl, "", &reusedHandle);
        const CurlCompletionInfoV1 newConnection =
            performOne(engine, 30'002, alternateOrigin, "", &newConnectionHandle);
        CHECK(first.connectionCacheId == reused.connectionCacheId &&
                  first.connectionId == reused.connectionId,
              "same keep-alive connection keeps the same physical identity");
        CHECK(first.connectionCacheId == newConnection.connectionCacheId &&
                  first.connectionId != newConnection.connectionId,
              "new connection in one cache receives a different connection id");
        CHECK(first.tlsTimingState == CURL_TLS_TIMING_STATE_NOT_APPLICABLE &&
                  reused.tlsTimingState == CURL_TLS_TIMING_STATE_NOT_APPLICABLE &&
                  newConnection.tlsTimingState == CURL_TLS_TIMING_STATE_NOT_APPLICABLE &&
                  first.tlsTimeUs == 0 && reused.tlsTimeUs == 0 &&
                  newConnection.tlsTimeUs == 0,
              "plain HTTP completion exposes a known zero TLS phase");

        CurlMultiEngineHandle otherEngine =
            CreateCurlMultiEngine("wrapper-connection-identity-other-cache");
        CHECK(otherEngine != nullptr, "second connection cache starts");
        CurClientHandle otherHandle = nullptr;
        const CurlCompletionInfoV1 other =
            performOne(otherEngine, 30'003, primaryUrl, "", &otherHandle);
        CHECK(first.connectionCacheId != other.connectionCacheId,
              "different connection caches receive different process namespaces");

        if (!phaseHttpsBase.empty() && !phaseCaPath.empty()) {
            CurClientHandle tlsFreshHandle = nullptr;
            CurClientHandle tlsReusedHandle = nullptr;
            const CurlCompletionInfoV1 tlsFresh = performOne(
                engine, 30'004, phaseHttpsBase + "/", phaseCaPath.c_str(), &tlsFreshHandle);
            const CurlCompletionInfoV1 tlsReused = performOne(
                engine, 30'005, phaseHttpsBase + "/", phaseCaPath.c_str(), &tlsReusedHandle);
            CHECK(tlsFresh.connectionCacheId == tlsReused.connectionCacheId &&
                      tlsFresh.connectionId == tlsReused.connectionId,
                  "HTTPS keep-alive requests share one physical identity");
            CHECK(tlsFresh.tlsTimingState == CURL_TLS_TIMING_STATE_OBSERVED,
                  "fresh HTTPS connection reports an observed TLS phase");
            CHECK(tlsReused.tlsTimingState == CURL_TLS_TIMING_STATE_REUSED_CONNECTION &&
                      tlsReused.tlsTimeUs == 0,
                  "reused HTTPS connection reports an explicit known-zero TLS phase");
            DeleteCurlClient(tlsFreshHandle);
            DeleteCurlClient(tlsReusedHandle);
        }

        CurlCompletionInfoV1 untouched;
        std::memset(&untouched, 0xA5, sizeof(untouched));
        const CurlCompletionInfoV1 beforeMismatch = untouched;
        CHECK(GetCurlCompletionInfoV1(
                  firstHandle, &untouched, sizeof(untouched) - 1,
                  CURL_COMPLETION_INFO_ABI_VERSION) == 0,
              "completion diagnostics reject mismatched struct size");
        CHECK(std::memcmp(&untouched, &beforeMismatch, sizeof(untouched)) == 0,
              "completion diagnostics mismatch does not write caller memory");
        CHECK(GetCurlCompletionInfoV1(firstHandle, &untouched, sizeof(untouched), 99) == 0,
              "completion diagnostics reject mismatched ABI version");
        CHECK(std::memcmp(&untouched, &beforeMismatch, sizeof(untouched)) == 0,
              "completion diagnostics ABI mismatch remains fail-closed");
        CHECK(GetCurlCompletionInfoV1(
                  nullptr, &untouched, sizeof(untouched), CURL_COMPLETION_INFO_ABI_VERSION) == 0,
              "completion diagnostics reject a null client");
        CHECK(std::memcmp(&untouched, &beforeMismatch, sizeof(untouched)) == 0,
              "completion diagnostics null-client rejection writes no caller memory");

        DeleteCurlMultiEngine(engine);
        DeleteCurlMultiEngine(otherEngine);
        DeleteCurlClient(firstHandle);
        DeleteCurlClient(reusedHandle);
        DeleteCurlClient(newConnectionHandle);
        DeleteCurlClient(otherHandle);
    }

    // A real non-TLS completion is a known 0us TLS phase, while an HTTPS
    // transfer that never reaches TLS carries the explicit not-reached state.
    {
        auto fetchWithCompletion = [&](const std::string &url, int64_t timeoutMs,
                                       Captured *captured) {
            StringDic headers{};
            CurlRequest request{};
            request.url = url.c_str();
            request.method = "GET";
            request.headers = &headers;
            request.timeout = timeoutMs;
            CurlCallback callback{captured, OnResponse};
            CurClientHandle handle = CreateCurlClient("wrapper-timing-state");
            SetCurlProxy(handle, "");
            StartRequestV27(
                handle, &request, sizeof(request), CURL_WRAPPER_ABI_VERSION, &callback);
            CurlCompletionInfoV1 info{};
            CHECK(GetCurlCompletionInfoV1(
                      handle, &info, sizeof(info), CURL_COMPLETION_INFO_ABI_VERSION) == 1,
                  "timing-state completion diagnostics snapshot succeeds");
            DeleteCurlClient(handle);
            return info;
        };

        Captured plain;
        const CurlCompletionInfoV1 plainInfo =
            fetchWithCompletion(base + "/ok", 5000, &plain);
        CHECK(plain.code == 0 && plainInfo.tlsTimingState == CURL_TLS_TIMING_STATE_NOT_APPLICABLE,
              "plain HTTP reports TLS not-applicable instead of failure");
        CHECK(plainInfo.abiVersion == CURL_COMPLETION_INFO_ABI_VERSION &&
                  plainInfo.structSize == sizeof(CurlCompletionInfoV1),
              "standalone completion reports the exact V1 ABI shape");
        CHECK(plainInfo.nameLookupTimingAvailable == 1 &&
                  plainInfo.connectTimingAvailable == 1 &&
                  plainInfo.startTransferTimingAvailable == 1,
              "successful plain HTTP exposes completed phase availability");

        Captured otherPlain;
        const CurlCompletionInfoV1 otherPlainInfo =
            fetchWithCompletion(base + "/ok", 5000, &otherPlain);
        CHECK(otherPlain.code == 0 && plainInfo.connectionIdAvailable == 1 &&
                  otherPlainInfo.connectionIdAvailable == 1 &&
                  plainInfo.connectionCacheId != otherPlainInfo.connectionCacheId,
              "standalone clients receive distinct process-local cache namespaces");

        Captured tlsNotReached;
        const CurlCompletionInfoV1 tlsNotReachedInfo =
            fetchWithCompletion("https://127.0.0.1:1/", 500, &tlsNotReached);
        CHECK(tlsNotReached.code != 0 &&
                  tlsNotReachedInfo.tlsTimingState == CURL_TLS_TIMING_STATE_NOT_REACHED,
              "HTTPS failure before handshake is distinct from non-TLS");
        CHECK(tlsNotReachedInfo.connectionIdAvailable == 1 &&
                  tlsNotReachedInfo.connectionCacheId > 0 &&
                  tlsNotReachedInfo.connectionId >= 0,
              "pre-connect failure retains libcurl's physical connection identity");
        CHECK(tlsNotReachedInfo.nameLookupTimingAvailable ==
                      (tlsNotReachedInfo.nameLookupTimeUs > 0 ? 1 : 0),
              "pre-connect failure exposes name lookup only when libcurl measured it");
        CHECK(tlsNotReachedInfo.connectTimingAvailable == 0 &&
                  tlsNotReachedInfo.preTransferTimingAvailable == 0 &&
                  tlsNotReachedInfo.startTransferTimingAvailable == 0 &&
                  tlsNotReachedInfo.connectTimeUs == 0 &&
                  tlsNotReachedInfo.tlsTimeUs == 0 &&
                  tlsNotReachedInfo.preTransferTimeUs == 0 &&
                  tlsNotReachedInfo.startTransferTimeUs == 0,
              "physical identity never fabricates uncompleted phase timing");
        CHECK(tlsNotReachedInfo.totalTimingAvailable == 1 &&
                  tlsNotReachedInfo.totalTimeUs >= 0,
              "failed completion still exposes its measured total duration");
    }

    {
        CurlMultiEngineHandle engine = CreateCurlMultiEngine("wrapper-multi-config-failure");
        CHECK(engine != nullptr, "configure-failure multi engine starts");
        MultiCaptured captured;
        captured.expected = 1;
        captured.callbackCounts.assign(1, 0);
        captured.codes.assign(1, -1);
        captured.httpCodes.assign(1, -1);
        MultiCallbackRef ref{&captured, 0};
        CurClientHandle handle = CreateCurlClient("wrapper-multi-config-failure-request");
        SetCurlClientTestConfigureFailure(handle);
        StringDic headers{};
        const std::string url = base + "/ok";
        CurlRequest request{};
        request.url = url.c_str();
        request.method = "GET";
        request.headers = &headers;
        request.timeout = 5000;
        CurlCallback callback{&ref, OnMultiResponse};
        CHECK(SubmitBufferedRequestV27(engine, 21'000, handle, &request, sizeof(request),
                                       CURL_WRAPPER_ABI_VERSION, &callback) == 1,
              "multi accepts request before configure failure is observed");
        CHECK(AwaitMulti(captured, 3000),
              "accepted configure failure reaches a terminal callback");
        CHECK(captured.callbackCounts[0] == 1,
              "accepted configure failure completes exactly once");
        CHECK(captured.codes[0] == CURLE_FAILED_INIT,
              "accepted configure failure is classified as failed init");
        DeleteCurlMultiEngine(engine);
        DeleteCurlClient(handle);
    }

    {
        CurlMultiEngineHandle engine = CreateCurlMultiEngine("wrapper-multi-pre-cancel");
        CHECK(engine != nullptr, "pre-cancel multi engine starts");
        MultiCaptured captured;
        captured.expected = 1;
        captured.callbackCounts.assign(1, 0);
        captured.codes.assign(1, -1);
        captured.httpCodes.assign(1, -1);
        MultiCallbackRef ref{&captured, 0};
        CurClientHandle handle = CreateCurlClient("wrapper-multi-pre-cancel-request");
        SetCurlClientTestConfigureFailure(handle);
        StringDic headers{};
        const std::string url = base + "/slow";
        CurlRequest request{};
        request.url = url.c_str();
        request.method = "GET";
        request.headers = &headers;
        request.timeout = 30'000;
        CurlCallback callback{&ref, OnMultiResponse};
        CHECK(SubmitBufferedRequestV27(engine, 21'001, handle, &request, sizeof(request),
                                       CURL_WRAPPER_ABI_VERSION, &callback) == 1,
              "multi accepts request before configure/pre-cancel race");
        CancelCurlMultiRequest(engine, 21'001);
        CHECK(AwaitMulti(captured, 3000),
              "configure/pre-cancel race reaches one terminal callback");
        CHECK(captured.callbackCounts[0] == 1,
              "configure/pre-cancel race never double-completes");
        CHECK(captured.codes[0] == CURLE_FAILED_INIT ||
                  captured.codes[0] == CURLE_ABORTED_BY_CALLBACK,
              "configure/pre-cancel race preserves the winning terminal cause");
        DeleteCurlMultiEngine(engine);
        DeleteCurlClient(handle);
    }

    for (int failureMode : {1, 2}) {
        CurlMultiEngineHandle engine = CreateCurlMultiEngine("wrapper-multi-owner-fatal");
        CHECK(engine != nullptr, "owner-fatal multi engine starts");
        MultiCaptured captured;
        captured.expected = 1;
        captured.callbackCounts.assign(1, 0);
        captured.codes.assign(1, -1);
        captured.httpCodes.assign(1, -1);
        MultiCallbackRef ref{&captured, 0};
        CurClientHandle handle = CreateCurlClient("wrapper-multi-owner-fatal-request");
        StringDic headers{};
        const std::string url = base + "/slow";
        CurlRequest request{};
        request.url = url.c_str();
        request.method = "GET";
        request.headers = &headers;
        request.timeout = 30'000;
        CurlCallback callback{&ref, OnMultiResponse};
        CHECK(SubmitBufferedRequestV27(engine, 22'000 + failureMode, handle, &request,
                                       sizeof(request), CURL_WRAPPER_ABI_VERSION, &callback) == 1,
              "multi accepts request before owner API failure");
        SetCurlMultiTestFailureMode(engine, failureMode);
        CHECK(AwaitMulti(captured, 3000),
              "multi API failure drains accepted request without an infinite loop");
        CHECK(captured.callbackCounts[0] == 1,
              "multi API failure completes accepted request exactly once");
        CHECK(captured.codes[0] == CURLE_FAILED_INIT,
              "multi API failure has a stable failed-init terminal");
        DeleteCurlMultiEngine(engine);
        DeleteCurlClient(handle);
    }

    for (int deleteDelayMs : {0, 100}) {
        CurlMultiEngineHandle engine = CreateCurlMultiEngine("wrapper-multi-delete");
        CHECK(engine != nullptr, "delete-lifecycle multi engine starts");
        MultiCaptured captured;
        captured.expected = 1;
        captured.callbackCounts.assign(1, 0);
        captured.codes.assign(1, -1);
        captured.httpCodes.assign(1, -1);
        MultiCallbackRef ref{&captured, 0};
        CurClientHandle handle = CreateCurlClient("wrapper-multi-delete-request");
        StringDic headers{};
        const std::string url = base + "/slow";
        CurlRequest request{};
        request.url = url.c_str();
        request.method = "GET";
        request.headers = &headers;
        request.timeout = 30'000;
        CurlCallback callback{&ref, OnMultiResponse};
        CHECK(SubmitBufferedRequestV27(engine, 23'000 + deleteDelayMs, handle, &request,
                                       sizeof(request), CURL_WRAPPER_ABI_VERSION, &callback) == 1,
              "multi accepts request before engine deletion");
        if (deleteDelayMs > 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(deleteDelayMs));
        }
        DeleteCurlMultiEngine(engine);
        CHECK(AwaitMulti(captured, 100),
              "engine deletion synchronously drains pending or active request");
        CHECK(captured.callbackCounts[0] == 1,
              "engine deletion completes request exactly once");
        CHECK(captured.codes[0] == CURLE_ABORTED_BY_CALLBACK,
              "engine deletion classifies pending and active requests as cancelled");
        DeleteCurlClient(handle);
    }

    // CURLOPT_RESOLVE keeps the URL hostname while bypassing the process DNS
    // resolver. Use the reserved .invalid TLD so success proves the override.
    {
        const size_t portSeparator = base.rfind(':');
        CHECK(portSeparator != std::string::npos, "test server base URL has a port");
        const std::string port = base.substr(portSeparator + 1);
        const std::string alias = "networkkmm.invalid";
        Captured resolved = FetchWithResolve(
            "http://" + alias + ":" + port + "/ok",
            alias + ":" + port + ":127.0.0.1");
        CHECK(resolved.code == 0, "resolve override bypasses DNS");
        CHECK(resolved.httpCode == 200, "resolve override reaches the intended server");
    }

    // 1. Plain success: transfer ok, HTTP 200, body delivered.
    Captured ok = Fetch(base + "/ok");
    CHECK(ok.invoked, "callback invoked for /ok");
    CHECK(ok.code == 0, "/ok CURLcode is 0");
    CHECK(ok.httpCode == 200, "/ok httpCode is 200");
    CHECK(ok.data == "{\"ok\":true}", "/ok body delivered");

    // 2. THE raft.3 regression: 401 must surface as httpCode=401 with the
    //    error body intact, while the transfer itself reports success.
    Captured auth = Fetch(base + "/auth401");
    CHECK(auth.code == 0, "/auth401 CURLcode is 0 (transfer completed)");
    CHECK(auth.httpCode == 401, "/auth401 httpCode is 401, not 200");
    CHECK(auth.data.size() == 59, "/auth401 59-byte error body delivered");
    CHECK(auth.data.find("auth_required") != std::string::npos,
          "/auth401 body content intact");

    // 3. 500 passthrough.
    Captured boom = Fetch(base + "/boom500");
    CHECK(boom.httpCode == 500, "/boom500 httpCode is 500");
    CHECK(!boom.data.empty(), "/boom500 error body delivered");

    // 4. Timeout: server sleeps 10s, request allows 1.5s.
    Captured slow = Fetch(base + "/slow", 1500);
    CHECK(slow.code == 28, "/slow times out with CURLE_OPERATION_TIMEDOUT");

    Captured declaredOversize = FetchCapped(base + "/ok", 5);
    CHECK(declaredOversize.code == 63,
          "declared oversized buffered response fails as CURLE_FILESIZE_EXCEEDED");
    CHECK(declaredOversize.errorMsg.find("buffered response exceeded 5 bytes") != std::string::npos,
          "declared response cap carries a stable reason");
    CHECK(declaredOversize.data.empty(),
          "declared response cap exposes no body");

    Captured exactCap = FetchCapped(base + "/ok", 11);
    CHECK(exactCap.code == 0 && exactCap.data == "{\"ok\":true}",
          "buffered response exactly equal to the cap succeeds");

    Captured disabledCap = FetchCapped(base + "/gzip-large", 0);
    CHECK(disabledCap.code == 0 && disabledCap.data.size() == 4096,
          "zero buffered response cap remains disabled after decoding");

    Captured receivedOversize = FetchCapped(base + "/chunked-stream", 5);
    CHECK(receivedOversize.code == 63,
          "unknown-length received-byte cap fails as CURLE_FILESIZE_EXCEEDED");
    CHECK(receivedOversize.errorMsg.find("buffered response exceeded 5 bytes") != std::string::npos,
          "received-byte cap carries a stable reason");
    CHECK(receivedOversize.data.empty(),
          "received-byte cap fences the native partial body");

    Captured decodedOversize = FetchCapped(base + "/gzip-large", 100);
    CHECK(decodedOversize.code == 63,
          "decoded buffered response cap rejects compressed expansion");
    CHECK(decodedOversize.errorMsg.find("buffered response exceeded 100 bytes") != std::string::npos,
          "decoded response cap carries a stable reason");
    CHECK(decodedOversize.data.empty(),
          "decoded response cap fences expanded partial data");

    // Buffered GETs keep partial response bytes inside native until terminal
    // success. A body-progress stall must therefore abort as timeout and
    // expose no prefix to Kotlin/callers, preserving safe retry eligibility.
    Captured bufferedIdle = Fetch(base + "/idle-stream", 5000, "GET", nullptr, 500);
    CHECK(bufferedIdle.code == 28,
          "buffered inter-chunk idle completes as CURLE_OPERATION_TIMEDOUT");
    CHECK(bufferedIdle.errorMsg.find("buffered body idle timeout") != std::string::npos,
          "buffered idle timeout reason crosses the wrapper response ABI");
    CHECK(bufferedIdle.data.empty(),
          "buffered idle timeout fences native partial body from the caller");

    {
        Captured factsResponse;
        StringDic headers{};
        CurlRequest request{};
        std::string url = base + "/idle-stream";
        request.url = url.c_str();
        request.method = "GET";
        request.headers = &headers;
        request.timeout = 5000;

        CurlCallback callback{&factsResponse, OnResponse};
        CurClientHandle handle = CreateCurlClient("wrapper-transfer-facts-test");
        SetCurlBufferedBodyIdleTimeoutMs(handle, 500);
        StartRequestV27(handle, &request, sizeof(request), CURL_WRAPPER_ABI_VERSION, &callback);

        CurlTransferInfoV1 untouched{};
        untouched.abiVersion = 77;
        CHECK(GetCurlTransferInfoV1(
                  handle, &untouched, sizeof(untouched) - 1, CURL_TRANSFER_INFO_ABI_VERSION) == 0,
              "transfer facts reject mismatched struct size");
        CHECK(untouched.abiVersion == 77,
              "transfer facts size mismatch does not write caller memory");
        CHECK(GetCurlTransferInfoV1(handle, &untouched, sizeof(untouched), 99) == 0,
              "transfer facts reject mismatched ABI version");
        CHECK(untouched.abiVersion == 77,
              "transfer facts version mismatch does not write caller memory");

        CurlTransferInfoV1 facts{};
        CHECK(GetCurlTransferInfoV1(
                  handle, &facts, sizeof(facts), CURL_TRANSFER_INFO_ABI_VERSION) == 1,
              "transfer facts V1 snapshot succeeds");
        CHECK(facts.abiVersion == CURL_TRANSFER_INFO_ABI_VERSION &&
                  facts.structSize == sizeof(CurlTransferInfoV1),
              "transfer facts report their exact size and version");
        CHECK(facts.finalHeadersObserved == 1 && facts.firstBodyObserved == 1 &&
                  facts.bodyProgressObserved == 1,
              "buffered callback facts observe headers and partial body progress");
        CHECK(facts.finalHeadersElapsedMs >= 0 &&
                  facts.firstBodyElapsedMs >= facts.finalHeadersElapsedMs &&
                  facts.lastBodyProgressElapsedMs >= facts.firstBodyElapsedMs,
              "buffered callback facts are non-negative and monotonic");
        CHECK(facts.bodyBytes == 3,
              "buffered callback facts retain received byte count after partial-body timeout");
        DeleteCurlClient(handle);
    }

    {
        Captured bufferedFirstBody;
        StringDic headers{};
        CurlRequest request{};
        std::string url = base + "/headers-only-stall";
        request.url = url.c_str();
        request.method = "GET";
        request.headers = &headers;
        request.timeout = 5000;

        CurlCallback callback{&bufferedFirstBody, OnResponse};
        CurClientHandle handle = CreateCurlClient("wrapper-headers-only-facts-test");
        SetCurlBufferedBodyIdleTimeoutMs(handle, 500);
        StartRequestV27(handle, &request, sizeof(request), CURL_WRAPPER_ABI_VERSION, &callback);

        CHECK(bufferedFirstBody.code == 28,
              "buffered final-headers-to-first-body stall times out");
        CHECK(bufferedFirstBody.errorMsg.find("buffered body idle timeout") != std::string::npos,
              "first-body stall carries the buffered timeout reason");
        CHECK(bufferedFirstBody.data.empty(),
              "first-body stall exposes no body to the caller");

        CurlTransferInfoV1 facts{};
        CHECK(GetCurlTransferInfoV1(
                  handle, &facts, sizeof(facts), CURL_TRANSFER_INFO_ABI_VERSION) == 1,
              "headers-only timeout transfer facts snapshot succeeds");
        CHECK(facts.finalHeadersObserved == 1 && facts.firstBodyObserved == 0 &&
                  facts.bodyProgressObserved == 0,
              "headers-only timeout observes headers without fabricating body facts");
        CHECK(facts.firstBodyElapsedMs == 0 && facts.lastBodyProgressElapsedMs == 0 &&
                  facts.bodyBytes == 0,
              "headers-only timeout keeps absent body facts at zero");
        DeleteCurlClient(handle);
    }

    Captured postIdle =
        Fetch(base + "/post-idle-response", 5000, "POST", "request-body", 500);
    CHECK(postIdle.code == 28,
          "buffered write-request response still receives body-idle protection");
    CHECK(postIdle.data.empty(),
          "write-request response timeout exposes no partial response body");

    // Exercise the real streaming-upload entrypoint, whose response is still
    // buffered. It must share the same timeout normalization as StartRequest.
    {
        Captured uploadIdle;
        UploadBuffer uploadBuffer{"stream-upload-body"};
        StringDic headers{};
        CurlRequest request{};
        std::string url = base + "/post-idle-response";
        request.url = url.c_str();
        request.method = "POST";
        request.headers = &headers;
        request.timeout = 5000;

        CurlUploadSource source{&uploadBuffer, ReadUploadBuffer,
                                static_cast<int64_t>(uploadBuffer.data.size())};
        CurlCallback callback{&uploadIdle, OnResponse};
        CurClientHandle handle = CreateCurlClient("wrapper-upload-idle-test");
        SetCurlBufferedBodyIdleTimeoutMs(handle, 500);
        StartUploadRequestV27(
            handle, &request, sizeof(request), CURL_WRAPPER_ABI_VERSION, &source, &callback);
        DeleteCurlClient(handle);

        CHECK(uploadIdle.code == 28,
              "stream-upload buffered response idle normalizes to timeout");
        CHECK(uploadIdle.errorMsg.find("buffered body idle timeout") != std::string::npos,
              "stream-upload response idle carries stable timeout reason");
        CHECK(uploadIdle.data.empty(),
              "stream-upload response idle fences partial response body");
    }

    {
        Captured uploadCancelled;
        UploadBuffer uploadBuffer{"stream-upload-body"};
        StringDic headers{};
        CurlRequest request{};
        std::string url = base + "/post-idle-response";
        request.url = url.c_str();
        request.method = "POST";
        request.headers = &headers;
        request.timeout = 5000;

        CurlUploadSource source{&uploadBuffer, ReadUploadBuffer,
                                static_cast<int64_t>(uploadBuffer.data.size())};
        CurlCallback callback{&uploadCancelled, OnResponse};
        CurClientHandle handle = CreateCurlClient("wrapper-upload-cancel-test");
        SetCurlBufferedBodyIdleTimeoutMs(handle, 5000);
        std::thread performThread([&] {
            StartUploadRequestV27(
                handle, &request, sizeof(request), CURL_WRAPPER_ABI_VERSION, &source, &callback);
        });
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
        Cancel(handle);
        performThread.join();
        DeleteCurlClient(handle);

        CHECK(uploadCancelled.invoked, "stream-upload response cancel completes");
        CHECK(uploadCancelled.code == 42,
              "stream-upload response cancel normalizes to CURLE_ABORTED_BY_CALLBACK");
        CHECK(uploadCancelled.data.empty(),
              "stream-upload response cancel fences partial response body");
    }

    // Cancellation may be first observed inside DataWriteCallback, where
    // libcurl reports a short write as CURLE_WRITE_ERROR. The wrapper must
    // normalize that terminal result back to the public cancel code and keep
    // its partial native buffer private.
    {
        Captured cancelled;
        StringDic headers{};
        CurlRequest request{};
        std::string url = base + "/idle-stream";
        request.url = url.c_str();
        request.method = "GET";
        request.headers = &headers;
        request.timeout = 5000;

        CurlCallback callback{&cancelled, OnResponse};
        CurClientHandle handle = CreateCurlClient("wrapper-buffered-cancel-test");
        SetCurlBufferedBodyIdleTimeoutMs(handle, 5000);
        std::thread performThread([&] {
            StartRequestV27(handle, &request, sizeof(request), CURL_WRAPPER_ABI_VERSION, &callback);
        });
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
        Cancel(handle);
        performThread.join();
        DeleteCurlClient(handle);

        CHECK(cancelled.invoked, "buffered mid-body cancel completes");
        CHECK(cancelled.code == 42,
              "buffered mid-body cancel preserves CURLE_ABORTED_BY_CALLBACK");
        CHECK(cancelled.data.empty(),
              "buffered mid-body cancel fences partial body from the caller");
    }

    // 5. Redirect is followed to /ok.
    Captured redir = Fetch(base + "/redirect");
    CHECK(redir.httpCode == 200, "/redirect followed to 200");
    CHECK(redir.data == "{\"ok\":true}", "/redirect final body is /ok");

    // 6. POST body echo (custom-method plumbing).
    Captured post = Fetch(base + "/ok", 5000, "POST", "hello-wrapper");
    CHECK(post.httpCode == 200, "POST succeeds");
    CHECK(post.data.find("\"echoLen\":13") != std::string::npos,
          "POST body length echoed");

    // 7. Independent per-request clients remain functional. Connection-cache
    //    sharing across these clients is intentionally forbidden: libcurl
    //    does not support one shared connection cache across concurrent
    //    easy_perform threads. DNS/TLS-session sharing is not observable via
    //    connectTimeMs, so this behavior test only guards request continuity;
    //    run_tests.sh has the structural CONNECT-share prohibition.
    Captured first = Fetch(base + "/ok");
    Captured second = Fetch(base + "/ok");
    CHECK(first.invoked && second.invoked, "independent per-request clients complete");
    CHECK(first.httpCode == 200 && second.httpCode == 200,
          "independent per-request clients preserve HTTP success");

    // 8b. task #24 (RFC D-5): a cancel that lands BEFORE perform starts must
    //     fail deterministically with CURLE_ABORTED_BY_CALLBACK and exactly
    //     one callback — not depend on the first progress tick.
    {
        Captured cancelled;
        StringDic headers{};
        headers.size = 0;
        headers.stringPairs = nullptr;

        CurlRequest request{};
        std::string url = base + "/ok";
        request.url = url.c_str();
        request.method = "GET";
        request.headers = &headers;
        request.timeout = 5000;

        CurlCallback callback{&cancelled, OnResponse};
        CurClientHandle handle = CreateCurlClient("wrapper-test");
        Cancel(handle);
        StartRequestV27(handle, &request, sizeof(request), CURL_WRAPPER_ABI_VERSION, &callback);
        DeleteCurlClient(handle);

        CHECK(cancelled.invoked, "pre-start cancel still delivers a callback");
        CHECK(cancelled.code == 42,
              "pre-start cancel reports CURLE_ABORTED_BY_CALLBACK");
        CHECK(cancelled.data.empty(), "pre-start cancel delivers no body");
    }

    // 8c. Stream pre-start cancellation must suppress both start and chunks;
    //     only the exactly-once terminal cancellation is observable.
    {
        StreamCaptured stream;

        StringDic headers{};
        headers.size = 0;
        headers.stringPairs = nullptr;

        CurlRequest request{};
        std::string url = base + "/ok";
        request.url = url.c_str();
        request.method = "GET";
        request.headers = &headers;
        request.timeout = 5000;

        CurlStreamCallback callback{};
        callback.callbackRef = &stream;
        callback.onResponseStart = OnStreamStart;
        callback.onChunk = OnStreamChunk;
        callback.onComplete = OnStreamComplete;

        CurClientHandle handle = CreateCurlClient("wrapper-test");
        Cancel(handle);
        StartStreamRequestV27(handle, &request, sizeof(request), CURL_WRAPPER_ABI_VERSION, &callback);
        DeleteCurlClient(handle);

        CHECK(stream.starts == 0, "stream pre-start cancel suppresses response-start");
        CHECK(stream.chunks == 0, "stream pre-start cancel delivers no chunks");
        CHECK(stream.completes == 1, "stream pre-start cancel completes exactly once");
        CHECK(stream.code == 42,
              "stream pre-start cancel completion is CURLE_ABORTED_BY_CALLBACK");
    }

    // 8d. A normal known-length response starts after the final header block,
    //     streams multiple chunks, and completes exactly once.
    {
        StreamCaptured stream = FetchStream(base + "/stream");
        CHECK(stream.starts == 1, "stream response-start delivered exactly once");
        CHECK(stream.startHttpCode == 200, "stream response-start carries HTTP 200");
        CHECK(stream.chunks >= 2, "known-length response is delivered in multiple chunks");
        CHECK(stream.data == "alphabetagamma", "stream chunks preserve complete byte order");
        CHECK(stream.completes == 1 && stream.code == 0, "stream completes successfully exactly once");
    }

    // Native transfer facts are transport observations, not consumer callback
    // observations. A caller may omit onChunk and still inspect the completed
    // stream's byte/progress facts after the terminal callback returns.
    {
        StreamCaptured stream;
        StringDic headers{};
        CurlRequest request{};
        std::string url = base + "/stream";
        request.url = url.c_str();
        request.method = "GET";
        request.headers = &headers;
        request.streamConnectTimeoutMs = 1000;
        request.streamResponseHeadersTimeoutMs = 1000;
        request.streamIdleTimeoutMs = 1000;

        CurlStreamCallback callback{};
        callback.callbackRef = &stream;
        callback.onResponseStart = OnStreamStart;
        callback.onChunk = nullptr;
        callback.onComplete = OnStreamComplete;

        CurClientHandle handle = CreateCurlClient("wrapper-stream-facts-no-chunk-test");
        StartStreamRequestV27(
            handle, &request, sizeof(request), CURL_WRAPPER_ABI_VERSION, &callback);

        CHECK(stream.starts == 1 && stream.completes == 1 && stream.code == 0,
              "stream without onChunk reaches one successful terminal callback");
        CHECK(stream.chunks == 0 && stream.data.empty(),
              "stream without onChunk does not invoke a consumer chunk callback");
        CurlTransferInfoV1 facts{};
        CHECK(GetCurlTransferInfoV1(
                  handle, &facts, sizeof(facts), CURL_TRANSFER_INFO_ABI_VERSION) == 1,
              "completed stream transfer facts snapshot succeeds");
        CHECK(facts.finalHeadersObserved == 1 && facts.firstBodyObserved == 1 &&
                  facts.bodyProgressObserved == 1,
              "stream without onChunk still records native header and body progress");
        CHECK(facts.finalHeadersElapsedMs >= 0 &&
                  facts.firstBodyElapsedMs >= facts.finalHeadersElapsedMs &&
                  facts.lastBodyProgressElapsedMs >= facts.firstBodyElapsedMs,
              "completed stream transfer facts are non-negative and monotonic");
        CHECK(facts.bodyBytes == 14,
              "stream without onChunk records the complete native byte count");
        DeleteCurlClient(handle);
    }

    // 8e. Redirect intermediates never escape as response-start; only the
    //     followed final response is visible.
    {
        StreamCaptured stream = FetchStream(base + "/redirect");
        CHECK(stream.starts == 1, "redirect stream emits one final response-start");
        CHECK(stream.startHttpCode == 200, "redirect stream starts with final HTTP 200, not 302");
        CHECK(stream.data == "{\"ok\":true}", "redirect stream delivers final body");
        StreamCaptured mixedCase = FetchStream(base + "/redirect-mixed-case");
        CHECK(mixedCase.starts == 1 && mixedCase.startHttpCode == 200,
              "mixed-case Location header still exposes only the final 200 start");
        StreamCaptured loop = FetchStream(base + "/redirect-loop");
        CHECK(loop.starts == 1 && loop.startHttpCode == 302,
              "redirect limit promotes exactly one terminal 302 start");
        CHECK(loop.completes == 1 && loop.code == 47,
              "redirect loop completes as CURLE_TOO_MANY_REDIRECTS");
        StreamCaptured disallowed = FetchStream(base + "/redirect-disallowed");
        CHECK(disallowed.starts == 1 && disallowed.startHttpCode == 302,
              "disallowed redirect scheme promotes its terminal 302 start");
        CHECK(disallowed.completes == 1 && disallowed.code != 0,
              "disallowed redirect scheme completes with a transport error");
    }

    // Informational blocks are likewise internal; unknown-length chunked
    // bodies still stream as ordinary body chunks.
    {
        StreamCaptured informational = FetchStream(base + "/informational");
        CHECK(informational.starts == 1 && informational.startHttpCode == 200,
              "103 informational block is suppressed before final 200 start");
        CHECK(informational.startHeaders.find("103 Early Hints") == std::string::npos,
              "final response headers exclude informational block");
        CHECK(informational.data == "final", "informational response final body delivered");
        StreamCaptured chunked = FetchStream(base + "/chunked-stream");
        CHECK(chunked.starts == 1 && chunked.startHttpCode == 200,
              "chunked response starts normally");
        CHECK(chunked.data == "onetwothree" && chunked.completes == 1,
              "unknown-length chunked body is complete and ordered");
        StreamCaptured non2xx = FetchStream(base + "/auth401");
        CHECK(non2xx.starts == 1 && non2xx.startHttpCode == 401,
              "non-2xx stream exposes canonical HTTP status at start");
        CHECK(non2xx.data.find("auth_required") != std::string::npos,
              "non-2xx stream preserves error body chunks");
    }

    // 8f. Body-less HTTP responses still start at final-header completion.
    {
        StreamCaptured noContent = FetchStream(base + "/no-content");
        CHECK(noContent.starts == 1 && noContent.startHttpCode == 204,
              "204 emits response-start without a body");
        CHECK(noContent.chunks == 0 && noContent.completes == 1,
              "204 has zero chunks and one completion");
        StreamCaptured head = FetchStream(base + "/ok", "HEAD");
        CHECK(head.starts == 1 && head.startHttpCode == 200,
              "HEAD emits response-start after headers");
        CHECK(head.chunks == 0 && head.completes == 1,
              "HEAD has zero chunks and one completion");
    }

    // 8g. Header and inter-chunk deadlines fail independently; there is no
    //     implicit whole-transfer deadline for a healthy progressing stream.
    {
        if (!stalledProxy.empty()) {
            const auto connectStarted = std::chrono::steady_clock::now();
            StreamCaptured connectTimeout = FetchStream(
                "https://example.com/", "GET", 5000, 5000, 0, 0, 200,
                stalledProxy.c_str());
            const auto connectElapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - connectStarted).count();
            CHECK(connectTimeout.starts == 0,
                  "stalled CONNECT proxy times out before response-start");
            CHECK(connectTimeout.completes == 1 && connectTimeout.code == 28,
                  "stalled CONNECT proxy hits the positive connect-timeout path");
            CHECK(connectElapsed < 2000,
                  "positive connect timeout is bounded by its short phase budget");
        }
        if (!delayedProxy.empty() && !phaseHttpsBase.empty() && !phaseCaPath.empty()) {
            StreamCaptured sequentialPhases = FetchStream(
                phaseHttpsBase + "/phase-headers", "GET", 500, 1000, 0, 0,
                1500, delayedProxy.c_str(), phaseCaPath.c_str());
            CHECK(sequentialPhases.starts == 1 && sequentialPhases.startHttpCode == 200,
                  "header budget starts after delayed CONNECT/TLS establishment");
            CHECK(sequentialPhases.completes == 1 && sequentialPhases.code == 0 &&
                      sequentialPhases.data == "phase-ok",
                  "slow-connect plus in-budget delayed headers completes successfully");
        }
        StreamCaptured headersTimeout = FetchStream(base + "/delayed-headers", "GET", 500, 2000);
        CHECK(headersTimeout.starts == 0, "headers timeout does not fabricate response-start");
        CHECK(headersTimeout.completes == 1 && headersTimeout.code == 28,
              "headers timeout completes as CURLE_OPERATION_TIMEDOUT");
        StreamCaptured redirectHeadersTimeout =
            FetchStream(base + "/redirect-delayed-headers", "GET", 500, 2000);
        CHECK(redirectHeadersTimeout.starts == 0,
              "followed redirect keeps final-header timeout armed");
        CHECK(redirectHeadersTimeout.completes == 1 && redirectHeadersTimeout.code == 28,
              "redirect final-header stall completes as CURLE_OPERATION_TIMEDOUT");
        StreamCaptured crossOrigin =
            FetchStream(base + "/slow-redirect-cross-origin", "GET", 900, 3000);
        CHECK(crossOrigin.starts == 1 && crossOrigin.startHttpCode == 200,
              "cross-origin redirect resets the final-header phase after reconnect");
        CHECK(crossOrigin.code == 0 && crossOrigin.data == "cross-origin-ok",
              "each redirect hop receives its own in-budget header phase");
        StreamCaptured idleTimeout = FetchStream(base + "/idle-stream", "GET", 2000, 500);
        CHECK(idleTimeout.starts == 1, "idle-timeout stream starts after valid headers");
        CHECK(idleTimeout.data == "abc", "idle-timeout stream preserves chunks delivered before stall");
        CHECK(idleTimeout.completes == 1 && idleTimeout.code == 28,
              "inter-chunk idle timeout completes as CURLE_OPERATION_TIMEDOUT");
        StreamCaptured healthy = FetchStream(base + "/stream", "GET", 1000, 100, 0);
        CHECK(healthy.code == 0 && healthy.data == "alphabetagamma",
              "progressing stream is not killed by a whole-transfer deadline");
        StreamCaptured slowConsumer = FetchStream(base + "/stream", "GET", 1000, 100, 0, 200);
        CHECK(slowConsumer.code == 0 && slowConsumer.data == "alphabetagamma",
              "consumer callback backpressure is excluded from network-idle time");
        StreamCaptured wholeTimeout = FetchStream(base + "/idle-stream", "GET", 2000, 3000, 200);
        CHECK(wholeTimeout.completes == 1 && wholeTimeout.code == 28,
              "opt-in whole-transfer deadline terminates an otherwise-live stream");
    }

    // Cancellation requested synchronously from response-start must win
    // before the first body callback.
    {
        struct CancelAtStart {
            StreamCaptured captured;
            CurClientHandle handle = nullptr;
        } state;
        struct Hooks {
            static void Start(void *ref, long httpCode, const char *headers, int headerLen) {
                auto *state = static_cast<CancelAtStart *>(ref);
                OnStreamStart(&state->captured, httpCode, headers, headerLen);
                Cancel(state->handle);
            }
            static void Chunk(void *ref, const char *data, int len) {
                OnStreamChunk(&static_cast<CancelAtStart *>(ref)->captured, data, len);
            }
            static void Complete(void *ref, CurlResponse *response) {
                OnStreamComplete(&static_cast<CancelAtStart *>(ref)->captured, response);
            }
        };
        StringDic headers{};
        CurlRequest request{};
        std::string url = base + "/stream";
        request.url = url.c_str();
        request.method = "GET";
        request.headers = &headers;
        request.streamConnectTimeoutMs = 1000;
        request.streamResponseHeadersTimeoutMs = 1000;
        request.streamIdleTimeoutMs = 1000;
        CurlStreamCallback callback{&state, Hooks::Start, Hooks::Chunk, Hooks::Complete};
        state.handle = CreateCurlClient("wrapper-cancel-at-start");
        StartStreamRequestV27(
            state.handle, &request, sizeof(request), CURL_WRAPPER_ABI_VERSION, &callback);
        DeleteCurlClient(state.handle);

        CHECK(state.captured.starts == 1, "cancel-at-start observes the canonical start once");
        CHECK(state.captured.chunks == 0, "cancel-at-start suppresses every body chunk");
        CHECK(state.captured.completes == 1 && state.captured.code == 42,
              "cancel-at-start completes once as CURLE_ABORTED_BY_CALLBACK");
    }

    // Mid-stream cancellation preserves chunks already accepted, suppresses
    // all later chunks and still emits one terminal callback.
    {
        struct CancelOnFirstChunk {
            StreamCaptured captured;
            CurClientHandle handle = nullptr;
        } state;
        struct Hooks {
            static void Start(void *ref, long httpCode, const char *headers, int headerLen) {
                OnStreamStart(&static_cast<CancelOnFirstChunk *>(ref)->captured, httpCode, headers, headerLen);
            }
            static void Chunk(void *ref, const char *data, int len) {
                auto *state = static_cast<CancelOnFirstChunk *>(ref);
                OnStreamChunk(&state->captured, data, len);
                Cancel(state->handle);
            }
            static void Complete(void *ref, CurlResponse *response) {
                OnStreamComplete(&static_cast<CancelOnFirstChunk *>(ref)->captured, response);
            }
        };
        StringDic headers{};
        CurlRequest request{};
        std::string url = base + "/stream";
        request.url = url.c_str();
        request.method = "GET";
        request.headers = &headers;
        request.streamConnectTimeoutMs = 1000;
        request.streamResponseHeadersTimeoutMs = 1000;
        request.streamIdleTimeoutMs = 1000;
        CurlStreamCallback callback{&state, Hooks::Start, Hooks::Chunk, Hooks::Complete};
        state.handle = CreateCurlClient("wrapper-cancel-mid-stream");
        StartStreamRequestV27(
            state.handle, &request, sizeof(request), CURL_WRAPPER_ABI_VERSION, &callback);
        DeleteCurlClient(state.handle);

        CHECK(state.captured.starts == 1, "mid-stream cancel starts once");
        CHECK(state.captured.chunks == 1, "mid-stream cancel accepts exactly the first chunk");
        CHECK(!state.captured.data.empty(), "mid-stream cancel preserves accepted bytes");
        CHECK(state.captured.completes == 1 && state.captured.code == 42,
              "mid-stream cancel completes once as CURLE_ABORTED_BY_CALLBACK");
    }

    // 8. Upstream issue #28: request headers must not be duplicated on the
    //    wire. Send one custom header and count its occurrences in the echo.
    {
        Captured echo;
        StringPair pair{};
        pair.first = const_cast<char *>("X-Slock-Probe");
        pair.second = const_cast<char *>("once");
        StringDic headers{};
        headers.size = 1;
        headers.stringPairs = &pair;

        CurlRequest request{};
        std::string url = base + "/echo-headers";
        request.url = url.c_str();
        request.method = "GET";
        request.headers = &headers;
        request.timeout = 5000;

        CurlCallback callback{&echo, OnResponse};
        CurClientHandle handle = CreateCurlClient("wrapper-test");
        StartRequestV27(handle, &request, sizeof(request), CURL_WRAPPER_ABI_VERSION, &callback);
        DeleteCurlClient(handle);

        size_t count = 0, pos = 0;
        while ((pos = echo.data.find("X-Slock-Probe", pos)) != std::string::npos) {
            count++;
            pos += 1;
        }
        CHECK(echo.httpCode == 200, "/echo-headers succeeds");
        CHECK(count == 1, "custom request header sent exactly once (upstream #28)");
    }

#ifdef CURL_VERSION_LIBZ
    bool supportsGzip = HasCurlFeature(CURL_VERSION_LIBZ);
#else
    bool supportsGzip = true;
#endif
#ifdef CURL_VERSION_BROTLI
    bool supportsBrotli = HasCurlFeature(CURL_VERSION_BROTLI);
#else
    bool supportsBrotli = false;
#endif
#ifdef CURL_VERSION_ZSTD
    bool supportsZstd = HasCurlFeature(CURL_VERSION_ZSTD);
#else
    bool supportsZstd = false;
#endif
    if (RequireAllContentCodecs()) {
        CHECK(supportsGzip, "host libcurl advertises gzip/zlib decode support");
        CHECK(supportsBrotli, "host libcurl advertises brotli decode support");
        CHECK(supportsZstd, "host libcurl advertises zstd decode support");
    }
    CheckDecodedContentEncoding(base, "/gzip", "gzip", supportsGzip);
    CheckDecodedContentEncoding(base, "/br", "brotli", supportsBrotli);
    CheckDecodedContentEncoding(base, "/zstd", "zstd", supportsZstd);

    if (gFailures > 0) {
        std::fprintf(stderr, "\n%d failure(s)\n", gFailures);
        return 1;
    }
    std::fprintf(stderr, "\nall checks passed\n");
    return 0;
}

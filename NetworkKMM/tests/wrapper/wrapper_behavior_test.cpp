// Behavior-contract tests for pbcurlwrapper, run on the host (linux) against
// tests/wrapper/test_server.py. These lock down the wrapper's observable
// contract — most importantly that HTTP statuses pass through untouched
// (the raft.3 production bug: 401s surfaced as transfer-OK and Slock's auth
// refresh never fired).
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <chrono>
#include <string>
#include <thread>
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
    request.streamIdleTimeoutMs = bufferedBodyIdleTimeoutMs;
    request.postBodyLen = body ? static_cast<int>(std::strlen(body)) : 0;
    request.postBody = body;

    // Caller owns the callback (wrapper only borrows it) — a stack struct is
    // legal and pins the contract; the old take-ownership-and-delete
    // semantics crashed exactly this pattern.
    CurlCallback callback{&captured, OnResponse};
    CurClientHandle handle = CreateCurlClient("wrapper-test");
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

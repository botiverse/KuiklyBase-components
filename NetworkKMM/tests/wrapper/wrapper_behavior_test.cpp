// Behavior-contract tests for pbcurlwrapper, run on the host (linux) against
// tests/wrapper/test_server.py. These lock down the wrapper's observable
// contract — most importantly that HTTP statuses pass through untouched
// (the raft.3 production bug: 401s surfaced as transfer-OK and Slock's auth
// refresh never fired).
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <string>
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
                      const char *method = "GET", const char *body = nullptr) {
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
    StartRequest(handle, request, &callback);
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

    // 5. Redirect is followed to /ok.
    Captured redir = Fetch(base + "/redirect");
    CHECK(redir.httpCode == 200, "/redirect followed to 200");
    CHECK(redir.data == "{\"ok\":true}", "/redirect final body is /ok");

    // 6. POST body echo (custom-method plumbing).
    Captured post = Fetch(base + "/ok", 5000, "POST", "hello-wrapper");
    CHECK(post.httpCode == 200, "POST succeeds");
    CHECK(post.data.find("\"echoLen\":13") != std::string::npos,
          "POST body length echoed");

    // 7. Connection pooling: a second request to the same host should reuse
    //    the pooled connection via the process-wide share handle, reporting
    //    (near-)zero connect time.
    Captured first = Fetch(base + "/ok");
    Captured second = Fetch(base + "/ok");
    CHECK(first.invoked && second.invoked, "pooling probes ran");
    CHECK(second.connectTimeMs == 0,
          "second request reuses pooled connection (connectTimeMs == 0)");

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
        StartRequest(handle, request, &callback);
        DeleteCurlClient(handle);

        CHECK(cancelled.invoked, "pre-start cancel still delivers a callback");
        CHECK(cancelled.code == 42,
              "pre-start cancel reports CURLE_ABORTED_BY_CALLBACK");
        CHECK(cancelled.data.empty(), "pre-start cancel delivers no body");
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
        StartRequest(handle, request, &callback);
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

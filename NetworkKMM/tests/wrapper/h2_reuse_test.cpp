// raft.46 HTTP/2 connection-reuse contract (OkHttp HttpOverHttp2Test analogues), run by
// run_tests.sh against h2_test_server.py over TLS + ALPN h2. Rules under test: libcurl replays a
// request whose reused connection failed before any response byte, and the wrapper lets only
// GET/HEAD be replayed (CURLOPT_PREREQFUNCTION).
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include "curl_wrapper.h"

static int gFailures = 0;
static std::string gCa;

#define CHECK(cond, msg)                                                     \
    do {                                                                     \
        if (!(cond)) {                                                       \
            std::fprintf(stderr, "FAIL: %s (%s:%d)\n", msg, __FILE__, __LINE__); \
            gFailures++;                                                     \
        } else {                                                             \
            std::fprintf(stderr, "ok:   %s\n", msg);                         \
        }                                                                    \
    } while (0)

struct Outcome {
    int code = -1;
    long httpCode = -1;
    std::string data;
    std::string error;
};

struct Pending {
    std::mutex mutex;
    std::condition_variable condition;
    bool done = false;
    Outcome outcome;
    CurClientHandle client = nullptr;
    std::string url;
    std::string method;
    std::string body;
};

static void OnResponse(void *ref, CurlResponse *response) {
    auto *pending = static_cast<Pending *>(ref);
    std::lock_guard<std::mutex> lock(pending->mutex);
    pending->outcome.code = response->code;
    pending->outcome.httpCode = response->httpCode;
    if (response->data != nullptr) pending->outcome.data.assign(response->data, response->dataLen);
    if (response->errorMsg != nullptr) pending->outcome.error.assign(response->errorMsg, response->errorMsgLen);
    pending->done = true;
    pending->condition.notify_all();
}

static int64_t gNextId = 1;

static bool Submit(CurlMultiEngineHandle engine, Pending &pending) {
    pending.client = CreateCurlClient("h2-reuse-test");
    SetCurlCaInfo(pending.client, gCa.c_str());
    static StringDic headers{};
    CurlRequest request{};
    request.url = pending.url.c_str();
    request.method = pending.method.c_str();
    request.headers = &headers;
    request.timeout = 8000;
    if (!pending.body.empty()) {
        request.postBody = pending.body.c_str();
        request.postBodyLen = static_cast<int>(pending.body.size());
    }
    CurlCallback callback{&pending, OnResponse};
    return SubmitBufferedRequestV27(engine, gNextId++, pending.client, &request, sizeof(request),
                                    CURL_WRAPPER_ABI_VERSION, &callback) == 1;
}

static Outcome Await(Pending &pending) {
    std::unique_lock<std::mutex> lock(pending.mutex);
    pending.condition.wait_for(lock, std::chrono::seconds(15), [&pending]() { return pending.done; });
    Outcome outcome = pending.outcome;
    lock.unlock();
    DeleteCurlClient(pending.client);
    return outcome;
}

static Outcome Send(CurlMultiEngineHandle engine, const std::string &url, const char *method,
                    const char *body = nullptr) {
    Pending pending;
    pending.url = url;
    pending.method = method;
    if (body != nullptr) pending.body = body;
    if (!Submit(engine, pending)) {
        DeleteCurlClient(pending.client);
        Outcome rejected;
        rejected.code = -2;
        return rejected;
    }
    return Await(pending);
}

// "<requests> <connections>" for a key ("*": all connections the server accepted).
static void Count(CurlMultiEngineHandle engine, const std::string &base, const std::string &key,
                  int *requests, int *connections) {
    Outcome count = Send(engine, base + "/h2-count/" + key, "GET");
    *requests = -1;
    *connections = -1;
    if (count.httpCode == 200) std::sscanf(count.data.c_str(), "%d %d", requests, connections);
}

int main(int argc, char **argv) {
    if (argc < 3) {
        std::fprintf(stderr, "usage: %s <https-base-url> <ca-file>\n", argv[0]);
        return 2;
    }
    const std::string base = argv[1];
    gCa = argv[2];
    int requests = 0;
    int connections = 0;
    int before = 0;
    int ignored = 0;

    // 1. Requests on one engine multiplex over one HTTP/2 connection (the reuse we keep).
    {
        CurlMultiEngineHandle engine = CreateCurlMultiEngine("h2-reuse-test");
        Count(engine, base, "*", &ignored, &before);
        Outcome a = Send(engine, base + "/h2/mux-a/ok", "GET");
        Outcome b = Send(engine, base + "/h2/mux-b/ok", "POST", "payload");
        Count(engine, base, "*", &ignored, &connections);
        CHECK(a.httpCode == 200 && b.httpCode == 200, "h2 GET and POST succeed");
        std::fprintf(stderr, "info: mux before=%d after=%d\n", before, connections);
        CHECK(connections == before, "they share the one HTTP/2 connection already open");
        DeleteCurlMultiEngine(engine);
    }

    // 2. GOAWAY after a response (OkHttp connectionNotReusedAfterShutdown): the next request goes
    //    out on a new connection and succeeds once.
    {
        CurlMultiEngineHandle engine = CreateCurlMultiEngine("h2-reuse-test");
        Count(engine, base, "*", &ignored, &before);
        Outcome first = Send(engine, base + "/h2/goaway/goaway-after", "GET");
        Outcome next = Send(engine, base + "/h2/after-goaway/ok", "POST", "payload");
        Count(engine, base, "after-goaway", &requests, &ignored);
        Count(engine, base, "*", &ignored, &connections);
        CHECK(first.httpCode == 200, "response before GOAWAY arrives");
        CHECK(next.code == 0 && next.httpCode == 200, "POST after GOAWAY succeeds");
        CHECK(requests == 1, "POST after GOAWAY reached the server once");
        CHECK(connections - before == 1, "the request after GOAWAY used a new connection");
        DeleteCurlMultiEngine(engine);
    }

    // 3. RST_STREAM REFUSED_STREAM (OkHttp noRecoveryFromOneRefusedStream / refused-stream retry).
    {
        CurlMultiEngineHandle engine = CreateCurlMultiEngine("h2-reuse-test");
        Send(engine, base + "/h2/warm-refused/ok", "GET");
        Outcome get = Send(engine, base + "/h2/refused-get/refused-once", "GET");
        Count(engine, base, "refused-get", &requests, &ignored);
        std::fprintf(stderr, "info: refused GET code=%d http=%ld requests=%d error=%s\n",
                     get.code, get.httpCode, requests, get.error.c_str());
        CHECK(get.code == 0 && get.httpCode == 200, "GET refused once (REFUSED_STREAM) is retried and succeeds");
        CHECK(requests == 2, "refused GET reached the server twice (refused + retry)");
        Outcome post = Send(engine, base + "/h2/refused-post/refused-once", "POST", "payload");
        Count(engine, base, "refused-post", &requests, &ignored);
        std::fprintf(stderr, "info: refused POST code=%d http=%ld requests=%d error=%s\n",
                     post.code, post.httpCode, requests, post.error.c_str());
        CHECK(requests <= 2 && (post.code != 0 || requests == 2),
              "refused POST is either retried (REFUSED_STREAM promises no processing) or fails; never more than one retry");
        DeleteCurlMultiEngine(engine);
    }

    // 4. RST_STREAM INTERNAL_ERROR: the server may have acted; a POST must not be sent again.
    {
        CurlMultiEngineHandle engine = CreateCurlMultiEngine("h2-reuse-test");
        Send(engine, base + "/h2/warm-internal/ok", "GET");
        Outcome post = Send(engine, base + "/h2/internal-post/internal-once", "POST", "payload");
        Count(engine, base, "internal-post", &requests, &ignored);
        std::fprintf(stderr, "info: INTERNAL_ERROR POST code=%d http=%ld requests=%d error=%s\n",
                     post.code, post.httpCode, requests, post.error.c_str());
        CHECK(post.code != 0 && requests == 1, "POST reset with INTERNAL_ERROR fails and reached the server once");
        Outcome get = Send(engine, base + "/h2/internal-get/internal-once", "GET");
        Count(engine, base, "internal-get", &requests, &ignored);
        std::fprintf(stderr, "info: INTERNAL_ERROR GET code=%d http=%ld requests=%d error=%s\n",
                     get.code, get.httpCode, requests, get.error.c_str());
        CHECK(requests <= 2, "GET reset with INTERNAL_ERROR is sent at most twice");
        Outcome after = Send(engine, base + "/h2/after-internal/ok", "GET");
        CHECK(after.httpCode == 200, "the engine keeps working after a stream reset");
        DeleteCurlMultiEngine(engine);
    }

    // 5. The connection dies with three streams in flight (2 GET + 1 POST) and no response byte
    //    (OkHttp: connection failure while multiplexing). The POST must not go out again.
    {
        CurlMultiEngineHandle engine = CreateCurlMultiEngine("h2-reuse-test");
        Send(engine, base + "/h2/warm-kill/ok", "GET");
        std::vector<std::unique_ptr<Pending>> batch;
        const char *methods[] = {"GET", "GET", "POST"};
        for (const char *method : methods) {
            auto pending = std::make_unique<Pending>();
            pending->url = base + "/h2/kill/hold-kill-once";
            pending->method = method;
            if (std::strcmp(method, "POST") == 0) pending->body = "payload";
            CHECK(Submit(engine, *pending), "in-flight request accepted");
            batch.push_back(std::move(pending));
        }
        Outcome get1 = Await(*batch[0]);
        Outcome get2 = Await(*batch[1]);
        Outcome post = Await(*batch[2]);
        Count(engine, base, "kill", &requests, &ignored);
        std::fprintf(stderr, "info: killed in-flight get1=%d/%ld get2=%d/%ld post=%d/%ld requests=%d post_error=%s\n",
                     get1.code, get1.httpCode, get2.code, get2.httpCode, post.code, post.httpCode, requests,
                     post.error.c_str());
        // libcurl replays only a RECV_ERROR; a stream that hits the dead socket on send fails with
        // SEND_ERROR (55) instead. Each GET therefore either succeeds (libcurl replay) or fails with
        // a connection-failure code the routing layer retries once on a fresh connection
        // (isConnectionFailureBeforeResponse: 16/55/56/92 with no status).
        auto recovered = [](const Outcome &get) {
            return get.httpCode == 200 ||
                (get.httpCode == 0 && (get.code == 16 || get.code == 55 || get.code == 56 || get.code == 92));
        };
        CHECK(recovered(get1) && recovered(get2),
              "each in-flight GET succeeds or fails with a connection-failure code (retried by the routing layer)");
        CHECK(post.code != 0 && post.httpCode == 0, "the in-flight POST fails");
        CHECK(post.error.find("not replayed") != std::string::npos || post.code == 55,
              "the in-flight POST is not replayed (refused replay, or failed on send)");
        CHECK(requests <= 5, "server saw at most 3 originals + 2 GET replays");
        DeleteCurlMultiEngine(engine);
    }

    // 6. Per-request accounting with four streams in flight on one connection when it dies: first,
    //    middle and last GET, a GET whose response headers already arrived, and a POST (sent last).
    //    Each request is counted on its own key. No request may reach the server more than twice
    //    at this layer (original + libcurl's one replay); a GET with a status must not be replayed;
    //    the POST exactly once. GETs without a status that fail end on a connection-failure code,
    //    which the routing layer retries once on a fresh engine (Kotlin isConnectionFailureBeforeResponse).
    {
        CurlMultiEngineHandle engine = CreateCurlMultiEngine("h2-reuse-test");
        Send(engine, base + "/h2/warm-four/ok", "GET");
        struct Item {
            const char *key;
            const char *method;
        };
        const Item items[] = {{"four-get-first", "GET"}, {"four-get-middle", "GET"},
                              {"four-get-headers", "GET"}, {"four-post-last", "POST"}};
        std::vector<std::unique_ptr<Pending>> batch;
        for (const Item &item : items) {
            auto pending = std::make_unique<Pending>();
            pending->url = base + "/h2/" + item.key + "/hold4-group";
            pending->method = item.method;
            if (std::strcmp(item.method, "POST") == 0) pending->body = "payload";
            CHECK(Submit(engine, *pending), "four-stream request accepted");
            batch.push_back(std::move(pending));
        }
        for (size_t index = 0; index < batch.size(); ++index) {
            Outcome outcome = Await(*batch[index]);
            int sent = 0;
            Count(engine, base, items[index].key, &sent, &ignored);
            std::fprintf(stderr, "info: four-stream %s code=%d http=%ld sent=%d error=%s\n", items[index].key,
                         outcome.code, outcome.httpCode, sent, outcome.error.c_str());
            if (std::strcmp(items[index].method, "POST") == 0) {
                CHECK(outcome.code != 0 && sent == 1, "four-stream POST fails and reached the server exactly once");
            } else if (std::strstr(items[index].key, "headers") != nullptr) {
                // Normally the client has read the status (200 + error: the routing layer does not
                // retry); if the drop wins the race the client never saw it (no status: retryable).
                CHECK(outcome.code != 0 && sent == 1 &&
                          (outcome.httpCode == 200 || (outcome.httpCode == 0 && outcome.code == 55)),
                      "GET whose headers were sent fails, is not replayed by libcurl, and keeps its status when it arrived");
            } else {
                CHECK(sent <= 2, "four-stream GET reached the server at most twice at this layer");
                CHECK(outcome.httpCode == 200 ||
                          (outcome.httpCode == 0 && (outcome.code == 16 || outcome.code == 55 ||
                                                     outcome.code == 56 || outcome.code == 92)),
                      "four-stream GET succeeds or fails with a retryable connection-failure code and no status");
            }
        }
        DeleteCurlMultiEngine(engine);
    }

    if (gFailures > 0) {
        std::fprintf(stderr, "\n%d h2 reuse failure(s)\n", gFailures);
        return 1;
    }
    std::fprintf(stderr, "\nh2 reuse checks passed\n");
    return 0;
}

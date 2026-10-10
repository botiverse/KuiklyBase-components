// raft.46 connection-reuse contract (OkHttp ConnectionReuseTest / CallTest analogues), run by
// run_tests.sh against test_server.py's /reuse/<key>/<behaviour> endpoints. Every case first sends
// GET /ok on the same multi engine so the request under test goes out on a REUSED keep-alive
// connection, then checks the outcome and how many times the request reached the server.
// Rules under test: libcurl replays a request whose reused connection failed before any response
// byte (Curl_retry_request); the wrapper lets only GET/HEAD be replayed (CURLOPT_PREREQFUNCTION).
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
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

static Outcome Send(CurlMultiEngineHandle engine, const std::string &url, const char *method,
                    const char *body = nullptr) {
    Pending pending;
    CurClientHandle client = CreateCurlClient("connection-reuse-test");
    StringDic headers{};
    CurlRequest request{};
    request.url = url.c_str();
    request.method = method;
    request.headers = &headers;
    request.timeout = 8000;
    if (body != nullptr) {
        request.postBody = body;
        request.postBodyLen = static_cast<int>(std::strlen(body));
    }
    CurlCallback callback{&pending, OnResponse};
    if (SubmitBufferedRequestV27(engine, gNextId++, client, &request, sizeof(request),
                                 CURL_WRAPPER_ABI_VERSION, &callback) != 1) {
        DeleteCurlClient(client);
        Outcome rejected;
        rejected.code = -2;
        return rejected;
    }
    std::unique_lock<std::mutex> lock(pending.mutex);
    pending.condition.wait_for(lock, std::chrono::seconds(15), [&pending]() { return pending.done; });
    Outcome outcome = pending.outcome;
    lock.unlock();
    DeleteCurlClient(client);
    return outcome;
}

static int Hits(CurlMultiEngineHandle engine, const std::string &base, const std::string &key) {
    Outcome count = Send(engine, base + "/reuse-count/" + key, "GET");
    return count.httpCode == 200 ? std::atoi(count.data.c_str()) : -1;
}

// One case: warm a connection, send the request under test on it, report outcome and server hits.
static Outcome OnReusedConnection(const std::string &base, const std::string &key,
                                  const std::string &behaviour, const char *method,
                                  int *hits, const char *body = nullptr) {
    CurlMultiEngineHandle engine = CreateCurlMultiEngine("connection-reuse-test");
    Outcome warm = Send(engine, base + "/ok", "GET");
    if (warm.httpCode != 200) std::fprintf(stderr, "warm-up failed for %s\n", key.c_str());
    Outcome outcome = Send(engine, base + "/reuse/" + key + "/" + behaviour, method, body);
    *hits = Hits(engine, base, key);
    DeleteCurlMultiEngine(engine);
    return outcome;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s <base-url>\n", argv[0]);
        return 2;
    }
    const std::string base = argv[1];
    int hits = 0;

    // 1. Server drops the reused connection after reading a GET, no response byte: libcurl
    //    replays it on a new connection (OkHttp: retryOnConnectionFailure for a stale pooled
    //    connection). The server sees it twice, the caller gets the answer.
    Outcome getDrop = OnReusedConnection(base, "get-drop", "drop-once", "GET", &hits);
    CHECK(getDrop.code == 0 && getDrop.httpCode == 200, "GET whose reused connection is dropped is replayed and succeeds");
    CHECK(hits == 2, "dropped GET reached the server twice (original + one replay)");

    // 2. Same with an abortive close (RST).
    Outcome getRst = OnReusedConnection(base, "get-rst", "rst-once", "GET", &hits);
    CHECK(getRst.code == 0 && getRst.httpCode == 200, "GET whose reused connection is reset is replayed and succeeds");
    CHECK(hits == 2, "reset GET reached the server twice");

    // 3. HEAD follows the GET rule.
    Outcome headDrop = OnReusedConnection(base, "head-drop", "drop-once", "HEAD", &hits);
    CHECK(headDrop.code == 0 && headDrop.httpCode == 200, "HEAD whose reused connection is dropped is replayed and succeeds");
    CHECK(hits == 2, "dropped HEAD reached the server twice");

    // 4. The server had the POST (and its body) when it dropped the connection: never sent twice
    //    (OkHttp does not retry once the request may have been transmitted). Before raft.46
    //    libcurl replayed it and the server saw it twice.
    Outcome postDrop = OnReusedConnection(base, "post-drop", "drop-once", "POST", &hits, "payload");
    CHECK(postDrop.code == 56 && postDrop.error.find("not replayed") != std::string::npos,
          "POST whose reused connection is dropped fails with the not-replayed error");
    CHECK(hits == 1, "dropped POST reached the server exactly once");

    // 5. Same POST with a reset.
    Outcome postRst = OnReusedConnection(base, "post-rst", "rst-once", "POST", &hits, "payload");
    CHECK(postRst.code != 0, "POST whose reused connection is reset fails");
    CHECK(hits == 1, "reset POST reached the server exactly once");

    // 6. PUT and DELETE are not replayed either (only GET/HEAD are).
    Outcome putDrop = OnReusedConnection(base, "put-drop", "drop-once", "PUT", &hits, "payload");
    CHECK(putDrop.code != 0 && hits == 1, "PUT whose reused connection is dropped fails and reached the server once");
    Outcome deleteDrop = OnReusedConnection(base, "delete-drop", "drop-once", "DELETE", &hits);
    CHECK(deleteDrop.code != 0 && hits == 1, "DELETE whose reused connection is dropped fails and reached the server once");

    // 7. A response byte already arrived (headers + half the body): no replay for any method; the
    //    caller sees a transfer error, never a silently truncated success.
    Outcome getPartial = OnReusedConnection(base, "get-partial", "partial-once", "GET", &hits);
    CHECK(getPartial.code != 0, "GET cut off mid-body reports an error (no truncated success)");
    CHECK(hits == 1, "GET cut off mid-body is not replayed by libcurl");

    // 8. The server closes after answering without announcing it (stale pooled connection, OkHttp
    //    "connection closed by server after response"): the next request of any method must go out
    //    on a new connection and succeed exactly once.
    {
        CurlMultiEngineHandle engine = CreateCurlMultiEngine("connection-reuse-test");
        Outcome first = Send(engine, base + "/reuse/stale-first/close-after", "GET");
        CHECK(first.httpCode == 200, "close-after answer arrives");
        Outcome post = Send(engine, base + "/reuse/stale-post/ok", "POST", "payload");
        CHECK(post.code == 0 && post.httpCode == 200, "POST after a server-closed idle connection succeeds on a new connection");
        CHECK(Hits(engine, base, "stale-post") == 1, "POST after a server-closed idle connection reached the server once");
        DeleteCurlMultiEngine(engine);
    }

    // 9. A connection that failed once does not poison the engine: the next GET and POST on the
    //    same engine succeed.
    {
        CurlMultiEngineHandle engine = CreateCurlMultiEngine("connection-reuse-test");
        Send(engine, base + "/ok", "GET");
        Send(engine, base + "/reuse/poison/drop-once", "POST", "payload");
        Outcome after = Send(engine, base + "/reuse/poison-after/ok", "POST", "payload");
        CHECK(after.code == 0 && after.httpCode == 200, "the engine serves a POST after a refused replay");
        Outcome get = Send(engine, base + "/ok", "GET");
        CHECK(get.code == 0 && get.httpCode == 200, "the engine serves a GET after a refused replay");
        DeleteCurlMultiEngine(engine);
    }

    // 10. Redirect hop on a reused connection whose target drops it once (OkHttp postRedirectsToGet
    //     / ConnectionReuseTest redirect cases). 303 turns the POST into a GET, and that GET may be
    //     replayed: 200, target hit twice. 307 keeps the POST: never replayed, target hit once.
    Outcome seeOther = OnReusedConnection(base, "see-other", "see-other-drop", "POST", &hits, "payload");
    {
        CurlMultiEngineHandle engine = CreateCurlMultiEngine("connection-reuse-test");
        const int targetHits = Hits(engine, base, "see-other-target");
        DeleteCurlMultiEngine(engine);
        CHECK(seeOther.code == 0 && seeOther.httpCode == 200, "POST -> 303 -> GET whose connection drops is replayed as GET and succeeds");
        CHECK(targetHits == 2, "the 303 target GET reached the server twice (original + replay)");
    }
    Outcome temporary = OnReusedConnection(base, "temporary", "temporary-drop", "POST", &hits, "payload");
    {
        CurlMultiEngineHandle engine = CreateCurlMultiEngine("connection-reuse-test");
        const int targetHits = Hits(engine, base, "temporary-target");
        DeleteCurlMultiEngine(engine);
        CHECK(temporary.code == 56 && temporary.error.find("not replayed") != std::string::npos,
              "POST -> 307 -> POST whose connection drops is not replayed");
        CHECK(targetHits == 1, "the 307 target POST reached the server exactly once");
    }

    // 11. Close-delimited body (no Content-Length): the answer is complete and the connection is
    //     not reused for the next request (OkHttp connectionsAreNotReusedWithUnknownLengthResponseBody).
    {
        CurlMultiEngineHandle engine = CreateCurlMultiEngine("connection-reuse-test");
        Outcome delimited = Send(engine, base + "/reuse/delimited/close-delimited", "GET");
        CHECK(delimited.code == 0 && delimited.httpCode == 200 &&
                  delimited.data.find("\"reuse\":\"delimited\"") != std::string::npos,
              "close-delimited body arrives complete");
        Outcome next = Send(engine, base + "/reuse/delimited-next/ok", "POST", "payload");
        CHECK(next.code == 0 && next.httpCode == 200, "the request after a close-delimited body succeeds");
        CHECK(Hits(engine, base, "delimited-next") == 1, "and reaches the server once");
        DeleteCurlMultiEngine(engine);
    }

    // 12. 408 with Connection: close is passed through, not resent (OkHttp resends after 408; we
    //     leave that to the caller).
    Outcome timeout408 = OnReusedConnection(base, "timeout408", "timeout-408", "POST", &hits, "payload");
    CHECK(timeout408.code == 0 && timeout408.httpCode == 408, "408 + Connection: close is passed through");
    CHECK(hits == 1, "the 408 POST reached the server once (no hidden resend)");

    // 13. Server closed the idle connection a while ago; larger POST bodies (1 KB, 64 KB) still go
    //     out once on a new connection (OkHttp reusedConnectionFailsWithPost): the replay guard must
    //     not refuse a first attempt.
    for (size_t size : {static_cast<size_t>(1024), static_cast<size_t>(64 * 1024)}) {
        CurlMultiEngineHandle engine = CreateCurlMultiEngine("connection-reuse-test");
        Send(engine, base + "/reuse/idle-closed-" + std::to_string(size) + "-warm/close-after", "GET");
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
        const std::string payload(size, 'x');
        const std::string key = "idle-closed-" + std::to_string(size);
        Outcome post = Send(engine, base + "/reuse/" + key + "/ok", "POST", payload.c_str());
        CHECK(post.code == 0 && post.httpCode == 200 &&
                  post.data.find("\"echoLen\":" + std::to_string(size)) != std::string::npos,
              "POST after the server closed the idle connection succeeds with its full body");
        CHECK(Hits(engine, base, key) == 1, "and reaches the server once");
        DeleteCurlMultiEngine(engine);
    }

    if (gFailures > 0) {
        std::fprintf(stderr, "\n%d connection-reuse failure(s)\n", gFailures);
        return 1;
    }
    std::fprintf(stderr, "\nconnection-reuse checks passed\n");
    return 0;
}

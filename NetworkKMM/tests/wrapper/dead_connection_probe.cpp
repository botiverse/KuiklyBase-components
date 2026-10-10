// raft.46 dead-reused-connection probe (root-only, run by dead_connection_probe.sh; not part of
// run_tests.sh). One multi engine sends a GET so a keep-alive connection is pooled, then waits on
// stdin while the script silently drops that connection's packets (iptables DROP on its client
// port, so neither side sees FIN/RST, like a NAT mapping that vanished). After an optional idle
// period the second request goes out on the same engine; the probe prints its outcome and latency.
// GET/HEAD must recover on a new connection; POST must fail (libcurl would otherwise replay it).
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <mutex>
#include <string>
#include <thread>
#include "curl_wrapper.h"

struct Outcome {
    int code = -1;
    long httpCode = -1;
    std::string error;
};

struct Result {
    std::mutex mutex;
    std::condition_variable condition;
    bool done = false;
    int code = -1;
    long httpCode = -1;
    std::string error;
};

static void OnResponse(void *ref, CurlResponse *response) {
    auto *result = static_cast<Result *>(ref);
    std::lock_guard<std::mutex> lock(result->mutex);
    result->code = response->code;
    result->httpCode = response->httpCode;
    if (response->errorMsg != nullptr) result->error.assign(response->errorMsg, response->errorMsgLen);
    result->done = true;
    result->condition.notify_all();
}

static Outcome Send(CurlMultiEngineHandle engine, int64_t id, const std::string &url, const char *method) {
    Result result;
    CurClientHandle client = CreateCurlClient("dead-connection-probe");
    StringDic headers{};
    CurlRequest request{};
    request.url = url.c_str();
    request.method = method;
    request.headers = &headers;
    request.timeout = 30000;
    static const char body[] = "probe";
    if (std::strcmp(method, "POST") == 0) {
        request.postBody = body;
        request.postBodyLen = sizeof(body) - 1;
    }
    CurlCallback callback{&result, OnResponse};
    if (SubmitBufferedRequestV27(engine, id, client, &request, sizeof(request), CURL_WRAPPER_ABI_VERSION, &callback) != 1) {
        DeleteCurlClient(client);
        return Outcome{-2, -1, "submit rejected"};
    }
    std::unique_lock<std::mutex> lock(result.mutex);
    result.condition.wait_for(lock, std::chrono::seconds(40), [&result]() { return result.done; });
    lock.unlock();
    DeleteCurlClient(client);
    return Outcome{result.code, result.httpCode, result.error};
}

int main(int argc, char **argv) {
    if (argc < 4) {
        std::fprintf(stderr, "usage: %s <base-url> <GET|POST> <idle-seconds> [budget-ms]\n", argv[0]);
        return 2;
    }
    const std::string base = argv[1];
    const char *method = argv[2];
    const int idleSeconds = std::atoi(argv[3]);
    // "slow": the second POST goes out before the drop and the server holds its response, so the
    // connection dies after the server already has the request.
    const bool dropAfterSend = argc > 5 && std::strcmp(argv[5], "slow") == 0;
    CurlMultiEngineHandle engine = CreateCurlMultiEngine("dead-connection-probe");
    Outcome first = Send(engine, 1, base + "/ok", "GET");
    std::printf("first code=%d http=%ld\n", first.code, first.httpCode);
    std::printf("READY\n");
    std::fflush(stdout);
    std::string line;
    if (!dropAfterSend) {
        std::getline(std::cin, line);  // the script has blackholed the pooled connection
        std::this_thread::sleep_for(std::chrono::seconds(idleSeconds));
    }
    const auto start = std::chrono::steady_clock::now();
    Outcome second = Send(engine, 2, base + (std::strcmp(method, "POST") != 0 ? "/ok" : dropAfterSend ? "/counted-slow" : "/counted"), method);
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - start).count();
    std::printf("second method=%s idle=%ds code=%d http=%ld elapsedMs=%lld error=%s\n",
        method, idleSeconds, second.code, second.httpCode, static_cast<long long>(elapsed), second.error.c_str());
    DeleteCurlMultiEngine(engine);
    const long long budgetMs = argc > 4 ? std::atoll(argv[4]) : 0;
    // GET/HEAD recover on a new connection; any other method fails fast and is never sent twice.
    const bool replayable = std::strcmp(method, "GET") == 0 || std::strcmp(method, "HEAD") == 0;
    const bool outcome = replayable
        ? second.code == 0 && second.httpCode == 200
        : second.code == 56 && second.error.find("not replayed") != std::string::npos;
    const bool ok = outcome && (budgetMs <= 0 || elapsed <= budgetMs);
    std::printf("%s: %s after %ds idle on a silently dead reused connection %s within %lldms\n",
        ok ? "ok" : "FAIL", method, idleSeconds,
        replayable ? "recovers on a new connection" : "fails without a replay", budgetMs);
    return ok ? 0 : 1;
}

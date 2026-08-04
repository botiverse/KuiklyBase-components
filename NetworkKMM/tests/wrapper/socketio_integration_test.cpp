#include "curl_wrapper.h"
#include <curl/curl.h>

#include <cassert>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>
#include <cstdio>

struct Capture {
    std::mutex mutex;
    std::condition_variable changed;
    std::vector<int> states;
    std::vector<std::pair<std::string, std::string>> events;
};

static void OnState(void *ref, int state, int, const char *) {
    auto *capture = static_cast<Capture *>(ref);
    std::lock_guard<std::mutex> guard(capture->mutex);
    capture->states.push_back(state);
    capture->changed.notify_all();
}

static void OnEvent(void *ref, const char *name, const char *payload) {
    auto *capture = static_cast<Capture *>(ref);
    std::lock_guard<std::mutex> guard(capture->mutex);
    capture->events.emplace_back(name == nullptr ? "" : name,
                                 payload == nullptr ? "" : payload);
    capture->changed.notify_all();
}

int main(int argc, char **argv) {
    assert(argc == 2);
    const curl_version_info_data *version = curl_version_info(CURLVERSION_NOW);
    bool websocketAvailable = false;
    if (version != nullptr && version->protocols != nullptr) {
        for (const char *const *protocol = version->protocols; *protocol != nullptr; ++protocol) {
            websocketAvailable = websocketAvailable || std::strcmp(*protocol, "ws") == 0;
        }
    }
    if (!websocketAvailable) {
        std::fprintf(stderr, "skip: host libcurl does not advertise ws\n");
        return 77;
    }
    Capture capture;
    CurlSocketIoConfigV1 config{};
    config.abiVersion = CURL_SOCKET_IO_ABI_VERSION;
    config.structSize = sizeof(config);
    config.serverUrl = argv[1];
    config.authJson = "{\"token\":\"test\"}";
    config.proxyUrl = "";
    config.connectTimeoutMs = 3000;
    config.receivePollMs = 20;
    config.reconnectInitialDelayMs = 5000;
    config.reconnectMaxDelayMs = 5000;
    CurlSocketIoCallbackV1 callback{&capture, OnState, OnEvent};

    CurlSocketIoHandle client = CreateCurlSocketIoClientV1(
        &config, sizeof(config), CURL_SOCKET_IO_ABI_VERSION, &callback);
    assert(client != nullptr);
    assert(StartCurlSocketIoClientV1(client, CURL_SOCKET_IO_ABI_VERSION) == 1);

    {
        std::unique_lock<std::mutex> lock(capture.mutex);
        assert(capture.changed.wait_for(lock, std::chrono::seconds(5), [&capture]() {
            for (int state : capture.states) {
                if (state == CURL_SOCKET_IO_CONNECTED) return true;
            }
            return false;
        }));
    }
    assert(EmitCurlSocketIoEventV1(client, "room:join", "{\"roomId\":\"r1\"}",
                                   CURL_SOCKET_IO_ABI_VERSION) == 1);
    {
        std::unique_lock<std::mutex> lock(capture.mutex);
        assert(capture.changed.wait_for(lock, std::chrono::seconds(5), [&capture]() {
            return !capture.events.empty();
        }));
        assert(capture.events.size() == 1);
        assert(capture.events[0].first == "message:new");
        assert(capture.events[0].second == "{\"id\":\"m1\"}");
    }

    CloseCurlSocketIoClientV1(client, CURL_SOCKET_IO_ABI_VERSION);
    DeleteCurlSocketIoClientV1(client, CURL_SOCKET_IO_ABI_VERSION);
    return 0;
}

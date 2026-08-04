#include "curl_wrapper.h"

#include <cassert>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <mutex>
#include <thread>

struct ReentrantCloseCapture {
    std::mutex mutex;
    std::condition_variable changed;
    CurlSocketIoHandle client = nullptr;
    std::atomic<int> callbacks{0};
    bool closed = false;
};

static void CloseFromOwnerCallback(void *ref, int state, int, const char *) {
    auto *capture = static_cast<ReentrantCloseCapture *>(ref);
    capture->callbacks.fetch_add(1);
    if (state != CURL_SOCKET_IO_CONNECTING) return;
    CloseCurlSocketIoClientV1(capture->client, CURL_SOCKET_IO_ABI_VERSION);
    DeleteCurlSocketIoClientV1(capture->client, CURL_SOCKET_IO_ABI_VERSION);
    {
        std::lock_guard<std::mutex> guard(capture->mutex);
        capture->closed = true;
    }
    capture->changed.notify_all();
}

int main() {
    char output[512]{};
    assert(SocketIoTestWebSocketUrl("https://raft.example.com/", output, sizeof(output)) == 1);
    assert(std::strcmp(output,
        "wss://raft.example.com/socket.io/?EIO=4&transport=websocket") == 0);

    assert(SocketIoTestWebSocketUrl(
        "ws://localhost:3000/socket.io/?tenant=t1", output, sizeof(output)) == 1);
    assert(std::strcmp(output,
        "ws://localhost:3000/socket.io/?tenant=t1&EIO=4&transport=websocket") == 0);
    assert(SocketIoTestWebSocketUrl(
        "https://raft.example.com?tenant=t1", output, sizeof(output)) == 1);
    assert(std::strcmp(output,
        "wss://raft.example.com/socket.io/?tenant=t1&EIO=4&transport=websocket") == 0);

    assert(SocketIoTestEventFrame(
        "message:\"new\"", "{\"id\":\"m1\"}", output, sizeof(output)) == 1);
    assert(std::strcmp(output,
        "42[\"message:\\\"new\\\"\",{\"id\":\"m1\"}]") == 0);

    char eventName[128]{};
    char payload[256]{};
    assert(SocketIoTestDecodeEvent(output, eventName, sizeof(eventName), payload, sizeof(payload)) == 1);
    assert(std::strcmp(eventName, "message:\"new\"") == 0);
    assert(std::strcmp(payload, "{\"id\":\"m1\"}") == 0);

    assert(SocketIoTestDecodeEvent("42not-json", eventName, sizeof(eventName),
                                   payload, sizeof(payload)) == 0);
    assert(SocketIoTestEventFrame("bad\nname", "{}", output, sizeof(output)) == 1);

    ReentrantCloseCapture capture;
    CurlSocketIoConfigV1 config{};
    config.abiVersion = CURL_SOCKET_IO_ABI_VERSION;
    config.structSize = sizeof(config);
    config.serverUrl = "http://127.0.0.1:1";
    config.authJson = "{}";
    config.proxyUrl = "";
    config.connectTimeoutMs = 50;
    config.receivePollMs = 10;
    config.reconnectInitialDelayMs = 10;
    config.reconnectMaxDelayMs = 20;
    CurlSocketIoCallbackV1 callback{&capture, CloseFromOwnerCallback, nullptr};
    capture.client = CreateCurlSocketIoClientV1(
        &config, sizeof(config), CURL_SOCKET_IO_ABI_VERSION, &callback);
    assert(capture.client != nullptr);
    assert(StartCurlSocketIoClientV1(capture.client, CURL_SOCKET_IO_ABI_VERSION) == 1);
    {
        std::unique_lock<std::mutex> lock(capture.mutex);
        assert(capture.changed.wait_for(lock, std::chrono::seconds(2), [&capture]() {
            return capture.closed;
        }));
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    assert(capture.callbacks.load() == 1);
    return 0;
}

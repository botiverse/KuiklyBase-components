#include <jni.h>
#include <android/log.h>

#include <cstdio>
#include <string>

#include "curl_wrapper.h"

namespace {

constexpr const char *kLogTag = "NetworkKMMCurlSpike";
constexpr const char *kProbeUrl = "https://example.com/";

int AndroidCurlLog(int level, const char *tag, const char *content) {
    int priority = level == 0 ? ANDROID_LOG_DEBUG : ANDROID_LOG_INFO;
    __android_log_print(priority, kLogTag, "%s %s", tag == nullptr ? "" : tag, content == nullptr ? "" : content);
    return 1;
}

struct ProbeResult {
    bool passed = false;
    long http_code = 0;
    int curl_code = -1;
    int bytes = 0;
    double connect_ms = 0;
    double tls_ms = 0;
    double total_ms = 0;
};

void ProbeCallback(void *callbackRef, CurlResponse *response) {
    auto *result = static_cast<ProbeResult *>(callbackRef);
    result->curl_code = response->code;
    result->http_code = response->httpCode;
    result->bytes = response->dataLen;
    result->connect_ms = response->elapse.connectTimeMs;
    result->tls_ms = response->elapse.sslCostTimeMs;
    result->total_ms = response->elapse.totalTimeMs;
    result->passed = response->code == 0 && response->httpCode == 200 && response->dataLen > 0;
}

ProbeResult RunSingleProbe(const char *caInfoPath) {
    StringDic headers{};
    CurlRequest request{};
    request.url = kProbeUrl;
    request.method = "GET";
    request.headers = &headers;
    request.timeout = 10'000;

    ProbeResult result;
    CurlCallback callback{};
    callback.callbackRef = &result;
    callback.callback = ProbeCallback;

    CurClientHandle client = CreateCurlClient("android-curl-spike");
    SetCurlCaInfo(client, caInfoPath);
    StartRequest(client, request, &callback);
    DeleteCurlClient(client);
    return result;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_tencent_networkkmm_curlspike_MainActivity_runProbe(
    JNIEnv *env,
    jclass,
    jstring caInfoPath
) {
    const char *ca_path = env->GetStringUTFChars(caInfoPath, nullptr);
    setCurlLogImpl(AndroidCurlLog);
    ProbeResult first = RunSingleProbe(ca_path);
    ProbeResult second = RunSingleProbe(ca_path);
    env->ReleaseStringUTFChars(caInfoPath, ca_path);
    bool reused_connection = second.connect_ms == 0 && second.tls_ms == 0;

    char output[512];
    std::snprintf(
        output,
        sizeof(output),
        "completed passed=%s reused=%s first={curl=%d http=%ld bytes=%d connectMs=%.3f tlsMs=%.3f totalMs=%.3f} "
        "second={curl=%d http=%ld bytes=%d connectMs=%.3f tlsMs=%.3f totalMs=%.3f}",
        first.passed && second.passed && reused_connection ? "true" : "false",
        reused_connection ? "true" : "false",
        first.curl_code,
        first.http_code,
        first.bytes,
        first.connect_ms,
        first.tls_ms,
        first.total_ms,
        second.curl_code,
        second.http_code,
        second.bytes,
        second.connect_ms,
        second.tls_ms,
        second.total_ms
    );
    return env->NewStringUTF(output);
}

/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "curl_wrapper.h"

namespace {

constexpr int kModeBuffered = 0;
constexpr int kModeStreamDownload = 1;
constexpr int kModeStreamUpload = 2;
constexpr int kCurlEngineFailure = -1;

std::mutex g_clients_mutex;
std::unordered_map<int, CurClientHandle> g_clients;

class JStringUtfChars {
 public:
    JStringUtfChars(JNIEnv *env, jstring value) : env_(env), value_(value) {
        if (value_ != nullptr) {
            chars_ = env_->GetStringUTFChars(value_, nullptr);
        }
    }

    ~JStringUtfChars() {
        if (chars_ != nullptr) {
            env_->ReleaseStringUTFChars(value_, chars_);
        }
    }

    const char *get() const { return chars_; }

 private:
    JNIEnv *env_;
    jstring value_;
    const char *chars_ = nullptr;
};

struct CallbackContext {
    JNIEnv *env = nullptr;
    jobject callback = nullptr;
    CurClientHandle client = nullptr;
    jmethodID on_response_start = nullptr;
    jmethodID on_chunk = nullptr;
    jmethodID read_upload_chunk = nullptr;
    jmethodID is_cancelled = nullptr;
    jmethodID on_complete = nullptr;
};

jstring NewString(JNIEnv *env, const char *value) {
    return env->NewStringUTF(value == nullptr ? "" : value);
}

jbyteArray NewByteArray(JNIEnv *env, const char *data, int length) {
    if (data == nullptr || length < 0) {
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(length);
    if (result != nullptr && length > 0) {
        env->SetByteArrayRegion(result, 0, length, reinterpret_cast<const jbyte *>(data));
    }
    return result;
}

void CancelAfterCallbackException(CallbackContext *context) {
    if (context->env->ExceptionCheck()) {
        context->env->ExceptionClear();
        if (context->client != nullptr) {
            Cancel(context->client);
        }
    }
}

bool CancelIfSignalled(CallbackContext *context) {
    const jboolean cancelled = context->env->CallBooleanMethod(context->callback, context->is_cancelled);
    if (context->env->ExceptionCheck()) {
        context->env->ExceptionClear();
        Cancel(context->client);
        return true;
    }
    if (cancelled == JNI_TRUE) {
        Cancel(context->client);
        return true;
    }
    return false;
}

void OnResponseStart(void *callback_ref, long http_code, const char *headers, int header_length) {
    auto *context = static_cast<CallbackContext *>(callback_ref);
    if (CancelIfSignalled(context)) {
        return;
    }
    std::string header_text = headers == nullptr || header_length <= 0
        ? std::string()
        : std::string(headers, static_cast<size_t>(header_length));
    jstring java_headers = NewString(context->env, header_text.c_str());
    context->env->CallVoidMethod(
        context->callback,
        context->on_response_start,
        static_cast<jlong>(http_code),
        java_headers
    );
    context->env->DeleteLocalRef(java_headers);
    CancelAfterCallbackException(context);
    CancelIfSignalled(context);
}

void OnChunk(void *callback_ref, const char *data, int length) {
    auto *context = static_cast<CallbackContext *>(callback_ref);
    if (CancelIfSignalled(context)) {
        return;
    }
    jbyteArray chunk = NewByteArray(context->env, data, length);
    context->env->CallVoidMethod(context->callback, context->on_chunk, chunk);
    if (chunk != nullptr) {
        context->env->DeleteLocalRef(chunk);
    }
    CancelAfterCallbackException(context);
    CancelIfSignalled(context);
}

int ReadUploadChunk(void *read_ref, char *buffer, int max_length) {
    auto *context = static_cast<CallbackContext *>(read_ref);
    auto *chunk = static_cast<jbyteArray>(context->env->CallObjectMethod(
        context->callback,
        context->read_upload_chunk,
        static_cast<jint>(max_length)
    ));
    if (context->env->ExceptionCheck()) {
        context->env->ExceptionClear();
        return -1;
    }
    if (chunk == nullptr) {
        return -1;
    }
    const jsize length = std::min(context->env->GetArrayLength(chunk), static_cast<jsize>(max_length));
    if (length > 0) {
        context->env->GetByteArrayRegion(chunk, 0, length, reinterpret_cast<jbyte *>(buffer));
    }
    context->env->DeleteLocalRef(chunk);
    return length;
}

void OnComplete(void *callback_ref, CurlResponse *response) {
    auto *context = static_cast<CallbackContext *>(callback_ref);
    const CurlResponse fallback{
        kCurlEngineFailure,
        0,
        "curl wrapper returned no response",
        33,
        "",
        0,
        "",
        nullptr,
        0,
        {}
    };
    const CurlResponse *value = response == nullptr ? &fallback : response;
    std::string error_message = value->errorMsg == nullptr || value->errorMsgLen <= 0
        ? std::string()
        : std::string(value->errorMsg, static_cast<size_t>(value->errorMsgLen));
    std::string headers = value->headers == nullptr || value->headerLen <= 0
        ? std::string()
        : std::string(value->headers, static_cast<size_t>(value->headerLen));
    jstring java_error = NewString(context->env, error_message.c_str());
    jstring java_headers = NewString(context->env, headers.c_str());
    jstring java_redirect = NewString(context->env, value->redirectUrl);
    jbyteArray java_data = NewByteArray(context->env, value->data, value->dataLen);
    jstring java_protocol = NewString(context->env, GetCurlNegotiatedProtocol(context->client));

    context->env->CallVoidMethod(
        context->callback,
        context->on_complete,
        static_cast<jint>(value->code),
        static_cast<jlong>(value->httpCode),
        java_error,
        java_headers,
        java_redirect,
        java_data,
        java_protocol,
        static_cast<jdouble>(value->elapse.nameLookupTimeMs),
        static_cast<jdouble>(value->elapse.connectTimeMs),
        static_cast<jdouble>(value->elapse.sslCostTimeMs),
        static_cast<jdouble>(value->elapse.preTransferTime),
        static_cast<jdouble>(value->elapse.startTransferTimeMs),
        static_cast<jdouble>(value->elapse.redirectTime),
        static_cast<jdouble>(value->elapse.recvTime),
        static_cast<jdouble>(value->elapse.totalTimeMs)
    );

    context->env->DeleteLocalRef(java_error);
    context->env->DeleteLocalRef(java_headers);
    context->env->DeleteLocalRef(java_redirect);
    context->env->DeleteLocalRef(java_protocol);
    if (java_data != nullptr) {
        context->env->DeleteLocalRef(java_data);
    }
}

bool PopulateCallbackMethods(JNIEnv *env, jobject callback, CallbackContext *context) {
    jclass callback_class = env->GetObjectClass(callback);
    context->on_response_start = env->GetMethodID(callback_class, "onResponseStart", "(JLjava/lang/String;)V");
    context->on_chunk = env->GetMethodID(callback_class, "onChunk", "([B)V");
    context->read_upload_chunk = env->GetMethodID(callback_class, "readUploadChunk", "(I)[B");
    context->is_cancelled = env->GetMethodID(callback_class, "isCancelled", "()Z");
    context->on_complete = env->GetMethodID(
        callback_class,
        "onComplete",
        "(IJLjava/lang/String;Ljava/lang/String;Ljava/lang/String;[BLjava/lang/String;DDDDDDDD)V"
    );
    env->DeleteLocalRef(callback_class);
    return !env->ExceptionCheck() && context->on_response_start != nullptr && context->on_chunk != nullptr &&
        context->read_upload_chunk != nullptr && context->is_cancelled != nullptr && context->on_complete != nullptr;
}

void InvokeEngineFailure(CallbackContext *context, const char *message) {
    CurlResponse response{};
    response.code = kCurlEngineFailure;
    response.errorMsg = message;
    response.errorMsgLen = static_cast<int>(std::char_traits<char>::length(message));
    OnComplete(context, &response);
}

void NativePerform(
    JNIEnv *env,
    jclass,
    jint request_id,
    jstring url,
    jstring method,
    jobjectArray header_names,
    jobjectArray header_values,
    jlong timeout_millis,
    jlong stream_connect_timeout_millis,
    jlong stream_response_headers_timeout_millis,
    jlong stream_idle_timeout_millis,
    jlong stream_whole_timeout_millis,
    jbyteArray body,
    jlong upload_content_length,
    jstring ca_info_path,
    jstring proxy_url,
    jboolean http3_enabled,
    jint mode,
    jobject callback
) {
    CallbackContext context{};
    context.env = env;
    context.callback = callback;
    if (!PopulateCallbackMethods(env, callback, &context)) {
        return;
    }
    if (CurlWrapperAbiVersion() != CURL_WRAPPER_ABI_VERSION) {
        InvokeEngineFailure(&context, "NetworkKMM curl wrapper ABI mismatch");
        return;
    }

    JStringUtfChars url_chars(env, url);
    JStringUtfChars method_chars(env, method);
    JStringUtfChars ca_chars(env, ca_info_path);
    JStringUtfChars proxy_chars(env, proxy_url);
    if (url_chars.get() == nullptr || method_chars.get() == nullptr) {
        InvokeEngineFailure(&context, "request URL or method is unavailable");
        return;
    }

    const jsize name_count = header_names == nullptr ? 0 : env->GetArrayLength(header_names);
    const jsize value_count = header_values == nullptr ? 0 : env->GetArrayLength(header_values);
    const jsize header_count = std::min(name_count, value_count);
    std::vector<std::string> names;
    std::vector<std::string> values;
    names.reserve(header_count);
    values.reserve(header_count);
    for (jsize index = 0; index < header_count; ++index) {
        auto *java_name = static_cast<jstring>(env->GetObjectArrayElement(header_names, index));
        auto *java_value = static_cast<jstring>(env->GetObjectArrayElement(header_values, index));
        {
            // ReleaseStringUTFChars requires the jstring reference to remain
            // valid. Keep the RAII wrappers inside this scope so their
            // destructors run before the local references are deleted.
            JStringUtfChars name_chars(env, java_name);
            JStringUtfChars value_chars(env, java_value);
            names.emplace_back(name_chars.get() == nullptr ? "" : name_chars.get());
            values.emplace_back(value_chars.get() == nullptr ? "" : value_chars.get());
        }
        env->DeleteLocalRef(java_name);
        env->DeleteLocalRef(java_value);
    }
    std::vector<StringPair> pairs;
    pairs.reserve(header_count);
    for (jsize index = 0; index < header_count; ++index) {
        pairs.push_back(StringPair{names[index].c_str(), values[index].c_str()});
    }
    StringDic headers{pairs.data(), static_cast<int>(pairs.size())};

    std::vector<char> body_bytes;
    if (body != nullptr) {
        const jsize body_length = env->GetArrayLength(body);
        body_bytes.resize(body_length);
        if (body_length > 0) {
            env->GetByteArrayRegion(body, 0, body_length, reinterpret_cast<jbyte *>(body_bytes.data()));
        }
    }
    CurlRequest request{};
    request.url = url_chars.get();
    request.method = method_chars.get();
    request.headers = &headers;
    request.timeout = timeout_millis;
    request.streamConnectTimeoutMs = stream_connect_timeout_millis;
    request.streamResponseHeadersTimeoutMs = stream_response_headers_timeout_millis;
    request.streamIdleTimeoutMs = stream_idle_timeout_millis;
    request.streamWholeTimeoutMs = stream_whole_timeout_millis;
    request.postBodyLen = static_cast<int>(body_bytes.size());
    request.postBody = body_bytes.empty() ? nullptr : body_bytes.data();

    CurClientHandle client = CreateCurlClient("networkkmm-android");
    if (client == nullptr) {
        InvokeEngineFailure(&context, "CreateCurlClient failed");
        return;
    }
    context.client = client;
    SetCurlCaInfo(client, ca_chars.get());
    SetCurlProxy(client, proxy_chars.get());
    if (SetCurlHttp3Enabled(client, http3_enabled == JNI_TRUE ? 1 : 0) == 0) {
        InvokeEngineFailure(&context, "HTTP/3 requested but native curl backend is unavailable");
        context.client = nullptr;
        DeleteCurlClient(client);
        return;
    }
    {
        std::lock_guard<std::mutex> lock(g_clients_mutex);
        g_clients[request_id] = client;
    }
    CancelIfSignalled(&context);

    if (mode == kModeStreamDownload) {
        CurlStreamCallback stream_callback{};
        stream_callback.callbackRef = &context;
        stream_callback.onResponseStart = OnResponseStart;
        stream_callback.onChunk = OnChunk;
        stream_callback.onComplete = OnComplete;
        StartStreamRequest(client, request, &stream_callback);
    } else if (mode == kModeStreamUpload) {
        CurlUploadSource upload_source{};
        upload_source.readRef = &context;
        upload_source.readChunk = ReadUploadChunk;
        upload_source.totalLength = upload_content_length;
        CurlCallback curl_callback{&context, OnComplete};
        StartUploadRequest(client, request, &upload_source, &curl_callback);
    } else if (mode == kModeBuffered) {
        CurlCallback curl_callback{&context, OnComplete};
        StartRequest(client, request, &curl_callback);
    } else {
        InvokeEngineFailure(&context, "unknown Android curl request mode");
    }

    {
        std::lock_guard<std::mutex> lock(g_clients_mutex);
        const auto found = g_clients.find(request_id);
        if (found != g_clients.end() && found->second == client) {
            g_clients.erase(found);
        }
    }
    context.client = nullptr;
    DeleteCurlClient(client);
}

void NativeCancel(JNIEnv *, jclass, jint request_id) {
    std::lock_guard<std::mutex> lock(g_clients_mutex);
    const auto found = g_clients.find(request_id);
    if (found != g_clients.end()) {
        Cancel(found->second);
    }
}

jboolean NativeSupportsHttp3(JNIEnv *, jclass) {
    return CurlSupportsHttp3() != 0 ? JNI_TRUE : JNI_FALSE;
}

}  // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass bridge_class = env->FindClass("com/tencent/kmm/network/internal/platform/AndroidCurlJniBridge");
    if (bridge_class == nullptr) {
        return JNI_ERR;
    }
    JNINativeMethod methods[] = {
        {
            const_cast<char *>("nativePerform"),
            const_cast<char *>(
                "(ILjava/lang/String;Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;JJJJJ[BJLjava/lang/String;"
                "Ljava/lang/String;ZILcom/tencent/kmm/network/internal/platform/AndroidCurlJniCallback;)V"
            ),
            reinterpret_cast<void *>(NativePerform)
        },
        {
            const_cast<char *>("nativeCancel"),
            const_cast<char *>("(I)V"),
            reinterpret_cast<void *>(NativeCancel)
        },
        {
            const_cast<char *>("nativeSupportsHttp3"),
            const_cast<char *>("()Z"),
            reinterpret_cast<void *>(NativeSupportsHttp3)
        }
    };
    const jint result = env->RegisterNatives(bridge_class, methods, sizeof(methods) / sizeof(methods[0]));
    env->DeleteLocalRef(bridge_class);
    return result == JNI_OK ? JNI_VERSION_1_6 : JNI_ERR;
}

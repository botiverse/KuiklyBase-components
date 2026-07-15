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

#include "curl_wrapper.h"
#include <algorithm>
#include <atomic>
#include <cctype>
#include <chrono>
#include <climits>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <memory>
#include <mutex>
#include <string>
// Angle-bracket include so the REAL libcurl headers (cpp/include, 8.16 — the
// version actually linked) win. The quoted form used to resolve to a stale
// vendored 7.64.0-DEV copy next to the sources (Codex-KMP-Developer's find:
// it only worked because CURLOPT enums are append-only).
#include <curl/curl.h>
#include "log/curl_log.h"
#include "utils/curl_utils.h"
#include "zlib.h"

using namespace std;

static std::once_flag gCurlGlobalInitFlag;
static CURLcode gCurlGlobalInitResult = CURLE_FAILED_INIT;

static bool EnsureCurlGlobalInit() {
    std::call_once(gCurlGlobalInitFlag, []() {
        gCurlGlobalInitResult = curl_global_init(CURL_GLOBAL_DEFAULT);
    });
    return gCurlGlobalInitResult == CURLE_OK;
}

static bool CurlSupportsFeature(int feature) {
    if (!EnsureCurlGlobalInit()) {
        return false;
    }
    const curl_version_info_data *info = curl_version_info(CURLVERSION_NOW);
    return info != nullptr && (info->features & feature) != 0;
}

static const char *CurlProtocolName(long httpVersion) {
    if (httpVersion == CURL_HTTP_VERSION_3 || httpVersion == CURL_HTTP_VERSION_3ONLY) {
        return "h3";
    }
    if (httpVersion == CURL_HTTP_VERSION_2_0) {
        return "h2";
    }
    if (httpVersion == CURL_HTTP_VERSION_1_1) {
        return "http/1.1";
    }
    if (httpVersion == CURL_HTTP_VERSION_1_0) {
        return "http/1.0";
    }
    return "unknown";
}

// Connection pooling across the per-request easy handles. libcurl documents
// CURLOPT_HTTP_VERSION as a preference and may otherwise reuse a connection
// negotiated at another version. Keep default h2/h1 traffic and explicit h3
// gray traffic in separate connection caches so a prior h3 request cannot
// silently upgrade a default request. DNS/TLS sharing remains pooled within
// each cohort. Guarded by per-lock-kind mutexes as libcurl requires.
static CURLSH *gCurlDefaultShare = nullptr;
static CURLSH *gCurlHttp3Share = nullptr;
static std::mutex gShareInitMutex;
static std::mutex gShareDataMutexes[CURL_LOCK_DATA_LAST];

static void ShareLockCallback(CURL *handle, curl_lock_data data, curl_lock_access access, void *userptr) {
    if (data >= 0 && data < CURL_LOCK_DATA_LAST) {
        gShareDataMutexes[data].lock();
    }
}

static void ShareUnlockCallback(CURL *handle, curl_lock_data data, void *userptr) {
    if (data >= 0 && data < CURL_LOCK_DATA_LAST) {
        gShareDataMutexes[data].unlock();
    }
}

static CURLSH *GetCurlShare(bool http3Enabled) {
    std::lock_guard<std::mutex> guard(gShareInitMutex);
    CURLSH **slot = http3Enabled ? &gCurlHttp3Share : &gCurlDefaultShare;
    if (*slot == nullptr) {
        *slot = curl_share_init();
        if (*slot != nullptr) {
            curl_share_setopt(*slot, CURLSHOPT_LOCKFUNC, ShareLockCallback);
            curl_share_setopt(*slot, CURLSHOPT_UNLOCKFUNC, ShareUnlockCallback);
            curl_share_setopt(*slot, CURLSHOPT_SHARE, CURL_LOCK_DATA_CONNECT);
            curl_share_setopt(*slot, CURLSHOPT_SHARE, CURL_LOCK_DATA_DNS);
            curl_share_setopt(*slot, CURLSHOPT_SHARE, CURL_LOCK_DATA_SSL_SESSION);
        }
    }
    return *slot;
}
class CurlClient {
 public:
    explicit CurlClient(std::string logTag) {
        log_tag_ = logTag;
        logI(log_tag_, "CurlClient() execute.");
        if (!EnsureCurlGlobalInit()) {
            logE(log_tag_, "curl_global_init() failed: " + std::to_string(gCurlGlobalInitResult));
            return;
        }
        curl_ = curl_easy_init();

        // 重置错误描述信息
        memset(curl_error_msg_, 0, sizeof(curl_error_msg_));
    }

    ~CurlClient() {
        logI(log_tag_, "~CurlClient() execute.");
        // 清理 curl 任务参数
        CleanupCurl();

        // 手动销毁 new 的数据
        if (curl_response_ != nullptr) {
            delete curl_response_;
            curl_response_ = nullptr;
        }
    }

 private:
    // 处理响应头的回调函数
    static size_t HeaderCallback(char *contents, size_t size, size_t nmemb, void *userp) {
        CurlClient *client = static_cast<CurlClient *>(userp);
        if (client == nullptr) {
            logE(gDefaultTag, "HeaderCallback, client is nullptr!!!");
            return 0;
        }

        size_t realsize = size * nmemb;
        std::string line(contents, realsize);
        // 检测是否为HTTP状态行（如"HTTP/1.1 200 OK"）
        if (line.find("HTTP/") == 0) {
            client->current_headers_.clear();
            client->current_headers_ += line;
            client->current_has_location_ = false;
            client->current_status_code_ = ParseHttpStatusCode(line);
            client->final_headers_ready_ = false;
            logI(client->log_tag_, "HeaderCallback httpCode:" + std::to_string(client->current_status_code_));
        } else if (line.find("Location:") == 0 || line.find("location:") == 0) {
            client->current_headers_ += line;
            client->current_has_location_ = true;
            // 提取并存储重定向URL
            client->redirect_url_ = line.substr(line.find(":") + 1);
            // 去除末尾换行符（\r\n）
            if (client->redirect_url_.size() >= 2) {
                client->redirect_url_.resize(client->redirect_url_.size() - 2);
            }
        } else if (line == "\r\n" || line == "\n") {
            client->current_headers_ += line;
            if (!client->CompleteHeaderBlock()) {
                return 0;
            }
        } else {
            // 处理响应的头部信息是否包含 Content-Encoding = gzip
            HandleGzipContentEncoding(client, line);
        }
        return realsize;
    }

    static long ParseHttpStatusCode(const std::string &statusLine) {
        const size_t firstSpace = statusLine.find(' ');
        if (firstSpace == std::string::npos) {
            return 0;
        }
        const char *begin = statusLine.c_str() + firstSpace + 1;
        char *end = nullptr;
        const long parsed = std::strtol(begin, &end, 10);
        return end == begin ? 0 : parsed;
    }

    // Commits one complete header block. Informational blocks are never
    // surfaced as the response start. Redirect blocks are retained as a
    // fallback but delayed because CURLOPT_FOLLOWLOCATION may immediately
    // replace them with the next response. Final 2xx/4xx/5xx (including
    // body-less HEAD/204) start as soon as the terminating blank line arrives.
    bool CompleteHeaderBlock() {
        if (current_status_code_ <= 0) {
            return true;
        }
        if (current_status_code_ >= 100 && current_status_code_ < 200) {
            logI(log_tag_, "ignore informational header block:" + std::to_string(current_status_code_));
            return true;
        }
        const bool redirectMayFollow =
            current_status_code_ >= 300 && current_status_code_ < 400 && current_has_location_;
        final_status_code_ = current_status_code_;
        final_headers_ = current_headers_;
        headers_ = final_headers_;
        // A followed redirect is not the response visible to the caller. Keep
        // waiting (and keep the response-header deadline armed) until curl
        // completes a non-followed header block.
        final_headers_ready_ = !redirectMayFollow;
        if (final_headers_ready_) {
            last_stream_activity_ = std::chrono::steady_clock::now();
        }
        if (stream_mode_ && final_headers_ready_) {
            return DeliverStreamResponseStart();
        }
        return true;
    }

    static void HandleGzipContentEncoding(CurlClient *client, std::string line) {
        if (client == nullptr) {
            logE(gDefaultTag, "HandleGzipContentEncoding, client is nullptr!!!");
            return;
        }

        size_t colon_pos = line.find(':');
        if (colon_pos != std::string::npos) {
            std::string key = line.substr(0, colon_pos);
            std::string value = line.substr(colon_pos + 1);

            // 去除键值前后空格
            auto trim = [](std::string &s) {
                s.erase(s.begin(), std::find_if(s.begin(), s.end(), [](int ch) { return !std::isspace(ch); }));
                s.erase(std::find_if(s.rbegin(), s.rend(), [](int ch) { return !std::isspace(ch); }).base(), s.end());
            };
            trim(key);
            trim(value);

            // Content-Encoding is no longer decoded manually: libcurl (built with
            // zlib/brotli/zstd) transparently decodes the body via
            // CURLOPT_ACCEPT_ENCODING, so the write callback already receives the
            // decompressed bytes. Header lines are still recorded verbatim below.
        }
        client->current_headers_ += line;  // 保留原始头部信息
    }

    // 处理响应正文的回调函数
    static size_t DataWriteCallback(char *contents, size_t size, size_t nmemb, void *userp) {
        size_t realsize = size * nmemb;
        reinterpret_cast<std::string *>(userp)->append(reinterpret_cast<char *>(contents), realsize);
        return realsize;
    }

    // fork #8: streaming write callback. userp is the CurlClient. Each libcurl
    // body write is handed straight to Kotlin via onChunk (no buffering). The
    // first write is where the response headers are complete, so onResponseStart
    // is delivered there exactly once. Streaming requests do not negotiate gzip
    // (identity only), so chunks are the raw response bytes.
    static size_t StreamWriteCallback(char *contents, size_t size, size_t nmemb, void *userp) {
        size_t realsize = size * nmemb;
        CurlClient *client = static_cast<CurlClient *>(userp);
        if (client == nullptr || client->stream_callback_ == nullptr) {
            logE(gDefaultTag, "StreamWriteCallback, client/callback is nullptr!!!");
            return realsize;
        }
        if (client->cancel_flag_.load(std::memory_order_relaxed)) {
            logI(client->log_tag_, "StreamWriteCallback cancel by user.");
            return 0;  // abort the transfer
        }
        client->last_stream_activity_ = std::chrono::steady_clock::now();
        if (!client->DeliverStreamResponseStart()) {
            return 0;
        }
        if (realsize > 0 && client->stream_callback_->onChunk != nullptr) {
            client->stream_callback_->onChunk(
                client->stream_callback_->callbackRef, reinterpret_cast<char *>(contents), static_cast<int>(realsize));
        }
        // Kotlin callback failures request cancellation synchronously. Recheck
        // before returning to libcurl so no later chunk can escape.
        return client->cancel_flag_.load(std::memory_order_relaxed) ? 0 : realsize;
    }

    // Deliver onResponseStart exactly once, when the response headers are ready.
    bool DeliverStreamResponseStart() {
        if (stream_started_ || stream_callback_ == nullptr) {
            return !cancel_flag_.load(std::memory_order_relaxed);
        }
        if (!final_headers_ready_ || cancel_flag_.load(std::memory_order_relaxed)) {
            return false;
        }
        stream_started_ = true;
        if (stream_callback_->onResponseStart != nullptr) {
            stream_callback_->onResponseStart(
                stream_callback_->callbackRef,
                final_status_code_,
                final_headers_.c_str(),
                static_cast<int>(final_headers_.length()));
        }
        return !cancel_flag_.load(std::memory_order_relaxed);
    }

    // issue #8 slice 3: pull-based upload body. libcurl asks for up to
    // size*nitems bytes on the perform thread; the source copies what it has
    // ready (blocking until data is available is fine here). 0 = EOF,
    // negative from the source = abort (CURL_READFUNC_ABORT).
    static size_t UploadReadCallback(char *buffer, size_t size, size_t nitems, void *userp) {
        CurlClient *client = static_cast<CurlClient *>(userp);
        if (client == nullptr || client->upload_source_ == nullptr
            || client->upload_source_->readChunk == nullptr) {
            logE(gDefaultTag, "UploadReadCallback, client/source is nullptr!!!");
            return CURL_READFUNC_ABORT;
        }
        if (client->cancel_flag_.load(std::memory_order_relaxed)) {
            logI(client->log_tag_, "UploadReadCallback cancel by user.");
            return CURL_READFUNC_ABORT;
        }
        size_t maxLen = size * nitems;
        if (maxLen == 0) {
            return 0;
        }
        // readChunk's contract is int-sized; curl's per-call buffer is far
        // below INT_MAX, this cap is only defensive.
        int capped = maxLen > static_cast<size_t>(INT_MAX) ? INT_MAX : static_cast<int>(maxLen);
        int n = client->upload_source_->readChunk(client->upload_source_->readRef, buffer, capped);
        if (n < 0) {
            logE(client->log_tag_, "UploadReadCallback source aborted, ret:" + std::to_string(n));
            return CURL_READFUNC_ABORT;
        }
        return static_cast<size_t>(n);
    }

    // The upload source is a one-shot stream: a rewind request (redirect
    // re-POST, auth retry) must fail honestly (curl surfaces
    // CURLE_SEND_FAIL_REWIND) instead of silently resending a truncated body.
    static int UploadSeekCallback(void *userp, curl_off_t offset, int origin) {
        CurlClient *client = static_cast<CurlClient *>(userp);
        if (client != nullptr) {
            logE(client->log_tag_, "UploadSeekCallback: non-seekable upload source, rewind refused.");
        }
        return CURL_SEEKFUNC_FAIL;
    }

    static int ProgressCallback(void *clientp, curl_off_t dltotal, curl_off_t dlnow, curl_off_t ultotal,
                                curl_off_t ulnow) {
        CurlClient *client = static_cast<CurlClient *>(clientp);
        if (client == nullptr) {
            logE(gDefaultTag, "ProgressCallback client is nullptr!!!");
            return 0;
        }
        if (client->cancel_flag_.load(std::memory_order_relaxed)) {
            logI(gDefaultTag, "ProgressCallback cancel by user.");
            return 1;
        }
        if (client->stream_mode_ && client->StreamPhaseTimedOut()) {
            return 1;
        }
        return 0;
    }

    bool StreamPhaseTimedOut() {
        const auto now = std::chrono::steady_clock::now();
        if (!final_headers_ready_ && stream_response_headers_timeout_ms_ > 0) {
            const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                now - stream_request_started_).count();
            if (elapsed >= stream_response_headers_timeout_ms_) {
                stream_timeout_reason_ = "response headers timeout after " + std::to_string(elapsed) + "ms";
                std::snprintf(curl_error_msg_, sizeof(curl_error_msg_), "%s", stream_timeout_reason_.c_str());
                logE(log_tag_, stream_timeout_reason_);
                return true;
            }
        }
        if (stream_started_ && stream_idle_timeout_ms_ > 0) {
            const auto idle = std::chrono::duration_cast<std::chrono::milliseconds>(
                now - last_stream_activity_).count();
            if (idle >= stream_idle_timeout_ms_) {
                stream_timeout_reason_ = "stream idle timeout after " + std::to_string(idle) + "ms";
                std::snprintf(curl_error_msg_, sizeof(curl_error_msg_), "%s", stream_timeout_reason_.c_str());
                logE(log_tag_, stream_timeout_reason_);
                return true;
            }
        }
        return false;
    }

    // 调试回调函数
    static int DebugCallback(CURL *handle, curl_infotype type, char *data, size_t size, void *userptr) {
        if (type == CURLINFO_TEXT) {
            // 输出调试信息（包含 DNS 解析结果）
            std::string info = "DebugCallback: " + std::string(data, size);
            logI(gDefaultTag, info);
        }
        return 0;
    }

 public:
    // Common curl option setup shared by StartRequest and StartStreamRequest
    // (fork #8). Everything except the write callback + perform is configured
    // here; the caller sets its own CURLOPT_WRITEFUNCTION and performs.
    bool ConfigureRequest(CurlRequest &request, std::string &method) {
        if (!curl_) {
            logE(log_tag_, "curl_easy_init() failed.");
            return false;
        }

        if (request.url == nullptr) {
            logE(log_tag_, "request url is nullptr!!!");
            return false;
        }

        // 请求信息
        const char *ver = curl_version();
        std::string curlVer = "";
        if (ver != nullptr) {
            curlVer = ver;
        }
        const char *url = request.url;
        int64_t timeout = request.timeout;
        StringDic *headers = request.headers;
        int size = headers->size;
        int postBodyLen = request.postBodyLen;
        const char *postBody = request.postBody;
        method = request.method == nullptr ? "" : request.method;
        if (method.empty()) {
            method = postBodyLen > 0 && postBody != nullptr ? "POST" : "GET";
        }
        std::transform(method.begin(), method.end(), method.begin(), ::toupper);
        logI(log_tag_, "libcurl ver:" + curlVer + ", request method:" + method + ", request url:" + url
            + ", header size:" + std::to_string(size) + ", timeout:" + std::to_string(timeout));

        // 拼接 Header
        for (int i = 0; i < size; i++) {
            StringPair header = headers->stringPairs[i];
            std::string key = std::string(header.first);
            std::string tmpKey = key;
            std::string value = std::string(header.second);
            // 比较时转换为小写（避免大小写敏感问题）
            std::transform(tmpKey.begin(), tmpKey.end(), tmpKey.begin(), ::tolower);
            // Route a caller-supplied Accept-Encoding through CURLOPT_ACCEPT_ENCODING
            // (libcurl's content-decoder) instead of a raw header, so libcurl
            // transparently decodes the response and we never emit a duplicate
            // Accept-Encoding header. Unset defaults to "" below = all codecs
            // libcurl was built with (gzip/deflate/br/zstd).
            if (tmpKey == "accept-encoding") {
                accept_encoding_ = value;
                continue;
            }
            std::string header_opt = key + ": " + value;
            logI(log_tag_, "request header[" + std::to_string(i) + "]: " + header_opt);
            struct curl_slist *updated_header_list = curl_slist_append(header_list_, header_opt.c_str());
            if (updated_header_list != nullptr) {
                header_list_ = updated_header_list;
            }
        }

        // url
        curl_easy_setopt(curl_, CURLOPT_URL, url);
        // 收集错误描述信息
        curl_easy_setopt(curl_, CURLOPT_ERRORBUFFER, curl_error_msg_);

        // 拼接header
        if (header_list_) {
            curl_easy_setopt(curl_, CURLOPT_HTTPHEADER, header_list_);
        }
        // Content-encoding negotiation + transparent decoding. libcurl is built
        // with zlib/brotli/zstd (build-ohos-native.sh), so "" advertises every
        // supported codec (gzip, deflate, br, zstd) and decodes the response body
        // in-place. A caller can pin a value (e.g. "identity") via the request's
        // Accept-Encoding header, captured above. Streaming forces identity so the
        // Content-Length header matches the bytes delivered to onChunk (determinate
        // progress) — libcurl would otherwise report the compressed length.
        const char *accept_encoding = stream_mode_ ? "identity" : accept_encoding_.c_str();
        logI(log_tag_, std::string("libcurl accept-encoding: ") +
            (accept_encoding[0] == '\0' ? "<all supported>" : accept_encoding));
        curl_easy_setopt(curl_, CURLOPT_ACCEPT_ENCODING, accept_encoding);
        // Buffered requests keep the historical whole-request timeout.
        // Streaming uses phase deadlines: connect, response headers/TTFB and
        // inter-chunk idle; a whole-transfer deadline is opt-in only.
        if (!stream_mode_ && timeout > 0) {
            curl_easy_setopt(curl_, CURLOPT_TIMEOUT_MS, timeout);
        } else if (stream_mode_ && request.streamWholeTimeoutMs > 0) {
            curl_easy_setopt(curl_, CURLOPT_TIMEOUT_MS, request.streamWholeTimeoutMs);
        }
        // raft.11: connect gets its own short budget (aligned with the ktor
        // transports' 3s cap) so a black-holed address family fails fast
        // instead of inheriting the whole-request timeout, and Happy Eyeballs
        // racing is pinned explicitly instead of trusting the libcurl default.
        const long connectTimeout = stream_mode_ && request.streamConnectTimeoutMs > 0
            ? static_cast<long>(request.streamConnectTimeoutMs)
            : 3000L;
        curl_easy_setopt(curl_, CURLOPT_CONNECTTIMEOUT_MS, connectTimeout);
        curl_easy_setopt(curl_, CURLOPT_HAPPY_EYEBALLS_TIMEOUT_MS, 200L);
        // Pool connections/DNS/TLS sessions across per-request easy handles.
        CURLSH *share = GetCurlShare(http3_enabled_);
        if (share != nullptr) {
            curl_easy_setopt(curl_, CURLOPT_SHARE, share);
        }
        if (!resolve_entry_.empty()) {
            struct curl_slist *updated_resolve_list = curl_slist_append(resolve_list_, resolve_entry_.c_str());
            if (updated_resolve_list == nullptr) {
                logE(log_tag_, "failed to allocate CURLOPT_RESOLVE entry");
                return false;
            }
            resolve_list_ = updated_resolve_list;
            CURLcode resolveResult = curl_easy_setopt(curl_, CURLOPT_RESOLVE, resolve_list_);
            if (resolveResult != CURLE_OK) {
                logE(log_tag_, "CURLOPT_RESOLVE failed: " + std::to_string(resolveResult));
                return false;
            }
        }
        // Default requests are explicitly capped at h2/h1.1. Gray HTTP/3
        // requests use CURL_HTTP_VERSION_3, whose documented semantics race
        // QUIC and fall back to h2/h1.1; 3ONLY is intentionally never used.
        const long requestedHttpVersion = http3_enabled_
            ? CURL_HTTP_VERSION_3
            : (CurlSupportsFeature(CURL_VERSION_HTTP2)
                ? CURL_HTTP_VERSION_2TLS
                : CURL_HTTP_VERSION_1_1);
        CURLcode httpVersionResult = curl_easy_setopt(curl_, CURLOPT_HTTP_VERSION, requestedHttpVersion);
        if (httpVersionResult != CURLE_OK) {
            logE(log_tag_, "CURLOPT_HTTP_VERSION failed: " + std::to_string(httpVersionResult));
            return false;
        }

        // SSL: verify the server certificate chain and hostname (raft.2). The
        // trust anchors are the OHOS system CA store — libcurl/OpenSSL are built
        // with their default CA bundle/path compiled to /etc/ssl/certs (see
        // scripts/build-ohos-native.sh), which is where OpenHarmony ships the
        // system certificates, so no CA file needs to be bundled or set here.
        curl_easy_setopt(curl_, CURLOPT_SSL_VERIFYPEER, 1L);
        curl_easy_setopt(curl_, CURLOPT_SSL_VERIFYHOST, 2L);
        if (!ca_info_path_.empty()) {
            curl_easy_setopt(curl_, CURLOPT_CAINFO, ca_info_path_.c_str());
        }
        // Proxy policy is resolved outside libcurl. Empty means explicit
        // direct mode, not "consult http_proxy/HTTPS_PROXY". A fixed manual
        // URL also disables no_proxy environment bypasses so the host's
        // already-resolved platform decision is the single source of truth.
        curl_easy_setopt(curl_, CURLOPT_PROXY, proxy_url_.c_str());
        if (!proxy_url_.empty()) {
            curl_easy_setopt(curl_, CURLOPT_NOPROXY, "");
        }
        // 使用curl的内部重定向逻辑
        curl_easy_setopt(curl_, CURLOPT_FOLLOWLOCATION, 1L);

        // 进度回调
        curl_easy_setopt(curl_, CURLOPT_XFERINFOFUNCTION, ProgressCallback);
        curl_easy_setopt(curl_, CURLOPT_XFERINFODATA, this);
        curl_easy_setopt(curl_, CURLOPT_NOPROGRESS, 0L);

        if (method == "POST") {
            curl_easy_setopt(curl_, CURLOPT_POST, 1L);
        } else if (method == "HEAD") {
            curl_easy_setopt(curl_, CURLOPT_NOBODY, 1L);
        } else if (method != "GET") {
            curl_easy_setopt(curl_, CURLOPT_CUSTOMREQUEST, method.c_str());
        }

        // Body is supported for POST and custom methods such as PUT/PATCH/DELETE.
        if (method != "GET" && method != "HEAD" && postBodyLen > 0 && postBody != nullptr) {
            logI(log_tag_, "curl " + method + " request, body len:" + std::to_string(postBodyLen));
            // size of the POST input data
            curl_easy_setopt(curl_, CURLOPT_POSTFIELDSIZE, postBodyLen);
            if (postBodyLen >= 8 * 1024 * 1024) {
                logI(log_tag_, "Enter above 8MB branch.");
                // 传 body 指针，不会拷贝数据，因此必须确保 perform 之前数据有效
                curl_easy_setopt(curl_, CURLOPT_POSTFIELDS, postBody);
            } else {
                logI(log_tag_, "Enter libcurl below 8MB branch.");
                // 7.17.1 以上 libcurl 版本支持，最大不能超过 8MB 数据
                // 传 body 指针，会拷贝数据，因此必须先设置 body 大小，否则按空字符串处理
                curl_easy_setopt(curl_, CURLOPT_COPYPOSTFIELDS, postBody);
            }
        }

        // 配置调试选项
        curl_easy_setopt(curl_, CURLOPT_VERBOSE, 1L);
        curl_easy_setopt(curl_, CURLOPT_DEBUGFUNCTION, DebugCallback);

        // 响应头处理
        curl_easy_setopt(curl_, CURLOPT_HEADERFUNCTION, HeaderCallback);
        curl_easy_setopt(curl_, CURLOPT_HEADERDATA, this);
        if (stream_mode_) {
            stream_response_headers_timeout_ms_ = request.streamResponseHeadersTimeoutMs;
            stream_idle_timeout_ms_ = request.streamIdleTimeoutMs;
            stream_request_started_ = std::chrono::steady_clock::now();
            last_stream_activity_ = stream_request_started_;
            stream_timeout_reason_.clear();
        }
        return true;
    }

    void StartRequest(CurlRequest request, CurlCallback *callback) {
        std::string method;
        if (!ConfigureRequest(request, method)) {
            return;
        }
        // Cancel may land in the publish→perform window (RFC D-5): honor a
        // pre-set flag deterministically instead of relying on the first
        // progress tick.
        if (cancel_flag_.load(std::memory_order_relaxed)) {
            logI(log_tag_, "cancelled before perform started.");
            FinishBufferedRequest(CURLE_ABORTED_BY_CALLBACK, callback);
            return;
        }
        // 响应数据 body 处理
        curl_easy_setopt(curl_, CURLOPT_WRITEFUNCTION, DataWriteCallback);
        curl_easy_setopt(curl_, CURLOPT_WRITEDATA, &content_data_);
        // curl 请求处理. libcurl transparently decodes the body per the negotiated
        // Content-Encoding (zlib/brotli/zstd), so content_data_ is already the
        // decompressed payload — no manual gzip pass.
        CURLcode res = curl_easy_perform(curl_);
        FinishBufferedRequest(res, callback);
    }

    // issue #8 slice 3: streaming upload. The request body is pulled from the
    // source chunk-by-chunk through UploadReadCallback (never buffered whole);
    // the response is buffered and delivered exactly like StartRequest.
    void StartUploadRequest(CurlRequest request, CurlUploadSource *source, CurlCallback *callback) {
        upload_source_ = source;
        std::string method;
        if (!ConfigureRequest(request, method)) {
            return;
        }
        if (cancel_flag_.load(std::memory_order_relaxed)) {
            logI(log_tag_, "upload cancelled before perform started.");
            FinishBufferedRequest(CURLE_ABORTED_BY_CALLBACK, callback);
            return;
        }

        // Pull-based body plumbing. ConfigureRequest skipped the POSTFIELDS
        // branch (upload requests carry no postBody), so the read callback is
        // the only body source.
        curl_easy_setopt(curl_, CURLOPT_READFUNCTION, UploadReadCallback);
        curl_easy_setopt(curl_, CURLOPT_READDATA, this);
        curl_easy_setopt(curl_, CURLOPT_SEEKFUNCTION, UploadSeekCallback);
        curl_easy_setopt(curl_, CURLOPT_SEEKDATA, this);

        int64_t total = source != nullptr ? source->totalLength : -1;
        if (method == "POST") {
            // CURLOPT_POST is already set; a known size goes out as a real
            // Content-Length via POSTFIELDSIZE_LARGE.
            if (total >= 0) {
                curl_easy_setopt(curl_, CURLOPT_POSTFIELDSIZE_LARGE, static_cast<curl_off_t>(total));
            }
        } else {
            // PUT and custom methods use the upload channel; CUSTOMREQUEST
            // (set by ConfigureRequest for non-POST/GET/HEAD) still pins the
            // verb on the wire.
            curl_easy_setopt(curl_, CURLOPT_UPLOAD, 1L);
            if (total >= 0) {
                curl_easy_setopt(curl_, CURLOPT_INFILESIZE_LARGE, static_cast<curl_off_t>(total));
            }
        }
        // Unknown length must be announced as chunked by the caller side of
        // libcurl (it does not add the header on its own for HTTP/1.1).
        // Also disable Expect: 100-continue — the ktor/OkHttp transports never
        // send it, and it costs a round-trip (or a 1s stall) on servers that
        // ignore it; OHOS should not behave differently from the other ends.
        {
            struct curl_slist *updated = nullptr;
            if (total < 0) {
                updated = curl_slist_append(header_list_, "Transfer-Encoding: chunked");
                if (updated != nullptr) {
                    header_list_ = updated;
                }
            }
            updated = curl_slist_append(header_list_, "Expect:");
            if (updated != nullptr) {
                header_list_ = updated;
            }
            curl_easy_setopt(curl_, CURLOPT_HTTPHEADER, header_list_);
        }
        logI(log_tag_, "streaming upload, method:" + method + ", totalLength:" + std::to_string(total)
            + (total < 0 ? " (unknown -> chunked)" : ""));

        // Buffered response, same as StartRequest.
        curl_easy_setopt(curl_, CURLOPT_WRITEFUNCTION, DataWriteCallback);
        curl_easy_setopt(curl_, CURLOPT_WRITEDATA, &content_data_);
        CURLcode res = curl_easy_perform(curl_);
        FinishBufferedRequest(res, callback);
    }

    // Shared post-perform tail of StartRequest/StartUploadRequest: build the
    // buffered CurlResponse and invoke the callback exactly once.
    void FinishBufferedRequest(CURLcode res, CurlCallback *callback) {
        int errorCode = res;

        char *ip = nullptr;
        curl_easy_getinfo(curl_, CURLINFO_PRIMARY_IP, &ip);
        // ip is nullptr when the connection never established — appending a
        // null char* to std::string is undefined behavior.
        logI(log_tag_, "ret code:" + std::to_string(errorCode) + ", errorMsg:" + curl_error_msg_
            + ",ip:" + (ip != nullptr ? ip : "") + ", dataLen:" + std::to_string(content_data_.length()) + ", redirect url:"
            + redirect_url_ + "\nheader:\n" + headers_);
        logD(log_tag_, "data:\n" + content_data_);

        // The HTTP status is reported separately from the CURLcode: a completed
        // transfer with a 401/500 must not look like a success to callers.
        long httpCode = 0;
        curl_easy_getinfo(curl_, CURLINFO_RESPONSE_CODE, &httpCode);

        curl_response_ = new CurlResponse();
        curl_response_->code = errorCode;
        curl_response_->httpCode = httpCode;
        curl_response_->headerLen = headers_.length();
        curl_response_->headers = const_cast<char *>(reinterpret_cast<const char *>(headers_.c_str()));
        curl_response_->redirectUrl = const_cast<char *>(reinterpret_cast<const char *>(redirect_url_.c_str()));
        curl_response_->dataLen = 0;
        curl_response_->data = nullptr;
        // 失败场景不赋值响应数据,防止某些不规范调用的业务方即使在失败场景也按成功请求时一样去处理data,造成问题
        if (errorCode == 0) {
            curl_response_->dataLen = content_data_.length();
            curl_response_->data = const_cast<char *>(reinterpret_cast<const char *>(content_data_.c_str()));
        }
        curl_response_->errorMsg = curl_error_msg_;
        curl_response_->errorMsgLen = strlen(curl_error_msg_);
        HandleElapseStatisticsInfo(curl_response_);

        logI(log_tag_, "libcurl callback.");
        // Ownership: the callback struct is BORROWED — the caller allocates
        // and frees it (same contract as StartStreamRequest). The old
        // shared_ptr here took ownership and deleted it, which exploded on
        // stack/self-managed callbacks (iOS curl spike) and was UB for
        // malloc-family allocations (Kotlin nativeHeap).
        if (callback != nullptr && callback->callback != nullptr) {
            callback->callback(callback->callbackRef, curl_response_);
        }
    }

    // fork #8: streaming download. The body is streamed to Kotlin chunk-by-chunk
    // through StreamWriteCallback (no buffering); onResponseStart fires when the
    // headers are ready, onComplete at the end with a body-less CurlResponse.
    void StartStreamRequest(CurlRequest request, CurlStreamCallback *callback) {
        stream_callback_ = callback;
        stream_started_ = false;
        stream_terminal_ = false;
        stream_mode_ = true;
        std::string method;
        if (!ConfigureRequest(request, method)) {
            // Always deliver exactly one terminal callback (upstream #31 contract).
            BuildStreamCompletion(CURLE_FAILED_INIT);
            return;
        }
        if (cancel_flag_.load(std::memory_order_relaxed)) {
            logI(log_tag_, "stream cancelled before perform started.");
            BuildStreamCompletion(CURLE_ABORTED_BY_CALLBACK);
            return;
        }
        // 流式 body: 逐块回调, 不缓冲整包
        curl_easy_setopt(curl_, CURLOPT_WRITEFUNCTION, StreamWriteCallback);
        curl_easy_setopt(curl_, CURLOPT_WRITEDATA, this);
        CURLcode res = curl_easy_perform(curl_);
        if (!stream_timeout_reason_.empty()) {
            res = CURLE_OPERATION_TIMEDOUT;
        } else if (cancel_flag_.load(std::memory_order_relaxed)) {
            // Header/write callbacks abort with CURLE_WRITE_ERROR. Preserve the
            // public cancellation contract regardless of which callback first
            // observed the cancel flag.
            res = CURLE_ABORTED_BY_CALLBACK;
        }
        // A body-less final response (HEAD/204/final redirect) still owes the
        // caller a start. DNS/TLS/connect failures have no complete header
        // block and must not fabricate an HTTP response start.
        if (final_headers_ready_ && !cancel_flag_.load(std::memory_order_relaxed)) {
            DeliverStreamResponseStart();
        }
        BuildStreamCompletion(res);
    }

    // Builds a body-less CurlResponse (body already delivered via onChunk) and
    // invokes onComplete exactly once.
    void BuildStreamCompletion(int res) {
        if (stream_terminal_) {
            return;
        }
        stream_terminal_ = true;
        int errorCode = res;
        long httpCode = 0;
        curl_easy_getinfo(curl_, CURLINFO_RESPONSE_CODE, &httpCode);
        char *ip = nullptr;
        curl_easy_getinfo(curl_, CURLINFO_PRIMARY_IP, &ip);
        logI(log_tag_, "stream ret code:" + std::to_string(errorCode) + ", httpCode:" + std::to_string(httpCode)
            + ", ip:" + (ip != nullptr ? ip : "") + ", redirect url:" + redirect_url_);

        curl_response_ = new CurlResponse();
        curl_response_->code = errorCode;
        curl_response_->httpCode = httpCode;
        curl_response_->headerLen = headers_.length();
        curl_response_->headers = const_cast<char *>(reinterpret_cast<const char *>(headers_.c_str()));
        curl_response_->redirectUrl = const_cast<char *>(reinterpret_cast<const char *>(redirect_url_.c_str()));
        curl_response_->dataLen = 0;
        curl_response_->data = nullptr;
        curl_response_->errorMsg = curl_error_msg_;
        curl_response_->errorMsgLen = strlen(curl_error_msg_);
        HandleElapseStatisticsInfo(curl_response_);

        if (stream_callback_ != nullptr && stream_callback_->onComplete != nullptr) {
            stream_callback_->onComplete(stream_callback_->callbackRef, curl_response_);
        }
    }

 private:
    // 请求结束清理任务
    void CleanupCurl() {
        logI(log_tag_, "cleanup curl client");
        if (curl_) {
            curl_easy_cleanup(curl_);
        }
        if (header_list_ != nullptr) {
            curl_slist_free_all(header_list_);
            header_list_ = nullptr;
        }
        if (resolve_list_ != nullptr) {
            curl_slist_free_all(resolve_list_);
            resolve_list_ = nullptr;
        }

        // No curl_global_cleanup() here: it used to fire on the FIRST client
        // destruction while the process-wide gCurlShare (pooled TLS/DNS
        // sessions) and possibly in-flight clients were still alive — global
        // teardown mid-lifetime is undefined behavior territory. An app
        // process never needs to clean up libcurl globals; the OS reclaims
        // everything at exit (sanctioned by the libcurl docs).
    }


    void HandleElapseStatisticsInfo(CurlResponse *curlResponse) {
        if (curl_ == nullptr || curlResponse == nullptr) {
            return;
        }

        // 获取各阶段耗时统计, libcurl中的这些耗时指标的起始计算点是从发起请求开始,需要减去前面环节的耗时
        double tmpNameLookupTime = 0, tmpConnectTime = 0, tmpSslCostTime = 0, tmpPreTransferTime = 0;
        double tmpStartTransferTime = 0, tmpRedirectTime = 0, tmpTotalTime = 0;
        curl_easy_getinfo(curl_, CURLINFO_NAMELOOKUP_TIME, &tmpNameLookupTime);
        curl_easy_getinfo(curl_, CURLINFO_CONNECT_TIME, &tmpConnectTime);
        curl_easy_getinfo(curl_, CURLINFO_APPCONNECT_TIME, &tmpSslCostTime);
        curl_easy_getinfo(curl_, CURLINFO_PRETRANSFER_TIME, &tmpPreTransferTime);
        curl_easy_getinfo(curl_, CURLINFO_STARTTRANSFER_TIME, &tmpStartTransferTime);
        curl_easy_getinfo(curl_, CURLINFO_REDIRECT_TIME, &tmpRedirectTime);
        curl_easy_getinfo(curl_, CURLINFO_TOTAL_TIME, &tmpTotalTime);

        // 计算真正各阶段的网络耗时
        double nameLookupTime = 0, connectTime = 0, sslCostTime = 0, preTransferTime = 0;
        double startTransferTime = 0, redirectTime = 0, totalTime = 0, recvTime = 0;
        nameLookupTime = tmpNameLookupTime * 1000;
        connectTime = tmpConnectTime * 1000 - tmpNameLookupTime * 1000;
        sslCostTime = tmpSslCostTime * 1000 - tmpConnectTime * 1000;
        preTransferTime = tmpPreTransferTime * 1000 - tmpSslCostTime * 1000;
        // startTransferTime 统计的是数据发送耗时 和 数据发送完毕到首字节返回耗时 之和
        startTransferTime = tmpStartTransferTime * 1000 - tmpPreTransferTime * 1000;
        recvTime = tmpTotalTime * 1000 - tmpStartTransferTime * 1000;
        redirectTime = tmpRedirectTime * 1000;
        totalTime = tmpTotalTime * 1000;

        // Negotiated protocol observability (task #30): proves whether the
        // connection actually ran HTTP/2 after the nghttp2 build-line change.
        long httpVersion = 0;
        curl_easy_getinfo(curl_, CURLINFO_HTTP_VERSION, &httpVersion);
        logI(log_tag_, std::string("transport_protocol http_version=") + CurlProtocolName(httpVersion));

        logI(log_tag_, "request statistics, nameLookupTime:" + std::to_string(nameLookupTime) + ", connectTime:"
            + std::to_string(connectTime) + ", sslCostTime:" + std::to_string(sslCostTime) + ", preTransferTime:"
            + std::to_string(preTransferTime) + ", startTransferTime:" + std::to_string(startTransferTime)
            + ", redirectTime:" + std::to_string(redirectTime) + ", recvTime:" + std::to_string(recvTime)
            + ", totalTime:" + std::to_string(totalTime));
        curlResponse->elapse.nameLookupTimeMs = nameLookupTime;
        curlResponse->elapse.connectTimeMs = connectTime;
        curlResponse->elapse.sslCostTimeMs = sslCostTime;
        curlResponse->elapse.preTransferTime = preTransferTime;
        curlResponse->elapse.startTransferTimeMs = startTransferTime;
        curlResponse->elapse.redirectTime = redirectTime;
        curlResponse->elapse.recvTime = recvTime;
        curlResponse->elapse.totalTimeMs = totalTime;
    }

 public:
    // Written by Cancel() from arbitrary caller threads while the perform
    // thread reads it in callbacks — a plain bool here is a C++ data race
    // (RFC D-5 engine-level invariant). Relaxed ordering suffices: it is a
    // pure cancel hint and synchronises no other data.
    std::atomic<bool> cancel_flag_{false};

    void SetCaInfo(const char *caInfoPath) {
        ca_info_path_ = caInfoPath == nullptr ? "" : caInfoPath;
    }

    void SetProxy(const char *proxyUrl) {
        proxy_url_ = proxyUrl == nullptr ? "" : proxyUrl;
    }

    bool SetResolve(const char *resolveEntry) {
        resolve_entry_ = resolveEntry == nullptr ? "" : resolveEntry;
        return true;
    }

    bool SetHttp3Enabled(bool enabled) {
        if (enabled && !CurlSupportsHttp3()) {
            return false;
        }
        http3_enabled_ = enabled;
        return true;
    }

    const char *GetNegotiatedProtocol() {
        if (curl_ == nullptr) {
            return "unknown";
        }
        long httpVersion = 0;
        if (curl_easy_getinfo(curl_, CURLINFO_HTTP_VERSION, &httpVersion) != CURLE_OK) {
            return "unknown";
        }
        return CurlProtocolName(httpVersion);
    }

 private:
    std::string log_tag_;
    CURL *curl_ = nullptr;
    struct curl_slist *header_list_ = nullptr;
    struct curl_slist *resolve_list_ = nullptr;
    char curl_error_msg_[CURL_ERROR_SIZE];
    std::string headers_;
    std::string current_headers_;
    std::string final_headers_;
    std::string redirect_url_;
    std::string content_data_;
    CurlResponse *curl_response_ = nullptr;  // destructor deletes it — must not start wild
    // Caller-pinned Accept-Encoding (from the request header); empty = advertise
    // all codecs libcurl supports and let it decode transparently.
    std::string accept_encoding_;
    std::string ca_info_path_;
    std::string proxy_url_;
    std::string resolve_entry_;
    // fork #8 streaming: set for the lifetime of a StartStreamRequest call.
    CurlStreamCallback *stream_callback_ = nullptr;
    // issue #8 slice 3: set for the lifetime of a StartUploadRequest call.
    CurlUploadSource *upload_source_ = nullptr;
    bool stream_started_ = false;
    bool stream_terminal_ = false;
    bool final_headers_ready_ = false;
    bool current_has_location_ = false;
    long current_status_code_ = 0;
    long final_status_code_ = 0;
    int64_t stream_response_headers_timeout_ms_ = 0;
    int64_t stream_idle_timeout_ms_ = 0;
    std::chrono::steady_clock::time_point stream_request_started_{};
    std::chrono::steady_clock::time_point last_stream_activity_{};
    std::string stream_timeout_reason_;
    // Streaming forces Accept-Encoding: identity so Content-Length matches the
    // bytes delivered to onChunk (determinate progress, no transparent decode).
    bool stream_mode_ = false;
    bool http3_enabled_ = false;
};

void StartRequest(CurClientHandle handle, CurlRequest request, CurlCallback *callback) {
    if (handle == nullptr) {
        logE(gDefaultTag, "client is nullptr!!!");
        return;
    }
    reinterpret_cast<CurlClient *>(handle)->StartRequest(request, callback);
}

void StartStreamRequest(CurClientHandle handle, CurlRequest request, CurlStreamCallback *callback) {
    if (handle == nullptr) {
        logE(gDefaultTag, "client is nullptr!!!");
        return;
    }
    reinterpret_cast<CurlClient *>(handle)->StartStreamRequest(request, callback);
}

void StartUploadRequest(CurClientHandle handle, CurlRequest request, CurlUploadSource *source, CurlCallback *callback) {
    if (handle == nullptr) {
        logE(gDefaultTag, "client is nullptr!!!");
        return;
    }
    reinterpret_cast<CurlClient *>(handle)->StartUploadRequest(request, source, callback);
}

CurClientHandle CreateCurlClient(const char *logTag) {
    if (logTag == nullptr) {
        return new CurlClient(gDefaultTag);
    }
    return new CurlClient(logTag);
}

void DeleteCurlClient(CurClientHandle handle) {
    if (handle == nullptr) {
        return;
    }
    CurlClient *curl = reinterpret_cast<CurlClient *>(handle);
    delete curl;
}

void Cancel(CurClientHandle handle) {
    if (handle == nullptr) {
        logE(gDefaultTag, "cancel fail, handler is nullptr");
        return;
    }
    logI(gDefaultTag, "cancel request");
    CurlClient *curl = reinterpret_cast<CurlClient *>(handle);
    curl->cancel_flag_.store(true, std::memory_order_relaxed);
}

void SetCurlCaInfo(CurClientHandle handle, const char *caInfoPath) {
    if (handle == nullptr) {
        return;
    }
    reinterpret_cast<CurlClient *>(handle)->SetCaInfo(caInfoPath);
}

void SetCurlProxy(CurClientHandle handle, const char *proxyUrl) {
    if (handle == nullptr) {
        return;
    }
    reinterpret_cast<CurlClient *>(handle)->SetProxy(proxyUrl);
}

int SetCurlResolve(CurClientHandle handle, const char *resolveEntry) {
    if (handle == nullptr) {
        return 0;
    }
    return reinterpret_cast<CurlClient *>(handle)->SetResolve(resolveEntry) ? 1 : 0;
}

int CurlSupportsHttp3(void) {
    return CurlSupportsFeature(CURL_VERSION_HTTP3) ? 1 : 0;
}

int SetCurlHttp3Enabled(CurClientHandle handle, int enabled) {
    if (handle == nullptr) {
        return 0;
    }
    return reinterpret_cast<CurlClient *>(handle)->SetHttp3Enabled(enabled != 0) ? 1 : 0;
}

const char *GetCurlNegotiatedProtocol(CurClientHandle handle) {
    if (handle == nullptr) {
        return "unknown";
    }
    return reinterpret_cast<CurlClient *>(handle)->GetNegotiatedProtocol();
}
int CurlWrapperAbiVersion(void) {
    return CURL_WRAPPER_ABI_VERSION;
}

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
#include <cctype>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include "curl/curl.h"
#include "log/curl_log.h"
#include "utils/curl_utils.h"
#include "zlib.h"

using namespace std;

bool curlGlobalInited = false;
bool curlGlobalCleanuped = false;

// Connection pooling across the per-request easy handles: a process-wide
// share handle pools connections, DNS entries, and TLS sessions, so a fresh
// easy handle per request no longer means a fresh TCP+TLS handshake each
// time. Guarded by per-lock-kind mutexes as libcurl requires.
static CURLSH *gCurlShare = nullptr;
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

static CURLSH *GetCurlShare() {
    std::lock_guard<std::mutex> guard(gShareInitMutex);
    if (gCurlShare == nullptr) {
        gCurlShare = curl_share_init();
        if (gCurlShare != nullptr) {
            curl_share_setopt(gCurlShare, CURLSHOPT_LOCKFUNC, ShareLockCallback);
            curl_share_setopt(gCurlShare, CURLSHOPT_UNLOCKFUNC, ShareUnlockCallback);
            curl_share_setopt(gCurlShare, CURLSHOPT_SHARE, CURL_LOCK_DATA_CONNECT);
            curl_share_setopt(gCurlShare, CURLSHOPT_SHARE, CURL_LOCK_DATA_DNS);
            curl_share_setopt(gCurlShare, CURLSHOPT_SHARE, CURL_LOCK_DATA_SSL_SESSION);
        }
    }
    return gCurlShare;
}
class CurlClient {
 public:
    explicit CurlClient(std::string logTag) {
        log_tag_ = logTag;
        logI(log_tag_, "CurlClient() execute.");
        // 初始化 curl
        if (!curlGlobalInited) {
            curl_global_init(CURL_GLOBAL_DEFAULT);
            curlGlobalInited = true;
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
            // CURLINFO_RESPONSE_CODE writes a long (8 bytes on LP64) — an
            // int32_t here lets curl overwrite adjacent stack memory.
            long httpCode = 0;
            if (client->curl_ != nullptr) {
                curl_easy_getinfo(client->curl_, CURLINFO_RESPONSE_CODE, &httpCode);
            }
            logI(client->log_tag_, "HeaderCallback httpCode:" + std::to_string(httpCode));
            // 仅处理非重定向状态码（如200）
            // 检测重定向状态码（3xx）
            if (httpCode >= 300 && httpCode < 400) {
                client->redirect_url_.clear();  // 开始新重定向时清空旧值
            } else {
                if (!client->headers_.empty()) {
                    logI(client->log_tag_, "redirect header:" + client->headers_);
                }
                client->headers_.clear();  // 重置，准备记录最终头部.主要针对重定向场景,记录重定向之后的header信息
            }
            client->headers_ += line;
        } else if (line.find("Location:") == 0 || line.find("location:") == 0) {
            client->headers_ += line;
            // 提取并存储重定向URL
            client->redirect_url_ = line.substr(line.find(":") + 1);
            // 去除末尾换行符（\r\n）
            if (client->redirect_url_.size() >= 2) {
                client->redirect_url_.resize(client->redirect_url_.size() - 2);
            }
        } else {
            // 处理响应的头部信息是否包含 Content-Encoding = gzip
            HandleGzipContentEncoding(client, line);
        }
        return realsize;
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
        client->headers_ += line + "\n";  // 保留原始头部信息
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
        if (client->cancel_flag_) {
            logI(client->log_tag_, "StreamWriteCallback cancel by user.");
            return 0;  // abort the transfer
        }
        client->DeliverStreamResponseStart();
        if (realsize > 0 && client->stream_callback_->onChunk != nullptr) {
            client->stream_callback_->onChunk(
                client->stream_callback_->callbackRef, reinterpret_cast<char *>(contents), static_cast<int>(realsize));
        }
        return realsize;
    }

    // Deliver onResponseStart exactly once, when the response headers are ready.
    void DeliverStreamResponseStart() {
        if (stream_started_ || stream_callback_ == nullptr) {
            return;
        }
        stream_started_ = true;
        long httpCode = 0;
        curl_easy_getinfo(curl_, CURLINFO_RESPONSE_CODE, &httpCode);
        if (stream_callback_->onResponseStart != nullptr) {
            stream_callback_->onResponseStart(
                stream_callback_->callbackRef, httpCode, headers_.c_str(), static_cast<int>(headers_.length()));
        }
    }

    static int ProgressCallback(void *clientp, curl_off_t dltotal, curl_off_t dlnow, curl_off_t ultotal,
                                curl_off_t ulnow) {
        CurlClient *client = static_cast<CurlClient *>(clientp);
        if (client == nullptr) {
            logE(gDefaultTag, "ProgressCallback client is nullptr!!!");
            return 0;
        }
        if (client->cancel_flag_) {
            logI(gDefaultTag, "ProgressCallback cancel by user.");
            return 1;
        }
        return 0;
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
        // 超时配置
        if (timeout > 0) {
            curl_easy_setopt(curl_, CURLOPT_TIMEOUT_MS, timeout);
        }
        // Pool connections/DNS/TLS sessions across per-request easy handles.
        CURLSH *share = GetCurlShare();
        if (share != nullptr) {
            curl_easy_setopt(curl_, CURLOPT_SHARE, share);
        }

        // SSL: verify the server certificate chain and hostname (raft.2). The
        // trust anchors are the OHOS system CA store — libcurl/OpenSSL are built
        // with their default CA bundle/path compiled to /etc/ssl/certs (see
        // scripts/build-ohos-native.sh), which is where OpenHarmony ships the
        // system certificates, so no CA file needs to be bundled or set here.
        curl_easy_setopt(curl_, CURLOPT_SSL_VERIFYPEER, 1L);
        curl_easy_setopt(curl_, CURLOPT_SSL_VERIFYHOST, 2L);
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
        return true;
    }

    void StartRequest(CurlRequest request, CurlCallback *callback) {
        std::string method;
        if (!ConfigureRequest(request, method)) {
            return;
        }
        // 响应数据 body 处理
        curl_easy_setopt(curl_, CURLOPT_WRITEFUNCTION, DataWriteCallback);
        curl_easy_setopt(curl_, CURLOPT_WRITEDATA, &content_data_);
        // curl 请求处理. libcurl transparently decodes the body per the negotiated
        // Content-Encoding (zlib/brotli/zstd), so content_data_ is already the
        // decompressed payload — no manual gzip pass.
        CURLcode res = curl_easy_perform(curl_);
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
        shared_ptr<CurlCallback> fetchCallbackBlockPtr(callback);
        fetchCallbackBlockPtr->callback(fetchCallbackBlockPtr->callbackRef, curl_response_);
    }

    // fork #8: streaming download. The body is streamed to Kotlin chunk-by-chunk
    // through StreamWriteCallback (no buffering); onResponseStart fires when the
    // headers are ready, onComplete at the end with a body-less CurlResponse.
    void StartStreamRequest(CurlRequest request, CurlStreamCallback *callback) {
        stream_callback_ = callback;
        stream_started_ = false;
        stream_mode_ = true;
        std::string method;
        if (!ConfigureRequest(request, method)) {
            // Always deliver exactly one terminal callback (upstream #31 contract).
            DeliverStreamResponseStart();
            BuildStreamCompletion(CURLE_FAILED_INIT);
            return;
        }
        // 流式 body: 逐块回调, 不缓冲整包
        curl_easy_setopt(curl_, CURLOPT_WRITEFUNCTION, StreamWriteCallback);
        curl_easy_setopt(curl_, CURLOPT_WRITEDATA, this);
        CURLcode res = curl_easy_perform(curl_);
        // A body-less response (or a failure before any write) still owes the
        // caller an onResponseStart before onComplete.
        DeliverStreamResponseStart();
        BuildStreamCompletion(res);
    }

    // Builds a body-less CurlResponse (body already delivered via onChunk) and
    // invokes onComplete exactly once.
    void BuildStreamCompletion(int res) {
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

        if (!curlGlobalCleanuped) {
            curlGlobalCleanuped = true;
            curl_global_cleanup();
        }
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
    // Must be initialized: ProgressCallback reads it on every transfer tick,
    // and an indeterminate value aborts the request (CURLE_ABORTED_BY_CALLBACK).
    bool cancel_flag_ = false;

 private:
    std::string log_tag_;
    CURL *curl_;
    struct curl_slist *header_list_ = nullptr;
    char curl_error_msg_[CURL_ERROR_SIZE];
    std::string headers_;
    std::string redirect_url_;
    std::string content_data_;
    CurlResponse *curl_response_ = nullptr;  // destructor deletes it — must not start wild
    // Caller-pinned Accept-Encoding (from the request header); empty = advertise
    // all codecs libcurl supports and let it decode transparently.
    std::string accept_encoding_;
    // fork #8 streaming: set for the lifetime of a StartStreamRequest call.
    CurlStreamCallback *stream_callback_ = nullptr;
    bool stream_started_ = false;
    // Streaming forces Accept-Encoding: identity so Content-Length matches the
    // bytes delivered to onChunk (determinate progress, no transparent decode).
    bool stream_mode_ = false;
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
    curl->cancel_flag_ = 1;
}

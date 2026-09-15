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

#ifndef NETWORKKMM_OHOSAPP_PBCURLWRAPPER_MAIN_CPP_WRAPPER_INCLUDE_CURL_WRAPPER_H_
#define NETWORKKMM_OHOSAPP_PBCURLWRAPPER_MAIN_CPP_WRAPPER_INCLUDE_CURL_WRAPPER_H_

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// Increment whenever a public by-value struct or exported callback contract
// changes incompatibly. Kotlin/JNI callers must verify this before passing a
// CurlRequest so a stale native runtime fails closed instead of interpreting
// a differently-sized struct.
#define CURL_WRAPPER_ABI_VERSION 27

int CurlWrapperAbiVersion(void);

/**
 * 日志 回调
 * @param level
 * @param tag
 * @param content
 * @return 是否成功
 */
typedef int (*curlLog)(int level, const char *tag, const char *content);

void setCurlLogImpl(curlLog logImpl);

// 跨端 kv 对象
typedef struct {
    const char *first;
    const char *second;
} StringPair;

// 跨端字典
typedef struct {
    StringPair *stringPairs;
    int size;
} StringDic;

// 耗时指标统计
typedef struct {
    double nameLookupTimeMs;
    double connectTimeMs;
    double sslCostTimeMs;
    double preTransferTime;
    double startTransferTimeMs;
    double redirectTime;
    double recvTime;
    double totalTimeMs;
} ElapseStats;

// Curl 请求信息
typedef struct {
    const char *url;
    const char *method;
    StringDic *headers;
    int64_t timeout;  // 单位 ms
    int64_t streamConnectTimeoutMs;
    int64_t streamResponseHeadersTimeoutMs;
    int64_t streamIdleTimeoutMs;
    int64_t streamWholeTimeoutMs;  // 0 = disabled
    int postBodyLen;
    const char *postBody;
} CurlRequest;

// Curl 响应信息
typedef struct {
    int code;          // CURLcode: 0 = transfer completed (NOT the HTTP status)
    long httpCode;     // HTTP response status (CURLINFO_RESPONSE_CODE), 0 if unavailable
    const char *errorMsg;
    int errorMsgLen;
    const char *headers;
    int headerLen;
    const char *redirectUrl;
    const char *data;
    int dataLen;
    ElapseStats elapse;
} CurlResponse;

// Curl 响应回调
// 所有权契约（对所有 *Callback / CurlUploadSource 一致）：结构体由调用方分配、
// 调用方释放，wrapper 只在请求期间借用——传栈上/自管内存都合法。wrapper
// 绝不 delete/free 它们（历史上 StartRequest 曾接管并 delete，栈上回调会炸）。
typedef struct {
    void *callbackRef;
    void (*callback)(void *callbackRef, CurlResponse *response);
} CurlCallback;

// Curl 流式响应回调 (fork #8): 响应头就绪时 onResponseStart 交付状态码+头; 响应体
// 每到一块调 onChunk (不缓冲整包); 结束时 onComplete 交付状态/错误 (CurlResponse
// 的 data 为 null, body 已通过 onChunk 交付)。
typedef struct {
    void *callbackRef;
    void (*onResponseStart)(void *callbackRef, long httpCode, const char *headers, int headerLen);
    void (*onChunk)(void *callbackRef, const char *data, int len);
    void (*onComplete)(void *callbackRef, CurlResponse *response);
} CurlStreamCallback;

// 流式上传拉取回调 (issue #8 slice 3): 把最多 maxLen 字节拷入 buffer 并返回
// 实际字节数; 0 = EOF (body 结束), 负数 = 中止传输。在 curl perform 线程上
// 同步调用, 可以阻塞等待数据。
typedef int (*curlReadChunk)(void *readRef, char *buffer, int maxLen);

// 流式上传请求体来源 (issue #8 slice 3)。totalLength >= 0 时发送真实
// Content-Length; -1 = 长度未知, 走 Transfer-Encoding: chunked。
typedef struct {
    void *readRef;
    curlReadChunk readChunk;
    int64_t totalLength;  // -1 = unknown
} CurlUploadSource;

// Additive transfer facts ABI. This is intentionally separate from
// CurlResponse so existing callers keep their frozen response layout. Callers
// pass both size and version; the runtime rejects mismatches before writing.
#define CURL_TRANSFER_INFO_ABI_VERSION 1
typedef struct {
    int abiVersion;
    uint32_t structSize;
    int finalHeadersObserved;
    int firstBodyObserved;
    int bodyProgressObserved;
    int reserved;
    int64_t finalHeadersElapsedMs;
    int64_t firstBodyElapsedMs;
    int64_t lastBodyProgressElapsedMs;
    int64_t bodyBytes;
} CurlTransferInfoV1;

// Additive completion diagnostics ABI. Connection ids reported by libcurl
// are unique only inside one connection cache, so callers must use the
// (connectionCacheId, connectionId) pair as the physical identity. The cache
// id is a process-local monotonic namespace, never a pointer or request/easy
// handle id. A false connectionIdAvailable means the pair is invalid.
#define CURL_COMPLETION_INFO_ABI_VERSION 1
#define CURL_TLS_TIMING_STATE_UNKNOWN 0
#define CURL_TLS_TIMING_STATE_NOT_APPLICABLE 1
#define CURL_TLS_TIMING_STATE_OBSERVED 2
#define CURL_TLS_TIMING_STATE_REUSED_CONNECTION 3
#define CURL_TLS_TIMING_STATE_NOT_REACHED 4
typedef struct {
    int abiVersion;
    uint32_t structSize;
    int connectionIdAvailable;
    int nameLookupTimingAvailable;
    int connectTimingAvailable;
    int preTransferTimingAvailable;
    int startTransferTimingAvailable;
    int totalTimingAvailable;
    int tlsTimingState;
    int reserved;
    int64_t connectionCacheId;
    int64_t connectionId;
    // Normalized phase durations in microseconds. Availability flags and
    // tlsTimingState are authoritative; a numeric zero may be a real
    // sub-microsecond/skipped phase and must not be treated as unavailable.
    int64_t nameLookupTimeUs;
    int64_t connectTimeUs;
    int64_t tlsTimeUs;
    int64_t preTransferTimeUs;
    int64_t startTransferTimeUs;
    int64_t totalTimeUs;
} CurlCompletionInfoV1;

// CurClient 对象指针
typedef void* CurClientHandle;
typedef void* CurlMultiEngineHandle;
typedef void* CurlWebSocketHandle;

#define CURL_MULTI_INFO_ABI_VERSION 1

typedef struct CurlMultiInfoV1 {
    int abiVersion;
    int structSize;
    int64_t enqueueToNativeStartElapsedMs;
    int ownerThreadObserved;
    int reserved;
} CurlMultiInfoV1;

// Additive WebSocket ABI. It deliberately owns a separate easy handle and is
// driven by one caller thread. Return values are 1 = success/frame, 0 =
// timeout/no frame, -1 = terminal error. The last libcurl error is available
// through CurlWebSocketLastError.
#define CURL_WEBSOCKET_ABI_VERSION 1
typedef struct CurlWebSocketReadResultV1 {
    int abiVersion;
    uint32_t structSize;
    int flags;
    int dataLen;
    int64_t bytesLeft;
} CurlWebSocketReadResultV1;

CurlWebSocketHandle CreateCurlWebSocket(const char *logTag);
void DeleteCurlWebSocket(CurlWebSocketHandle handle);
void CancelCurlWebSocket(CurlWebSocketHandle handle);
int ConnectCurlWebSocketV1(CurlWebSocketHandle handle, const char *url,
                           const StringDic *headers, const char *caInfoPath,
                           const char *proxyUrl, int64_t connectTimeoutMs,
                           int abiVersion);
int SendCurlWebSocketTextV1(CurlWebSocketHandle handle, const char *data,
                            size_t dataLen, int abiVersion);
int ReceiveCurlWebSocketV1(CurlWebSocketHandle handle, char *buffer,
                           size_t bufferSize, int64_t timeoutMs,
                           CurlWebSocketReadResultV1 *result,
                           size_t resultSize, int abiVersion);
int CloseCurlWebSocketV1(CurlWebSocketHandle handle, int abiVersion);
int CurlWebSocketLastError(CurlWebSocketHandle handle);

// C++ Engine.IO v4 / Socket.IO v4 session built on the curl WebSocket ABI.
// The session copies every config string/header before Start returns. All
// callbacks are serialized on its owner thread; Delete must not be called
// from a callback. V1 supports the default namespace and text JSON events.
typedef void* CurlSocketIoHandle;
#define CURL_SOCKET_IO_ABI_VERSION 1

enum CurlSocketIoStateV1 {
    CURL_SOCKET_IO_CONNECTING = 1,
    CURL_SOCKET_IO_ENGINE_OPEN = 2,
    CURL_SOCKET_IO_CONNECTED = 3,
    CURL_SOCKET_IO_DISCONNECTED = 4,
    CURL_SOCKET_IO_RECONNECTING = 5,
    CURL_SOCKET_IO_ERROR = 6,
};

typedef struct CurlSocketIoConfigV1 {
    int abiVersion;
    uint32_t structSize;
    const char *serverUrl;
    const char *authJson;
    const StringDic *headers;
    const char *caInfoPath;
    const char *proxyUrl;
    int64_t connectTimeoutMs;
    int64_t receivePollMs;
    int64_t reconnectInitialDelayMs;
    int64_t reconnectMaxDelayMs;
} CurlSocketIoConfigV1;

typedef struct CurlSocketIoCallbackV1 {
    void *callbackRef;
    void (*onState)(void *callbackRef, int state, int code, const char *detail);
    void (*onEvent)(void *callbackRef, const char *eventName,
                    const char *payloadJson);
} CurlSocketIoCallbackV1;

CurlSocketIoHandle CreateCurlSocketIoClientV1(
    const CurlSocketIoConfigV1 *config, size_t configSize, int abiVersion,
    const CurlSocketIoCallbackV1 *callback);
int StartCurlSocketIoClientV1(CurlSocketIoHandle handle, int abiVersion);
int EmitCurlSocketIoEventV1(CurlSocketIoHandle handle, const char *eventName,
                            const char *payloadJson, int abiVersion);
void CloseCurlSocketIoClientV1(CurlSocketIoHandle handle, int abiVersion);
void DeleteCurlSocketIoClientV1(CurlSocketIoHandle handle, int abiVersion);

// 创建 CurClient 对象
CurClientHandle CreateCurlClient(const char *logTag);

// 销毁 CurClient 对象
void DeleteCurlClient(CurClientHandle handle);

// 取消 CurClient 请求
void Cancel(CurClientHandle handle);

// Override the CA bundle for this client. The path is copied. Production
// delegates validate and require an app-owned bundle before calling native.
void SetCurlCaInfo(CurClientHandle handle, const char *caInfoPath);

// Set a fixed proxy URL. The value is copied; an empty string explicitly
// disables environment/system proxy discovery for direct mode.
void SetCurlProxy(CurClientHandle handle, const char *proxyUrl);

// Cap decoded bytes retained by buffered responses. Zero disables the cap.
// Streaming downloads are not buffered and ignore this setting.
void SetCurlMaxBufferedResponseBytes(CurClientHandle handle, int64_t maxBytes);

// Set the body-progress idle deadline for buffered responses only. Zero
// disables it. This is intentionally separate from streaming phase timeouts.
void SetCurlBufferedBodyIdleTimeoutMs(CurClientHandle handle, int64_t timeoutMs);

// Add one libcurl CURLOPT_RESOLVE entry for this client. The entry is copied
// and follows libcurl's "host:port:address[,address]" format. This preserves
// the URL hostname for TLS SNI/verification while allowing a caller to supply
// an already-resolved address. Empty clears the override.
int SetCurlResolve(CurClientHandle handle, const char *resolveEntry);

// Runtime artifact capability probe. Version numbers alone are insufficient:
// this checks libcurl's compiled feature bits for an actual HTTP/3 backend.
int CurlSupportsHttp3(void);

// Select explicit HTTP/3-with-fallback for this client. Disabled clients are
// pinned to HTTP/2-over-TLS with HTTP/1.1 fallback. Default and H3 clients use
// separate DNS/TLS-session shares; connection caches are intentionally not
// shared across concurrent per-request easy handles because libcurl does not
// support that cross-thread topology. Returns 0 only when HTTP/3 was requested
// but the linked artifact lacks the backend.
int SetCurlHttp3Enabled(CurClientHandle handle, int enabled);

// Actual protocol negotiated by the completed request. The returned pointer
// is a process-lifetime string literal ("h3", "h2", "http/1.1", etc.).
const char *GetCurlNegotiatedProtocol(CurClientHandle handle);

// Snapshot callback-owned monotonic transfer facts for this client. Read only
// after Start*Request has completed its terminal callback and returned, and
// before DeleteCurlClient; concurrent during-transfer reads are not supported.
// Returns 0 on a null/mismatched ABI without writing to the output buffer.
int GetCurlTransferInfoV1(CurClientHandle handle, CurlTransferInfoV1 *info,
                          size_t infoSize, int abiVersion);

// Snapshot completion-only phase availability and physical connection
// identity. Read under the same lifetime contract as GetCurlTransferInfoV1.
// Returns 0 on a null/mismatched ABI without writing to the output buffer.
int GetCurlCompletionInfoV1(CurClientHandle handle, CurlCompletionInfoV1 *info,
                            size_t infoSize, int abiVersion);

// Single-owner multi engine for buffered requests. Submit is non-blocking:
// one native owner thread drives every accepted easy handle through CURLM and
// invokes the copied callback exactly once at transport terminal. The caller
// keeps `handle` alive through that callback and may read transfer/multi facts
// before deleting it. Request strings/headers/body are copied at submission.
// One engine accepts exactly one HTTP-version cohort (default h2/h1 or H3);
// create a separate engine for the other cohort so a default request can never
// silently acquire an H3 connection. Delete cancels all accepted requests and
// synchronously waits for their exactly-once callbacks to return. Therefore
// DeleteCurlMultiEngine must never be called from one of the engine's callbacks;
// the callback/client context must remain alive until that callback returns.
CurlMultiEngineHandle CreateCurlMultiEngine(const char *logTag);
void DeleteCurlMultiEngine(CurlMultiEngineHandle engine);
int SubmitBufferedRequestV27(CurlMultiEngineHandle engine, int64_t requestId,
                             CurClientHandle handle, const CurlRequest *request,
                             size_t requestSize, int abiVersion,
                             const CurlCallback *callback);
void CancelCurlMultiRequest(CurlMultiEngineHandle engine, int64_t requestId);

// Connection-generation rotation (task #52 phase 2).
//
// An engine's CURLM handle owns the reusable connection cache, so retiring an
// engine is what actually drops a connection that survived a network change.
// The default/H3 CURLSH pair does not: it shares DNS and TLS-session data only
// (CURL_LOCK_DATA_CONNECT is deliberately not shared), so rotating it leaves a
// half-dead connection in place.
//
// Retire makes the engine refuse new submissions while every already-accepted
// request runs to its normal terminal. That is the whole difference from
// DeleteCurlMultiEngine, which cancels them: rotation must not fail requests
// that happen to be in flight when the network changes. The caller publishes a
// fresh engine for new work, then deletes the retired one once
// CurlMultiEngineIsDrained reports 1 — at which point delete cancels nothing.
// Retiring is idempotent and safe to call from any thread.
void RetireCurlMultiEngine(CurlMultiEngineHandle engine);

// 1 when the engine holds no pending or accepted request, so its connection
// cache no longer backs a live transfer. Returns 1 for a null handle so a
// caller draining a set is not blocked by an engine that failed to create.
int CurlMultiEngineIsDrained(CurlMultiEngineHandle engine);

// Hand a retired engine over to be deleted once it drains, and return
// immediately. Safe to call from anywhere, including an engine callback --
// which is the reason this exists rather than each platform polling
// CurlMultiEngineIsDrained and calling delete itself.
//
// A completion erases its job before invoking that job's callback, so while an
// engine's callback runs on its owner thread the engine already reports
// drained. Deleting it there would join the owner thread from the owner
// thread. Deletion therefore happens on a reaper thread that is never an
// engine owner. Ownership of `engine` transfers to the reaper: the caller must
// not use, delete or re-retire it afterwards.
void ScheduleRetiredEngineDeletion(CurlMultiEngineHandle engine);
int GetCurlMultiInfoV1(CurClientHandle handle, CurlMultiInfoV1 *info,
                       size_t infoSize, int abiVersion);

#if defined(NETWORKKMM_WRAPPER_TESTING)
// Host-test-only seam: how many retired engines have not been deleted yet.
int CurlRetiredEngineCountForTesting(void);
// Host-test-only seam: 1 fails the next multi perform, 2 the next multi poll.
void SetCurlMultiTestFailureMode(CurlMultiEngineHandle engine, int mode);
void SetCurlClientTestConfigureFailure(CurClientHandle handle);
int SocketIoTestWebSocketUrl(const char *serverUrl, char *output, size_t outputSize);
int SocketIoTestEventFrame(const char *eventName, const char *payloadJson,
                           char *output, size_t outputSize);
int SocketIoTestDecodeEvent(const char *frame, char *eventName,
                            size_t eventNameSize, char *payloadJson,
                            size_t payloadJsonSize);
#endif

// Curl 发送请求
int StartRequestV27(CurClientHandle handle, const CurlRequest *request,
                    size_t requestSize, int abiVersion, CurlCallback *callback);

// Curl 流式发送请求 (fork #8): 响应体逐块通过 callback->onChunk 交付, 不缓冲整包。
int StartStreamRequestV27(CurClientHandle handle, const CurlRequest *request,
                          size_t requestSize, int abiVersion, CurlStreamCallback *callback);

// Curl 流式上传请求 (issue #8 slice 3): 请求体从 source->readChunk 逐块拉取,
// 不整包进内存 (request 的 postBody/postBodyLen 被忽略); 响应仍整包缓冲后经
// callback 交付, 与 StartRequest 一致。source 为非可寻址流: 需要重发 body 的
// 场景 (重定向 re-POST / 认证重试) 会以 CURLE_SEND_FAIL_REWIND 失败而不是
// 静默发送残缺 body。
int StartUploadRequestV27(CurClientHandle handle, const CurlRequest *request,
                          size_t requestSize, int abiVersion,
                          CurlUploadSource *source, CurlCallback *callback);

#ifdef __cplusplus
}
#endif

#endif  // NETWORKKMM_OHOSAPP_PBCURLWRAPPER_MAIN_CPP_WRAPPER_INCLUDE_CURL_WRAPPER_H_

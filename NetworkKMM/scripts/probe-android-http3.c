/* Diagnostic executable only. dlopen the committed, unmodified Android .so.
 * Public unauthenticated endpoint only; discard bodies and log CURLINFO_TEXT.
 * This bypasses the Kotlin/JNI wrapper, so success does not validate that path.
 */
#include <curl/curl.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static void stamp(const char *event) {
    struct timespec ts; clock_gettime(CLOCK_REALTIME, &ts);
    fprintf(stderr, "%lld.%03ld %s ", (long long)ts.tv_sec, ts.tv_nsec / 1000000, event);
}
static size_t discard(char *p, size_t n, size_t m, void *u) { (void)p; (void)u; return n*m; }
static int debug(CURL *c, curl_infotype type, char *data, size_t size, void *u) {
    (void)c; (void)u;
    if (type == CURLINFO_TEXT) { stamp("curl"); fwrite(data, 1, size, stderr); }
    return 0;
}
#define LOAD(name) __typeof__(&name) p_##name = (__typeof__(&name))dlsym(lib, #name); \
    if (!p_##name) { fprintf(stderr, "missing=%s error=%s\n", #name, dlerror()); return 2; }
#define OPT(key, value) do { CURLcode rc = p_curl_easy_setopt(easy, key, value); \
    if (rc != CURLE_OK) { fprintf(stderr, "setopt=%s code=%d\n", #key, rc); return 2; } } while (0)
int main(int argc, char **argv) {
    if (argc != 5) { fprintf(stderr, "usage: probe library ca mode[h3-only|h3-fallback|h2] ipv4\n"); return 2; }
    void *lib = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!lib) { fprintf(stderr, "dlopen=%s\n", dlerror()); return 2; }
    LOAD(curl_version); LOAD(curl_version_info); LOAD(curl_global_init); LOAD(curl_global_cleanup);
    LOAD(curl_easy_init); LOAD(curl_easy_setopt); LOAD(curl_easy_perform); LOAD(curl_easy_getinfo);
    LOAD(curl_easy_cleanup); LOAD(curl_easy_strerror); LOAD(curl_slist_append); LOAD(curl_slist_free_all);
    if (p_curl_global_init(CURL_GLOBAL_DEFAULT) != CURLE_OK) return 2;
    curl_version_info_data *v = p_curl_version_info(CURLVERSION_NOW);
    printf("library=%s\nversion=%s\nfeatures=%d\nssl=%s\nquic=%s\n", argv[1], p_curl_version(), v->features,
           v->ssl_version ? v->ssl_version : "", v->age >= CURLVERSION_SIXTH && v->quic_version ? v->quic_version : "");
    if (v->age >= CURLVERSION_ELEVENTH && v->feature_names)
        for (const char *const *f = v->feature_names; *f; ++f) printf("feature=%s\n", *f);
    long mode = !strcmp(argv[3], "h3-only") ? CURL_HTTP_VERSION_3ONLY :
        !strcmp(argv[3], "h3-fallback") ? CURL_HTTP_VERSION_3 : CURL_HTTP_VERSION_2TLS;
    CURL *easy = p_curl_easy_init(); if (!easy) return 2;
    char resolve[256], error[CURL_ERROR_SIZE] = {0};
    snprintf(resolve, sizeof(resolve), "cloudflare-quic.com:443:%s", argv[4]);
    struct curl_slist *addresses = p_curl_slist_append(NULL, resolve); if (!addresses) return 2;
    OPT(CURLOPT_URL, "https://cloudflare-quic.com/"); OPT(CURLOPT_CAINFO, argv[2]);
    OPT(CURLOPT_PROXY, ""); OPT(CURLOPT_IPRESOLVE, CURL_IPRESOLVE_V4); OPT(CURLOPT_RESOLVE, addresses);
    OPT(CURLOPT_HTTP_VERSION, mode); OPT(CURLOPT_CONNECTTIMEOUT_MS, 10000L); OPT(CURLOPT_TIMEOUT_MS, 20000L);
    OPT(CURLOPT_VERBOSE, 1L); OPT(CURLOPT_DEBUGFUNCTION, debug); OPT(CURLOPT_WRITEFUNCTION, discard);
    OPT(CURLOPT_ERRORBUFFER, error); OPT(CURLOPT_NOSIGNAL, 1L);
    stamp("start"); fprintf(stderr, "mode=%s address=%s\n", argv[3], argv[4]);
    CURLcode rc = p_curl_easy_perform(easy);
    long protocol=0, status=0, port=0; char *ip=NULL; double total=0, connect=0, tls=0;
    p_curl_easy_getinfo(easy, CURLINFO_HTTP_VERSION, &protocol);
    p_curl_easy_getinfo(easy, CURLINFO_RESPONSE_CODE, &status);
    p_curl_easy_getinfo(easy, CURLINFO_PRIMARY_IP, &ip);
    p_curl_easy_getinfo(easy, CURLINFO_PRIMARY_PORT, &port);
    p_curl_easy_getinfo(easy, CURLINFO_TOTAL_TIME, &total);
    p_curl_easy_getinfo(easy, CURLINFO_CONNECT_TIME, &connect);
    p_curl_easy_getinfo(easy, CURLINFO_APPCONNECT_TIME, &tls);
    stamp("end"); fprintf(stderr, "code=%d error=%s buffer=%s\n", rc, p_curl_easy_strerror(rc), error);
    printf("mode=%s code=%d protocol_enum=%ld status=%ld ip=%s port=%ld total=%f connect=%f tls=%f\n",
           argv[3], rc, protocol, status, ip ? ip : "", port, total, connect, tls);
    p_curl_easy_cleanup(easy); p_curl_slist_free_all(addresses); p_curl_global_cleanup(); dlclose(lib);
    return (int)rc;
}

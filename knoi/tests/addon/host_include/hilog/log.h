#ifndef KNOI_HOST_PROBE_HILOG_LOG_H
#define KNOI_HOST_PROBE_HILOG_LOG_H

#include <stdarg.h>

#ifndef LOG_DOMAIN
#define LOG_DOMAIN 0
#endif

typedef enum {
    LOG_APP = 0,
} LogType;

typedef enum {
    LOG_DEBUG = 3,
    LOG_INFO = 4,
    LOG_WARN = 5,
    LOG_ERROR = 6,
    LOG_FATAL = 7,
} LogLevel;

static inline int OH_LOG_Print(
    LogType type,
    LogLevel level,
    unsigned int domain,
    const char* tag,
    const char* format,
    ...)
{
    (void)type;
    (void)level;
    (void)domain;
    (void)tag;
    (void)format;
    return 0;
}

#endif // KNOI_HOST_PROBE_HILOG_LOG_H

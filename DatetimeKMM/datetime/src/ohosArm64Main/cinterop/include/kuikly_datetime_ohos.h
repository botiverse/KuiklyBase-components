/*
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 *
 * Small OHOS/POSIX bridge written for KuiklyBase Datetime. No source from
 * kotlinx-datetime or the CPF fork is incorporated in this file.
 */

#ifndef KUIKLY_DATETIME_OHOS_H
#define KUIKLY_DATETIME_OHOS_H

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <stdint.h>
#include <time.h>
#include <BasicServicesKit/time_service.h>

#ifdef __cplusplus
extern "C" {
#endif

static inline int kuikly_datetime_now_millis(int64_t *result)
{
    if (result == NULL) {
        return -1;
    }
    struct timespec now;
    if (clock_gettime(CLOCK_REALTIME, &now) != 0) {
        return -1;
    }
    *result = ((int64_t)now.tv_sec * 1000) + ((int64_t)now.tv_nsec / 1000000);
    return 0;
}

static inline int kuikly_datetime_read_zone_id(char *result, uint32_t length)
{
    if (result == NULL || length == 0) {
        return (int)TIMESERVICE_ERR_INVALID_PARAMETER;
    }
    return (int)OH_TimeService_GetTimeZone(result, length);
}

static inline int kuikly_datetime_local_offset_seconds(
    int64_t epoch_seconds,
    int32_t *result)
{
    if (result == NULL) {
        return -1;
    }
    const time_t epoch = (time_t)epoch_seconds;
    struct tm local_time;
    tzset();
    if (localtime_r(&epoch, &local_time) == NULL) {
        return -1;
    }
    *result = (int32_t)local_time.tm_gmtoff;
    return 0;
}

#ifdef __cplusplus
}
#endif

#endif /* KUIKLY_DATETIME_OHOS_H */

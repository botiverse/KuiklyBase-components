// Copyright 2025 Tencent Inc. All rights reserved.
//
// Author: junkepeng@tencent.com
//
// resource Manager C Api
//
#ifndef KMMRESOURCE_OPEN_RESOURCE_CORE_NATIVEINTEROP_CINTEROP_CPP_INCLUDE_RESOURCE_COMPOSE_H_
#define KMMRESOURCE_OPEN_RESOURCE_CORE_NATIVEINTEROP_CINTEROP_CPP_INCLUDE_RESOURCE_COMPOSE_H_

#include "napi/native_api.h"
#include "js_native_api.h"
#include "js_native_api_types.h"

#ifdef __cplusplus
extern "C" {
#endif

void ohos_init_resource_manager(napi_env env, napi_value resourceManager);

int ohos_get_media_by_name(const char *mediaName, uint64_t *mediaLen, uint8_t **mediaData,
                           uint32_t density);

int ohos_get_color_by_name(const char *resName, uint32_t *resValue);

int ohos_get_string_plural_by_name(const char *resName, uint32_t num, char **resultValue);

int ohos_get_image_base64_by_name(const char *resName, char **resultValue, uint64_t *resultLen,
                                  uint32_t density);


#ifdef __cplusplus
}
#endif

#endif  // KMMRESOURCE_OPEN_RESOURCE_CORE_NATIVEINTEROP_CINTEROP_CPP_INCLUDE_RESOURCE_COMPOSE_H_

// Copyright 2025 Tencent Inc. All rights reserved.
//
// Author: junkepeng@tencent.com
//
// resource Manager C Api
//
#include "resourcemanager/ohresmgr.h"
#include "rawfile/raw_file_manager.h"
#include "resource_compose.h"

const NativeResourceManager *nativeResourceManager = nullptr;

void ohos_init_resource_manager(napi_env env, napi_value resourceManager) {
    nativeResourceManager = OH_ResourceManager_InitNativeResourceManager(env, resourceManager);
}


int ohos_get_media_by_name(const char *mediaName, uint64_t *mediaLen, uint8_t **mediaData,
                           uint32_t density) {
    auto code = OH_ResourceManager_GetMediaByName(nativeResourceManager, mediaName, mediaData,
                                                  mediaLen, density);

    return code;
}

int ohos_get_color_by_name(const char *resName, uint32_t *resValue) {
    auto code = OH_ResourceManager_GetColorByName(nativeResourceManager, resName, resValue);

    return code;
}

int ohos_get_string_plural_by_name(const char *resName, uint32_t num, char **resultValue) {
    auto code = OH_ResourceManager_GetPluralStringByName(nativeResourceManager, resName, num,
                                                         resultValue);
    return code;
}


int ohos_get_image_base64_by_name(const char *resName, char **resultValue, uint64_t *resultLen,
                                  uint32_t density = 0) {
    auto code = OH_ResourceManager_GetMediaBase64ByName(nativeResourceManager, resName, resultValue,
                                                        resultLen, density);
    return code;
}

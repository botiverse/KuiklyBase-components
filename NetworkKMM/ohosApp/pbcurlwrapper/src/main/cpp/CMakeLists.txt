# the minimum version of CMake.
cmake_minimum_required(VERSION 3.5.1)
project(CurlDemo)

set(NATIVERENDER_ROOT_PATH ${CMAKE_CURRENT_SOURCE_DIR})

# include/ holds the REAL libcurl headers (copied from the cross-compiled
# curl by build-ohos-native.sh, currently 8.16) — they must match the linked
# libcurl.a. The stale vendored 7.64 copy under wrapper/src/curl is gone.
include_directories(${NATIVERENDER_ROOT_PATH}
                    ${NATIVERENDER_ROOT_PATH}/include
                    ${NATIVERENDER_ROOT_PATH}/wrapper/include)

# 添加 -fPIC 编译选项
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

find_library(
    hilog-lib
    hilog_ndk.z
    libz.so
)

add_library(pbcurlwrapper SHARED
    wrapper/src/curl_wrapper.cpp
    wrapper/include/curl_wrapper.h
    wrapper/src/log/curl_log.cpp
    wrapper/src/log/curl_log.h
    wrapper/src/utils/curl_utils.cpp
    wrapper/src/utils/curl_utils.h
    )
# 下方的 libcurl.a 来自tvk工程
set(PB_LIBS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/arm64-v8a)
# libcurl.a is static and is built with zlib/brotli/zstd content-encoding
# support (build-ohos-native.sh), so its codec dependencies are linked here as
# static archives after libcurl.a. brotlidec depends on brotlicommon, so it
# must precede it in link order. These replace the old system libz.so link,
# which was vestigial while libcurl had CURL_ZLIB=OFF.
target_link_libraries(pbcurlwrapper PUBLIC
        libhilog_ndk.z.so
        "-Wl,-Bsymbolic,--gc-sections,--whole-archive"
        ${PB_LIBS_DIR}/libcurl.a
        "-Wl,--no-whole-archive"
        ${PB_LIBS_DIR}/libz.a
        ${PB_LIBS_DIR}/libbrotlidec.a
        ${PB_LIBS_DIR}/libbrotlicommon.a
        ${PB_LIBS_DIR}/libzstd.a
        ${PB_LIBS_DIR}/libnghttp2.a
        ${PB_LIBS_DIR}/libnghttp3.a
        ${PB_LIBS_DIR}/libopenssl.so
)

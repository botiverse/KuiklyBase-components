# the minimum version of CMake.
cmake_minimum_required(VERSION 3.16)
project(KNOI LANGUAGES C CXX)

option(KNOI_USE_AKI "Build the KNOI addon on the pinned Aki runtime" ON)
set(KNOI_WAITER_TIMEOUT_MS "10000" CACHE STRING "Synchronous waiter timeout in milliseconds")

add_definitions(-DOHOS_PLATFORM)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(KNOI_ROOT_PATH ${CMAKE_CURRENT_SOURCE_DIR})
set(KNOI_PROJECT_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)

if(KNOI_USE_AKI)
    # Aki is one hidden static implementation detail of libknoi.so. The
    # process must never load a second default-visible Aki runtime from this
    # addon.
    set(AKI_BUILDING_SHARED OFF CACHE BOOL "" FORCE)
    set(AKI_ENABLE_NAPI ON CACHE BOOL "" FORCE)
    set(AKI_ENABLE_DECLARATION OFF CACHE BOOL "" FORCE)
    set(AKI_ENABLE_TRACING OFF CACHE BOOL "" FORCE)
    set(AKI_ENABLE_CXX_STANDARD_11 OFF CACHE BOOL "" FORCE)
    add_subdirectory(
        ${KNOI_PROJECT_ROOT}/third_party/aki
        ${CMAKE_CURRENT_BINARY_DIR}/aki
    )

    add_library(knoi SHARED
        knoi_aki.cpp
        async_invoker_aki.cpp
        native_bridge_loader.cpp
        function_waiter_registry.cpp
    )
    target_link_libraries(knoi PRIVATE aki_jsbind)
    target_include_directories(knoi SYSTEM PRIVATE
        ${KNOI_PROJECT_ROOT}/third_party/aki/include
        ${KNOI_PROJECT_ROOT}/third_party/aki/src
    )
    target_compile_definitions(knoi PRIVATE
        KNOI_USE_AKI=1
        KNOI_AKI_VERSION="1.3.1"
        KNOI_WAITER_TIMEOUT_MS=${KNOI_WAITER_TIMEOUT_MS}
    )
    target_compile_options(knoi PRIVATE
        -Wall
        -Wextra
        -Werror
        -Wno-unused-command-line-argument
    )

    set_target_properties(aki_jsbind PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN YES
    )
    target_compile_options(aki_jsbind PRIVATE -fvisibility=hidden)
else()
    add_library(knoi SHARED knoi_legacy.cpp async_invoker_legacy.cpp)
    target_compile_definitions(knoi PRIVATE KNOI_USE_AKI=0)
endif()

target_include_directories(knoi PRIVATE
    ${KNOI_ROOT_PATH}
    ${KNOI_ROOT_PATH}/include
)
target_compile_features(knoi PRIVATE cxx_std_17)
set_target_properties(knoi PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
)
target_link_options(knoi PRIVATE -Wl,-z,relro -Wl,-z,now)
target_link_libraries(knoi PRIVATE libace_napi.z.so libhilog_ndk.z.so)

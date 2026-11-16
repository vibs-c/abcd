cmake_minimum_required(VERSION 3.22)
project(abcd_dj_native LANGUAGES C CXX)

# Layout
#   core/                 portable C++17 analysis (no JNI, no Android): the part to reuse on other platforms
#   jni/                  Android JNI glue (thin)
#   dev_tools/            command-line analyzer and synthetic-song self test (not built by Gradle)
#   essentia_generated/   registry + version.h generated once by scripts/generate_essentia_registry.py
#   ../../../third_party/essentia   Essentia (AGPL-3.0), git submodule, only the algorithms we use
#   ../../../third_party/eigen      Eigen 3.4.0 (MPL-2.0), header-only, required by Essentia's core headers
#
# Gradle builds the `abcd_jni` library. For development on a device or emulator:
#   cmake -DABCD_BUILD_TOOLS=ON ... then push `abcd_analyze_cli` with adb.

option(ABCD_BUILD_TOOLS "Build the command-line analyzer and self test" OFF)

set(ESSENTIA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/essentia)
set(ESSENTIA_SRC ${ESSENTIA_DIR}/src)
set(GENERATED_DIR ${CMAKE_CURRENT_SOURCE_DIR}/essentia_generated)
set(EIGEN_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/eigen)

if(NOT EXISTS ${ESSENTIA_SRC}/essentia/essentia.h)
    message(FATAL_ERROR "Essentia submodule missing. Run: git submodule update --init dj-native/third_party/essentia dj-native/third_party/eigen")
endif()

# ---- Essentia subset -------------------------------------------------------------------------
include(${GENERATED_DIR}/algorithms.cmake)  # ESSENTIA_ALGORITHM_SOURCES

file(GLOB_RECURSE ESSENTIA_CORE_SOURCES CONFIGURE_DEPENDS ${ESSENTIA_SRC}/essentia/*.cpp)
# Not used: FFmpeg audio contexts, YAML/JSON conversion (their libraries are not linked).
list(FILTER ESSENTIA_CORE_SOURCES EXCLUDE REGEX "/(audiocontext|jsonconvert)[^/]*\\.cpp$|yaml[^/]*\\.cpp$")

set(ESSENTIA_SOURCES ${ESSENTIA_CORE_SOURCES})
foreach(source ${ESSENTIA_ALGORITHM_SOURCES})
    list(APPEND ESSENTIA_SOURCES ${ESSENTIA_SRC}/${source})
endforeach()
list(APPEND ESSENTIA_SOURCES
    ${GENERATED_DIR}/essentia_algorithms_reg.cpp
    ${ESSENTIA_SRC}/3rdparty/kiss_fft130/kiss_fft.c
    ${ESSENTIA_SRC}/3rdparty/kiss_fft130/tools/kiss_fftr.c)

add_library(essentia_subset STATIC ${ESSENTIA_SOURCES})
target_include_directories(essentia_subset PUBLIC
    ${ESSENTIA_SRC}
    ${ESSENTIA_SRC}/essentia
    ${ESSENTIA_SRC}/essentia/scheduler
    ${ESSENTIA_SRC}/essentia/streaming
    ${ESSENTIA_SRC}/essentia/streaming/algorithms
    ${ESSENTIA_SRC}/essentia/utils
    ${ESSENTIA_SRC}/3rdparty
    ${ESSENTIA_SRC}/3rdparty/kiss_fft130
    ${EIGEN_DIR}
    ${GENERATED_DIR})
# Essentia is written for C++11/14 and is not warning-clean, so build it as C++14 and silence warnings.
set_target_properties(essentia_subset PROPERTIES CXX_STANDARD 14 CXX_STANDARD_REQUIRED ON POSITION_INDEPENDENT_CODE ON)
target_compile_definitions(essentia_subset PUBLIC OS_LINUX EIGEN_MPL2_ONLY)
target_compile_options(essentia_subset PRIVATE -w -O2 -fvisibility=hidden)

# ---- abcd analysis core (portable) --------------------------------------------------------------
add_library(abcd_analysis STATIC
    core/abcd_analysis.cpp
    core/analyzer.cpp
    core/dsp.cpp
    core/json_writer.cpp
    core/structure.cpp)
target_include_directories(abcd_analysis PUBLIC core)
set_target_properties(abcd_analysis PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON POSITION_INDEPENDENT_CODE ON)
target_compile_options(abcd_analysis PRIVATE -Wall -Wextra -O2)
target_link_libraries(abcd_analysis PUBLIC essentia_subset)

# ---- Android JNI library ----------------------------------------------------------------------------
if(ANDROID)
    add_library(abcd_jni SHARED jni/abcd_jni.cpp)
    set_target_properties(abcd_jni PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
    target_link_libraries(abcd_jni PRIVATE abcd_analysis)
    # Only the JNI entry points are exported; Essentia stays internal.
    target_compile_options(abcd_jni PRIVATE -fvisibility=hidden)
    target_link_options(abcd_jni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
endif()

# ---- Development tools ----------------------------------------------------------------------------------
if(ABCD_BUILD_TOOLS)
    add_executable(abcd_analyze_cli dev_tools/analyze_cli.cpp)
    set_target_properties(abcd_analyze_cli PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
    target_link_libraries(abcd_analyze_cli PRIVATE abcd_analysis)
endif()

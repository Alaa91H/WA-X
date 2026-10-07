cmake_minimum_required(VERSION 3.22.1)
project(waenhancer)

# Ogg
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build shared libs" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "Build testing" FORCE)
set(INSTALL_DOCS OFF CACHE BOOL "Install docs" FORCE)
set(INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "Install pkgconfig" FORCE)
set(INSTALL_CMAKE_PACKAGE_MODULE OFF CACHE BOOL "Install cmake pkg" FORCE)
add_subdirectory(ogg)

# Opus
set(OPUS_BUILD_TESTS OFF CACHE BOOL "Build tests" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "Build programs" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "Install pkgconfig" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "Install cmake pkg" FORCE)

# The opus submodule is pinned by gitlink, not by tag. Upstream derives its version by
# running `git describe --tags` inside the submodule, which finds nothing in a fresh or
# shallow checkout - the same shape CI checks out - so the build prints "fatal: No names
# found", warns "Could not get package version" and falls back to version 0. The probe is
# skipped and the release is declared instead, so the version is deterministic and the
# warning has nothing left to report.
#
# Disabling the Git package finder is what makes the declared value stick: upstream's
# helper assigns the empty `git describe` result to a *function-local* variable of the same
# name, so a cache entry set from outside is shadowed rather than honoured. Nothing else in
# this build asks CMake for Git.
#
# 1.6.1 is not a guess: `git ls-remote --tags https://github.com/xiph/opus` resolves the
# recorded gitlink 22244de5a79bd1d6d623c32e72bf1954b56235be as refs/tags/v1.6.1^{}. Bump
# this line together with the gitlink, never on its own.
set(CMAKE_DISABLE_FIND_PACKAGE_Git ON)
set(OPUS_PACKAGE_VERSION "1.6.1" CACHE STRING "Pinned opus release" FORCE)
add_subdirectory(opus)

# Opus intentionally runs unoptimized in Android debug builds. Upstream emits a
# compiler pragma warning for that configuration unless this acknowledgement is
# present; make the intent explicit instead of silencing warnings globally.
target_compile_definitions(opus PRIVATE $<$<CONFIG:Debug>:OPUS_WILL_BE_SLOW>)

# libopusenc
set(OPUSENC_SRC
    libopusenc/src/opusenc.c
    libopusenc/src/picture.c
    libopusenc/src/ogg_packer.c
    libopusenc/src/opus_header.c
    libopusenc/src/resample.c
    libopusenc/src/random.c
    libopusenc/src/unicode_support.c
)

add_library(opusenc STATIC ${OPUSENC_SRC})
target_include_directories(opusenc PUBLIC libopusenc/include)
target_include_directories(opusenc PRIVATE libopusenc/src)
target_compile_definitions(opusenc PRIVATE RANDOM_PREFIX=libopusenc OUTSIDE_SPEEX PACKAGE_NAME="\\"libopusenc\\"" PACKAGE_VERSION="\\"0.2.1\\"")
# link libopus and libogg
target_link_libraries(opusenc PUBLIC opus ogg)

# Our App's Native Library
add_library(
    audio_opus_converter
    SHARED
    AudioOpusConverter.cpp
)

target_compile_options(
    audio_opus_converter
    PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -Wconversion
    -Wshadow
    -Werror
)

target_link_libraries(
    audio_opus_converter
    opusenc
    log
)

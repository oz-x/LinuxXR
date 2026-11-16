cmake_minimum_required(VERSION 3.22)
project(linuxxr_xr C CXX)

find_package(OpenXR REQUIRED CONFIG)

add_library(linuxxr_xr SHARED xr_main.cpp)
target_compile_features(linuxxr_xr PRIVATE cxx_std_17)
target_compile_options(linuxxr_xr PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(linuxxr_xr PRIVATE OpenXR::openxr_loader OpenXR::headers EGL GLESv3 android log)

# --- offline dictation: whisper.cpp ------------------------------------------------------------
# Always optimised, even in debug APKs: an -O0 whisper is unusably slow.
set(CMAKE_C_FLAGS_DEBUG "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG")
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# Snapdragon XR2 Gen 2 (Quest 3): ARMv8.2 with dot-product and fp16 arithmetic.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
add_subdirectory(${WHISPER_SRC} whisper EXCLUDE_FROM_ALL)

add_library(linuxxr_whisper SHARED whisper_jni.cpp)
target_compile_features(linuxxr_whisper PRIVATE cxx_std_17)
target_link_libraries(linuxxr_whisper PRIVATE whisper log)

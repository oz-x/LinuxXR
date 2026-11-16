cmake_minimum_required(VERSION 3.22)
project(linuxxr_xr CXX)

find_package(OpenXR REQUIRED CONFIG)

add_library(linuxxr_xr SHARED xr_main.cpp)
target_compile_features(linuxxr_xr PRIVATE cxx_std_17)
target_compile_options(linuxxr_xr PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(linuxxr_xr PRIVATE OpenXR::openxr_loader OpenXR::headers EGL GLESv3 android log)

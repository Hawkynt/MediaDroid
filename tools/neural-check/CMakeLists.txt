# The desktop check of the app's neural networks (app/src/main/cpp/networks.cpp) against ncnn's Linux release: see
# README.md.
cmake_minimum_required(VERSION 3.22)
project(neural_check CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

set(ncnn_DIR "${NCNN_ROOT}/lib/cmake/ncnn")
find_package(ncnn REQUIRED)

set(APP_CPP "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp")
add_executable(neural_check check.cpp
    "${APP_CPP}/networks.cpp" "${APP_CPP}/shaders.cpp" "${APP_CPP}/warp_layer.cpp" "${APP_CPP}/dain_layers.cpp"
    "${APP_CPP}/xvfi.cpp" "${APP_CPP}/enlarger.cpp")
target_include_directories(neural_check PRIVATE "${APP_CPP}" "${NCNN_ROOT}/include/ncnn")
find_package(OpenMP REQUIRED)
target_link_libraries(neural_check ncnn OpenMP::OpenMP_CXX)

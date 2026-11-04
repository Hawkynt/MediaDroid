# The app's native part: neural networks on ncnn (Tencent, BSD-3-Clause, see app/THIRD-PARTY-NOTICE.ncnn.txt), its
# prebuilt Android release unpacked by the ncnnPrebuilt Gradle task into NCNN_ROOT.
cmake_minimum_required(VERSION 3.22)
project(mediadroid_neural CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ncnn_DIR "${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn")
find_package(ncnn REQUIRED)

add_library(mediadroid_neural SHARED
    interpolator.cpp networks.cpp shaders.cpp warp_layer.cpp dain_layers.cpp xvfi.cpp enlarger.cpp)
target_compile_options(mediadroid_neural PRIVATE -fopenmp)
target_link_libraries(mediadroid_neural ncnn android -fopenmp -static-openmp)
# 16 KB pages, as Android 15 devices may use.
target_link_options(mediadroid_neural PRIVATE "-Wl,-z,max-page-size=16384")

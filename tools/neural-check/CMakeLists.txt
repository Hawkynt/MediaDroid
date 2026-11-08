# The desktop check of the app's neural networks (native/src) against ncnn's Linux release: see README.md. It links the
# native tree's networks (native/CMakeLists.txt, without the JNI library).
cmake_minimum_required(VERSION 3.22)
project(neural_check CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

set(MEDIADROYD_JNI OFF CACHE BOOL "" FORCE)
add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../../native" native)

add_executable(neural_check check.cpp)
target_link_libraries(neural_check mediadroyd_networks)

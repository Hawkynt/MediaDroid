# The desktop check of the app's upscaling shaders (app/src/main/glsl) against third-party oracles: see README.md.
cmake_minimum_required(VERSION 3.22)
project(shader_check CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

find_package(PkgConfig REQUIRED)
pkg_check_modules(GL REQUIRED egl glesv2)

add_executable(shader_check check.cpp)
target_compile_definitions(shader_check PRIVATE GLSL_DIR="${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/glsl")
target_include_directories(shader_check PRIVATE ${GL_INCLUDE_DIRS})
target_link_libraries(shader_check ${GL_LIBRARIES})

# The desktop check of the app's upscaling shaders (stage/src/main/glsl) against third-party oracles: see README.md.
# ORACLE_DIR holds the originals oracle.sh fetches (AMD's ffx_a.h and ffx_fsr1.h, NVIDIA's NIS_Config.h …).
cmake_minimum_required(VERSION 3.22)
project(shader_check CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()
if(NOT ORACLE_DIR)
    message(FATAL_ERROR "ORACLE_DIR: the folder oracle.sh fetches the originals into")
endif()

find_package(PkgConfig REQUIRED)
pkg_check_modules(GLES REQUIRED egl glesv2)
pkg_check_modules(GL REQUIRED egl opengl)

add_executable(shader_check check.cpp)
target_compile_definitions(shader_check PRIVATE GLSL_DIR="${CMAKE_CURRENT_SOURCE_DIR}/../../stage/src/main/glsl")
target_include_directories(shader_check PRIVATE ${GLES_INCLUDE_DIRS} "${ORACLE_DIR}")
target_link_libraries(shader_check ${GLES_LIBRARIES})

add_executable(shader_oracle oracle.cpp)
target_include_directories(shader_oracle PRIVATE ${GL_INCLUDE_DIRS} "${ORACLE_DIR}")
target_link_libraries(shader_oracle ${GL_LIBRARIES})

# MediaDroyd's native code: neural networks on ncnn (Tencent, BSD-3-Clause, see app/THIRD-PARTY-NOTICE.ncnn.txt), styled
# subtitles on libass (ISC, see app/THIRD-PARTY-NOTICE.libass.txt; ass/) and their JNI surface, one tree for every system
# the app runs on.
#  - Android (each ABI, through the app's externalNativeBuild): libmediadroyd_neural.so against ncnn's prebuilt
#    Android release, NCNN_ROOT holding a folder per ABI.
#  - Windows x64 and Linux x64 (native/build.gradle.kts, desktopLibrary): mediadroyd.dll / libmediadroyd.so against
#    ncnn's prebuilt Windows or Ubuntu release, NCNN_ROOT holding lib/cmake/ncnn.
#  - Linux arm64, which ncnn publishes no release for: the same library against ncnn built here from its digest-checked
#    full source, NCNN_SOURCE.
# src/ is the networks without any JNI or system (tools/neural-check links it alone, MEDIADROYD_JNI off); ass/ libass and
# what it needs, built from the releases in ASS_SOURCES (native/build.gradle.kts, assSources); jni/ the surface the JVM
# calls, with what differs per system behind jni/platform.h.
cmake_minimum_required(VERSION 3.22)
project(mediadroyd C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release)
endif()

option(MEDIADROYD_JNI "The JNI library the app loads; off, only the networks (for tools/neural-check)" ON)
set(MEDIADROYD_OUTPUT "" CACHE PATH "Where the desktop library is written (the build folder where empty)")

if(NCNN_SOURCE)
    # ncnn as its own release builds it (static, Vulkan through its own loader, glslang inside), without its tools.
    set(CMAKE_POSITION_INDEPENDENT_CODE ON)
    set(NCNN_SHARED_LIB OFF CACHE BOOL "" FORCE)
    set(NCNN_VULKAN ON CACHE BOOL "" FORCE)
    set(NCNN_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(NCNN_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(NCNN_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
    set(NCNN_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(NCNN_INSTALL_SDK OFF CACHE BOOL "" FORCE)
    add_subdirectory("${NCNN_SOURCE}" ncnn EXCLUDE_FROM_ALL)
elseif(ANDROID)
    set(ncnn_DIR "${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn")
    find_package(ncnn REQUIRED)
else()
    set(ncnn_DIR "${NCNN_ROOT}/lib/cmake/ncnn")
    find_package(ncnn REQUIRED)
endif()

add_library(mediadroyd_networks STATIC
    src/networks.cpp src/shaders.cpp src/warp_layer.cpp src/dain_layers.cpp src/xvfi.cpp src/enlarger.cpp
    src/flavr.cpp src/pair_networks.cpp src/colorizer.cpp)
set_target_properties(mediadroyd_networks PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(mediadroyd_networks PUBLIC src)
target_link_libraries(mediadroyd_networks PUBLIC ncnn)
if(ANDROID)
    target_compile_options(mediadroyd_networks PRIVATE -fopenmp)
endif()

if(NOT MEDIADROYD_JNI)
    return()
endif()

set(ASS_SOURCES "" CACHE PATH "libass, FreeType, FriBidi and HarfBuzz unpacked, a folder each (native/build.gradle.kts)")
if(NOT ASS_SOURCES)
    message(FATAL_ERROR "ASS_SOURCES is not set: the releases :native:assSources unpacks")
endif()
add_subdirectory(ass)

if(ANDROID)
    # The name the app loads (NeuralInterpolator.kt).
    add_library(mediadroyd_neural SHARED jni/interpolator.cpp jni/platform_android.cpp jni/subtitles.cpp)
    target_link_libraries(mediadroyd_neural mediadroyd_networks mediadroyd_subtitles android -fopenmp -static-openmp)
    # 16 KB pages, as Android 15 devices may use.
    target_link_options(mediadroyd_neural PRIVATE "-Wl,-z,max-page-size=16384")
else()
    find_package(JNI REQUIRED)
    add_library(mediadroyd SHARED jni/interpolator.cpp jni/platform_desktop.cpp jni/desktop.cpp jni/subtitles.cpp)
    target_include_directories(mediadroyd PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(mediadroyd PRIVATE mediadroyd_networks mediadroyd_subtitles)
    # Only the JNI entries (JNIEXPORT) are exported.
    set_target_properties(mediadroyd mediadroyd_networks PROPERTIES
        CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
    if(CMAKE_CXX_COMPILER_ID STREQUAL "GNU" OR CMAKE_CXX_COMPILER_ID STREQUAL "Clang")
        # C++'s own runtime inside, ncnn's symbols kept to the library, nothing left unresolved until it loads.
        target_link_options(mediadroyd PRIVATE -static-libstdc++ -static-libgcc "-Wl,--exclude-libs,ALL"
            "-Wl,--no-undefined")
    endif()
    if(MEDIADROYD_OUTPUT)
        # $<1:…> keeps multi-configuration generators (Visual Studio) from adding a Release/ folder.
        set_target_properties(mediadroyd PROPERTIES
            LIBRARY_OUTPUT_DIRECTORY "$<1:${MEDIADROYD_OUTPUT}>"
            RUNTIME_OUTPUT_DIRECTORY "$<1:${MEDIADROYD_OUTPUT}>")
    endif()
endif()

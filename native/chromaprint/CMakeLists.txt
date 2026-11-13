# Chromaprint (MIT; its resampler avresample/resample2.c from FFmpeg, LGPL-2.1) as a static library inside MediaDroyd's
# own: the audio fingerprints intros and credits are found by (#480). CHROMAPRINT_SOURCES holds its source tree at the
# revision native/build.gradle.kts (chromaprintSource) fetches and checks; its notice is
# app/THIRD-PARTY-NOTICE.Chromaprint.txt.
#
# The files and settings are those of Chromaprint's own src/CMakeLists.txt with its bundled KissFFT and its internal
# resampler, the configuration fpcalc's releases use without FFmpeg: nothing beyond the C and C++ runtime.

set(CHROMAPRINT "${CHROMAPRINT_SOURCES}/src")
set(CHROMAPRINT_CONFIG "${CMAKE_CURRENT_BINARY_DIR}/config")
file(WRITE "${CHROMAPRINT_CONFIG}/config.h"
    "#define HAVE_ROUND 1\n#define HAVE_LRINTF 1\n#define USE_INTERNAL_AVRESAMPLE 1\n#define USE_KISSFFT 1\n")

add_library(mediadroyd_chromaprint STATIC
    ${CHROMAPRINT}/chromaprint.cpp
    ${CHROMAPRINT}/audio_processor.cpp
    ${CHROMAPRINT}/chroma.cpp
    ${CHROMAPRINT}/chroma_resampler.cpp
    ${CHROMAPRINT}/chroma_filter.cpp
    ${CHROMAPRINT}/spectrum.cpp
    ${CHROMAPRINT}/fft.cpp
    ${CHROMAPRINT}/fft_lib_kissfft.cpp
    ${CHROMAPRINT}/fingerprinter.cpp
    ${CHROMAPRINT}/image_builder.cpp
    ${CHROMAPRINT}/simhash.cpp
    ${CHROMAPRINT}/silence_remover.cpp
    ${CHROMAPRINT}/fingerprint_calculator.cpp
    ${CHROMAPRINT}/fingerprint_compressor.cpp
    ${CHROMAPRINT}/fingerprint_decompressor.cpp
    ${CHROMAPRINT}/fingerprinter_configuration.cpp
    ${CHROMAPRINT}/fingerprint_matcher.cpp
    ${CHROMAPRINT}/utils/base64.cpp
    ${CHROMAPRINT}/avresample/resample2.c
    ${CHROMAPRINT}/3rdparty/kissfft/kiss_fft.c
    ${CHROMAPRINT}/3rdparty/kissfft/kiss_fftr.c)
set_target_properties(mediadroyd_chromaprint PROPERTIES
    POSITION_INDEPENDENT_CODE ON C_VISIBILITY_PRESET hidden CXX_VISIBILITY_PRESET hidden)
target_include_directories(mediadroyd_chromaprint
    PRIVATE "${CHROMAPRINT_CONFIG}" "${CHROMAPRINT}/3rdparty/kissfft" "${CHROMAPRINT}/3rdparty/kissfft/tools"
    PUBLIC "${CHROMAPRINT}")
target_compile_definitions(mediadroyd_chromaprint
    PRIVATE HAVE_CONFIG_H _USE_MATH_DEFINES __STDC_LIMIT_MACROS __STDC_CONSTANT_MACROS
    PUBLIC CHROMAPRINT_NODLL)

#!/bin/sh
# Makes expected.txt.gz: NVIDIA's own NIS_Scaler.h (SDK v1.0.3, MIT), run on the processor by harness.cpp,
# enlarging in.gray (48 x 40 grey) to 80 x 66 at sharpness 0.5. The header is fetched, not kept here; its
# shader-language blocks are cut out and `.xyz` becomes a call, so plain C++ compiles it.
set -e
base=https://raw.githubusercontent.com/NVIDIAGameWorks/NVIDIAImageScaling/v1.0.3/NIS
curl -sfL -o NIS_Config.h $base/NIS_Config.h
curl -sfL $base/NIS_Scaler.h | sed -e '131,145d' -e 's/^#if NIS_HLSL$/#if 0/' -e 's/^#if NIS_GLSL$/#if 0/' \
  -e 's/\.xyz)/.xyz())/g' > NIS_Scaler_cpu.h
g++ -std=c++17 -O1 -w -o harness harness.cpp
./harness 48 40 80 66 0.5 < in.gray | gzip -n > expected.txt.gz

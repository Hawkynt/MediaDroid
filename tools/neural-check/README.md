# Neural check on the desktop

The app's neural frame interpolation (`app/src/main/cpp/networks.cpp` and its layers) built for Linux against
ncnn's Linux release, so a network can be checked without a phone: the self-test's frames (two of the same
non-repeating texture, moved 10 × 6 pixels apart, as `NeuralInterpolator.kt` makes them) go through RIFE and DAIN on
the CPU and, where there is one, the Vulkan GPU; it prints how far the in-between is from the frame truly halfway
(and, for scale, how far a plain blend of the two is), how long it took and the memory the process peaked at.

1. ncnn's Linux release, `ncnn-20261020-ubuntu-2404.zip` from <https://github.com/Tencent/ncnn/releases>
   (sha256 `d3b2dcc98f454424911c2bfe3589c0431f07591b7b2d193f18911be6b1ffc28c`), unpacked.
2. DAIN's files from <https://huggingface.co/Hawkynt/mediadroid-models/tree/main/dain> in a folder.
3. `cmake -S tools/neural-check -B build/neural-check -G Ninja -DNCNN_ROOT=<ncnn-20261020-ubuntu-2404>` and
   `cmake --build build/neural-check`.
4. `build/neural-check/neural_check app/src/main/glsl/ncnn app/src/main/assets/models/rife-4.25-lite <dain folder> 256`
   — further sizes after the first; `NEURAL_CHECK_UNIT=CPU` or `GPU` runs only that unit.

On an Intel HD Graphics 520 laptop (Core i5, 2016) at 256 × 256 — a plain blend is off by 29.68 per value:

| Network | Unit | Off by | Time | Peak |
|---------|------|--------|------|------|
| RIFE 4.25 lite | CPU | 1.83 | 233 ms | 200 MB |
| RIFE 4.25 lite | GPU | 1.83 | 159 ms | |
| DAIN | CPU | 0.81 | 98 s | 1.1 GB |
| DAIN | GPU | 0.82 | 22 s | |

It found what the phone could not show: DAIN's correlation reading packed features as unpacked, its result
downloaded from the GPU as raw half floats, and ncnn's Winograd and im2col convolutions taking gigabytes on the CPU.

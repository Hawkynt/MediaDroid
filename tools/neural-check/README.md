# Neural check on the desktop

The app's neural frame interpolation (`app/src/main/cpp/networks.cpp` and its layers) built for Linux against
ncnn's Linux release, so a network can be checked without a phone: the self-test's frames (two of the same
non-repeating texture, moved 10 × 6 pixels apart, as `NeuralInterpolator.kt` makes them) go through RIFE, DAIN and XVFI on
the CPU and, where there is one, the Vulkan GPU; it prints how far the in-between is from the frame truly halfway
(and, for scale, how far a plain blend of the two is), how long it took and the memory the process peaked at.

1. ncnn's Linux release, `ncnn-20261020-ubuntu-2404.zip` from <https://github.com/Tencent/ncnn/releases>
   (sha256 `d3b2dcc98f454424911c2bfe3589c0431f07591b7b2d193f18911be6b1ffc28c`), unpacked.
2. DAIN's files from <https://huggingface.co/Hawkynt/mediadroid-models/tree/main/dain> in a folder.
3. XVFI's files from <https://huggingface.co/Hawkynt/mediadroid-models/tree/main/xvfi> in another (see
   `tools/xvfi`).
4. `cmake -S tools/neural-check -B build/neural-check -G Ninja -DNCNN_ROOT=<ncnn-20261020-ubuntu-2404>` and
   `cmake --build build/neural-check`.
5. `build/neural-check/neural_check app/src/main/glsl/ncnn app/src/main/assets/models/rife-4.25-lite <dain folder>
   <xvfi folder> 256` — further sizes after the first; `NEURAL_CHECK_UNIT=CPU` or `GPU` runs only that unit,
   `NEURAL_CHECK_NETWORK=RIFE`, `DAIN` or `XVFI` only that network. After each check, the next pair of a stream with
   its earlier frame's work kept must agree to the byte with the same pair worked out afresh.

On an Intel HD Graphics 520 laptop (Core i5, 2016) at 256 × 256 — a plain blend is off by 29.68 per value:

| Network | Unit | Off by | Time | Peak |
|---------|------|--------|------|------|
| RIFE 4.25 lite | CPU | 1.83 | 233 ms | 200 MB |
| RIFE 4.25 lite | GPU | 1.83 | 159 ms | |
| DAIN | CPU | 0.81 | 98 s | 1.1 GB |
| DAIN | GPU | 0.82 | 22 s | |
| XVFI | CPU | 1.27 | 1.1 s | 0.8 GB |
| XVFI | GPU | 1.27 | 1.5 s | |

The upscalers (with `WAIFU2X_DIR` and `REALCUGAN_DIR` set to `app/src/main/assets/models/waifu2x-cunet` and
`realcugan-se`) enlarge a drawing — discs of flat colour — shrunk to half: at 128 × 128, waifu2x is off from the
original by 2.42 per value and Real-CUGAN by 8.85 (it sharpens hard edges with halos), pixels merely doubled by
3.08; shrunk back, their outputs are off from their inputs by 1.28 and 6.47, the app's check allowing 10.
`NEURAL_CHECK_DUMP=<folder>` writes the pictures.

It found what the phone could not show: DAIN's correlation reading packed features as unpacked, its result
downloaded from the GPU as raw half floats, and ncnn's Winograd and im2col convolutions taking gigabytes on the CPU.

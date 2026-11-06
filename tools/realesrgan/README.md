# Real-ESRGAN's general model on ncnn, against PyTorch

The desktop's general Real-ESRGAN (`NeuralModels.REALESRGAN_X4PLUS`) is RealESRGAN_x4plus (RRDBNet, BSD-3-Clause) as
Real-ESRGAN-ncnn-vulkan (MIT) converted it: `models/realesrgan-x4plus.param` and `.bin` from
`realesrgan-ncnn-vulkan-20220424-ubuntu.zip` (<https://github.com/xinntao/Real-ESRGAN/releases/tag/v0.2.5.0>, sha256
`e5aa6eb131234b87c0c51f82b89390f5e3e642b7b70f2b9bbe95b6a285a40c96`), the same release the carried video model comes
from. The app fetches that zip when the model is picked and keeps only the two files, each checked against its digest.

How it was checked, and how `engine/desktop/src/test/resources/enlargers/realesrgan-x4plus.png` (what
`NativeNeuralTest` holds the app's enlarger to) was made:

1. Python with `torch` (CPU), `numpy` and `pillow`.
2. `RealESRGAN_x4plus.pth` from <https://github.com/xinntao/Real-ESRGAN/releases/tag/v0.1.0> (sha256
   `4fa0d38905f75ac06eb49a7951b426670021be3018265fd191d2125df9d682f1`).
3. `python reference.py RealESRGAN_x4plus.pth <out> <picture.png>...` — `rrdbnet.py` is BasicSR's RRDBNet (Apache-2.0,
   converted). It writes each picture as Real-ESRGAN's own inference runs it (mirrored by 10 at the right and bottom)
   and as the ncnn port runs it (mirrored by 10 on every side); the second is the like-for-like reference.

Per value 0..255, the app's enlarger (`native/src/enlarger.cpp`, ncnn on the CPU) against PyTorch:

| Picture | Same padding: mean / at most | Real-ESRGAN's own padding: mean / at most |
|---------|------------------------------|-------------------------------------------|
| `input.png` of the enlargers' tests, 42 × 38 | 0.14 / 1 | 1.51 / 59 (the edges) |
| the port's `input.jpg`, 220 × 220 | 0.15 / 2 | 0.49 / 216 (the edges) |
| the port's `input2.jpg`, 256 × 256 | 0.16 / 3 | 0.72 / 157 (the edges) |

With Real-ESRGAN's own padding the differences sit near the edges and fade inwards: RRDBNet sees far more than the ten
mirrored pixels, so where the two put different pixels beyond the picture the rows and columns near it come out
otherwise.

The issue asked for a conversion of our own with pnnx; upstream ships one, so it is taken (sourcing ladder, rung 1). A
pnnx conversion of the same weights (`pnnx.export`, torch 2.15) was made to compare: 0.02 / 1 from PyTorch, and
0.14 / 1 from upstream's — the two conversions agree, and upstream's needs no hosting of our own.

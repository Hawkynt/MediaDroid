# The ncnn upscalers against their references

How `engine/desktop/src/test/resources/enlargers` — the pictures `NativeNeuralTest` holds every waifu2x and Real-CUGAN
model of `NeuralEnlarging.models` against — are made, and what they showed.

`input.png` is a 42 × 38 drawing (discs of flat colour with round edges over a gradient, fine noise in one corner),
sizes no network divides evenly, so the padding to an even size (to a multiple of four at Real-CUGAN's 3x) is in play.

1. Python with `torch` (CPU), `numpy` and `pillow`.
2. waifu2x's CUnet weights as nunif carries them, converted from the original waifu2x models (MIT):
   `waifu2x_pretrained_models_20221109.tar.gz` from <https://github.com/nagadomi/nunif/releases/tag/0.0.0> (sha256
   `bf9feb2bf5c90b9c4b128a400e547be580850230988c8862c045b359e43e22eb`), its `pretrained_models/cunet/art`.
3. Real-CUGAN's SE weights (MIT): `updated_weights.zip` from
   <https://github.com/bilibili/ailab/releases/tag/Real-CUGAN> (sha256
   `99a8ce2e4a6ce0548668879f148b3db3e143201d3df42c825a4143490396da53`), unpacked.
4. `python reference.py input.png <out> <cunet/art folder> <updated_weights folder>` — `cunet.py` holds the networks,
   converted from Real-CUGAN's `upcunet_v3.py` and nunif's `waifu2x/models/cunet.py` (both MIT), their whole-picture
   path only; the edges extended as the app's ports extend them (waifu2x repeated, Real-CUGAN mirrored).

Real-CUGAN's pro weights are published only on Google Drive and Baidu, so the pro pictures
(`realcugan-pro-*.png`) were captured from realcugan-ncnn-vulkan itself on the CPU
(`realcugan-ncnn-vulkan-20220728-ubuntu.zip`, sha256
`d745174bd04c0232c89d935b74799311008fda06bea4195f61be5f0f3cc087cb`; `-g -1 -c 0`, and `-x` for the eight-way one).

What the app's enlarger (`native/src/enlarger.cpp`, ncnn on the CPU) made of `input.png`, per value 0..255:

| Models | Against | Mean off | At most |
|--------|---------|----------|---------|
| waifu2x, noise off and 0–3 | PyTorch | 0.12–0.15 | 5 |
| waifu2x noise 1, eight ways | PyTorch, eight ways | 0.11 | 3 |
| Real-CUGAN SE, all eleven | PyTorch | 0.01–0.06 | 1 |
| Real-CUGAN SE 2x conservative and 4x denoise 3, eight ways | PyTorch, eight ways | 0.02–0.03 | 1 |
| Real-CUGAN SE and pro, all seventeen, and eight ways | realcugan-ncnn-vulkan | 0.00 | 1 |
| Real-CUGAN 2x, 3x and 4x in tiles of 32 and 100 (230 × 170) | realcugan-ncnn-vulkan's own tiles (`-t`, `-c 0`) | 0.00 | 1 |

waifu2x's ncnn port leaves out the clamp to 0..1 between its two U-Nets that the original has, which is where its few
larger differences sit. Tiles cost a little at their seams — each tile's SE blocks average that tile alone, as in the
port without its sync-gap modes — so a picture is cut only where the graphics chip's memory asks for it
(`NeuralEnlarging.tile`, the ports' own sizes by heap budget). The test allows 0.5 on average and 8 at most.

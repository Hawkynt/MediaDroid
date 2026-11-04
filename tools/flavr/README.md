# FLAVR for ncnn

How the files in `flavr/` of [Hawkynt/mediadroid-models](https://huggingface.co/Hawkynt/mediadroid-models) — which the
app fetches when FLAVR is picked — are made from FLAVR's 2x model ([tarun005/FLAVR](https://github.com/tarun005/FLAVR),
Apache-2.0; see `app/THIRD-PARTY-NOTICE.FLAVR.txt`).

1. Python with `torch` (CPU) and `numpy<2`.
2. `FLAVR_2x.pth` from the FLAVR README's 2x link (sha256
   `45509c279355866b46021b112301c4d1862e13569b4e0d58d826171d94695412`). FLAVR's own Python files are not run:
   `flavr_net.py` writes the network out from its published architecture (UNet_3D_3D, unet_18 encoder, no batch norm,
   transposed convolutions, concatenation), and the weights load as tensors only.
3. `python export.py FLAVR_2x.pth <out dir>` — `body` (the 3D part: Convolution3D, Deconvolution3D, SE gating as a
   global Pooling3D, an InnerProduct with a sigmoid and a per-channel multiplication) and `head` (the 2D part), weights
   as half floats.

`app/src/main/cpp/flavr.cpp` joins them as `flavr_net.py`'s forward does: the four frames' mean taken away, the four
times of the body's output folded into channels for the head, the mean put back. Checked with `tools/neural-check`
(`FLAVR_DIR`): off by 5.23 per value from the frame truly halfway at 128 x 128, where the PyTorch network is off by
5.25 and a blend by 29.95.

ncnn's 3D layers run on the CPU only and without the optimised paths its 2D convolutions have: an in-between at
128 x 128 takes 46 s on a desktop CPU, where PyTorch takes 2.2 s. FLAVR is right here, but no device keeps up with it
this way; written out as 2D convolutions over the four times, sharing their weights, it would run on ncnn's fast 2D
paths and its GPU.

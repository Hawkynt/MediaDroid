# FLAVR for ncnn

How the files in `flavr/` of [Hawkynt/mediadroid-models](https://huggingface.co/Hawkynt/mediadroid-models) — which the
app fetches when FLAVR is picked — are made from FLAVR's 2x model ([tarun005/FLAVR](https://github.com/tarun005/FLAVR),
Apache-2.0; see `app/THIRD-PARTY-NOTICE.FLAVR.txt`).

1. Python with `torch` (CPU) and `numpy<2`.
2. `FLAVR_2x.pth` from the FLAVR README's 2x link (sha256
   `45509c279355866b46021b112301c4d1862e13569b4e0d58d826171d94695412`). FLAVR's own Python files are not run:
   `flavr_net.py` writes the network out from its published architecture (UNet_3D_3D, unet_18 encoder, no batch norm,
   transposed convolutions, concatenation), and the weights load as tensors only.
3. `python export.py FLAVR_2x.pth <out dir>` — `body` (the 3D part) and `head` (the 2D part), weights as half floats.
   The 3D layers are written as 2D ones over the four frames: for a temporal kernel of 3 the frames before and after
   each are stacked into its channels (zeros beyond the ends), each frame gets zero rows below it so a 2D kernel cannot
   reach into the next, and the four frames, one below the other, go through one ordinary Convolution or
   Deconvolution whose input channels hold the kernel's three times — ncnn's fast 2D paths and its GPU, the weights
   stored once. SE gating is a global average over that tall picture (all frames the same size), an InnerProduct with
   a sigmoid and a per-channel multiplication.

`native/src/flavr.cpp` joins them as `flavr_net.py`'s forward does: the four frames' mean taken away, the four
times of the body's output folded into channels for the head, the mean put back. Checked with `tools/neural-check`
(`FLAVR_DIR`): off by 5.23 per value from the frame truly halfway at 128 x 128, where the PyTorch network is off by
5.25 and a blend by 29.95.

At 128 x 128 on a desktop (Intel HD Graphics 520 laptop) an in-between takes 1.5 s on the CPU and 1.8 s on the GPU —
written with ncnn's own 3D layers, which have no optimised or GPU path, it took 46 s; PyTorch takes 2.2 s.

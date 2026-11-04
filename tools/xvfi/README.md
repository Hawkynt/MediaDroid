# XVFI for ncnn

How the files in `xvfi/` of [Hawkynt/mediadroid-models](https://huggingface.co/Hawkynt/mediadroid-models) — which the
app fetches when XVFI is switched on — are made from XVFI-Net's Vimeo-trained weights
([JihyongOh/XVFI](https://github.com/JihyongOh/XVFI); research and education only, used with the authors'
permission: see `app/THIRD-PARTY-NOTICE.XVFI.txt`).

1. Python with `torch` (CPU) and `numpy<2`.
2. `XVFInet_Vimeo_exp1_latest.pt` from the XVFI README's Vimeo90K link (sha256
   `02af209e45f2cf9cb967b436485f42337347f258f1ebdae823e8e62836736793`). XVFI's own Python files are not run:
   `xvfi_net.py` writes the network out from its published architecture (test path only, module scale 2, S_tst = 1),
   and the weights load as tensors only (`weights_only`, numpy scalars allowed for the checkpoint's own numbers).
3. `python export.py XVFInet_Vimeo_exp1_latest.pt <out dir>` — the seven convolution blocks as ncnn `.param` and
   `.bin` (weights as half floats; ReLUs fused into their convolutions; splits where a blob is read twice).

`app/src/main/cpp/xvfi.cpp` joins the blocks as `xvfi_net.py`'s forward does. Both give the same in-between on the
self-test's frames — off by 1.27 per value from the frame truly halfway, where a blend is off by 29.68 — checked with
`tools/neural-check`.

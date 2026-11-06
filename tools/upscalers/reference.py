"""What waifu2x's CUnet and Real-CUGAN make of a picture in PyTorch on the CPU: the expected pictures the app's ncnn
upscalers are checked against (engine/desktop/src/test/resources/enlargers, NativeNeuralTest).

    python reference.py <input.png> <out dir> <nunif cunet/art folder> <Real-CUGAN updated_weights folder>

Writes waifu2x-<model>.png for each noise level, realcugan-se-<model>.png for each SE model, and the same run eight ways
(mirrored and turned, each turned back, averaged) as <name>+tta.png for the models listed in TTA.
"""
import sys
from pathlib import Path

import numpy as np
import torch
from PIL import Image

from cunet import load

WAIFU2X = {
    "scale2.0x_model": "scale2x.pth",
    "noise0_scale2.0x_model": "noise0_scale2x.pth",
    "noise1_scale2.0x_model": "noise1_scale2x.pth",
    "noise2_scale2.0x_model": "noise2_scale2x.pth",
    "noise3_scale2.0x_model": "noise3_scale2x.pth",
}
REALCUGAN = [
    "up2x-conservative", "up2x-no-denoise", "up2x-denoise1x", "up2x-denoise2x", "up2x-denoise3x",
    "up3x-conservative", "up3x-no-denoise", "up3x-denoise3x",
    "up4x-conservative", "up4x-no-denoise", "up4x-denoise3x",
]
TTA = {"waifu2x-noise1_scale2.0x_model", "realcugan-se-up2x-conservative", "realcugan-se-up4x-denoise3x"}


def turn(x, way):
    if way & 1:
        x = torch.flip(x, [2])
    if way & 2:
        x = torch.flip(x, [3])
    if way & 4:
        x = x.transpose(2, 3)
    return x


def unturn(x, way):
    if way & 4:
        x = x.transpose(2, 3)
    if way & 2:
        x = torch.flip(x, [3])
    if way & 1:
        x = torch.flip(x, [2])
    return x


def run(net, picture, padding_mode, tta):
    with torch.no_grad():
        if not tta:
            z = net(picture, padding_mode)
        else:
            z = sum(unturn(net(turn(picture, way), padding_mode), way) for way in range(8)) / 8
    out = (z[0].permute(1, 2, 0).numpy() * 255).round().clip(0, 255).astype(np.uint8)
    return Image.fromarray(out)


def main():
    source, out, waifu2x, cugan = (Path(a) for a in sys.argv[1:5])
    out.mkdir(parents=True, exist_ok=True)
    rgb = np.asarray(Image.open(source).convert("RGB"), dtype=np.float32) / 255
    picture = torch.from_numpy(rgb).permute(2, 0, 1)[None]
    jobs = [(f"waifu2x-{name}", waifu2x / pth, 2, True, "replicate") for name, pth in WAIFU2X.items()]
    jobs += [(f"realcugan-se-{name}", cugan / f"{name.replace('x-', 'x-latest-', 1)}.pth", int(name[2]), False,
              "reflect") for name in REALCUGAN]
    for name, weights, scale, clip_first, padding_mode in jobs:
        net = load(weights, scale, clip_first)
        run(net, picture, padding_mode, False).save(out / f"{name}.png")
        if name in TTA:
            run(net, picture, padding_mode, True).save(out / f"{name}+tta.png")
        print(name)


if __name__ == "__main__":
    main()

"""What Real-ESRGAN's general x4plus model makes of pictures in PyTorch on the CPU: the expected pictures the app's ncnn
Real-ESRGAN is checked against (engine/desktop/src/test/resources/enlargers, NativeNeuralTest).

    python reference.py <RealESRGAN_x4plus.pth> <out dir> <input.png>...

Each picture as Real-ESRGAN's own inference (RealESRGANer, no tiles) runs it — mirrored by 10 pixels at the right and
bottom, enlarged, the 40 added cut off again — as <name>-realesrgan-x4plus.png, and with the 10 pixels mirrored on every
side as the ncnn port adds them, as <name>-realesrgan-x4plus-ncnn.png.
"""
import sys
from pathlib import Path

import numpy as np
import torch
from PIL import Image
from torch.nn import functional as F

from rrdbnet import load


def enlarge(net, picture, every_side):
    _, _, h, w = picture.shape
    with torch.no_grad():
        if every_side:
            z = net(F.pad(picture, (10, 10, 10, 10), "reflect"))[:, :, 40:40 + 4 * h, 40:40 + 4 * w]
        else:
            z = net(F.pad(picture, (0, 10, 0, 10), "reflect"))[:, :, : 4 * h, : 4 * w]
    out = (z[0].clamp(0, 1).permute(1, 2, 0).numpy() * 255).round().astype(np.uint8)
    return Image.fromarray(out)


def main():
    weights, out = Path(sys.argv[1]), Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    net = load(weights)
    for source in map(Path, sys.argv[3:]):
        rgb = np.asarray(Image.open(source).convert("RGB"), dtype=np.float32) / 255
        picture = torch.from_numpy(rgb).permute(2, 0, 1)[None]
        enlarge(net, picture, False).save(out / f"{source.stem}-realesrgan-x4plus.png")
        enlarge(net, picture, True).save(out / f"{source.stem}-realesrgan-x4plus-ncnn.png")
        print(source.name)


if __name__ == "__main__":
    main()

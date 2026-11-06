"""What DDColor's paper-tiny model makes of the self-test's picture in PyTorch on the CPU, for the app's checks.

    python reference.py <DDColor source folder> <pytorch_model.bin> <core resources folder> <test resources folder>

Writes check.grey (the 256 x 256 grey picture the self-test colours, a byte a pixel, rows from the top) and check.ab
(the a and b it should get, averaged over 16 x 16 blocks, a value a line: the a blocks then the b blocks) for
NeuralChecks.colorizer, and check-ab.bin (the whole a and b at 256 x 256, rounded to signed bytes) for NativeNeuralTest.
"""
import sys
from pathlib import Path

import numpy as np
import torch

from export import build

SIZE = 256


def picture():
    """A grey scene: a sky lightening to the horizon, a darker ground, round shapes and a little texture."""
    y, x = np.mgrid[0:SIZE, 0:SIZE].astype(np.float64)
    v = np.where(y < 150, 200 - 60 * y / 150, 90 + 30 * np.sin(x / 9) * np.exp(-(y - 150) / 80))
    for cx, cy, r, level in [(70, 120, 30, 60), (180, 170, 40, 150), (200, 60, 18, 245)]:
        v = np.where((x - cx) ** 2 + (y - cy) ** 2 < r * r, level, v)
    v += 6 * np.sin(x * 1.7) * np.sin(y * 1.3)
    return np.clip(np.rint(v), 0, 255).astype(np.uint8)


def main():
    net = build(sys.argv[2])
    core, test = Path(sys.argv[3]), Path(sys.argv[4])
    grey = picture()
    planes = np.repeat((grey.astype(np.float32) / 255)[None], 3, axis=0)
    with torch.no_grad():
        ab = net(torch.from_numpy(planes)[None])[0].numpy()
    core.mkdir(parents=True, exist_ok=True)
    test.mkdir(parents=True, exist_ok=True)
    grey.tofile(core / "check.grey")
    blocks = ab.reshape(2, 16, 16, 16, 16).mean(axis=(2, 4))
    (core / "check.ab").write_text("".join(f"{v:.3f}\n" for v in blocks.reshape(-1)))
    np.clip(np.rint(ab), -128, 127).astype(np.int8).tofile(test / "check-ab.bin")
    print("a", ab[0].min(), ab[0].max(), "b", ab[1].min(), ab[1].max())


if __name__ == "__main__":
    main()

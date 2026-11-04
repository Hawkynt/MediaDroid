"""export.py <checkpoint> <out dir>: FLAVR's two parts (flavr_net.py) as ncnn networks — .param (structure, text) and
.bin (weights as half floats, biases as floats) — for app/src/main/cpp/flavr.cpp, which joins them:

  body  in0 (4D: 3 channels x 4 frames x H x W, their mean taken away) -> out (64 x 4 x H x W)
  head  in0 (256 x H x W: body's four times folded into channels, time major) -> out (3 x H x W, mean still away)

The 3D layers are written as 2D ones over the four frames (see Graph.conv3d), so ncnn runs them on its fast 2D paths
and its GPU, the weights stored once; SE gating is a global average, an InnerProduct with a sigmoid and a per-channel
multiplication. The weights load as tensors only.
"""
import os
import struct
import sys

import numpy as np
import torch
import torch.nn as nn

from flavr_net import BasicBlock, SEGating, load

FRAMES = 4


class Graph:
    """An ncnn network written layer by layer: a Split added where a blob feeds more than one layer."""

    def __init__(self):
        self.layers = []
        self.weights = bytearray()
        self.count = 0

    def blob(self):
        self.count += 1
        return f"b{self.count}"

    def layer(self, kind, inputs, params, outputs=1):
        outs = [self.blob() for _ in range(outputs)]
        self.layers.append([kind, f"{kind.lower()}{self.count}", list(inputs), outs, params])
        return outs[0] if outputs == 1 else outs

    def input(self):
        self.layers.append(["Input", "in0", [], ["in0"], "0=0"])
        return "in0"

    def half(self, w):
        self.weights += struct.pack("<I", 0x01306B47)
        data = w.detach().float().numpy().astype(np.float16).tobytes()
        self.weights += data + b"\0" * (-len(data) % 4)

    def floats(self, b):
        self.weights += b.detach().float().numpy().astype("<f4").tobytes()

    # Crop's sizes not limited (its default, 0, would leave nothing): only the offsets cut.
    WHOLE = "3=-233 4=-233 14=-233 5=-233"

    # The 3D layers as 2D ones. A blob is 4D — width, height, the four frames (ncnn's depth), channels. For a layer
    # with a temporal kernel of 3, the frames before and after each are stacked into its channels (zeros beyond the
    # first and the last), so the time part of the kernel becomes channels of a 2D one; each frame gets zero rows below
    # it, enough that the 2D kernel cannot reach from one frame into the next; the four frames are put one below the
    # other as one tall picture for an ordinary 2D layer — ncnn's fast paths, its GPU among them, the weights stored
    # once — and the result put back into frames, the rows added cut away.

    def frames(self, x, first, count):
        """Frames [first, first + count) of the 4D blob [x]."""
        return self.layer("Crop", [x], f"13={first} 15={FRAMES - first - count} {Graph.WHOLE}")

    def stacked(self, x, order):
        """[x] with the frame before and after each in its channels: (prev, cur, next) or (next, cur, prev)."""
        zero = self.layer("BinaryOp", [self.frames(x, 0, 1)], "0=2 1=1 2=0.000000e+00")
        prev = self.layer("Concat", [zero, self.frames(x, 0, FRAMES - 1)], "0=1")
        zero2 = self.layer("BinaryOp", [self.frames(x, 0, 1)], "0=2 1=1 2=0.000000e+00")
        nxt = self.layer("Concat", [self.frames(x, 1, FRAMES - 1), zero2], "0=1")
        parts = [prev, x, nxt] if order == "forward" else [nxt, x, prev]
        return self.layer("Concat", parts, "0=0")

    def tall(self, x, gap, channels):
        """The 4D blob [x] ([channels] deep) as one tall 2D picture, [gap] zero rows below each frame."""
        if gap:
            x = self.layer("Padding", [x], f"0=0 1={gap} 2=0 3=0 4=0")
        return self.layer("Reshape", [x], f"0=0 1=-1 2={channels}")

    def untall(self, x, gap, channels):
        """A tall 2D picture ([channels] deep) back into the four frames, the [gap] rows below each cut away."""
        y = self.layer("Reshape", [x], f"0=0 1=-1 11={FRAMES} 2={channels}")
        return self.layer("Crop", [y], f"7={gap} {Graph.WHOLE}") if gap else y

    @staticmethod
    def gap(pad, stride):
        """Rows enough below each frame for a kernel reaching [pad] rows, a multiple of [stride]."""
        g = pad
        while g % stride:
            g += 1
        return g

    def conv3d(self, x, conv, act=0):
        out_c, in_c, kd, kh, kw = conv.weight.shape
        sd, sh, sw = conv.stride
        pd, ph, pw = conv.padding
        assert sd == 1 and sh == sw and ph == pw and (kd, pd) in ((1, 0), (3, 1))
        bias = conv.bias is not None
        w = conv.weight
        if kd == 3:
            x = self.stacked(x, "forward")
            # Channel block k of the 2D kernel is time k of the 3D one: (out, 3, in, kh, kw).
            w = w.permute(0, 2, 1, 3, 4).reshape(out_c, 3 * in_c, kh, kw)
        else:
            w = w.reshape(out_c, in_c, kh, kw)
        gap = self.gap(ph, sh)
        p = f"0={out_c} 1={kw} 11={kh} 3={sw} 13={sh} 4={pw} 14={ph} 5={int(bias)} 6={w.numel()}"
        if act == 1:
            p += " 9=1"
        y = self.layer("Convolution", [self.tall(x, gap, w.shape[1])], p)
        self.half(w)
        if bias:
            self.floats(conv.bias)
        return self.untall(y, gap // sh, out_c)

    def deconv3d(self, x, conv):
        in_c, out_c, kd, kh, kw = conv.weight.shape
        sd, sh, sw = conv.stride
        pd, ph, pw = conv.padding
        assert (kd, sd, pd) == (3, 1, 1) and sh == sw and ph == pw
        # A transposed kernel takes frame t + 1 - k at time k: (next, cur, prev), channel block k of the 2D kernel.
        x = self.stacked(x, "backward")
        # ncnn takes a transposed kernel as out x in x kh x kw; PyTorch keeps it in x out x kernel.
        w = conv.weight.permute(1, 2, 0, 3, 4).reshape(out_c, 3 * in_c, kh, kw)
        gap = 1
        p = f"0={out_c} 1={kw} 11={kh} 3={sw} 13={sh} 4={pw} 14={ph} 5=1 6={w.numel()}"
        y = self.layer("Deconvolution", [self.tall(x, gap, 3 * in_c)], p)
        self.half(w)
        self.floats(conv.bias)
        return self.untall(y, gap * sh, out_c)

    def se(self, x, gating):
        conv = gating.attn_layer[0]
        c = conv.weight.shape[0]
        # All frames are the same size, so the average over the tall picture is the average over the frames.
        t = self.tall(x, 0, c)
        pooled = self.layer("Pooling", [t], "0=1 4=1")
        attention = self.layer("InnerProduct", [pooled], f"0={c} 1=1 2={c * c} 9=4")
        self.half(conv.weight.reshape(c, c))
        self.floats(conv.bias)
        return self.untall(self.layer("BinaryOp", [t, attention], "0=2"), 0, c)

    def relu(self, x, slope=0.0):
        return self.layer("ReLU", [x], f"0={slope:.6e}" if slope else "")

    def add(self, a, b):
        return self.layer("BinaryOp", [a, b], "0=0")

    def cat(self, *xs):
        return self.layer("Concat", list(xs), "0=0")

    def write(self, path, last):
        for layer in self.layers:
            layer[3] = ["out" if b == last else b for b in layer[3]]
        readers = {}
        for i, layer in enumerate(self.layers):
            for b in layer[2]:
                readers.setdefault(b, []).append(i)
        out = []
        for layer in self.layers:
            out.append(layer)
            for b in layer[3]:
                if len(readers.get(b, [])) > 1:
                    copies = [f"{b}_{k}" for k in range(len(readers[b]))]
                    out.append(["Split", f"split_{b}", [b], copies, ""])
                    for k, i in enumerate(readers[b]):
                        self.layers[i][2] = [copies[k] if x == b else x for x in self.layers[i][2]]
        blobs = {b for layer in out for b in layer[3]}
        with open(path + ".param", "w") as f:
            f.write("7767517\n")
            f.write(f"{len(out)} {len(blobs)}\n")
            for kind, name, ins, outs, params in out:
                f.write(f"{kind:<16} {name:<20} {len(ins)} {len(outs)} {' '.join(ins + outs)} {params}".rstrip() + "\n")
        with open(path + ".bin", "wb") as f:
            f.write(self.weights)


def block(g, x, b: BasicBlock):
    y = g.conv3d(x, b.conv1[0], act=1)
    y = g.se(g.conv3d(y, b.conv2[0]), b.fg)
    residual = g.conv3d(x, b.downsample[0]) if b.downsample is not None else x
    return g.relu(g.add(y, residual))


def main():
    net = load(sys.argv[1])
    out_dir = sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)

    g = Graph()
    x = g.input()
    e = net.encoder
    x0 = g.conv3d(x, e.stem[0], act=1)
    x1 = block(g, block(g, x0, e.layer1[0]), e.layer1[1])
    x2 = block(g, block(g, x1, e.layer2[0]), e.layer2[1])
    x3 = block(g, block(g, x2, e.layer3[0]), e.layer3[1])
    x4 = block(g, block(g, x3, e.layer4[0]), e.layer4[1])
    d = net.decoder
    y = g.cat(g.relu(g.se(g.conv3d(x4, d[0].conv[0]), d[0].conv[1]), 0.2), x3)
    y = g.cat(g.relu(g.se(g.deconv3d(y, d[1].upconv[0]), d[1].upconv[1]), 0.2), x2)
    y = g.cat(g.relu(g.se(g.deconv3d(y, d[2].upconv[0]), d[2].upconv[1]), 0.2), x1)
    y = g.cat(g.relu(g.se(g.conv3d(y, d[3].conv[0]), d[3].conv[1]), 0.2), x0)
    y = g.relu(g.se(g.deconv3d(y, d[4].upconv[0]), d[4].upconv[1]), 0.2)
    g.write(os.path.join(out_dir, "body"), y)

    h = Graph()
    x = h.input()
    fuse = net.feature_fuse.conv[0]
    y = h.layer("Convolution", [x], f"0=64 1=1 11=1 5=0 6={fuse.weight.numel()} 9=2 -23310=1,2.000000e-01")
    h.half(fuse.weight)
    y = h.layer("Padding", [y], "0=3 1=3 2=3 3=3 4=2")
    conv = net.outconv[1]
    y = h.layer("Convolution", [y], f"0=3 1=7 11=7 5=1 6={conv.weight.numel()}")
    h.half(conv.weight)
    h.floats(conv.bias)
    h.write(os.path.join(out_dir, "head"), y)


if __name__ == "__main__":
    main()

"""export.py <checkpoint> <out dir>: FLAVR's two parts (flavr_net.py) as ncnn networks — .param (structure, text) and
.bin (weights as half floats, biases as floats) — for app/src/main/cpp/flavr.cpp, which joins them:

  body  in0 (4D: 3 channels x 4 frames x H x W, their mean taken away) -> out (64 x 4 x H x W)
  head  in0 (256 x H x W: body's four times folded into channels, time major) -> out (3 x H x W, mean still away)

The 3D layers are ncnn's Convolution3D, Deconvolution3D and Pooling3D; SE gating is a global average, an InnerProduct
with a sigmoid and a per-channel multiplication. The weights load as tensors only.
"""
import os
import struct
import sys

import numpy as np
import torch
import torch.nn as nn

from flavr_net import BasicBlock, SEGating, load


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

    def conv3d(self, x, conv, act=0):
        out_c, in_c, kd, kh, kw = conv.weight.shape
        sd, sh, sw = conv.stride
        pd, ph, pw = conv.padding
        bias = conv.bias is not None
        p = (f"0={out_c} 1={kw} 11={kh} 21={kd} 3={sw} 13={sh} 23={sd} 4={pw} 14={ph} 24={pd} "
             f"5={int(bias)} 6={conv.weight.numel()}")
        if act == 1:
            p += " 9=1"
        y = self.layer("Convolution3D", [x], p)
        self.half(conv.weight)
        if bias:
            self.floats(conv.bias)
        return y

    def deconv3d(self, x, conv):
        in_c, out_c, kd, kh, kw = conv.weight.shape
        sd, sh, sw = conv.stride
        pd, ph, pw = conv.padding
        p = (f"0={out_c} 1={kw} 11={kh} 21={kd} 3={sw} 13={sh} 23={sd} 4={pw} 14={ph} 24={pd} "
             f"5=1 6={conv.weight.numel()}")
        y = self.layer("Deconvolution3D", [x], p)
        # ncnn takes a transposed convolution's weights as out x in x kernel; PyTorch keeps them in x out x kernel.
        self.half(conv.weight.permute(1, 0, 2, 3, 4).contiguous())
        self.floats(conv.bias)
        return y

    def se(self, x, gating):
        conv = gating.attn_layer[0]
        c = conv.weight.shape[0]
        pooled = self.layer("Pooling3D", [x], "0=1 4=1")
        attention = self.layer("InnerProduct", [pooled], f"0={c} 1=1 2={c * c} 9=4")
        self.half(conv.weight.reshape(c, c))
        self.floats(conv.bias)
        return self.layer("BinaryOp", [x, attention], "0=2")

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

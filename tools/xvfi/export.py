"""export.py <checkpoint> <out dir>: XVFI-Net's convolution blocks (xvfi_net.py) as ncnn networks — .param (structure,
text) and .bin (weights as half floats, biases as floats) — for app/src/main/cpp/xvfi.cpp, which joins them:

  extract   in0 (3) -> feat (nf at half size): a frame's features
  context   in0 (nf) -> out (nf at half size): the next coarser level's
  bottom    in0 (2nf) -> out (6): flow and its confidence at the coarsest level
  flow1     in0 (2nf) -> out (nf)
  flow2     in0 (2nf + 4) -> out (6): the flow at a finer level, from the coarser one
  flow3     in0 (4nf + 4) -> out (4): the refined flows from t
  refine    in0 (4nf / scale^2 + 16) -> out (4): occlusion and residual

The weights load as tensors only (weights_only; numpy scalars allowed for the checkpoint's own numbers).
"""
import os
import struct
import sys

import numpy as np
import torch
import torch.nn as nn

from xvfi_net import XVFInet, flow_net


class Graph:
    """An ncnn network written layer by layer: blobs named in order, a Split added where one feeds more than one."""

    def __init__(self):
        self.layers = []  # (type, name, inputs, outputs, params)
        self.weights = bytearray()
        self.count = 0

    def blob(self, prefix="b"):
        self.count += 1
        return f"{prefix}{self.count}"

    def input(self, name):
        self.layers.append(["Input", name, [], [name], "0=0"])
        return name

    def conv(self, x, conv, relu=False):
        w = conv.weight.detach().float()
        if w.dim() == 5:  # Conv3d [1, k, k] on one frame is a Conv2d
            w = w[:, :, 0]
        out_c, in_c, kh, kw = w.shape
        stride = conv.stride[-1]
        pad = conv.padding[-1]
        y = self.blob()
        params = f"0={out_c} 1={kw} 11={kh} 3={stride} 13={stride} 4={pad} 14={pad} 5=1 6={w.numel()}"
        if relu:
            params += " 9=1"
        self.layers.append(["Convolution", self.blob("conv"), [x], [y], params])
        self.weights += struct.pack("<I", 0x01306B47)  # half floats
        half = w.numpy().astype(np.float16).tobytes()
        self.weights += half + b"\0" * (-len(half) % 4)
        self.weights += conv.bias.detach().float().numpy().astype("<f4").tobytes()
        return y

    def relu(self, x):
        y = self.blob()
        self.layers.append(["ReLU", self.blob("relu"), [x], [y], ""])
        return y

    def up(self, x):
        y = self.blob()
        self.layers.append(["Interp", self.blob("up"), [x], [y], "0=1 1=2.000000e+00 2=2.000000e+00"])
        return y

    def add(self, a, b):
        y = self.blob()
        self.layers.append(["BinaryOp", self.blob("add"), [a, b], [y], "0=0"])
        return y

    def cat(self, *xs):
        y = self.blob()
        self.layers.append(["Concat", self.blob("cat"), list(xs), [y], "0=0"])
        return y

    def output(self, x, name):
        """Renames the last blob [x] to [name]."""
        for layer in self.layers:
            layer[2] = [name if b == x else b for b in layer[2]]
            layer[3] = [name if b == x else b for b in layer[3]]

    def write(self, path):
        # Splits for blobs read by more than one layer.
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
                f.write(f"{kind:<16} {name:<16} {len(ins)} {len(outs)} {' '.join(ins + outs)} {params}".rstrip() + "\n")
        with open(path + ".bin", "wb") as f:
            f.write(self.weights)


def sequential(g, x, seq):
    """An nn.Sequential of convolutions, ReLUs and nearest doublings, ReLUs fused into the convolution before."""
    mods = list(seq)
    i = 0
    while i < len(mods):
        m = mods[i]
        if isinstance(m, (nn.Conv2d, nn.Conv3d)):
            relu = i + 1 < len(mods) and isinstance(mods[i + 1], nn.ReLU)
            x = g.conv(x, m, relu)
            i += 2 if relu else 1
        elif isinstance(m, nn.UpsamplingNearest2d):
            x = g.up(x)
            i += 1
        elif isinstance(m, nn.ReLU):
            x = g.relu(x)
            i += 1
        elif isinstance(m, nn.Sequential):
            x = sequential(g, x, m)
            i += 1
        else:
            x = resblocks(g, x, m)
            i += 1
    return x


def resblocks(g, x, rr):
    """RResBlock: two residual blocks, then the input added again."""
    start = x
    for block in (rr.resblock1, rr.resblock2):
        y = g.conv(x, block.conv3x3_1, relu=True)
        y = g.conv(y, block.conv3x3_2)
        x = g.add(x, y)
    return g.add(x, start)


def main():
    checkpoint, out_dir = sys.argv[1], sys.argv[2]
    dtypes = [getattr(np.dtypes, n) for n in dir(np.dtypes) if n.endswith("DType")]
    safe = [np.core.multiarray.scalar, np.dtype] + dtypes
    with torch.serialization.safe_globals(safe):
        ck = torch.load(checkpoint, map_location="cpu", weights_only=True)
    net = XVFInet(64, 2, 1).eval()
    net.load_state_dict(ck["state_dict_Model"], strict=True)
    v = net.vfinet
    os.makedirs(out_dir, exist_ok=True)

    def simple(name, build):
        g = Graph()
        g.output(build(g, g.input("in0")), "out")
        g.write(os.path.join(out_dir, name))

    simple("extract", lambda g, x: sequential(g, x, net.rec_ext_ds_module))
    simple("context", lambda g, x: g.conv(x, net.rec_ctx_ds))
    simple("bottom", lambda g, x: sequential(g, x, v.conv_flow_bottom))
    simple("flow1", lambda g, x: g.conv(x, v.conv_flow1))
    simple("flow2", lambda g, x: sequential(g, x, v.conv_flow2))
    simple("flow3", lambda g, x: sequential(g, x, v.conv_flow3))

    def refine(g, x):
        u = v.refine_unet
        enc1 = g.conv(x, u.enc1, relu=True)
        enc2 = g.conv(enc1, u.enc2, relu=True)
        out = g.conv(enc2, u.enc3, relu=True)
        out = g.up(g.conv(out, u.dec0, relu=True))
        out = g.up(g.conv(g.cat(out, enc2), u.dec1, relu=True))
        out = g.up(g.conv(g.cat(out, enc1), u.dec2, relu=True))
        return g.conv(out, u.dec3)

    simple("refine", refine)


if __name__ == "__main__":
    main()

"""FLAVR for 2x interpolation (Kalluri et al.: FLAVR, WACV 2023; github.com/tarun005/FLAVR, Apache-2.0), written out
for MediaDroid from the published architecture — UNet_3D_3D with the unet_18 encoder, no batch norm, transposed
convolutions, joined by concatenation; the parameter names are the checkpoint's (without its `module.`), so its
weights load as tensors only. Four frames in — the two around the in-between and one either side — the in-between
out.

The app runs it on ncnn in two networks (export.py): the 3D part (`body`: the frames, their mean taken away, to the
decoder's last output) and the 2D part (`head`: the four times folded into channels, to the picture); what joins them
is native/src/flavr.cpp.
"""
import torch
import torch.nn as nn
import torch.nn.functional as F


class SEGating(nn.Module):
    """Each channel weighed by a sigmoid of a 1x1x1 convolution of all channels' means."""

    def __init__(self, planes):
        super().__init__()
        self.attn_layer = nn.Sequential(nn.Conv3d(planes, planes, 1, 1, bias=True), nn.Sigmoid())

    def forward(self, x):
        return x * self.attn_layer(F.adaptive_avg_pool3d(x, 1))


class BasicBlock(nn.Module):
    def __init__(self, inplanes, planes, stride=1, downsample=False):
        super().__init__()
        s = (1, stride, stride) if stride != 1 else 1
        self.conv1 = nn.Sequential(nn.Conv3d(inplanes, planes, 3, s, 1, bias=False))
        self.conv2 = nn.Sequential(nn.Conv3d(planes, planes, 3, 1, 1, bias=False))
        self.fg = SEGating(planes)
        self.downsample = nn.Sequential(nn.Conv3d(inplanes, planes, 1, s, bias=False)) if downsample else None

    def forward(self, x):
        out = self.fg(self.conv2(F.relu(self.conv1(x))))
        residual = self.downsample(x) if self.downsample is not None else x
        return F.relu(out + residual)


class Encoder(nn.Module):
    def __init__(self):
        super().__init__()
        self.stem = nn.Sequential(nn.Conv3d(3, 64, (3, 7, 7), (1, 2, 2), (1, 3, 3), bias=False))
        self.layer1 = nn.Sequential(BasicBlock(64, 64), BasicBlock(64, 64))
        self.layer2 = nn.Sequential(BasicBlock(64, 128, 2, True), BasicBlock(128, 128))
        self.layer3 = nn.Sequential(BasicBlock(128, 256, 2, True), BasicBlock(256, 256))
        self.layer4 = nn.Sequential(BasicBlock(256, 512, 1, True), BasicBlock(512, 512))

    def forward(self, x):
        x0 = F.relu(self.stem(x))
        x1 = self.layer1(x0)
        x2 = self.layer2(x1)
        x3 = self.layer3(x2)
        return x0, x1, x2, x3, self.layer4(x3)


class Conv3d(nn.Module):
    def __init__(self, cin, cout):
        super().__init__()
        self.conv = nn.Sequential(nn.Conv3d(cin, cout, 3, 1, 1, bias=True), SEGating(cout))

    def forward(self, x):
        return self.conv(x)


class UpConv3d(nn.Module):
    def __init__(self, cin, cout):
        super().__init__()
        self.upconv = nn.Sequential(nn.ConvTranspose3d(cin, cout, (3, 4, 4), (1, 2, 2), (1, 1, 1)), SEGating(cout))

    def forward(self, x):
        return self.upconv(x)


class FeatureFuse(nn.Module):
    def __init__(self, cin, cout):
        super().__init__()
        self.conv = nn.Sequential(nn.Conv2d(cin, cout, 1, 1, bias=False))

    def forward(self, x):
        return self.conv(x)


class FLAVR(nn.Module):
    def __init__(self, n_inputs=4):
        super().__init__()
        self.encoder = Encoder()
        self.decoder = nn.Sequential(
            Conv3d(512, 256), UpConv3d(512, 128), UpConv3d(256, 64), Conv3d(128, 64), UpConv3d(128, 64))
        self.feature_fuse = FeatureFuse(64 * n_inputs, 64)
        self.outconv = nn.Sequential(nn.ReflectionPad2d(3), nn.Conv2d(64, 3, 7, 1, 0))

    def body(self, x):
        """The 3D part: frames (1, 3, 4, H, W), their mean taken away, to the decoder's last output (1, 64, 4, H, W)."""
        x0, x1, x2, x3, x4 = self.encoder(x)
        lrelu = lambda t: F.leaky_relu(t, 0.2)
        d = torch.cat([lrelu(self.decoder[0](x4)), x3], 1)
        d = torch.cat([lrelu(self.decoder[1](d)), x2], 1)
        d = torch.cat([lrelu(self.decoder[2](d)), x1], 1)
        d = torch.cat([lrelu(self.decoder[3](d)), x0], 1)
        return lrelu(self.decoder[4](d))

    def head(self, d):
        """The 2D part: the four times of the decoder's output folded into channels (time major) to the picture."""
        folded = torch.cat(torch.unbind(d, 2), 1)
        return self.outconv(F.leaky_relu(self.feature_fuse(folded), 0.2))

    def forward(self, frames):
        """The in-between of frames[1] and frames[2] (each 1x3xHxW in 0..1, H and W multiples of 8)."""
        x = torch.stack(frames, dim=2)
        mean = x.mean(dim=(2, 3, 4), keepdim=True)
        return self.head(self.body(x - mean)) + mean.squeeze(2)


def load(checkpoint):
    """FLAVR with the 2x checkpoint's weights, loaded as tensors only."""
    net = FLAVR().eval()
    state = torch.load(checkpoint, map_location="cpu", weights_only=True)["state_dict"]
    net.load_state_dict({k.removeprefix("module."): v for k, v in state.items()}, strict=True)
    return net

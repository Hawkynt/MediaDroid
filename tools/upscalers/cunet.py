"""The cascaded U-Nets behind waifu2x's CUnet and Real-CUGAN, in PyTorch, as their reference implementations define them.

Converted from bilibili's Real-CUGAN upcunet_v3.py (MIT, https://github.com/bilibili/ailab/tree/main/Real-CUGAN) and
nagadomi's nunif waifu2x/models/cunet.py (MIT, https://github.com/nagadomi/nunif): the layers and their names as there,
so the published weights load as they are; only the whole-picture path (no tiles, no caches) is kept.
"""
import torch
from torch import nn
from torch.nn import functional as F


class SEBlock(nn.Module):
    def __init__(self, channels, reduction=8):
        super().__init__()
        self.conv1 = nn.Conv2d(channels, channels // reduction, 1, 1, 0, bias=True)
        self.conv2 = nn.Conv2d(channels // reduction, channels, 1, 1, 0, bias=True)

    def forward(self, x):
        w = torch.mean(x, dim=(2, 3), keepdim=True)
        w = torch.sigmoid(self.conv2(F.relu(self.conv1(w))))
        return x * w


class UNetConv(nn.Module):
    def __init__(self, in_channels, mid_channels, out_channels, se):
        super().__init__()
        self.conv = nn.Sequential(
            nn.Conv2d(in_channels, mid_channels, 3, 1, 0),
            nn.LeakyReLU(0.1),
            nn.Conv2d(mid_channels, out_channels, 3, 1, 0),
            nn.LeakyReLU(0.1),
        )
        self.seblock = SEBlock(out_channels) if se else None

    def forward(self, x):
        z = self.conv(x)
        return self.seblock(z) if self.seblock is not None else z


class UNet1(nn.Module):
    """The first U-Net; [bottom] is its last layer: (kernel, stride, padding) of a transposed convolution, or None."""

    def __init__(self, in_channels, out_channels, bottom):
        super().__init__()
        self.conv1 = UNetConv(in_channels, 32, 64, se=False)
        self.conv1_down = nn.Conv2d(64, 64, 2, 2, 0)
        self.conv2 = UNetConv(64, 128, 64, se=True)
        self.conv2_up = nn.ConvTranspose2d(64, 64, 2, 2, 0)
        self.conv3 = nn.Conv2d(64, 64, 3, 1, 0)
        if bottom is not None:
            self.conv_bottom = nn.ConvTranspose2d(64, out_channels, *bottom)
        else:
            self.conv_bottom = nn.Conv2d(64, out_channels, 3, 1, 0)

    def forward(self, x):
        x1 = self.conv1(x)
        x2 = F.leaky_relu(self.conv1_down(x1), 0.1)
        x2 = F.leaky_relu(self.conv2_up(self.conv2(x2)), 0.1)
        x1 = F.pad(x1, (-4, -4, -4, -4))
        x3 = F.leaky_relu(self.conv3(x1 + x2), 0.1)
        return self.conv_bottom(x3)


class UNet2(nn.Module):
    def __init__(self, in_channels, out_channels):
        super().__init__()
        self.conv1 = UNetConv(in_channels, 32, 64, se=False)
        self.conv1_down = nn.Conv2d(64, 64, 2, 2, 0)
        self.conv2 = UNetConv(64, 64, 128, se=True)
        self.conv2_down = nn.Conv2d(128, 128, 2, 2, 0)
        self.conv3 = UNetConv(128, 256, 128, se=True)
        self.conv3_up = nn.ConvTranspose2d(128, 128, 2, 2, 0)
        self.conv4 = UNetConv(128, 64, 64, se=True)
        self.conv4_up = nn.ConvTranspose2d(64, 64, 2, 2, 0)
        self.conv5 = nn.Conv2d(64, 64, 3, 1, 0)
        self.conv_bottom = nn.Conv2d(64, out_channels, 3, 1, 0)

    def forward(self, x):
        x1 = self.conv1(x)
        x2 = self.conv2(F.leaky_relu(self.conv1_down(x1), 0.1))
        x3 = F.leaky_relu(self.conv2_down(x2), 0.1)
        x3 = F.leaky_relu(self.conv3_up(self.conv3(x3)), 0.1)
        x2 = F.pad(x2, (-4, -4, -4, -4))
        x4 = F.leaky_relu(self.conv4_up(self.conv4(x2 + x3)), 0.1)
        x1 = F.pad(x1, (-16, -16, -16, -16))
        x5 = F.leaky_relu(self.conv5(x1 + x4), 0.1)
        return self.conv_bottom(x5)


class UpCunet(nn.Module):
    """Real-CUGAN's UpCunet2x, 3x or 4x, or (clip_first) waifu2x's UpCUNet, which clamps the first U-Net's output."""

    # Per scale: the padding around the picture, what its size is rounded up to, and UNet1's last layer.
    SETUP = {2: (18, 2, (4, 2, 3)), 3: (14, 4, (5, 3, 2)), 4: (19, 2, (4, 2, 3))}

    def __init__(self, scale, clip_first=False):
        super().__init__()
        self.scale = scale
        self.clip_first = clip_first
        _, _, bottom = self.SETUP[scale]
        if scale == 4:
            self.unet1 = UNet1(3, 64, bottom)
            self.unet2 = UNet2(64, 64)
            self.ps = nn.PixelShuffle(2)
            self.conv_final = nn.Conv2d(64, 12, 3, 1, padding=0, bias=True)
        else:
            self.unet1 = UNet1(3, 3, bottom)
            self.unet2 = UNet2(3, 3)

    def forward(self, x, padding_mode="reflect"):
        """[x] (1 x 3 x h x w, 0..1) enlarged, still as floats; the edges extended by [padding_mode]."""
        _, _, h0, w0 = x.shape
        pad, multiple, _ = self.SETUP[self.scale]
        ph = -(-h0 // multiple) * multiple
        pw = -(-w0 // multiple) * multiple
        z = F.pad(x, (pad, pad + pw - w0, pad, pad + ph - h0), padding_mode)
        z1 = self.unet1(z)
        if self.clip_first:
            z1 = torch.clamp(z1, 0, 1)
        z2 = self.unet2(z1)
        z = z2 + F.pad(z1, (-20, -20, -20, -20))
        if self.scale == 4:
            z = self.ps(F.pad(self.conv_final(z), (-1, -1, -1, -1)))
        z = z[:, :, : h0 * self.scale, : w0 * self.scale]
        if self.scale == 4:
            z = z + F.interpolate(x, scale_factor=4, mode="nearest")
        return z


def load(path, scale, clip_first=False):
    """The network at [scale] with the weights at [path]: a Real-CUGAN .pth, or a nunif one ({"state_dict": ...})."""
    weights = torch.load(path, map_location="cpu", weights_only=False)
    weights = weights.get("state_dict", weights)
    weights = {k: v for k, v in weights.items() if k != "pro"}
    net = UpCunet(scale, clip_first)
    net.load_state_dict(weights, strict=True)
    return net.eval()

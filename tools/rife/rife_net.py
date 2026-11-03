"""RIFE 4.25 lite's network written out from its published architecture (Practical-RIFE, MIT): IFNet with an encoder
head and five IFBlocks, backward warping by bilinear grid sampling with border padding (align_corners)."""
import torch
import torch.nn as nn
import torch.nn.functional as F


def conv(cin, cout, k=3, s=1, p=1):
    return nn.Sequential(nn.Conv2d(cin, cout, k, s, p, bias=True), nn.LeakyReLU(0.2, True))


class Head(nn.Module):
    def __init__(self):
        super().__init__()
        self.cnn0 = nn.Conv2d(3, 16, 3, 2, 1)
        self.cnn1 = nn.Conv2d(16, 16, 3, 1, 1)
        self.cnn2 = nn.Conv2d(16, 16, 3, 1, 1)
        self.cnn3 = nn.ConvTranspose2d(16, 4, 4, 2, 1)
        self.relu = nn.LeakyReLU(0.2, True)

    def forward(self, x):
        x = self.relu(self.cnn0(x))
        x = self.relu(self.cnn1(x))
        x = self.relu(self.cnn2(x))
        return self.cnn3(x)


class ResConv(nn.Module):
    def __init__(self, c):
        super().__init__()
        self.conv = nn.Conv2d(c, c, 3, 1, 1)
        self.beta = nn.Parameter(torch.ones((1, c, 1, 1)))
        self.relu = nn.LeakyReLU(0.2, True)

    def forward(self, x):
        return self.relu(self.conv(x) * self.beta + x)


class IFBlock(nn.Module):
    def __init__(self, cin, c):
        super().__init__()
        self.conv0 = nn.Sequential(conv(cin, c // 2, 3, 2, 1), conv(c // 2, c, 3, 2, 1))
        self.convblock = nn.Sequential(*[ResConv(c) for _ in range(8)])
        self.lastconv = nn.Sequential(nn.ConvTranspose2d(c, 4 * 13, 4, 2, 1), nn.PixelShuffle(2))

    def forward(self, x, flow, scale):
        # Sizes, not scale factors, so the export holds them as constants; they divide exactly at the fixed size.
        h, w = int(x.shape[2]), int(x.shape[3])
        small = (h // scale, w // scale)
        x = F.interpolate(x, size=small, mode="bilinear", align_corners=False)
        if flow is not None:
            flow = F.interpolate(flow, size=small, mode="bilinear", align_corners=False) / scale
            x = torch.cat((x, flow), 1)
        feat = self.convblock(self.conv0(x))
        tmp = F.interpolate(self.lastconv(feat), size=(h, w), mode="bilinear", align_corners=False)
        return tmp[:, :4] * scale, tmp[:, 4:5], tmp[:, 5:]


def identity_grid(h, w):
    """The sampling grid of no motion, -1..1 across and down, for a frame of [h] x [w], laid out as grid_sample takes it."""
    gx = torch.linspace(-1.0, 1.0, w).view(1, 1, w, 1).repeat(1, h, 1, 1)
    gy = torch.linspace(-1.0, 1.0, h).view(1, h, 1, 1).repeat(1, 1, w, 1)
    return torch.cat([gx, gy], 3)


def warp(img, flow, grid, norm):
    """[img] sampled where [flow] (pixels) points: the flow brought to -1..1 by [norm] and added to the identity grid."""
    # Border padding with align_corners is the coordinates clamped to -1..1, then sampled with zero padding — the
    # form TensorFlow Lite converters support.
    g = torch.clamp(grid + (flow * norm).permute(0, 2, 3, 1), -1.0, 1.0)
    return F.grid_sample(img, g, mode="bilinear", padding_mode="zeros", align_corners=True)


class IFNet(nn.Module):
    def __init__(self, h=640, w=768):
        super().__init__()
        # Built once here, it is a constant of the exported graph, not operations in it.
        self.register_buffer("grid", identity_grid(h, w), persistent=False)
        self.register_buffer("norm", torch.tensor([2.0 / (w - 1), 2.0 / (h - 1)]).view(1, 2, 1, 1), persistent=False)
        # The in-between's time: halfway, as a plane of the frame's size.
        self.register_buffer("halfway", torch.full((1, 1, h, w), 0.5), persistent=False)
        self.block0 = IFBlock(7 + 8, 192)
        self.block1 = IFBlock(8 + 4 + 8 + 8, 128)
        self.block2 = IFBlock(8 + 4 + 8 + 8, 96)
        self.block3 = IFBlock(8 + 4 + 8 + 8, 64)
        self.block4 = IFBlock(8 + 4 + 8 + 8, 24)
        self.encode = Head()

    def forward(self, img0, img1, timestep=0.5, scales=(32, 16, 8, 4, 1)):
        t = self.halfway
        f0, f1 = self.encode(img0), self.encode(img1)
        blocks = [self.block0, self.block1, self.block2, self.block3, self.block4]
        flow = mask = feat = None
        w0, w1 = img0, img1
        for block, scale in zip(blocks, scales):
            if flow is None:
                flow, mask, feat = block(torch.cat((img0, img1, f0, f1, t), 1), None, scale)
            else:
                wf0, wf1 = warp(f0, flow[:, :2], self.grid, self.norm), warp(f1, flow[:, 2:4], self.grid, self.norm)
                fd, mask, feat = block(torch.cat((w0, w1, wf0, wf1, t, mask, feat), 1), flow, scale)
                flow = flow + fd
            w0, w1 = warp(img0, flow[:, :2], self.grid, self.norm), warp(img1, flow[:, 2:4], self.grid, self.norm)
        mask = torch.sigmoid(mask)
        return w0 * mask + w1 * (1 - mask)

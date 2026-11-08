"""XVFI-Net for inference (Sim, Oh, Kim: XVFI, ICCV 2021; github.com/JihyongOh/XVFI, used with the authors'
permission under its terms: research and education, no commercial use). Written out for MediaDroyd from the published
architecture: the parameter names are the checkpoint's, so its weights load as tensors only. The test path only
(is_training=False): S_tst levels from the coarsest, then the full level.

The blocks the app runs on ncnn — the feature extractor, the context downscale, the flow and refinement networks — are
plain convolutions (export.py writes them out); what joins them (warping, the complementary flow reversal, the
blending) is in forward() here and in native/src/xvfi.cpp there.
"""
import torch
import torch.nn as nn
import torch.nn.functional as F


class ResBlock(nn.Module):
    """Two 3x3 convolutions with a ReLU between, added to the input; as Conv3d [1,3,3] in the checkpoint."""

    def __init__(self, nf):
        super().__init__()
        self.conv3x3_1 = nn.Conv3d(nf, nf, [1, 3, 3], 1, [0, 1, 1])
        self.conv3x3_2 = nn.Conv3d(nf, nf, [1, 3, 3], 1, [0, 1, 1])

    def forward(self, x):
        return x + self.conv3x3_2(F.relu(self.conv3x3_1(x)))


class RResBlock(nn.Module):
    def __init__(self, nf):
        super().__init__()
        self.resblock1 = ResBlock(nf)
        self.resblock2 = ResBlock(nf)

    def forward(self, x):
        return self.resblock2(self.resblock1(x)) + x


def flow_net(cin, nf, cout, first_1x1=False):
    """The flow networks' hourglass: two 4x4 stride-2 convolutions down, two nearest doublings up, as nn.Sequential
    with the checkpoint's indices."""
    layers = []
    if first_1x1:
        layers += [nn.Conv2d(cin, nf, 1, 1, 0), nn.ReLU()]
        cin = nf
    layers += [nn.Conv2d(cin, 2 * nf, 4, 2, 1), nn.ReLU(), nn.Conv2d(2 * nf, 4 * nf, 4, 2, 1), nn.ReLU(),
               nn.UpsamplingNearest2d(scale_factor=2), nn.Conv2d(4 * nf, 2 * nf, 3, 1, 1), nn.ReLU(),
               nn.UpsamplingNearest2d(scale_factor=2), nn.Conv2d(2 * nf, nf, 3, 1, 1), nn.ReLU(),
               nn.Conv2d(nf, cout, 3, 1, 1)]
    return nn.Sequential(*layers)


class RefineUNet(nn.Module):
    def __init__(self, nf, scale, img_ch=3):
        super().__init__()
        self.conv1 = nn.Conv2d(nf, nf, 3, 1, 1)  # in the checkpoint, unused
        self.conv2 = nn.Conv2d(nf, nf, 3, 1, 1)  # in the checkpoint, unused
        self.enc1 = nn.Conv2d((4 * nf) // scale // scale + 4 * img_ch + 4, nf, 4, 2, 1)
        self.enc2 = nn.Conv2d(nf, 2 * nf, 4, 2, 1)
        self.enc3 = nn.Conv2d(2 * nf, 4 * nf, 4, 2, 1)
        self.dec0 = nn.Conv2d(4 * nf, 4 * nf, 3, 1, 1)
        self.dec1 = nn.Conv2d(4 * nf + 2 * nf, 2 * nf, 3, 1, 1)
        self.dec2 = nn.Conv2d(2 * nf + nf, nf, 3, 1, 1)
        self.dec3 = nn.Conv2d(nf, 1 + img_ch, 3, 1, 1)

    def forward(self, x):
        up = lambda t: F.interpolate(t, scale_factor=2, mode="nearest")
        enc1 = F.relu(self.enc1(x))
        enc2 = F.relu(self.enc2(enc1))
        out = F.relu(self.enc3(enc2))
        out = up(F.relu(self.dec0(out)))
        out = up(F.relu(self.dec1(torch.cat((out, enc2), 1))))
        out = up(F.relu(self.dec2(torch.cat((out, enc1), 1))))
        return self.dec3(out)


class VFInet(nn.Module):
    def __init__(self, nf, scale):
        super().__init__()
        self.scale = scale
        self.conv_flow_bottom = flow_net(2 * nf, nf, 6)
        self.conv_flow1 = nn.Conv2d(2 * nf, nf, 3, 1, 1)
        self.conv_flow2 = flow_net(2 * nf + 4, nf, 6)
        self.conv_flow3 = flow_net(4 + nf * 4, nf, 4, first_1x1=True)
        self.refine_unet = RefineUNet(nf, scale)


def bwarp(x, flow):
    """x sampled where flow (pixels) points, bilinear with align_corners and zeros outside; where any of the sample's
    weight falls outside the picture (its coverage below 0.999), 0."""
    b, c, h, w = x.shape
    yy, xx = torch.meshgrid(torch.arange(h, dtype=x.dtype), torch.arange(w, dtype=x.dtype), indexing="ij")
    gx = 2.0 * (xx + flow[:, 0]) / max(w - 1, 1) - 1.0
    gy = 2.0 * (yy + flow[:, 1]) / max(h - 1, 1) - 1.0
    grid = torch.stack((gx, gy), dim=3)
    out = F.grid_sample(x, grid, mode="bilinear", padding_mode="zeros", align_corners=True)
    cover = F.grid_sample(torch.ones_like(x[:, :1]), grid, mode="bilinear", padding_mode="zeros", align_corners=True)
    return out * (cover >= 0.999).to(x.dtype)


def z_fwarp(img, flow, z):
    """Forward warp: each pixel of img added to the four pixels around where flow takes it, weighted by
    z * exp(-distance^2) to each; returns the sums and the summed weights."""
    b, c, h, w = img.shape
    out = torch.zeros(b * c * h * w, dtype=img.dtype)
    weight = torch.zeros(b * c * h * w, dtype=img.dtype)
    rows = torch.arange(h).view(1, 1, h, 1).expand(b, c, h, w)
    cols = torch.arange(w).view(1, 1, 1, w).expand(b, c, h, w)
    fy = flow[:, 1:2].expand(b, c, h, w)
    fx = flow[:, 0:1].expand(b, c, h, w)
    y1 = torch.floor(fy)
    x1 = torch.floor(fx)
    zz = (z + 1e-5).expand(b, c, h, w)
    n = torch.arange(b).view(b, 1, 1, 1).expand(b, c, h, w)
    ch = torch.arange(c).view(1, c, 1, 1).expand(b, c, h, w)
    for dy in (0, 1):
        for dx in (0, 1):
            wt = zz * torch.exp(-((fy - (y1 + dy)) ** 2 + (fx - (x1 + dx)) ** 2))
            r = rows + (y1 + dy).long()
            cc = cols + (x1 + dx).long()
            inside = (r >= 0) & (r < h) & (cc >= 0) & (cc < w)
            idx = ((n * c + ch) * h + r) * w + cc
            out.index_put_((idx[inside],), (img * wt)[inside], accumulate=True)
            weight.index_put_((idx[inside],), wt[inside], accumulate=True)
    return out.view(b, c, h, w), weight.view(b, c, h, w)


class XVFInet(nn.Module):
    def __init__(self, nf=64, scale=2, s_tst=1):
        super().__init__()
        self.nf, self.scale, self.s_tst = nf, scale, s_tst
        self.vfinet = VFInet(nf, scale)
        self.channel_converter = nn.Sequential(nn.Conv3d(3, nf, [1, 3, 3], 1, [0, 1, 1]), nn.ReLU())
        self.rec_ext_ds = nn.Conv3d(nf, nf, [1, 3, 3], [1, 2, 2], [0, 1, 1])
        modules = [self.channel_converter]
        s = scale
        while s > 1:
            modules += [self.rec_ext_ds, nn.ReLU()]
            s //= 2
        modules += [nn.Conv3d(nf, nf, [1, 3, 3], 1, [0, 1, 1]), RResBlock(nf)]
        self.rec_ext_ds_module = nn.Sequential(*modules)
        self.rec_ctx_ds = nn.Conv3d(nf, nf, [1, 3, 3], [1, 2, 2], [0, 1, 1])

    def forward(self, img0, img1, t):
        """The in-between at t of img0 and img1 (1x3xHxW in 0..1, H and W multiples of 2^(s_tst+2) * scale)."""
        x = torch.stack((img0, img1), dim=2)  # B, C, T, H, W
        feats = [self.rec_ext_ds_module(x)]
        for _ in range(self.s_tst):
            feats.append(self.rec_ctx_ds(feats[-1]))
        v = self.vfinet
        flow = None
        for level in range(self.s_tst, -1, -1):
            f0, f1 = feats[level][:, :, 0], feats[level][:, :, 1]
            if flow is None:
                tmp = v.conv_flow_bottom(torch.cat((f0, f1), 1))
                flow = tmp[:, :4]
            else:
                up = 2.0 * F.interpolate(flow, scale_factor=2, mode="bilinear", align_corners=False)
                w1 = bwarp(f1, up[:, :2])
                w0 = bwarp(f0, up[:, 2:])
                tmp = v.conv_flow2(torch.cat([v.conv_flow1(torch.cat([f0, w1], 1)),
                                              v.conv_flow1(torch.cat([f1, w0], 1)), up], 1))
                flow = tmp[:, :4] + up
            if level != 0:
                continue
            l = 2 ** level
            frames = [img0, img1] if l == 1 else [F.interpolate(i, scale_factor=1.0 / l, mode="bicubic",
                                                                align_corners=False) for i in (img0, img1)]
            f01, f10 = flow[:, :2], flow[:, 2:]
            z01, z10 = torch.sigmoid(tmp[:, 4:5]), torch.sigmoid(tmp[:, 5:6])
            # Complementary flow reversal (Eq. 1, 2): the flows from t back to each frame.
            forward, norm0 = z_fwarp(f01, t * f01, z01)
            backward, norm1 = z_fwarp(f10, (1 - t) * f10, z10)
            ft0 = -(1 - t) * (t * forward) + t * (t * backward)
            ft1 = (1 - t) * ((1 - t) * forward) - t * ((1 - t) * backward)
            norm = (1 - t) * norm0 + t * norm1
            mask = (norm > 0).to(norm.dtype)
            ft0 = (1 - mask) * ft0 + mask * (ft0 / (norm + (1 - mask)))
            ft1 = (1 - mask) * ft1 + mask * (ft1 / (norm + (1 - mask)))
            wf0, wf1 = bwarp(f0, ft0), bwarp(f1, ft1)
            refined = v.conv_flow3(torch.cat([f0, wf0, wf1, f1, ft0, ft1], 1)) + torch.cat([ft0, ft1], 1)
            ft0, ft1 = refined[:, :2], refined[:, 2:4]
            wf0, wf1 = bwarp(f0, ft0), bwarp(f1, ft1)
            ft0 = self.scale * F.interpolate(ft0, scale_factor=self.scale, mode="bilinear", align_corners=False)
            ft1 = self.scale * F.interpolate(ft1, scale_factor=self.scale, mode="bilinear", align_corners=False)
            wi0, wi1 = bwarp(frames[0], ft0), bwarp(frames[1], ft1)
            out = v.refine_unet(torch.cat([F.pixel_shuffle(torch.cat([f0, f1, wf0, wf1], 1), self.scale),
                                           frames[0], frames[1], wi0, wi1, ft0, ft1], 1))
            occ0 = torch.sigmoid(out[:, 0:1])
            occ1 = 1 - occ0
            blend = ((1 - t) * occ0 * wi0 + t * occ1 * wi1) / ((1 - t) * occ0 + t * occ1)
            return blend + out[:, 1:4]

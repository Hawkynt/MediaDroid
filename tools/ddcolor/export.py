"""DDColor written out for ncnn: the paper-tiny model's network, its transformer decoder with the batch first.

    python export.py <DDColor source folder> <pytorch_model.bin> <out dir>

DDColor (https://github.com/piddnad/DDColor, Apache-2.0) is built from its own source; only the colour decoder's forward
is replaced for the export, by the same arithmetic: every attention on (1, length, channels) tensors — the batch first,
as ncnn's converter expects it, not in the middle as DETR's (length, batch, channels) — and the queries' colours against
the picture's features as one matrix product. With the batch in the middle pnnx lost it, and the joins and reshapes
around the attentions came out wrong. Writes ddcolor.onnx (the network at 256 x 256: in0 the grey picture as RGB
0..1, out0 the a and b of Lab at 256 x 256) and checks the rewritten decoder against the original forward.
"""
import sys

import torch
from torch.nn import functional as F

sys.path.insert(0, sys.argv[1])
from basicsr.archs.ddcolor_arch import DDColor, MultiScaleColorDecoder  # noqa: E402

SIZE = 256


def attention(layer, query, key, value):
    """nn.MultiheadAttention [layer] over (1, length, channels) tensors, as its own forward computes it."""
    c = query.shape[2]
    heads = layer.num_heads
    d = c // heads
    w, b = layer.in_proj_weight, layer.in_proj_bias
    q = F.linear(query, w[:c], b[:c]).reshape(1, -1, heads, d).permute(0, 2, 1, 3) * (d ** -0.5)
    k = F.linear(key, w[c:2 * c], b[c:2 * c]).reshape(1, -1, heads, d).permute(0, 2, 3, 1)
    v = F.linear(value, w[2 * c:], b[2 * c:]).reshape(1, -1, heads, d).permute(0, 2, 1, 3)
    a = torch.softmax(torch.matmul(q, k), dim=-1)
    out = torch.matmul(a, v).permute(0, 2, 1, 3).reshape(1, -1, c)
    return layer.out_proj(out)


def decoder_forward(self, x, img_features):
    src, pos = [], []
    for i in range(self.num_feature_levels):
        p = self.pe_layer(x[i], None)
        s = self.input_proj[i](x[i])
        pos.append(p.flatten(2).permute(0, 2, 1))
        src.append(s.flatten(2).permute(0, 2, 1) + self.level_embed.weight[i][None, None, :])
    query_pos = self.query_embed.weight[None]
    output = self.query_feat.weight[None]
    for i in range(self.num_layers):
        level = i % self.num_feature_levels
        cross = self.transformer_cross_attention_layers[i]
        output = cross.norm(output + attention(cross.multihead_attn, output + query_pos, src[level] + pos[level],
                                               src[level]))
        self_attn = self.transformer_self_attention_layers[i]
        q = output + query_pos
        output = self_attn.norm(output + attention(self_attn.self_attn, q, q, output))
        ffn = self.transformer_ffn_layers[i]
        output = ffn.norm(output + ffn.linear2(F.relu(ffn.linear1(output))))
    color_embed = self.color_embed(self.decoder_norm(output))
    _, c, h, w = img_features.shape
    return torch.matmul(color_embed, img_features.flatten(2)).reshape(1, -1, h, w)


def build(weights):
    net = DDColor(encoder_name="convnext-t", decoder_name="MultiScaleColorDecoder", input_size=[SIZE, SIZE],
                  num_output_channels=2, last_norm="Spectral", do_normalize=False, num_queries=100, num_scales=3,
                  dec_layers=9)
    state = torch.load(weights, map_location="cpu", weights_only=False)
    net.load_state_dict(state.get("params", state), strict=True)
    return net.eval()


def main():
    net = build(sys.argv[2])
    x = torch.rand(1, 3, SIZE, SIZE)
    with torch.no_grad():
        original = net(x)
        MultiScaleColorDecoder.forward = decoder_forward
        rewritten = net(x)
    print("rewritten decoder off by at most", float((original - rewritten).abs().max()))
    torch.onnx.export(net, (x,), sys.argv[3] + "/ddcolor.onnx", opset_version=17, input_names=["in0"],
                      output_names=["out0"], dynamo=False)


if __name__ == "__main__":
    main()

import sys, torch
sys.path.insert(0, ".")
from rife_net import IFNet
W, H = 768, 640
net = IFNet().eval()
state = torch.load("train_log/flownet.pkl", map_location="cpu", weights_only=True)
state = {k.replace("module.", ""): v for k, v in state.items()}
missing, unexpected = net.load_state_dict(state, strict=False)
print("missing", missing[:6], "unexpected", [k for k in unexpected if not k.startswith(("teacher", "caltime"))][:6])
torch.manual_seed(0)
a = torch.rand(1, 3, H, W); b = torch.roll(a, shifts=(3, 5), dims=(2, 3))
with torch.no_grad():
    ref = net(a, b)
prev = torch.load("probe.pt", weights_only=True)["ref"] if __import__("os").path.exists("probe.pt") else None
if prev is not None: print("same as scale-factor version:", float((prev - ref).abs().max()))
torch.save({"a": a, "b": b, "ref": ref}, "probe.pt")
print("torch out", tuple(ref.shape), round(float(ref.min()), 3), round(float(ref.max()), 3))
class Middle(torch.nn.Module):
    """The in-between at t = 0.5 from two frames: all the export takes."""
    def __init__(self, net):
        super().__init__()
        self.net = net

    def forward(self, img0, img1):
        return self.net(img0, img1)

torch.onnx.export(Middle(net).eval(), (a, b), "rife425lite_576p.onnx", input_names=["img0", "img1"], output_names=["middle"], opset_version=17)
print("onnx written")

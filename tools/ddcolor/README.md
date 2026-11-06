# DDColor for ncnn

How `ddcolor/ddcolor.param` and `ddcolor.bin` of [Hawkynt/mediadroid-models](https://huggingface.co/Hawkynt/mediadroid-models)
— which the desktop fetches when colour is picked (`NeuralModels.DDCOLOR`) — are made from DDColor's paper-tiny model
([piddnad/DDColor](https://github.com/piddnad/DDColor), Apache-2.0; see `engine/desktop/THIRD-PARTY-NOTICE.DDColor.txt`),
and how they were checked.

1. Python with `torch` (CPU; 2.15 was used), `numpy`, `onnx` and `pnnx` (20261020).
2. DDColor's source (<https://github.com/piddnad/DDColor>, its `basicsr/archs`) and the weights:
   `pytorch_model.bin` of <https://huggingface.co/piddnad/ddcolor_paper_tiny> (sha256
   `8a1277bc90a1bfbb6d2d83933a9a6bc821931879ca93e26e4fcec12165d41fce`).
3. `python export.py <DDColor folder> pytorch_model.bin <out>` — the network built from DDColor's own source at
   256 × 256 and written to ONNX, its colour decoder's forward replaced by the same arithmetic with the batch first
   (DETR's `(length, batch, channels)` layout is where pnnx lost the batch, and the reshapes and joins around the
   attentions came out wrong); it prints how far the rewritten decoder is from the original (0 here).
4. `pnnx ddcolor.onnx inputshape=[1,3,256,256]` — `ddcolor.ncnn.param` and `.bin`, renamed `ddcolor.param` and
   `ddcolor.bin`: 50,830 bytes, sha256 `a9a6ddbdff8ed63928aee0f60370f011b0eb0954faa6ec6ec64e95b8f7f972af`, and
   115,752,240 bytes (weights as half floats), sha256 `b9979c720cc89286818b84eced58cb5bcb3a11c11345198b47ffca0eafaffd9c`;
   the same bytes again on a second run.
5. `python reference.py <DDColor folder> pytorch_model.bin <core resources>/colorize <test resources>/colorize` — the
   self-test's check picture (`check.grey`, a grey scene drawn by the script), what the PyTorch model gives it averaged
   over 16 × 16 blocks (`check.ab`, for `NeuralChecks.colorizer` on the device) and in full, rounded (`check-ab.bin`, for
   `NativeNeuralTest`).

The network alone (the a and b of Lab at 256 × 256, about ±128), ncnn on the CPU against PyTorch on the CPU, on the
check picture and on two photographs (Real-ESRGAN's demo pictures, not committed): off by 0.10 to 0.22 on average, 7.5
at the most. At 256 × 256 a run takes about 0.5 s on a desktop CPU (8 threads), PyTorch 0.2 s.

Around the network, `Colorizing.kt` follows DDColor's own inference: the picture at 256 × 256 (OpenCV's linear resize),
its Lab lightness as a grey RGB in, Lab's conversions OpenCV's for floats (its unit test holds them to OpenCV's own
values); the a and b out are enlarged linearly (DDColor's script repeats pixels) and laid under the frame's own
lightness, on the graphics chip (`stage/src/main/glsl/video/colorize.frag`). For video the network runs on a thread of
its own whenever it is free, its results smoothed over time and dropped at a cut.

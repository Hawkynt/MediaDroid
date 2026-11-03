# RIFE for TensorFlow Lite

How `app/src/main/assets/models/rife-4.25-lite-576p.tflite` is made from RIFE 4.25 lite
([Practical-RIFE](https://github.com/hzwer/Practical-RIFE), MIT; its models under the same licence).

1. A Python 3.12 environment with `torch==2.5.1+cpu`, `numpy<2`, `onnx==1.17.0`, `tensorflow-cpu==2.18.0`,
   `tf-keras==2.18.0`, `onnx2tf==1.26.3`, `onnx-graphsurgeon`, `sng4onnx`, `psutil`, `onnxslim==0.1.48`.
2. The 4.25.lite download from Practical-RIFE's README; only its `train_log/flownet.pkl` is used, put into
   `train_log/` beside these scripts. Its own Python files are not run: `rife_net.py` writes the network out from
   its published architecture, and the weights are loaded as tensors only (`weights_only=True`).
3. `python export.py` — loads the weights (it reports nothing missing or left over), runs a test pair and keeps
   PyTorch's in-between as the reference in `probe.pt`, and exports `rife425lite_576p.onnx` at 768 × 640 for the
   in-between at t = 0.5.
4. `onnxslim rife425lite_576p.onnx rife_slim.onnx`, then `onnx2tf -i rife_slim.onnx -o tfl -osd` — with a plain
   numeric `calibration_image_sample_data_20x128x128x3_float32.npy` of its own in the working directory, as onnx2tf
   otherwise downloads a pickled one that numpy (rightly) refuses.
5. `python check.py tfl/rife_slim_float32.tflite` — the TensorFlow Lite in-between against PyTorch's: mean
   difference 0.00008, 0.08 % of values off by more than 1/255. The float16 file comes out wrong; float32 is used.

What differs from Practical-RIFE's own code, all for the conversion and checked to give the same result: sizes
instead of scale factors in the resampling, the identity grid and the time plane as constants, and the warp's
border padding as clamped coordinates with zero padding (the same with `align_corners`).

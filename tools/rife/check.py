import sys, numpy as np, torch, onnxruntime as ort
p = torch.load("probe.pt", weights_only=True)
a, b, ref = p["a"].numpy(), p["b"].numpy(), p["ref"].numpy()
if len(sys.argv) > 1:
    import tensorflow as tf
    it = tf.lite.Interpreter(model_path=sys.argv[1]); it.allocate_tensors()
    ins = it.get_input_details(); outs = it.get_output_details()
    print("tflite inputs", [(d["name"], tuple(d["shape"]), d["dtype"].__name__) for d in ins], "out", tuple(outs[0]["shape"]))
    for d in ins:
        x = a if "0" in d["name"] else b
        it.set_tensor(d["index"], np.transpose(x, (0, 2, 3, 1)).astype(d["dtype"]))
    it.invoke()
    y = np.transpose(it.get_tensor(outs[0]["index"]), (0, 3, 1, 2))
    d = np.abs(y - ref)
    print("tflite vs torch: max", float(d.max()), "mean", float(d.mean()), "share above 1/255:", float((d > 1 / 255).mean()))

# VESCURUS on-device YOLO model

The Guide phone expects `yolo_egg.onnx` in this directory.

Generate it with:

```bash
python tools/export_yolo_world_egg.py
```

The export uses `yolov8s-worldv2.pt` with the vocabulary baked to `egg`, then exports a 640x640 ONNX model for ONNX Runtime Android.

The binary model is intentionally not committed by this source-only change. The Android app returns no detections until `yolo_egg.onnx` is present here.

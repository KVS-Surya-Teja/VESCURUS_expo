# VESCURUS on-device YOLO model

The Guide phone expects `yolo_food.onnx` in this directory.

Generate it with:

```bash
python tools/export_yolo_world_egg.py
```

The export uses `yolov8s-worldv2.pt` with a baked multi-ingredient food vocabulary, then exports a 640x640 ONNX model for ONNX Runtime Android.

The binary model is intentionally not committed by this source-only change. The Android app returns no detections until `yolo_food.onnx` is present here.

# VESCURUS on-device YOLO model

The Guide phone uses `yolo_food.onnx` for on-device ingredient detection.

The model is built automatically by GitHub Actions from `yolov8s-worldv2.pt` with VESCURUS's baked food vocabulary. It is **not** committed to the source repository because it is a large generated binary.

To build it manually:

```bash
python -m pip install ultralytics
python tools/export_yolo_world_food.py
```

The generated model is placed at:

```text
app/src/main/assets/yolo_food.onnx
```

For the Android app, the model must be available as an asset (or copied into app-private storage by the model downloader before ONNX Runtime initialization).
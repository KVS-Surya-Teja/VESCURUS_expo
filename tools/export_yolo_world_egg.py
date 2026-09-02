"""Export YOLOv8-World v2 with a baked single-class vocabulary for VESCURUS.

Run from a Python environment with Ultralytics installed:
    pip install ultralytics
    python tools/export_yolo_world_egg.py

The resulting ONNX file should be copied to:
    app/src/main/assets/yolo_egg.onnx

This deliberately bakes ["egg"] into the YOLO-World model so Android only
needs ONNX Runtime; it does not need Gemini, a laptop, or a text-embedding
model at inference time.
"""

from pathlib import Path

from ultralytics import YOLOWorld

ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "app" / "src" / "main" / "assets"
OUT_DIR.mkdir(parents=True, exist_ok=True)

model = YOLOWorld("yolov8s-worldv2.pt")
model.set_classes(["egg"])

# Save the vocabulary-baked model first, then export that exact model.
baked_pt = OUT_DIR / "yolov8s-worldv2-egg.pt"
model.save(baked_pt)

exported = model.export(
    format="onnx",
    imgsz=640,
    dynamic=False,
    simplify=True,
)

exported_path = Path(exported)
target = OUT_DIR / "yolo_egg.onnx"
if exported_path.resolve() != target.resolve():
    target.write_bytes(exported_path.read_bytes())

print(f"Created: {target}")
print("Copy/keep yolo_egg.onnx in app/src/main/assets before building the Android app.")

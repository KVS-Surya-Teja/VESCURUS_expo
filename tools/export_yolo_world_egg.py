"""Export YOLOv8-World v2 with a baked food vocabulary for VESCURUS.

Run from a Python environment with Ultralytics installed:
    pip install ultralytics
    python tools/export_yolo_world_egg.py

The resulting ONNX file should be copied to:
    app/src/main/assets/yolo_food.onnx

The vocabulary is baked into the model, so Android only needs ONNX Runtime.
"""

from pathlib import Path
from ultralytics import YOLOWorld

ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "app" / "src" / "main" / "assets"
OUT_DIR.mkdir(parents=True, exist_ok=True)

FOOD_CLASSES = [
    "egg", "tomato", "onion", "green chili", "banana", "chicken breast",
    "broccoli", "bread", "cheese", "apple", "potato", "garlic", "bell pepper",
    "salmon", "rice", "pasta", "mushroom", "avocado", "carrot", "butter",
    "milk", "flour", "spinach", "lemon", "lime", "cucumber", "beef", "pork",
    "shrimp", "paneer", "tofu", "corn", "strawberry", "grape", "orange",
    "olive oil", "black pepper", "salt", "turmeric", "chili powder", "yogurt",
    "cream", "beans", "peas", "cabbage", "cauliflower", "ginger", "coconut",
    "oats", "peanut", "cashew", "almond", "honey", "sugar", "flour tortilla",
]

model = YOLOWorld("yolov8s-worldv2.pt")
model.set_classes(FOOD_CLASSES)

exported = model.export(
    format="onnx",
    imgsz=640,
    dynamic=False,
    simplify=True,
)

exported_path = Path(exported)
target = OUT_DIR / "yolo_food.onnx"
if exported_path.resolve() != target.resolve():
    target.write_bytes(exported_path.read_bytes())

print(f"Created: {target}")
print(f"Baked vocabulary contains {len(FOOD_CLASSES)} food/ingredient classes.")

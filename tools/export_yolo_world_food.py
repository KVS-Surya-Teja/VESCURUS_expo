"""Export YOLOv8-World v2 with VESCURUS's baked food vocabulary."""

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
exported = model.export(format="onnx", imgsz=640, dynamic=False, simplify=True)

exported_path = Path(exported)
target = OUT_DIR / "yolo_food.onnx"
if exported_path.resolve() != target.resolve():
    target.write_bytes(exported_path.read_bytes())

print(f"Created {target} with {len(FOOD_CLASSES)} baked classes")

"""
VESCURUS - Local YOLO-World Inference Server
=============================================
Runs YOLO-World locally on your laptop with GPU/CPU acceleration.
Exposes a high-performance REST API for the VESCURUS Android App.

Usage:
  1. Install dependencies:  pip install ultralytics flask opencv-python pillow
  2. Run server:            python vescurus_yolo_server.py
  3. Set Laptop IP in Android AppModule.kt (e.g. 192.168.0.100)
"""

import io
import cv2
import numpy as np
from flask import Flask, request, jsonify
from ultralytics import YOLO

app = Flask(__name__)

print("==================================================")
print("Loading YOLO-World (yolov8s-worldv2.pt)...")
# Automatically downloads yolov8s-worldv2.pt on first run
model = YOLO("yolov8s-worldv2.pt")

# Define target detection classes for open-world food detection
FOOD_CLASSES = [
    "egg", "tomato", "onion", "green chili", "banana",
    "chicken", "broccoli", "bread", "cheese", "apple",
    "potato", "garlic", "bell pepper", "salmon", "rice", "pasta"
]
model.set_classes(FOOD_CLASSES)
print(f"YOLO-World configured for target classes: {FOOD_CLASSES}")
print("==================================================")

@app.route('/detect', methods=['POST'])
def detect():
    try:
        if 'image' not in request.files:
            return jsonify({"request_id": "yolo-v0", "detections": [], "overall_confidence": 0.0})

        # Read image bytes directly from HTTP POST form data
        file = request.files['image']
        in_memory_file = io.BytesIO()
        file.save(in_memory_file)
        data = np.frombuffer(in_memory_file.getvalue(), dtype=np.uint8)
        frame = cv2.imdecode(data, cv2.IMREAD_COLOR)

        if frame is None:
            return jsonify({"request_id": "yolo-v0", "detections": [], "overall_confidence": 0.0})

        h, w, _ = frame.shape

        # Run YOLO-World inference
        results = model.predict(source=frame, conf=0.25, verbose=False)
        boxes = results[0].boxes

        detections = []
        max_conf = 0.0

        for i, box in enumerate(boxes):
            conf = float(box.conf[0])
            cls_id = int(box.cls[0])

            # Retrieve class label from model vocabulary
            if hasattr(results[0], 'names') and cls_id in results[0].names:
                cls_name = results[0].names[cls_id]
            else:
                cls_name = FOOD_CLASSES[cls_id] if cls_id < len(FOOD_CLASSES) else "food"

            xyxy = box.xyxy[0].tolist()  # [xmin, ymin, xmax, ymax] in pixels

            # Normalize bounding box coordinates to 0.0 - 1.0 float range
            xmin, ymin, xmax, ymax = xyxy[0] / w, xyxy[1] / h, xyxy[2] / w, xyxy[3] / h

            if conf > max_conf:
                max_conf = conf

            detections.append({
                "id": f"food-{i+1}",
                "label": cls_name,
                "confidence": round(conf, 2),
                "box_2d": {
                    "ymin": round(ymin, 4),
                    "xmin": round(xmin, 4),
                    "ymax": round(ymax, 4),
                    "xmax": round(xmax, 4)
                },
                "alternatives": [],
                "is_supported": True
            })

        print(f"[YOLO-Server] Detected {len(detections)} items: {[d['label'] for d in detections]} (Max Conf: {max_conf:.2f})")

        return jsonify({
            "request_id": "yolo-v0",
            "detections": detections,
            "overall_confidence": round(max_conf, 2)
        })

    except Exception as e:
        print(f"[YOLO-Server] Error processing frame: {e}")
        return jsonify({"request_id": "yolo-v0", "detections": [], "overall_confidence": 0.0})

if __name__ == '__main__':
    # 0.0.0.0 listens on all local network interfaces (Hotspot / Wi-Fi)
    print("Starting Flask server on http://0.0.0.0:5000 ...")
    app.run(host='0.0.0.0', port=5000, debug=False)

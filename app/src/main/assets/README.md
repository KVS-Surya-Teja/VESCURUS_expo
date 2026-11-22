# VESCURUS vision assets

The active route is explicit cloud Gemini REST image inference. `vision_prompt.txt` and
`vision_schema.json` are shared by Android and `tools/vision/vision_api.py`.
Default model: `gemini-3.1-flash-lite`, selected after actual image requests with the existing key.
Set `GEMINI_MODEL` in ignored `local.properties` to deliberately change it; no silent provider fallback.

Optional local ONNX route: `yolo_food.onnx` is NOT present or packaged in this workspace.
`tools/export_yolo_world_egg.py` describes a 640x640 RGB, CHW, 0..1 baked-vocabulary
YOLO-World export. That script has not been run or validated here. The existing runtime
uses ONNX Runtime CPU (no accelerated provider configured). Only [1, 4+classCount, anchors]
output is accepted; the previous claim of transposed-layout support was incorrect.
Do not enable it until model provenance, package versions, checksum, vocabulary, tensor
metadata and reference/Android output comparison are recorded. Missing model is an
explicit failure, never successful empty recognition or an undisclosed cloud fallback.

See `docs/CV_VALIDATION.md` for actual results, limits and API use.

Exhibition build update: the unused ONNX Runtime 1.17 binary was removed because
lint verified it is not 16 KB aligned and no model is available. Selecting local
mode now explicitly reports ON_DEVICE_UNAVAILABLE. The previous CPU implementation
is retained at tools/reference/OnDeviceYoloRepositoryImpl.kt for a future verified
export; it is outside the packaged source set. Active Gemini inference is unchanged.

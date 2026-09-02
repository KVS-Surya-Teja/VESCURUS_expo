package com.example.vescurus.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.vescurus.debug.VescurusLogger
import com.example.vescurus.domain.model.AnalysisResponse
import com.example.vescurus.domain.model.BoundingBox
import com.example.vescurus.domain.model.IngredientDetection
import com.example.vescurus.domain.repository.IngredientRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.util.Collections

/**
 * On-device YOLO-World v2 inference through ONNX Runtime.
 *
 * The exported model has a baked food vocabulary, so Android does not need
 * Gemini, a laptop, or a text-embedding model for CV. The same pipeline can
 * therefore recognize any class in FOOD_CLASSES, not just eggs.
 *
 * Model asset: app/src/main/assets/yolo_food.onnx
 */
class OnDeviceYoloRepositoryImpl(
    private val context: Context,
    private val modelFileName: String = "yolo_food.onnx"
) : IngredientRepository {

    private val tag = "OnDeviceYoloRepo"
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var isInitialized = false

    companion object {
        // Keep this vocabulary broad enough for V0 while remaining practical
        // for a single baked YOLO-World ONNX model.
        val FOOD_CLASSES = listOf(
            "egg", "tomato", "onion", "green chili", "banana", "chicken breast",
            "broccoli", "bread", "cheese", "apple", "potato", "garlic", "bell pepper",
            "salmon", "rice", "pasta", "mushroom", "avocado", "carrot", "butter",
            "milk", "flour", "spinach", "lemon", "lime", "cucumber", "beef", "pork",
            "shrimp", "paneer", "tofu", "corn", "strawberry", "grape", "orange",
            "olive oil", "black pepper", "salt", "turmeric", "chili powder", "yogurt",
            "cream", "beans", "peas", "cabbage", "cauliflower", "ginger", "coconut",
            "oats", "peanut", "cashew", "almond", "honey", "sugar", "flour tortilla"
        )
    }

    init {
        initOnnxModel()
    }

    private fun initOnnxModel() {
        try {
            val modelBytes = context.assets.open(modelFileName).use { it.readBytes() }
            env = OrtEnvironment.getEnvironment()
            session = env?.createSession(modelBytes, OrtSession.SessionOptions())
            isInitialized = true
            Log.d(tag, "Loaded on-device YOLO food model from assets/$modelFileName")
        } catch (e: Exception) {
            Log.w(tag, "Missing YOLO model asset: assets/$modelFileName", e)
            isInitialized = false
        }
    }

    override suspend fun analyzeIngredients(
        rawBitmap: Bitmap,
        scaledBitmap: Bitmap
    ): AnalysisResponse = withContext(Dispatchers.Default) {
        val localEnv = env
        val localSession = session
        if (!isInitialized || localEnv == null || localSession == null) {
            return@withContext AnalysisResponse("ondevice-no-model", emptyList(), 0f)
        }

        try {
            val inputBitmap = Bitmap.createScaledBitmap(scaledBitmap, 640, 640, true)
            val floatBuffer = bitmapToFloatBuffer(inputBitmap)
            val inputShape = longArrayOf(1, 3, 640, 640)

            OnnxTensor.createTensor(localEnv, floatBuffer, inputShape).use { inputTensor ->
                localSession.run(Collections.singletonMap("images", inputTensor)).use { results ->
                    val outputTensor = results.get(0) as? OnnxTensor
                        ?: return@withContext AnalysisResponse("ondevice-error", emptyList(), 0f)

                    outputTensor.use { tensor ->
                        val shape = tensor.info.shape
                        val output = FloatArray(tensor.floatBuffer.remaining())
                        tensor.floatBuffer.get(output)
                        val detections = parseYoloOutput(output, shape)

                        VescurusLogger.logInference(
                            rawBitmap,
                            scaledBitmap,
                            "ONNX YOLO found ${detections.size} food detections"
                        )

                        AnalysisResponse(
                            request_id = "ondevice-yolo-food",
                            detections = detections,
                            overall_confidence = detections.maxOfOrNull { it.confidence } ?: 0f
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "ONNX inference error", e)
            AnalysisResponse("ondevice-exception", emptyList(), 0f)
        }
    }

    private fun bitmapToFloatBuffer(bitmap: Bitmap): FloatBuffer {
        val pixels = IntArray(640 * 640)
        bitmap.getPixels(pixels, 0, 640, 0, 0, 640, 640)
        val plane = 640 * 640
        val buffer = FloatBuffer.allocate(plane * 3)

        for (i in 0 until plane) {
            buffer.put(i, ((pixels[i] shr 16) and 0xFF) / 255f)
            buffer.put(plane + i, ((pixels[i] shr 8) and 0xFF) / 255f)
            buffer.put(2 * plane + i, (pixels[i] and 0xFF) / 255f)
        }
        buffer.rewind()
        return buffer
    }

    private fun parseYoloOutput(output: FloatArray, shape: LongArray): List<IngredientDetection> {
        if (shape.size != 3) return emptyList()

        val rows: Int
        val anchors: Int
        if (shape[1] <= 256) {
            rows = shape[1].toInt()
            anchors = shape[2].toInt()
        } else {
            rows = shape[2].toInt()
            anchors = shape[1].toInt()
        }

        val numClasses = rows - 4
        if (numClasses < 1 || output.size < rows * anchors) return emptyList()

        // High recall is intentional for V0. UI/FSM decides what constitutes
        // a supported ingredient; CV should not silently discard weak targets.
        val confThreshold = 0.15f
        val iouThreshold = 0.45f
        val candidates = mutableListOf<RawDetection>()

        for (col in 0 until anchors) {
            val cx = output[col]
            val cy = output[anchors + col]
            val w = output[2 * anchors + col]
            val h = output[3 * anchors + col]

            var bestScore = 0f
            var bestClass = -1
            val classLimit = minOf(numClasses, FOOD_CLASSES.size)
            for (classId in 0 until classLimit) {
                val score = output[(4 + classId) * anchors + col]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = classId
                }
            }

            if (bestClass < 0 || bestScore < confThreshold) continue

            val xmin = ((cx - w / 2f) / 640f).coerceIn(0f, 1f)
            val ymin = ((cy - h / 2f) / 640f).coerceIn(0f, 1f)
            val xmax = ((cx + w / 2f) / 640f).coerceIn(0f, 1f)
            val ymax = ((cy + h / 2f) / 640f).coerceIn(0f, 1f)

            if (xmax > xmin + 0.02f && ymax > ymin + 0.02f) {
                candidates.add(
                    RawDetection(
                        label = FOOD_CLASSES[bestClass],
                        confidence = bestScore,
                        box = BoundingBox(ymin, xmin, ymax, xmax)
                    )
                )
            }
        }

        return applyClassAwareNms(candidates, iouThreshold).mapIndexed { index, detection ->
            IngredientDetection(
                id = "yolo-food-${index + 1}",
                label = detection.label,
                confidence = detection.confidence,
                box_2d = detection.box,
                alternatives = emptyList(),
                is_supported = true
            )
        }
    }

    private data class RawDetection(
        val label: String,
        val confidence: Float,
        val box: BoundingBox
    )

    private fun applyClassAwareNms(
        candidates: List<RawDetection>,
        iouThreshold: Float
    ): List<RawDetection> {
        val sorted = candidates.sortedByDescending { it.confidence }
        val selected = mutableListOf<RawDetection>()

        for (candidate in sorted) {
            // Different food classes may legitimately overlap (e.g. egg on bread),
            // so suppress only overlapping boxes of the same class.
            if (selected.none {
                    it.label == candidate.label && computeIou(candidate.box, it.box) > iouThreshold
                }) {
                selected.add(candidate)
                if (selected.size >= 10) break
            }
        }
        return selected
    }

    private fun computeIou(a: BoundingBox, b: BoundingBox): Float {
        val interXmin = maxOf(a.xmin, b.xmin)
        val interYmin = maxOf(a.ymin, b.ymin)
        val interXmax = minOf(a.xmax, b.xmax)
        val interYmax = minOf(a.ymax, b.ymax)
        val interArea = maxOf(0f, interXmax - interXmin) * maxOf(0f, interYmax - interYmin)
        val areaA = (a.xmax - a.xmin) * (a.ymax - a.ymin)
        val areaB = (b.xmax - b.xmin) * (b.ymax - b.ymin)
        val union = areaA + areaB - interArea
        return if (union > 0f) interArea / union else 0f
    }
}

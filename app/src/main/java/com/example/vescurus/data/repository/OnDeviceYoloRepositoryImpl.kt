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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.util.Collections
import kotlin.math.max
import kotlin.math.min

/** On-device YOLO-World v2 inference with a lazily downloaded model. */
class OnDeviceYoloRepositoryImpl(
    private val context: Context,
    private val modelFileName: String = MODEL_FILE
) : IngredientRepository {

    private val tag = "OnDeviceYoloRepo"
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var isInitialized = false

    companion object {
        const val MODEL_FILE = "yolo_food.onnx"
        private const val MODEL_URL =
            "https://github.com/KVS-Surya-Teja/VESCURUS_expo/releases/download/yolo-food-model/yolo_food.onnx"
        private const val MODEL_VERSION = 1
        private const val INPUT_SIZE = 640
        private const val CONF_THRESHOLD = 0.15f
        private const val IOU_THRESHOLD = 0.45f

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

    override suspend fun analyzeIngredients(
        rawBitmap: Bitmap,
        scaledBitmap: Bitmap
    ): AnalysisResponse = withContext(Dispatchers.Default) {
        if (!ensureModel()) {
            return@withContext AnalysisResponse("ondevice-no-model", emptyList(), 0f)
        }

        val localEnv = env ?: return@withContext AnalysisResponse("ondevice-error", emptyList(), 0f)
        val localSession = session ?: return@withContext AnalysisResponse("ondevice-error", emptyList(), 0f)

        try {
            val letterboxed = letterbox(scaledBitmap, INPUT_SIZE)
            val floatBuffer = bitmapToFloatBuffer(letterboxed.bitmap)
            val inputShape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())

            OnnxTensor.createTensor(localEnv, floatBuffer, inputShape).use { inputTensor ->
                localSession.run(Collections.singletonMap("images", inputTensor)).use { results ->
                    val outputTensor = results.get(0) as? OnnxTensor
                        ?: return@withContext AnalysisResponse("ondevice-error", emptyList(), 0f)
                    outputTensor.use { tensor ->
                        val shape = tensor.info.shape
                        val output = FloatArray(tensor.floatBuffer.remaining())
                        tensor.floatBuffer.get(output)
                        val detections = parseYoloOutput(output, shape, letterboxed)
                        VescurusLogger.logInference(rawBitmap, scaledBitmap,
                            "ONNX YOLO found ${detections.size} food detections")
                        AnalysisResponse("ondevice-yolo-food", detections,
                            detections.maxOfOrNull { it.confidence } ?: 0f)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "ONNX inference error", e)
            AnalysisResponse("ondevice-exception", emptyList(), 0f)
        }
    }

    private fun ensureModel(): Boolean {
        if (isInitialized && session != null && env != null) return true
        return try {
            val modelFile = File(context.filesDir, modelFileName)
            if (!modelFile.exists() || modelFile.length() < 1_000_000L) {
                downloadModel(modelFile)
            }
            env = OrtEnvironment.getEnvironment()
            session = env?.createSession(modelFile.readBytes(), OrtSession.SessionOptions())
            isInitialized = session != null
            Log.i(tag, "YOLO food model ready: ${modelFile.absolutePath}")
            isInitialized
        } catch (e: Exception) {
            Log.e(tag, "Unable to prepare YOLO model", e)
            false
        }
    }

    private fun downloadModel(target: File) {
        Log.i(tag, "Downloading YOLO food model from GitHub Release")
        val temp = File(target.parentFile, "${target.name}.download")
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Model download HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { input ->
                temp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            if (temp.length() < 1_000_000L) throw IllegalStateException("Downloaded model is unexpectedly small")
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) throw IllegalStateException("Could not install downloaded model")
            context.getSharedPreferences("yolo_model", Context.MODE_PRIVATE)
                .edit().putInt("version", MODEL_VERSION).apply()
        } finally {
            connection.disconnect()
            if (temp.exists() && temp.length() < 1_000_000L) temp.delete()
        }
    }

    private data class Letterboxed(
        val bitmap: Bitmap,
        val scale: Float,
        val padX: Float,
        val padY: Float,
        val sourceWidth: Int,
        val sourceHeight: Int
    )

    private fun letterbox(source: Bitmap, size: Int): Letterboxed {
        val scale = min(size.toFloat() / source.width, size.toFloat() / source.height)
        val newW = max(1, (source.width * scale).toInt())
        val newH = max(1, (source.height * scale).toInt())
        val resized = Bitmap.createScaledBitmap(source, newW, newH, true)
        val canvasBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(canvasBitmap)
        canvas.drawColor(android.graphics.Color.rgb(114, 114, 114))
        val padX = (size - newW) / 2f
        val padY = (size - newH) / 2f
        canvas.drawBitmap(resized, padX, padY, null)
        if (resized !== source) resized.recycle()
        return Letterboxed(canvasBitmap, scale, padX, padY, source.width, source.height)
    }

    private fun bitmapToFloatBuffer(bitmap: Bitmap): FloatBuffer {
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        val plane = INPUT_SIZE * INPUT_SIZE
        val buffer = FloatBuffer.allocate(plane * 3)
        for (i in 0 until plane) {
            buffer.put(i, ((pixels[i] shr 16) and 0xFF) / 255f)
            buffer.put(plane + i, ((pixels[i] shr 8) and 0xFF) / 255f)
            buffer.put(2 * plane + i, (pixels[i] and 0xFF) / 255f)
        }
        buffer.rewind()
        return buffer
    }

    private fun parseYoloOutput(
        output: FloatArray,
        shape: LongArray,
        transform: Letterboxed
    ): List<IngredientDetection> {
        if (shape.size != 3) return emptyList()
        val rows: Int
        val anchors: Int
        if (shape[1] <= 256) {
            rows = shape[1].toInt(); anchors = shape[2].toInt()
        } else {
            rows = shape[2].toInt(); anchors = shape[1].toInt()
        }
        val numClasses = rows - 4
        if (numClasses < 1 || output.size < rows * anchors) return emptyList()

        val candidates = mutableListOf<RawDetection>()
        val classLimit = minOf(numClasses, FOOD_CLASSES.size)
        for (col in 0 until anchors) {
            val cx = output[col]
            val cy = output[anchors + col]
            val w = output[2 * anchors + col]
            val h = output[3 * anchors + col]
            var bestScore = 0f
            var bestClass = -1
            for (classId in 0 until classLimit) {
                val score = output[(4 + classId) * anchors + col]
                if (score > bestScore) { bestScore = score; bestClass = classId }
            }
            if (bestClass < 0 || bestScore < CONF_THRESHOLD) continue

            val modelLeft = cx - w / 2f
            val modelTop = cy - h / 2f
            val modelRight = cx + w / 2f
            val modelBottom = cy + h / 2f
            val xmin = ((modelLeft - transform.padX) / transform.scale / transform.sourceWidth).coerceIn(0f, 1f)
            val ymin = ((modelTop - transform.padY) / transform.scale / transform.sourceHeight).coerceIn(0f, 1f)
            val xmax = ((modelRight - transform.padX) / transform.scale / transform.sourceWidth).coerceIn(0f, 1f)
            val ymax = ((modelBottom - transform.padY) / transform.scale / transform.sourceHeight).coerceIn(0f, 1f)
            if (xmax > xmin + 0.02f && ymax > ymin + 0.02f) {
                candidates += RawDetection(FOOD_CLASSES[bestClass], bestScore,
                    BoundingBox(ymin, xmin, ymax, xmax))
            }
        }
        return applyClassAwareNms(candidates).mapIndexed { index, d ->
            IngredientDetection("yolo-food-${index + 1}", d.label, d.confidence,
                d.box, emptyList(), true)
        }
    }

    private data class RawDetection(val label: String, val confidence: Float, val box: BoundingBox)

    private fun applyClassAwareNms(candidates: List<RawDetection>): List<RawDetection> {
        val selected = mutableListOf<RawDetection>()
        for (candidate in candidates.sortedByDescending { it.confidence }) {
            if (selected.none { it.label == candidate.label && computeIou(candidate.box, it.box) > IOU_THRESHOLD }) {
                selected += candidate
                if (selected.size >= 10) break
            }
        }
        return selected
    }

    private fun computeIou(a: BoundingBox, b: BoundingBox): Float {
        val ix1 = max(a.xmin, b.xmin)
        val iy1 = max(a.ymin, b.ymin)
        val ix2 = min(a.xmax, b.xmax)
        val iy2 = min(a.ymax, b.ymax)
        val inter = max(0f, ix2 - ix1) * max(0f, iy2 - iy1)
        val union = (a.xmax - a.xmin) * (a.ymax - a.ymin) +
            (b.xmax - b.xmin) * (b.ymax - b.ymin) - inter
        return if (union > 0f) inter / union else 0f
    }
}

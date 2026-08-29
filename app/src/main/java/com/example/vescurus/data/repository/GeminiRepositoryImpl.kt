package com.example.vescurus.data.repository

import android.graphics.Bitmap
import android.util.Log
import com.example.vescurus.BuildConfig
import com.example.vescurus.data.remote.GeminiClient
import com.example.vescurus.data.remote.Prompts
import com.example.vescurus.domain.model.AnalysisResponse
import com.example.vescurus.domain.model.BoundingBox
import com.example.vescurus.domain.model.DetectionCandidate
import com.example.vescurus.domain.model.DetectionMode
import com.example.vescurus.domain.model.IngredientDetection
import com.example.vescurus.domain.repository.IngredientRepository
import com.example.vescurus.model.canonicalizeIngredientLabel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GeminiRepositoryImpl(private val client: GeminiClient) : IngredientRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun analyzeIngredients(
        rawBitmap: Bitmap,
        scaledBitmap: Bitmap,
        mode: DetectionMode
    ): AnalysisResponse = withContext(Dispatchers.IO) {
        val prompt = when (mode) {
            DetectionMode.EGG_ONLY -> Prompts.DETECTION_PROMPT
            DetectionMode.GENERAL_INGREDIENTS -> Prompts.GENERAL_INGREDIENT_PROMPT
        }
        val promptVersion = when (mode) {
            DetectionMode.EGG_ONLY -> Prompts.DETECTION_PROMPT_VERSION
            DetectionMode.GENERAL_INGREDIENTS -> Prompts.GENERAL_INGREDIENT_PROMPT_VERSION
        }

        try {
            Log.d(TAG, "Sending prompt=$promptVersion mode=$mode image=${scaledBitmap.width}x${scaledBitmap.height}")

            val response = client.detectorModel.generateContent(
                content {
                    image(scaledBitmap)
                    text(prompt)
                }
            )

            val text = response.text?.trim()
                ?: throw IllegalStateException("Empty response from Gemini")
            val cleanJson = extractJson(text)

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Gemini raw: $cleanJson")
            }

            val parsed = parseFlexibleResponse(cleanJson)
            val sanitized = sanitizeDetectionResponse(parsed, mode)

            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "Parsed detections=${sanitized.detections.size}: " +
                        sanitized.detections.joinToString {
                            "${it.label}=${it.confidence} box=${it.box_2d} supported=${it.is_supported}"
                        }
                )
            }

            sanitized
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Analysis failed", e)
            throw e
        }
    }

    /**
     * Accepts both the historical object-shaped box (`{"ymin":…,"xmin":…}`)
     * and Gemini's documented array-shaped `[ymin, xmin, ymax, xmax]` box.
     * Missing/malformed entries are dropped, never crashed.
     */
    internal fun parseFlexibleResponse(text: String): AnalysisResponse {
        // Try strict deserialization first — cheap fast path.
        try {
            return json.decodeFromString<AnalysisResponse>(text)
        } catch (_: Exception) {
            // Fall through to flexible parser below.
        }

        val root = json.parseToJsonElement(text).jsonObject
        val requestId = root["request_id"]?.jsonPrimitive?.content ?: Prompts.DETECTION_PROMPT_VERSION
        val overallConfidence = root["overall_confidence"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
        val detectionsJson = root["detections"]?.jsonArray ?: JsonArray(emptyList())

        // Build with explicit index so the fallback id is stable (the previous
        // code read `detections.size` from inside the mapNotNull, which
        // captured the outer field before it was assigned — every fallback id
        // collapsed to "egg-1").
        val parsedDetections = mutableListOf<IngredientDetection>()
        detectionsJson.forEachIndexed { index, element ->
            val det = element.parseDetection(index + 1) ?: return@forEachIndexed
            parsedDetections += det
        }

        return AnalysisResponse(
            request_id = requestId,
            detections = parsedDetections,
            overall_confidence = overallConfidence
        )
    }

    private fun JsonElement.parseDetection(seq: Int): IngredientDetection? {
        return try {
            val obj = this.jsonObject
            val label = obj["label"]?.jsonPrimitive?.content ?: return null
            val confidence = obj["confidence"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
            val supported = obj["is_supported"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
            val boxElement = obj["box_2d"] ?: return null

            val box = when (boxElement) {
                is JsonArray -> {
                    if (boxElement.size != 4) return null
                    BoundingBox(
                        ymin = boxElement[0].jsonPrimitive.contentOrNull?.toFloatOrNull() ?: return null,
                        xmin = boxElement[1].jsonPrimitive.contentOrNull?.toFloatOrNull() ?: return null,
                        ymax = boxElement[2].jsonPrimitive.contentOrNull?.toFloatOrNull() ?: return null,
                        xmax = boxElement[3].jsonPrimitive.contentOrNull?.toFloatOrNull() ?: return null
                    )
                }
                is JsonObject -> BoundingBox(
                    ymin = boxElement["ymin"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: return null,
                    xmin = boxElement["xmin"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: return null,
                    ymax = boxElement["ymax"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: return null,
                    xmax = boxElement["xmax"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: return null
                )
                else -> return null
            }

            IngredientDetection(
                id = obj["id"]?.jsonPrimitive?.content ?: "det-$seq",
                label = label,
                confidence = confidence,
                box_2d = box,
                alternatives = emptyList<DetectionCandidate>(),
                is_supported = supported
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Clamps, normalizes coordinates, and filters by [mode]:
     * - EGG_ONLY: keep only detections whose canonical label == "egg".
     * - GENERAL_INGREDIENTS: keep detections whose label maps to any
     *   canonical vocabulary entry (see `canonicalizeIngredientLabel`).
     */
    internal fun sanitizeDetectionResponse(
        response: AnalysisResponse,
        mode: DetectionMode = DetectionMode.EGG_ONLY
    ): AnalysisResponse {
        val sanitized = response.detections.mapNotNull { detection ->
            val canonical = canonicalizeIngredientLabel(detection.label) ?: return@mapNotNull null

            when (mode) {
                DetectionMode.EGG_ONLY -> if (canonical != "egg") return@mapNotNull null
                DetectionMode.GENERAL_INGREDIENTS -> if (canonical == "Unsupported object") return@mapNotNull null
            }

            val box = detection.box_2d
            val values = listOf(box.ymin, box.xmin, box.ymax, box.xmax)
            if (detection.confidence < 0f || detection.confidence > 1f) return@mapNotNull null
            if (values.any { it.isNaN() || it.isInfinite() }) return@mapNotNull null

            // Accept either 0..1 (legacy) or 0..1000 (Gemini object detection).
            val normalized = if (values.any { it > 1.1f }) {
                BoundingBox(
                    ymin = box.ymin / 1000f,
                    xmin = box.xmin / 1000f,
                    ymax = box.ymax / 1000f,
                    xmax = box.xmax / 1000f
                )
            } else {
                box
            }

            if (normalized.ymin < 0f || normalized.xmin < 0f ||
                normalized.ymax > 1f || normalized.xmax > 1f ||
                normalized.ymax <= normalized.ymin || normalized.xmax <= normalized.xmin
            ) return@mapNotNull null

            detection.copy(
                label = canonical,
                box_2d = normalized,
                is_supported = true
            )
        }

        return response.copy(
            detections = sanitized,
            overall_confidence = if (sanitized.isNotEmpty()) sanitized.maxOf { it.confidence } else 0f
        )
    }

    internal fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end <= start) {
            throw IllegalStateException("Gemini did not return a JSON object: ${text.take(200)}")
        }
        return text.substring(start, end + 1)
    }

    private companion object {
        const val TAG = "GeminiRepo"
    }
}

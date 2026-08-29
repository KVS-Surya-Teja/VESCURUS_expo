package com.example.vescurus.data.repository

import android.graphics.Bitmap
import android.util.Log
import com.example.vescurus.GeminiService
import com.example.vescurus.debug.VescurusLogger
import com.example.vescurus.domain.model.AnalysisResponse
import com.example.vescurus.domain.model.BoundingBox
import com.example.vescurus.domain.model.DetectionCandidate
import com.example.vescurus.domain.model.IngredientDetection
import com.example.vescurus.domain.repository.IngredientRepository
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GeminiRepositoryImpl : IngredientRepository {
    private val tag = "GeminiRepo"
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * V0: prioritize detecting the intended egg target in the controlled Expo
     * scene. A missed egg is much more damaging to the demo than an occasional
     * false positive on an egg-like object.
     */
    private val prompt = """
        You are VESCURUS's live camera detector for a controlled cooking demonstration.

        PRIMARY TASK:
        Find the main egg-like target visible in the image and localize it with a tight 2D bounding box.

        HIGH-RECALL DEMO MODE:
        If a prominent white, off-white, cream, lightly speckled, dirty, marked,
        oval, or egg-shaped target is being deliberately presented to the camera,
        identify it as "egg". The intended egg may be held in a hand, resting on
        a tawa, resting on a table, partly covered by fingers, viewed from another
        angle, moving slightly, or mildly motion-blurred.

        Do not overthink edge cases. In this controlled demonstration, it is better
        to detect the intended egg-like target than to miss it.

        Ignore the hand itself, fingers, tawa, pan, table, utensils, phone, camera,
        steam, smoke, shadows, reflections, and unrelated background objects.
        Only return the intended egg target.

        If the intended egg-like target is visible, return ONE detection labeled
        exactly "egg". Give a high confidence when the target is reasonably clear.
        If no plausible egg-like target is visible at all, return an empty list.

        BOUNDING BOX:
        box_2d MUST be [ymin, xmin, ymax, xmax] as four integers from 0 to 1000,
        measured relative to the image supplied to you. Make the box tightly enclose
        the visible egg-like target.

        RETURN ONLY JSON:
        {
          "request_id": "v0",
          "detections": [
            {
              "id": "egg-1",
              "label": "egg",
              "confidence": 0.96,
              "box_2d": [250, 300, 700, 650],
              "alternatives": [],
              "is_supported": true
            }
          ],
          "overall_confidence": 0.96
        }

        If no plausible target exists:
        {
          "request_id": "v0",
          "detections": [],
          "overall_confidence": 0.0
        }

        No markdown. No explanation. JSON only.
    """.trimIndent()

    override suspend fun analyzeIngredients(
        rawBitmap: Bitmap,
        scaledBitmap: Bitmap
    ): AnalysisResponse = withContext(Dispatchers.IO) {
        try {
            Log.d(tag, "Sending V0 egg-detection frame: ${scaledBitmap.width}x${scaledBitmap.height}")

            val response = GeminiService.model.generateContent(
                content {
                    image(scaledBitmap)
                    text(prompt)
                }
            )

            val text = response.text?.trim()
                ?: throw IllegalStateException("Empty response from Gemini")
            val cleanJson = extractJson(text)

            Log.d(tag, "Gemini raw detection response: $cleanJson")
            VescurusLogger.logInference(rawBitmap, scaledBitmap, cleanJson)

            val parsed = parseFlexibleResponse(cleanJson)
            val sanitized = sanitizeDetectionResponse(parsed)

            Log.d(
                tag,
                "Parsed detections=${sanitized.detections.size}: " +
                    sanitized.detections.joinToString { detection ->
                        "${detection.label}=${detection.confidence} box=${detection.box_2d} supported=${detection.is_supported}"
                    }
            )

            sanitized
        } catch (e: Exception) {
            Log.e(tag, "Analysis failed", e)
            throw e
        }
    }

    /**
     * Accept both our original object-shaped box and Gemini's documented
     * array-shaped [ymin, xmin, ymax, xmax] box format.
     */
    private fun parseFlexibleResponse(text: String): AnalysisResponse {
        try {
            return json.decodeFromString<AnalysisResponse>(text)
        } catch (_: Exception) {
            // Fall through to the flexible parser.
        }

        val root = json.parseToJsonElement(text).jsonObject
        val requestId = root["request_id"]?.jsonPrimitive?.content ?: "v0"
        val overallConfidence = root["overall_confidence"]?.jsonPrimitive?.floatOrNull ?: 0f
        val detectionsJson = root["detections"]?.jsonArray ?: JsonArray(emptyList())

        val detections = detectionsJson.mapNotNull { element ->
            try {
                val obj = element.jsonObject
                val label = obj["label"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val confidence = obj["confidence"]?.jsonPrimitive?.floatOrNull ?: 0f
                val supported = obj["is_supported"]?.jsonPrimitive?.booleanOrNull ?: true
                val boxElement = obj["box_2d"] ?: return@mapNotNull null

                val box = when (boxElement) {
                    is JsonArray -> {
                        if (boxElement.size != 4) return@mapNotNull null
                        BoundingBox(
                            ymin = boxElement[0].jsonPrimitive.float,
                            xmin = boxElement[1].jsonPrimitive.float,
                            ymax = boxElement[2].jsonPrimitive.float,
                            xmax = boxElement[3].jsonPrimitive.float
                        )
                    }
                    is JsonObject -> {
                        BoundingBox(
                            ymin = boxElement["ymin"]?.jsonPrimitive?.floatOrNull ?: return@mapNotNull null,
                            xmin = boxElement["xmin"]?.jsonPrimitive?.floatOrNull ?: return@mapNotNull null,
                            ymax = boxElement["ymax"]?.jsonPrimitive?.floatOrNull ?: return@mapNotNull null,
                            xmax = boxElement["xmax"]?.jsonPrimitive?.floatOrNull ?: return@mapNotNull null
                        )
                    }
                    else -> return@mapNotNull null
                }

                IngredientDetection(
                    id = obj["id"]?.jsonPrimitive?.content ?: "egg-${detections.size + 1}",
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

        return AnalysisResponse(
            request_id = requestId,
            detections = detections,
            overall_confidence = overallConfidence
        )
    }

    private fun sanitizeDetectionResponse(response: AnalysisResponse): AnalysisResponse {
        val sanitized = response.detections.mapNotNull { detection ->
            val label = detection.label.trim().lowercase()
            val box = detection.box_2d
            val values = listOf(box.ymin, box.xmin, box.ymax, box.xmax)
            if (label != "egg") return@mapNotNull null
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
                label = "egg",
                box_2d = normalized,
                is_supported = true
            )
        }

        return response.copy(
            detections = sanitized,
            overall_confidence = if (sanitized.isNotEmpty()) sanitized.maxOf { it.confidence } else 0f
        )
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end <= start) {
            throw IllegalStateException("Gemini did not return a JSON object: $text")
        }
        return text.substring(start, end + 1)
    }
}

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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
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
     * Universal Open-World Food & Ingredient Vision Engine.
     * Detects any visible food item, raw produce, protein, grain, dairy, or spice.
     */
    private val prompt = """
        You are the universal open-world food, ingredient, and dish detector for VESCURUS.

        PRIMARY TASK:
        Identify ALL visible food items, raw ingredients, spices, vegetables, fruits, proteins, dairy, grains, or prepared dishes in the image.

        HIGH-RECALL ACCURACY RULES:
        - If an egg (raw in shell, boiled, or peeled), tomato, onion, chili, bread, cheese, fruit, vegetable, or protein is visible or presented to the camera, detect and localize it.
        - Treat white, off-white, or oval food items presented in the camera view as "egg".

        Rules:
        1. For each visible food item/ingredient, assign a tight 2D bounding box box_2d [ymin, xmin, ymax, xmax] as 4 integers from 0 to 1000.
        2. Label the item with its standard common English name (e.g. "egg", "tomato", "chicken breast", "broccoli", "avocado", "cheese", "rice", "bread", "salmon", "onion", "apple").
        3. Assign a confidence score between 0.0 and 1.0.
        4. Ignore cookware (pans, tawa), utensils, hands, fingers, phones, cables, and background furniture.

        RETURN ONLY VALID JSON:
        {
          "request_id": "open-world-v1",
          "detections": [
            {
              "id": "food-1",
              "label": "egg",
              "confidence": 0.95,
              "box_2d": [250, 300, 700, 650],
              "alternatives": [],
              "is_supported": true
            }
          ],
          "overall_confidence": 0.95
        }

        If no food items or ingredients are visible, return:
        {
          "request_id": "open-world-v1",
          "detections": [],
          "overall_confidence": 0.0
        }

        No markdown formatting. JSON only.
    """.trimIndent()

    override suspend fun analyzeIngredients(
        rawBitmap: Bitmap,
        scaledBitmap: Bitmap
    ): AnalysisResponse = withContext(Dispatchers.IO) {
        try {
            Log.d(tag, "Sending open-world vision frame to Gemini: ${scaledBitmap.width}x${scaledBitmap.height}")

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
                "Parsed open-world detections=${sanitized.detections.size}: " +
                    sanitized.detections.joinToString { detection ->
                        "${detection.label}=${detection.confidence} box=${detection.box_2d}"
                    }
            )

            sanitized
        } catch (e: Exception) {
            Log.e(tag, "Analysis failed: ${e.message}", e)
            throw e
        }
    }

    private fun parseFlexibleResponse(text: String): AnalysisResponse {
        try {
            return json.decodeFromString<AnalysisResponse>(text)
        } catch (_: Exception) {
            // Fall through to the flexible parser.
        }

        val root = json.parseToJsonElement(text).jsonObject
        val requestId = root["request_id"]?.jsonPrimitive?.content ?: "open-world-v1"
        val overallConfidence = root["overall_confidence"]?.jsonPrimitive?.floatOrNull ?: 0f
        val detectionsJson = root["detections"]?.jsonArray ?: JsonArray(emptyList())

        val detections = detectionsJson.mapIndexedNotNull { index, element ->
            try {
                val obj = element.jsonObject
                val label = obj["label"]?.jsonPrimitive?.content ?: return@mapIndexedNotNull null
                val confidence = obj["confidence"]?.jsonPrimitive?.floatOrNull ?: 0f
                val supported = obj["is_supported"]?.jsonPrimitive?.booleanOrNull ?: true
                val boxElement = obj["box_2d"] ?: return@mapIndexedNotNull null

                val box = when (boxElement) {
                    is JsonArray -> {
                        if (boxElement.size != 4) return@mapIndexedNotNull null
                        BoundingBox(
                            ymin = boxElement[0].jsonPrimitive.float,
                            xmin = boxElement[1].jsonPrimitive.float,
                            ymax = boxElement[2].jsonPrimitive.float,
                            xmax = boxElement[3].jsonPrimitive.float
                        )
                    }
                    is JsonObject -> {
                        BoundingBox(
                            ymin = boxElement["ymin"]?.jsonPrimitive?.floatOrNull ?: return@mapIndexedNotNull null,
                            xmin = boxElement["xmin"]?.jsonPrimitive?.floatOrNull ?: return@mapIndexedNotNull null,
                            ymax = boxElement["ymax"]?.jsonPrimitive?.floatOrNull ?: return@mapIndexedNotNull null,
                            xmax = boxElement["xmax"]?.jsonPrimitive?.floatOrNull ?: return@mapIndexedNotNull null
                        )
                    }
                    else -> return@mapIndexedNotNull null
                }

                IngredientDetection(
                    id = obj["id"]?.jsonPrimitive?.content ?: "food-${index + 1}",
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
            val rawLabel = detection.label.trim()
            if (rawLabel.isEmpty()) return@mapNotNull null

            val box = detection.box_2d
            val values = listOf(box.ymin, box.xmin, box.ymax, box.xmax)
            if (detection.confidence < 0.15f || detection.confidence > 1f) return@mapNotNull null
            if (values.any { it.isNaN() || it.isInfinite() }) return@mapNotNull null

            // Accept either 0..1 or 0..1000 coordinate format.
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
                label = rawLabel,
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

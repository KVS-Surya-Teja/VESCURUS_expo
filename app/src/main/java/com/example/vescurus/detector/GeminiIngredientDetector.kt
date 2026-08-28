package com.example.vescurus.detector

import android.graphics.Bitmap
import android.util.Log
import com.example.vescurus.GeminiService
import com.example.vescurus.model.DetectionResponse
import com.example.vescurus.model.DetectionResult
import com.example.vescurus.model.BoundingBox
import com.google.ai.client.generativeai.type.content
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GeminiIngredientDetector : IngredientDetector {
    private val TAG = "GeminiDetector"
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
    }

    private val prompt = """
        You are the visual ingredient recognition engine of VESCURUS.
        Identify objects and assign them strictly to POSITIVE, NEUTRAL, or NEGATIVE categories.

        POSITIVE CLASSES (Cooking Ingredients):
        - egg (raw, boiled, or peeled), onion, green chili, tomato, banana, salt, black pepper, oil, butter, milk, turmeric powder, red chilli powder.

        NEUTRAL CLASSES (EXPLICITLY IGNORE - DO NOT DETECT):
        - Cookware: tawa, pan, stove burner.
        - Utensils: spatula, spoon, fork, knife, bowl, plate, lid.
        - Other: human hands, fingers, paper towels, steam, smoke.

        NEGATIVE / HAZARDS (Detected as "Unsupported object"):
        - smartphone, pen, plastic wrapper, cleaner bottle, cable, watch, paper notebook.

        Rules:
        1. For valid ingredients, return label, confidence (0.0-1.0), recipe_class (1: Omelette, 2: Scramble, 3: Sunny-Side, 4: Pancake, 0: Other), supported=true, and box_2d {top, left, bottom, right} scaled 0 to 1000.
        2. Even if an egg is peeled or boiled, identify it as "egg".
        3. If an object is from the NEGATIVE/HAZARDS category, return label="Unsupported object", confidence, recipe_class=0, supported=false, and box_2d.
        4. Return ONLY a valid JSON object. No other text.

        Format:
        {"detections": [{"label": "egg", "confidence": 0.95, "recipe_class": 2, "box_2d": {"top": 250, "left": 300, "bottom": 650, "right": 700}, "supported": true}]}
        If no ingredients or hazards are present, return {"detections": []}.
    """.trimIndent()

    override suspend fun detect(bitmap: Bitmap): List<DetectionResult> = withContext(Dispatchers.IO) {
        try {
            Log.d("CV_FLOW", "Gemini: Sending request...")
            val response = GeminiService.model.generateContent(
                content {
                    image(bitmap)
                    text(prompt)
                }
            )

            val responseText = response.text?.trim() ?: ""
            Log.d("CV_FLOW", "Gemini: Raw Response -> $responseText")

            val cleanJson = extractCleanJson(responseText)
            if (cleanJson.isEmpty()) {
                Log.w("CV_FLOW", "Gemini: No valid JSON found")
                return@withContext emptyList()
            }

            val parsed = json.decodeFromString<DetectionResponse>(cleanJson)

            val results = parsed.detections.mapNotNull { det ->
                val normalizedBox = normalizeBox(det.box_2d)
                // Threshold 0.15 for better detection during dynamic cooking
                if (det.confidence >= 0.15f && normalizedBox != null) {
                    det.copy(box_2d = normalizedBox)
                } else null
            }
            Log.d("CV_FLOW", "Gemini: Successfully parsed ${results.size} detections")
            results
        } catch (e: Exception) {
            Log.e("CV_FLOW", "Gemini: Inference error: ${e.message}")
            emptyList()
        }
    }

    private fun extractCleanJson(raw: String): String {
        val start = raw.indexOf("{")
        val end = raw.lastIndexOf("}")
        return if (start != -1 && end != -1 && end > start) {
            raw.substring(start, end + 1)
        } else ""
    }

    private fun normalizeBox(box: BoundingBox): BoundingBox? {
        var t = box.top
        var l = box.left
        var b = box.bottom
        var r = box.right

        // Auto-detect coordinate range (0..1 vs 0..1000)
        if (t > 1.1f || l > 1.1f || b > 1.1f || r > 1.1f) {
            t /= 1000f
            l /= 1000f
            b /= 1000f
            r /= 1000f
        }

        return if (t in 0.0f..1.0f && l in 0.0f..1.0f && b in 0.0f..1.0f && r in 0.0f..1.0f && b > t && r > l) {
            BoundingBox(top = t, left = l, bottom = b, right = r)
        } else {
            Log.w("CV_FLOW", "Gemini: Filtered malformed box: t=$t, l=$l, b=$b, r=$r")
            null
        }
    }
}

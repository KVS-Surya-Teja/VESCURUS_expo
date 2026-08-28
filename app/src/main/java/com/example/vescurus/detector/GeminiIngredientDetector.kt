package com.example.vescurus.detector

import android.graphics.Bitmap
import android.util.Log
import com.example.vescurus.GeminiService
import com.example.vescurus.model.BoundingBox
import com.example.vescurus.model.DetectionResponse
import com.example.vescurus.model.DetectionResult
import com.example.vescurus.model.SUPPORTED_INGREDIENT_LABELS
import com.example.vescurus.model.canonicalizeIngredientLabel
import com.example.vescurus.model.deriveRecipeClass
import com.example.vescurus.model.normalizeDetectionBox
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class GeminiIngredientDetector : IngredientDetector {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val primaryPrompt = """
        You are the visual ingredient recognition engine of VESCURUS.
        Detect visible cooking ingredients even in cluttered home-kitchen lighting.
        Whole raw eggs in shell count as "egg". Multiple eggs are common.

        POSITIVE CLASSES:
        - egg (raw in shell, boiled, or peeled), onion, green chili, tomato, banana, flour, salt, black pepper, oil, butter, milk, turmeric powder, red chilli powder.

        IGNORE:
        - cookware, utensils, hands, fingers, steam, smoke, patterned backgrounds.

        NEGATIVE / HAZARDS:
        - smartphone, pen, plastic wrapper, cleaner bottle, cable, watch, paper notebook.

        Rules:
        1. Return ONLY JSON.
        2. For valid ingredients, return label, confidence (0.0-1.0), supported=true, recipe_class=0, and an approximate box_2d scaled 0 to 1000.
        3. Approximate boxes are acceptable and preferred over returning nothing.
        4. If whole eggs are clearly visible, do not return an empty list.
        5. If a hazard is visible, return label="Unsupported object", supported=false, recipe_class=0, and box_2d.

        Format:
        {"detections":[{"label":"egg","confidence":0.95,"recipe_class":0,"box_2d":{"top":250,"left":300,"bottom":650,"right":700},"supported":true}]}
        If no supported ingredients or hazards are visible, return {"detections":[]}.
    """.trimIndent()

    private val fallbackPrompt = """
        You are doing ingredient presence classification for a cooking app.
        Look only for these supported ingredients:
        egg, onion, green chili, tomato, banana, flour, salt, black pepper, oil, butter, milk, turmeric powder, red chilli powder.

        Whole raw eggs in shell count as egg.
        Ignore background patterns, utensils, cookware, hands, and lighting noise.

        Return ONLY JSON:
        {"ingredients":[{"label":"egg","confidence":0.95,"supported":true}]}

        If any whole eggs are clearly visible, include egg.
        If none of the supported ingredients are visible, return {"ingredients":[]}.
    """.trimIndent()

    @Serializable
    private data class IngredientOnlyResponse(
        val ingredients: List<IngredientOnlyDetection> = emptyList()
    )

    @Serializable
    private data class IngredientOnlyDetection(
        val label: String,
        val confidence: Float = 0f,
        val supported: Boolean = true
    )

    override suspend fun detect(bitmap: Bitmap): List<DetectionResult> = withContext(Dispatchers.IO) {
        try {
            Log.d("CV_FLOW", "Gemini: Sending request...")
            val primaryText = generateJsonResponse(bitmap, primaryPrompt)
            Log.d("CV_FLOW", "Gemini: Raw Response -> $primaryText")

            val primaryDetections = parsePrimaryDetections(primaryText)
            if (primaryDetections.isNotEmpty()) {
                Log.d("CV_FLOW", "Gemini: Parsed ${primaryDetections.size} primary detections")
                return@withContext primaryDetections
            }

            val fallbackText = generateJsonResponse(bitmap, fallbackPrompt)
            Log.d("CV_FLOW", "Gemini: Fallback Response -> $fallbackText")
            val fallbackDetections = parseFallbackDetections(fallbackText)
            Log.d("CV_FLOW", "Gemini: Parsed ${fallbackDetections.size} fallback detections")
            fallbackDetections
        } catch (e: Exception) {
            Log.e("CV_FLOW", "Gemini: Inference error: ${e.message}")
            emptyList()
        }
    }

    private suspend fun generateJsonResponse(bitmap: Bitmap, prompt: String): String {
        val response = GeminiService.model.generateContent(
            content {
                image(bitmap)
                text(prompt)
            }
        )
        return response.text?.trim().orEmpty()
    }

    private fun parsePrimaryDetections(responseText: String): List<DetectionResult> {
        val cleanJson = extractCleanJson(responseText)
        if (cleanJson.isEmpty()) {
            Log.w("CV_FLOW", "Gemini: No valid JSON found in primary response")
            return emptyList()
        }

        val parsed = json.decodeFromString<DetectionResponse>(cleanJson)
        if (parsed.detections.isEmpty()) {
            return emptyList()
        }

        val canonicalLabels = parsed.detections.mapNotNull { canonicalizeIngredientLabel(it.label) }
        val derivedRecipeClass = deriveRecipeClass(canonicalLabels)

        return parsed.detections.mapIndexedNotNull { index, detection ->
            val canonicalLabel = canonicalizeIngredientLabel(detection.label) ?: return@mapIndexedNotNull null
            val confidence = detection.confidence.coerceIn(0f, 1f)
            if (confidence < 0.15f) return@mapIndexedNotNull null

            val normalizedBox = normalizeDetectionBox(detection.box_2d)
                ?: syntheticBox(index, parsed.detections.size)

            DetectionResult(
                label = canonicalLabel,
                confidence = confidence,
                recipe_class = if (detection.supported && canonicalLabel in SUPPORTED_INGREDIENT_LABELS) derivedRecipeClass else 0,
                box_2d = normalizedBox,
                supported = detection.supported && canonicalLabel in SUPPORTED_INGREDIENT_LABELS
            )
        }
    }

    private fun parseFallbackDetections(responseText: String): List<DetectionResult> {
        val cleanJson = extractCleanJson(responseText)
        if (cleanJson.isEmpty()) {
            Log.w("CV_FLOW", "Gemini: No valid JSON found in fallback response")
            return emptyList()
        }

        val parsed = json.decodeFromString<IngredientOnlyResponse>(cleanJson)
        if (parsed.ingredients.isEmpty()) {
            return emptyList()
        }

        val canonicalLabels = parsed.ingredients.mapNotNull { canonicalizeIngredientLabel(it.label) }
        val derivedRecipeClass = deriveRecipeClass(canonicalLabels)

        return parsed.ingredients.mapIndexedNotNull { index, ingredient ->
            val canonicalLabel = canonicalizeIngredientLabel(ingredient.label) ?: return@mapIndexedNotNull null
            if (!ingredient.supported || canonicalLabel !in SUPPORTED_INGREDIENT_LABELS) return@mapIndexedNotNull null

            DetectionResult(
                label = canonicalLabel,
                confidence = ingredient.confidence.coerceIn(0.15f, 1f),
                recipe_class = derivedRecipeClass,
                box_2d = syntheticBox(index, parsed.ingredients.size),
                supported = true
            )
        }
    }

    private fun extractCleanJson(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start != -1 && end != -1 && end > start) {
            raw.substring(start, end + 1)
        } else {
            ""
        }
    }

    private fun syntheticBox(index: Int, total: Int): BoundingBox {
        val centers = when (total) {
            1 -> listOf(0.5f to 0.56f)
            2 -> listOf(0.4f to 0.56f, 0.62f to 0.56f)
            else -> listOf(0.32f to 0.56f, 0.5f to 0.56f, 0.68f to 0.56f)
        }
        val (centerX, centerY) = centers.getOrElse(index) { 0.5f to 0.56f }
        val halfWidth = 0.13f
        val halfHeight = 0.19f

        return BoundingBox(
            top = (centerY - halfHeight).coerceIn(0f, 1f),
            left = (centerX - halfWidth).coerceIn(0f, 1f),
            bottom = (centerY + halfHeight).coerceIn(0f, 1f),
            right = (centerX + halfWidth).coerceIn(0f, 1f)
        )
    }
}

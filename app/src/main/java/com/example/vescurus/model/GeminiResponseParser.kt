package com.example.vescurus.model

import com.example.vescurus.domain.model.AnalysisResponse
import com.example.vescurus.domain.model.IngredientDetection
import com.example.vescurus.domain.model.BoundingBox
import kotlinx.serialization.json.*

object GeminiResponseParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val preparations = setOf("raw", "whole", "peeled", "sliced", "diced", "julienne", "brunoise", "chopped", "grated", "mashed", "fried")
    fun parse(text: String): AnalysisResponse = try { parseChecked(text) }
    catch (e: RuntimeException) { throw IllegalArgumentException("Malformed Gemini response", e) }

    private fun parseChecked(text: String): AnalysisResponse {
        val root = json.parseToJsonElement(text).jsonObject
        val items = root["detections"]?.jsonArray ?: throw IllegalArgumentException("Missing detections")
        require(items.size <= 100) { "Too many detections" }
        val overall = root["overall_confidence"]?.jsonPrimitive?.floatOrNull ?: throw IllegalArgumentException("Missing confidence")
        require(overall.isFinite() && overall in 0f..1f)
        val detections = items.mapIndexed { index, item ->
            val o = item.jsonObject
            val label = canonicalizeIngredientLabel(o.getValue("label").jsonPrimitive.content)
                ?: throw IllegalArgumentException("Empty label")
            val confidence = o.getValue("confidence").jsonPrimitive.float
            require(confidence.isFinite() && confidence in 0f..1f)
            val b = o.getValue("box_2d").jsonArray.map { it.jsonPrimitive.float }
            require(b.size == 4 && b.all { it.isFinite() && it in 0f..1000f })
            require(b[2] > b[0] && b[3] > b[1])
            val supported = o.getValue("is_supported").jsonPrimitive.boolean
            val preparation = (o["preparation"] as? JsonPrimitive)?.takeIf { it.isString }
                ?.content?.trim()?.lowercase(java.util.Locale.ROOT)?.takeIf { it.length <= 24 && it in preparations && supported }
            IngredientDetection("food-$index", label, confidence, BoundingBox(b[0]/1000, b[1]/1000, b[2]/1000, b[3]/1000), is_supported = supported, preparation = preparation)
        }
        return AnalysisResponse("gemini-frame", detections, overall)
    }
}

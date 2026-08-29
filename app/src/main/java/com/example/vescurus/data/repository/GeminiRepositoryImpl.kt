package com.example.vescurus.data.repository

import android.graphics.Bitmap
import android.util.Log
import com.example.vescurus.GeminiService
import com.example.vescurus.debug.VescurusLogger
import com.example.vescurus.domain.model.AnalysisResponse
import com.example.vescurus.domain.repository.IngredientRepository
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class GeminiRepositoryImpl : IngredientRepository {
    private val tag = "GeminiRepo"
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * V0: the Guide only needs to recognize a real egg and localize it.
     * Recipe selection happens after this detection on the Cook device.
     */
    private val prompt = """
        You are the VESCURUS V0 visual ingredient detector.

        TASK:
        Determine whether a REAL FOOD EGG is visibly present in this image.
        If one or more eggs are clearly visible, detect each egg and return
        its bounding box. If no real egg is confidently visible, return an
        empty detections array.

        ONLY SUPPORTED OBJECT:
        - egg

        IMPORTANT:
        - Do not classify an object as an egg merely because it is white,
          round, oval, or egg-shaped.
        - Do not classify ping-pong balls, balls, paper, plastic objects,
          lamps, or other props as eggs.
        - Prefer no detection over a false egg detection.

        IGNORE:
        - tawa / pan / cooking surface
        - hands / fingers
        - spatula / spoon / utensils
        - bowl / plate / containers
        - steam / smoke
        - background objects
        - phone / camera
        - shadows / reflections

        BOUNDING BOX:
        Return box_2d as [ymin, xmin, ymax, xmax], normalized from 0 to 1000,
        exactly as requested. The coordinates must tightly enclose the egg.

        CONFIDENCE:
        Use a value from 0.0 to 1.0. Only report an egg when confidence is
        sufficiently high that a human would reasonably agree it is a real egg.

        RETURN ONLY THIS JSON OBJECT:
        {
          "request_id": "v0",
          "detections": [
            {
              "id": "egg-1",
              "label": "egg",
              "confidence": 0.96,
              "box_2d": {
                "ymin": 250,
                "xmin": 300,
                "ymax": 700,
                "xmax": 650
              },
              "alternatives": [],
              "is_supported": true
            }
          ],
          "overall_confidence": 0.96
        }

        When no real egg is confidently visible, return:
        {
          "request_id": "v0",
          "detections": [],
          "overall_confidence": 0.0
        }

        Do not return markdown, code fences, explanations, or extra fields.
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

            val parsed = json.decodeFromString<AnalysisResponse>(cleanJson)

            Log.d(
                tag,
                "Parsed detections=${parsed.detections.size}: " +
                    parsed.detections.joinToString { detection ->
                        "${detection.label}=${detection.confidence} " +
                            "box=${detection.box_2d} supported=${detection.is_supported}"
                    }
            )

            parsed
        } catch (e: Exception) {
            Log.e(tag, "Analysis failed", e)
            throw e
        }
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

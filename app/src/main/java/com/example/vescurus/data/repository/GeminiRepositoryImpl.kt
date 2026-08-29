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
     * V0: maximize recall for the expo initiation gesture.
     * The Guide only needs to recognize and localize the main ingredient:
     * an egg. Recipe selection happens after this detection on the Cook device.
     */
    private val prompt = """
        You are the VESCURUS V0 visual ingredient detector.

        TASK:
        Look for the main object the user is deliberately presenting to the
        camera: an EGG. Return a bounding box around the egg and label it
        exactly "egg".

        THIS IS A HIGH-RECALL EXPO DETECTOR.
        The cost of missing an egg is much higher than the cost of a false
        positive during this demonstration. If an object is reasonably
        egg-like, prefer detecting it as an egg rather than returning no
        detection.

        ACCEPT AS EGG:
        - white, off-white, cream, or lightly colored chicken eggs
        - raw eggs in their shell
        - eggs with small shell marks, dirt, speckles, stains, or surface
          imperfections
        - eggs partially covered by a person's fingers or hand
        - eggs being held above a tawa or placed on a tawa
        - eggs being held over a table or kitchen surface
        - eggs viewed from different angles
        - slightly blurred or moving eggs
        - an approximately round or oval white/off-white object when it is
          clearly the object the user is presenting to the camera

        DO NOT REQUIRE:
        - perfect egg shape
        - visible shell texture
        - a clean white shell
        - the entire egg to be unobstructed

        IGNORE THESE AS SEPARATE OBJECTS:
        - the user's hand or fingers
        - tawa / pan / cooking surface
        - spatula, spoon, or utensils
        - steam or smoke
        - table or background
        - phone or camera

        IMPORTANT:
        For this V0 demonstration, do NOT reject an egg merely because it
        resembles another rounded object. If a rounded white/off-white object
        is the obvious object being presented by the user, label it "egg".
        High recall is more important than perfect real-world classification.

        BOUNDING BOX:
        Return box_2d as [ymin, xmin, ymax, xmax], normalized from 0 to 1000.
        The box should tightly enclose the visible egg/object, not the hand,
        tawa, or surrounding background.

        CONFIDENCE:
        Use a value from 0.0 to 1.0. For an obvious egg-like presented object,
        use a high confidence value such as 0.90-0.99.

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

        If there is genuinely no plausible egg-like object being presented,
        return:
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

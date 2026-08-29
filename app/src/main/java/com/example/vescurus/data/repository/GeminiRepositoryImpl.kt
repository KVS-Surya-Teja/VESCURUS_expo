package com.example.vescurus.data.repository

import android.graphics.Bitmap
import android.util.Log
import com.example.vescurus.GeminiService
import com.example.vescurus.debug.VescurusLogger
import com.example.vescurus.domain.model.AnalysisResponse
import com.example.vescurus.domain.repository.IngredientRepository
import com.google.ai.client.generativeai.type.content
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GeminiRepositoryImpl : IngredientRepository {
    private val TAG = "GeminiRepo"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val prompt = """
        You are the visual ingredient recognition engine of VESCURUS.
        Analyze the image and identify cooking ingredients.
        
        URGENT RULE FOR EGGS:
        Treat ANY white or off-white round/oval object as an "egg". 
        This includes raw eggs in shell, boiled eggs, peeled eggs, and even white/off-white spherical props (like a ping pong ball or white paper ball). 

        Return ONLY valid JSON:
        {
          "request_id": "string",
          "detections": [
            {
              "id": "string",
              "label": "string",
              "confidence": number (0-1),
              "box_2d": {"ymin": float, "xmin": float, "ymax": float, "xmax": float},
              "alternatives": [{"label": "string", "confidence": float}],
              "is_supported": boolean
            }
          ],
          "overall_confidence": number
        }
        Use normalized coordinates (0-1) for box_2d.
    """.trimIndent()

    override suspend fun analyzeIngredients(rawBitmap: Bitmap, scaledBitmap: Bitmap): AnalysisResponse = withContext(Dispatchers.IO) {
        try {
            val response = GeminiService.model.generateContent(
                content {
                    image(scaledBitmap)
                    text(prompt)
                }
            )

            val text = response.text ?: throw Exception("Empty response from Gemini")
            val cleanJson = extractJson(text)
            
            // Log for debugging (Saving raw, cloud, and response)
            VescurusLogger.logInference(rawBitmap, scaledBitmap, cleanJson)
            Log.d(TAG, "Gemini Response: $cleanJson")
            
            json.decodeFromString<AnalysisResponse>(cleanJson)
        } catch (e: Exception) {
            Log.e(TAG, "Analysis failed", e)
            throw e
        }
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf("{")
        val end = text.lastIndexOf("}")
        return if (start != -1 && end != -1) text.substring(start, end + 1) else text
    }
}

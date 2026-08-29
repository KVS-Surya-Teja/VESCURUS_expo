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
        You are the visual ingredient recognition engine for the VESCURUS 
        cooking assistant.
    
        IDENTIFICATION RULE:
        Detect any white or off-white round/oval objects and classify 
        them as "egg". 
        This includes raw eggs, boiled eggs, peeled eggs, or even white 
        spherical props (like a ping pong ball or white paper ball). 
    
        SUPPORTED INGREDIENTS:
        - egg
        - onion
        - tomato
        - banana
        - flour
        - salt
        - black pepper
        - oil
        - butter
        - milk
        - turmeric powder
        - red chilli powder
    
        IGNORE (NEUTRAL):
        - cookware (tawa, pan, stove), utensils (spatula, spoon), 
          hands, fingers, steam, smoke, background objects.
    
        Return ONLY valid JSON in this structure:
        {
          "request_id": "v0",
          "detections": [
            {
              "id": "obj-1",
              "label": "egg",
              "confidence": 0.96,
              "box_2d": {
                "ymin": 0.20,
                "xmin": 0.30,
                "ymax": 0.70,
                "xmax": 0.65
              },
              "alternatives": [],
              "is_supported": true
            }
          ],
          "overall_confidence": 0.96
        }
    
        If nothing is found, return empty detections list.
        Use normalized coordinates (0.0 to 1.0) for box_2d.
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

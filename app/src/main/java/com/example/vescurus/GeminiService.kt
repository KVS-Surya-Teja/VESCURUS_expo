package com.example.vescurus

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig

object GeminiService {
    // Reads key from BuildConfig (configured via local.properties)
    private val apiKey = BuildConfig.GEMINI_API_KEY

    val model = GenerativeModel(
        modelName = "gemini-2.5-flash",
        apiKey = apiKey,
        generationConfig = generationConfig {
            responseMimeType = "application/json"
            temperature = 0.2f
        }
    )
}

package com.vescurus.app

import com.google.ai.client.generativeai.GenerativeModel

object GeminiService {
    // Uses the key safely loaded from local.properties
    private val apiKey = BuildConfig.GEMINI_API_KEY

    val model = GenerativeModel(
        modelName = "gemini-2.5-flash",
        apiKey = apiKey
    )
}
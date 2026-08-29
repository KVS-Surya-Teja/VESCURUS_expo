package com.example.vescurus.data.remote

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig

/**
 * Single point of contact for the Gemini SDK. Instantiate once (through
 * [com.example.vescurus.AppContainer]) and share both the detector model and
 * the chat session — a per-ViewModel `GenerativeModel` wastes memory and
 * gets us two independent clients per app.
 *
 * The key is loaded from BuildConfig, which is populated at build time from
 * the gitignored `local.properties`.
 */
class GeminiClient(apiKey: String) {

    /**
     * Detector model — structured JSON output, deterministic-ish temperature.
     * A single instance is safe to share across threads.
     */
    val detectorModel: GenerativeModel = GenerativeModel(
        modelName = MODEL_NAME,
        apiKey = apiKey,
        generationConfig = generationConfig {
            responseMimeType = "application/json"
            temperature = 0.2f
        }
    )

    /**
     * Chat model — plain text output, higher temperature for personality,
     * `systemInstruction` carries the persona so we do not have to stuff it
     * into a fake first user turn.
     */
    val chatModel: GenerativeModel = GenerativeModel(
        modelName = MODEL_NAME,
        apiKey = apiKey,
        systemInstruction = content(role = "system") {
            text(Prompts.CHAT_SYSTEM_INSTRUCTION)
        },
        generationConfig = generationConfig {
            temperature = 0.6f
        }
    )

    fun newChatSession(seed: List<Content> = emptyList()) =
        chatModel.startChat(history = seed)

    companion object {
        // The current Gemini flash model as of Aug 2026. gemini-2.5-flash
        // is deprecated ("no longer available to new users") — the API
        // itself returns a 404 telling callers to move to 3.6.
        const val MODEL_NAME = "gemini-3.6-flash"
    }
}

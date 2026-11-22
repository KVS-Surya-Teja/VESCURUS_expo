package com.example.vescurus.data.repository

import android.graphics.Bitmap
import android.util.Base64
import com.example.vescurus.BuildConfig
import com.example.vescurus.di.AppModule
import com.example.vescurus.domain.model.AnalysisResponse
import com.example.vescurus.domain.repository.IngredientRepository
import com.example.vescurus.model.GeminiResponseParser
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream

class ProviderFailure(val status: String) : Exception(status)

/** Direct Gemini REST call, shared prompt/schema with the local validation API. No provider fallback. */
class GeminiRepositoryImpl : IngredientRepository {
    companion object {
        private val client = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 35000; connectTimeoutMillis = 10000 } }
        private val json = Json { ignoreUnknownKeys = true }
    }
    override suspend fun analyzeIngredients(rawBitmap: Bitmap, scaledBitmap: Bitmap): AnalysisResponse = withContext(Dispatchers.IO) {
        if (BuildConfig.GEMINI_API_KEY.isBlank()) throw ProviderFailure("UNCONFIGURED")
        val output = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        val body = buildJsonObject {
            put("contents", buildJsonArray { add(buildJsonObject { put("parts", buildJsonArray {
                add(buildJsonObject { put("inline_data", buildJsonObject {
                    put("mime_type", "image/jpeg"); put("data", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
                }) })
                add(buildJsonObject { put("text", AppModule.assetText("vision_prompt.txt")) })
            }) }) })
            put("generationConfig", buildJsonObject {
                put("temperature", 0.2); put("responseMimeType", "application/json")
                put("responseSchema", json.parseToJsonElement(AppModule.assetText("vision_schema.json")))
            })
        }
        val response = client.post("https://generativelanguage.googleapis.com/v1beta/models/${BuildConfig.GEMINI_MODEL}:generateContent") {
            header("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
            contentType(ContentType.Application.Json); setBody(body.toString())
        }
        if (response.status.value !in 200..299) throw ProviderFailure(when (response.status.value) {
            401,403 -> "CREDENTIAL_FAILURE"; 404 -> "MODEL_UNAVAILABLE"; 429 -> "QUOTA_OR_RATE_LIMIT"; else -> "API_FAILURE"
        })
        val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: throw ProviderFailure("BLOCKED_OR_EMPTY_RESPONSE")
        if (candidate["finishReason"]?.jsonPrimitive?.content != "STOP") throw ProviderFailure("INCOMPLETE_RESPONSE")
        val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray ?: throw ProviderFailure("MALFORMED_RESPONSE")
        val text = parts.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }.joinToString("")
        GeminiResponseParser.parse(text)
    }
}

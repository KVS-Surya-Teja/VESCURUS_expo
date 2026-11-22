package com.example.vescurus.data.network

import com.example.vescurus.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.core.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

class GeminiTextException(val code: String) : Exception(code)

/** Header-authenticated REST, shared by chat and structured planning. Never logs request content or credentials. */
class GeminiTextService {
    companion object {
        private const val MAX_RESPONSE = 262_144L
        private val client = HttpClient(CIO) {
            install(HttpTimeout) { requestTimeoutMillis = 40_000; connectTimeoutMillis = 10_000; socketTimeoutMillis = 40_000 }
        }
    }
    suspend fun generate(prompt: String, jsonOutput: Boolean = false, responseSchema: String? = null): String = withContext(Dispatchers.IO) {
        if (BuildConfig.GEMINI_API_KEY.isBlank()) throw GeminiTextException("UNCONFIGURED")
        require(prompt.isNotBlank() && prompt.length <= 100_000) { "Invalid prompt length" }
        try {
            withTimeout(45_000) {
                val structuredOutput = jsonOutput || responseSchema != null
                val config = JSONObject().put("temperature", if (structuredOutput) .3 else .5).put("maxOutputTokens", if (structuredOutput) 8192 else 1024)
                if (structuredOutput) config.put("responseMimeType", "application/json")
                if (responseSchema != null) config.put("responseSchema", JSONObject(responseSchema))
                val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                    .put("generationConfig", config)
                suspend fun request() = client.post("https://generativelanguage.googleapis.com/v1beta/models/${BuildConfig.GEMINI_MODEL}:generateContent") {
                    header("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
                var response = request()
                if (response.status.value in setOf(502, 503, 504)) {
                    response.bodyAsChannel().cancel(null)
                    delay(1000)
                    response = request()
                }
                if (response.status.value !in 200..299) throw GeminiTextException(when (response.status.value) {
                    401, 403 -> "CREDENTIAL_FAILURE"
                    404 -> "MODEL_UNAVAILABLE"
                    429 -> "QUOTA_OR_RATE_LIMIT"
                    in 500..599 -> "SERVICE_UNAVAILABLE"
                    else -> "API_FAILURE"
                })
                val packet = response.bodyAsChannel().readRemaining(MAX_RESPONSE + 1)
                if (packet.remaining > MAX_RESPONSE) throw GeminiTextException("RESPONSE_TOO_LARGE")
                val root = JSONObject(packet.readText())
                val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
                    ?: throw GeminiTextException("BLOCKED_OR_EMPTY_RESPONSE")
                if (candidate.optString("finishReason") != "STOP") throw GeminiTextException("INCOMPLETE_RESPONSE")
                val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
                    ?: throw GeminiTextException("MALFORMED_RESPONSE")
                val text = (0 until parts.length()).mapNotNull { index ->
                    parts.optJSONObject(index)?.takeUnless { it.optBoolean("thought", false) }?.optString("text")
                }.joinToString("").trim()
                if (text.isBlank()) throw GeminiTextException("EMPTY_RESPONSE")
                text
            }
        } catch (e: TimeoutCancellationException) { throw GeminiTextException("TIMEOUT")
        } catch (e: CancellationException) { throw e
        } catch (e: GeminiTextException) { throw e
        } catch (e: HttpRequestTimeoutException) { throw GeminiTextException("TIMEOUT")
        } catch (_: java.io.IOException) { throw GeminiTextException("NETWORK_FAILURE")
        } catch (_: Exception) { throw GeminiTextException("MALFORMED_RESPONSE") }
    }
}

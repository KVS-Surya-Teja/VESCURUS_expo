package com.example.vescurus.data.repository

import android.graphics.Bitmap
import android.util.Log
import com.example.vescurus.debug.VescurusLogger
import com.example.vescurus.domain.model.AnalysisResponse
import com.example.vescurus.domain.repository.IngredientRepository
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

/**
 * YOLO-World Local Server Repository Implementation.
 * 
 * Replaces cloud-based Gemini inference with ultra-fast, deterministic 
 * local YOLO-World object detection running on the laptop (or local gateway).
 * 
 * Target Endpoint: http://<LAPTOP_IP>:5000/detect
 */
class YoloRepositoryImpl(
    private val serverIp: String = "192.168.0.100", // Update with your laptop's local IP
    private val serverPort: Int = 5000
) : IngredientRepository {

    private val tag = "YoloRepo"
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = 3000 // 3 seconds timeout
        }
    }

    override suspend fun analyzeIngredients(
        rawBitmap: Bitmap,
        scaledBitmap: Bitmap
    ): AnalysisResponse = withContext(Dispatchers.IO) {
        try {
            // Compress scaled bitmap to JPEG byte array
            val byteArrayOutputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, byteArrayOutputStream)
            val imageBytes = byteArrayOutputStream.toByteArray()

            val url = "http://$serverIp:$serverPort/detect"
            Log.d(tag, "Sending frame to YOLO-World server at $url (${imageBytes.size} bytes)")

            // HTTP POST Multipart Form Request
            val httpResponse: HttpResponse = client.submitFormWithBinaryData(
                url = url,
                formData = formData {
                    append("image", imageBytes, Headers.build {
                        append(HttpHeaders.ContentType, "image/jpeg")
                        append(HttpHeaders.ContentDisposition, "filename=frame.jpg")
                    })
                }
            )

            val responseText = httpResponse.bodyAsText()
            Log.d(tag, "YOLO-World Response: $responseText")

            // Log inference session locally
            VescurusLogger.logInference(rawBitmap, scaledBitmap, responseText)

            // Decode response using strict domain schema
            val response = json.decodeFromString<AnalysisResponse>(responseText)
            response
        } catch (e: Exception) {
            Log.e(tag, "YOLO-World Server connection failed: ${e.message}")
            // Return empty response on network or server error so the UI handles it gracefully
            AnalysisResponse(
                request_id = "yolo-error",
                detections = emptyList(),
                overall_confidence = 0f
            )
        }
    }
}

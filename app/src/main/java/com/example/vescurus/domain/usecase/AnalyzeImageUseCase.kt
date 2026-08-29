package com.example.vescurus.domain.usecase

import android.graphics.Bitmap
import com.example.vescurus.core.quality.ImageQualityManager
import com.example.vescurus.domain.model.AnalysisResponse
import com.example.vescurus.domain.model.DetectionMode
import com.example.vescurus.domain.repository.IngredientRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class AnalyzeImageUseCase(private val repository: IngredientRepository) {

    sealed class Result {
        data class Success(val data: AnalysisResponse) : Result()
        data class Failure(val reason: Reason, val message: String) : Result()
    }

    enum class Reason { QUALITY, TIMEOUT, RATE_LIMIT, NETWORK, PARSE, UNKNOWN }

    suspend fun execute(
        rawBitmap: Bitmap,
        scaledBitmap: Bitmap,
        mode: DetectionMode = DetectionMode.EGG_ONLY
    ): Result {
        val quality = ImageQualityManager.assess(rawBitmap)
        if (!quality.isSuitable) {
            return Result.Failure(Reason.QUALITY, quality.message ?: "Image quality insufficient")
        }

        return try {
            val response = withTimeout(GEMINI_TIMEOUT_MS) {
                repository.analyzeIngredients(rawBitmap, scaledBitmap, mode)
            }
            Result.Success(response)
        } catch (e: CancellationException) {
            // Preserve structured cancellation — never swallow it.
            throw e
        } catch (e: TimeoutCancellationException) {
            Result.Failure(Reason.TIMEOUT, "Detector timed out after ${GEMINI_TIMEOUT_MS}ms")
        } catch (e: Exception) {
            val classified = classify(e)
            Result.Failure(classified, e.localizedMessage ?: e.javaClass.simpleName)
        }
    }

    private fun classify(e: Exception): Reason {
        val msg = (e.message ?: "").lowercase()
        return when {
            "429" in msg || "quota" in msg || "rate" in msg -> Reason.RATE_LIMIT
            "unable to resolve host" in msg || "timeout" in msg ||
                "unreachable" in msg || "connection" in msg -> Reason.NETWORK
            "json" in msg || "parse" in msg -> Reason.PARSE
            else -> Reason.UNKNOWN
        }
    }

    private companion object {
        const val GEMINI_TIMEOUT_MS = 15_000L
    }
}

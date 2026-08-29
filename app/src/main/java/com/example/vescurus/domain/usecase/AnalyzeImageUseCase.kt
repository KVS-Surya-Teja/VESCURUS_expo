package com.example.vescurus.domain.usecase

import android.graphics.Bitmap
import com.example.vescurus.core.quality.ImageQualityManager
import com.example.vescurus.domain.model.AnalysisResponse
import com.example.vescurus.domain.repository.IngredientRepository

class AnalyzeImageUseCase(private val repository: IngredientRepository) {
    
    sealed class Result {
        data class Success(val data: AnalysisResponse) : Result()
        data class Failure(val message: String) : Result()
    }

    suspend fun execute(rawBitmap: Bitmap, scaledBitmap: Bitmap): Result {
        val quality = ImageQualityManager.assess(rawBitmap)
        if (!quality.isSuitable) {
            return Result.Failure(quality.message ?: "Image quality insufficient")
        }

        return try {
            val response = repository.analyzeIngredients(rawBitmap, scaledBitmap)
            Result.Success(response)
        } catch (e: Exception) {
            Result.Failure("Analysis failed: ${e.localizedMessage}")
        }
    }
}

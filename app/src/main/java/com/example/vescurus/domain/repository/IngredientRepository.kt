package com.example.vescurus.domain.repository

import android.graphics.Bitmap
import com.example.vescurus.domain.model.AnalysisResponse
import com.example.vescurus.domain.model.DetectionMode

interface IngredientRepository {
    suspend fun analyzeIngredients(
        rawBitmap: Bitmap,
        scaledBitmap: Bitmap,
        mode: DetectionMode = DetectionMode.EGG_ONLY
    ): AnalysisResponse
}

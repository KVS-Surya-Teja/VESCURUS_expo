package com.example.vescurus.domain.repository

import android.graphics.Bitmap
import com.example.vescurus.domain.model.AnalysisResponse

interface IngredientRepository {
    suspend fun analyzeIngredients(rawBitmap: Bitmap, scaledBitmap: Bitmap): AnalysisResponse
}

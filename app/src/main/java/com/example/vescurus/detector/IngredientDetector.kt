package com.example.vescurus.detector

import android.graphics.Bitmap
import com.example.vescurus.model.DetectionResult

interface IngredientDetector {
    suspend fun detect(bitmap: Bitmap): List<DetectionResult>
}

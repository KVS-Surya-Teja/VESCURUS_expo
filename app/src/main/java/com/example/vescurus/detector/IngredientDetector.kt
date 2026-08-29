package com.example.vescurus.detector

import android.graphics.Bitmap
import com.example.vescurus.domain.model.IngredientDetection

/**
 * Ingredient detector abstraction. Returns the canonical domain type so
 * callers never see whichever backend produced the detection.
 */
interface IngredientDetector {
    suspend fun detect(rawBitmap: Bitmap, scaledBitmap: Bitmap): List<IngredientDetection>
}

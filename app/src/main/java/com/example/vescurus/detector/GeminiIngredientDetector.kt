package com.example.vescurus.detector

import android.graphics.Bitmap
import android.util.Log
import com.example.vescurus.domain.model.IngredientDetection
import com.example.vescurus.domain.usecase.AnalyzeImageUseCase

/**
 * Adapter over [AnalyzeImageUseCase]. On failure returns an empty list —
 * upstream `GuideScreen` interprets that as a transient miss and only clears
 * boxes after N consecutive misses (see the two-miss debounce there).
 *
 * The dependency is passed explicitly so this class is trivially test-double-able.
 */
class GeminiIngredientDetector(private val useCase: AnalyzeImageUseCase) : IngredientDetector {

    override suspend fun detect(rawBitmap: Bitmap, scaledBitmap: Bitmap): List<IngredientDetection> {
        return when (val result = useCase.execute(rawBitmap, scaledBitmap)) {
            is AnalyzeImageUseCase.Result.Success -> {
                Log.d(TAG, "Detected ${result.data.detections.size}: ${
                    result.data.detections.joinToString { "${it.label}=${it.confidence}" }
                }")
                result.data.detections
            }
            is AnalyzeImageUseCase.Result.Failure -> {
                Log.w(TAG, "Detection failed: ${result.message}")
                emptyList()
            }
        }
    }

    private companion object {
        const val TAG = "GeminiDetector"
    }
}

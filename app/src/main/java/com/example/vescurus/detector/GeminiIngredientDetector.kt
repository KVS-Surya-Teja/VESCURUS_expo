package com.example.vescurus.detector

import android.graphics.Bitmap
import android.util.Log
import com.example.vescurus.di.AppModule
import com.example.vescurus.domain.usecase.AnalyzeImageUseCase
import com.example.vescurus.model.DetectionResult
import com.example.vescurus.model.BoundingBox as LegacyBoundingBox

class GeminiIngredientDetector : IngredientDetector {
    private val useCase = AppModule.analyzeImageUseCase

    override suspend fun detect(rawBitmap: Bitmap, scaledBitmap: Bitmap): List<DetectionResult> {
        val result = useCase.execute(rawBitmap, scaledBitmap)
        
        return when (result) {
            is AnalyzeImageUseCase.Result.Success -> {
                result.data.detections.map { det ->
                    DetectionResult(
                        label = det.label,
                        confidence = det.confidence,
                        recipe_class = deriveRecipeClass(det.label), 
                        box_2d = LegacyBoundingBox(
                            det.box_2d.ymin,
                            det.box_2d.xmin,
                            det.box_2d.ymax,
                            det.box_2d.xmax
                        ),
                        supported = det.is_supported
                    )
                }
            }
            is AnalyzeImageUseCase.Result.Failure -> {
                Log.e("CV_FLOW", "Analysis failure: ${result.message}")
                emptyList()
            }
        }
    }

    private fun deriveRecipeClass(label: String): Int {
        return when (label.lowercase()) {
            "egg" -> 2 // Scrambled Eggs for demo
            "onion", "tomato" -> 1 // Omelette
            "banana", "flour" -> 4 // Pancake
            else -> 0
        }
    }
}

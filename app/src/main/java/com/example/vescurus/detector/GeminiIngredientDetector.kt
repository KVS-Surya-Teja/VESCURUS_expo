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
                Log.d(
                    "CV_FLOW",
                    "Gemini parsed ${result.data.detections.size} detections: " +
                            result.data.detections.joinToString {
                                "${it.label}=${it.confidence} " +
                                        "box=${it.box_2d}"
                            }
                )

                result.data.detections.map { det ->
                    DetectionResult(
                        label = det.label,
                        confidence = det.confidence,
                        recipe_class = 0, // V0: Recipe selection is handled in UI/Chatbot after detection
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
                Log.e(
                    "CV_FLOW",
                    "Gemini detection failure: ${result.message}"
                )
                emptyList()
            }
        }
    }
}

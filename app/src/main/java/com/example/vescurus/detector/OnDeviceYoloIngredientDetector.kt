package com.example.vescurus.detector

import android.content.Context
import android.graphics.Bitmap
import com.example.vescurus.data.repository.OnDeviceYoloRepositoryImpl
import com.example.vescurus.domain.usecase.AnalyzeImageUseCase
import com.example.vescurus.model.BoundingBox
import com.example.vescurus.model.DetectionResult

/**
 * Adapter between the on-device ONNX YOLO engine and the existing VESCURUS
 * DetectionResult transport/UI model.
 *
 * CV is entirely local: CameraX -> ONNX YOLO -> DetectionResult.
 */
class OnDeviceYoloIngredientDetector(context: Context) : IngredientDetector {
    private val useCase = AnalyzeImageUseCase(
        OnDeviceYoloRepositoryImpl(context.applicationContext)
    )

    override suspend fun detect(
        rawBitmap: Bitmap,
        scaledBitmap: Bitmap
    ): List<DetectionResult> {
        return when (val result = useCase.execute(rawBitmap, scaledBitmap)) {
            is AnalyzeImageUseCase.Result.Success -> result.data.detections
                .filter { it.label.equals("egg", ignoreCase = true) }
                .map { detection ->
                    DetectionResult(
                        label = "egg",
                        confidence = detection.confidence,
                        recipe_class = 0,
                        box_2d = BoundingBox(
                            top = detection.box_2d.ymin,
                            left = detection.box_2d.xmin,
                            bottom = detection.box_2d.ymax,
                            right = detection.box_2d.xmax
                        ),
                        supported = detection.is_supported
                    )
                }

            is AnalyzeImageUseCase.Result.Failure -> emptyList()
        }
    }
}

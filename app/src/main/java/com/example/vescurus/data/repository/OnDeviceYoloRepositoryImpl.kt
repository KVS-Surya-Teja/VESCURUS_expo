package com.example.vescurus.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.example.vescurus.domain.model.AnalysisResponse
import com.example.vescurus.domain.repository.IngredientRepository

/** No verified model is packaged. Never load an absent native runtime or silently use cloud. */
class OnDeviceYoloRepositoryImpl(context: Context, modelFileName: String = "yolo_food.onnx") : IngredientRepository {
    override suspend fun analyzeIngredients(rawBitmap: Bitmap, scaledBitmap: Bitmap): AnalysisResponse {
        throw ProviderFailure("ON_DEVICE_UNAVAILABLE")
    }
}

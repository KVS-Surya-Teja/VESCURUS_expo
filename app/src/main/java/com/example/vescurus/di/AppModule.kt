package com.example.vescurus.di

import android.content.Context
import com.example.vescurus.data.repository.GeminiRepositoryImpl
import com.example.vescurus.data.repository.OnDeviceYoloRepositoryImpl
import com.example.vescurus.data.repository.YoloRepositoryImpl
import com.example.vescurus.domain.repository.IngredientRepository
import com.example.vescurus.domain.usecase.AnalyzeImageUseCase

enum class DetectionEngine {
    GEMINI,         // Cloud Gemini REST image inference
    ON_DEVICE_YOLO, // 100% On-Device Standalone YOLO CV on Phone 1
    LAPTOP_YOLO     // Laptop Local REST Server (vescurus_yolo_server.py)
}

object AppModule {
    /**
     * ACTIVE DETECTION ENGINE FOR CV:
     * Set to [DetectionEngine.GEMINI] for cloud AI multimodal food/egg detection.
     */
    val activeEngine = DetectionEngine.GEMINI
    val engineDescription: String get() = when (activeEngine) {
        DetectionEngine.GEMINI -> "GEMINI/${com.example.vescurus.BuildConfig.GEMINI_MODEL}"
        DetectionEngine.ON_DEVICE_YOLO -> "ON_DEVICE_UNAVAILABLE (model/runtime not packaged)"
        DetectionEngine.LAPTOP_YOLO -> "LAPTOP_YOLO (unverified)"
    }

    // Laptop local server IP address if using LAPTOP_YOLO
    private const val LAPTOP_IP = "192.168.0.100"

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun assetText(name: String): String = requireNotNull(appContext).assets.open(name).bufferedReader().use { it.readText() }

    fun getRepository(context: Context? = null): IngredientRepository {
        val ctx = context?.applicationContext ?: appContext
        return when (activeEngine) {
            DetectionEngine.GEMINI -> GeminiRepositoryImpl()
            DetectionEngine.ON_DEVICE_YOLO -> {
                OnDeviceYoloRepositoryImpl(ctx ?: throw IllegalStateException("Application Context required. Call AppModule.init(context)"))
            }
            DetectionEngine.LAPTOP_YOLO -> YoloRepositoryImpl(serverIp = LAPTOP_IP, serverPort = 5000)
        }
    }

    val analyzeImageUseCase: AnalyzeImageUseCase
        get() = AnalyzeImageUseCase(getRepository())
}

package com.example.vescurus.di

import android.content.Context
import com.example.vescurus.data.repository.GeminiRepositoryImpl
import com.example.vescurus.data.repository.OnDeviceYoloRepositoryImpl
import com.example.vescurus.data.repository.YoloRepositoryImpl
import com.example.vescurus.domain.repository.IngredientRepository
import com.example.vescurus.domain.usecase.AnalyzeImageUseCase

enum class DetectionEngine {
    GEMINI,         // Cloud Gemini 1.5 Flash Vision Engine (V0 Reference)
    ON_DEVICE_YOLO, // 100% On-Device Standalone YOLO CV on Phone 1
    LAPTOP_YOLO     // Laptop Local REST Server (vescurus_yolo_server.py)
}

object AppModule {
    /**
     * ACTIVE DETECTION ENGINE FOR CV:
     * Set to [DetectionEngine.GEMINI] for cloud AI multimodal food/egg detection.
     */
    var activeEngine = DetectionEngine.GEMINI

    // Laptop local server IP address if using LAPTOP_YOLO
    private const val LAPTOP_IP = "192.168.0.100"

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

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

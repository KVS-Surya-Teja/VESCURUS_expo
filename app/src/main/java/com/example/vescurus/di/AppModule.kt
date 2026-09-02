package com.example.vescurus.di

import android.content.Context
import com.example.vescurus.data.repository.OnDeviceYoloRepositoryImpl
import com.example.vescurus.data.repository.YoloRepositoryImpl
import com.example.vescurus.domain.repository.IngredientRepository
import com.example.vescurus.domain.usecase.AnalyzeImageUseCase

enum class DetectionEngine {
    ON_DEVICE_YOLO, // 100% On-Device Standalone YOLO CV on Phone 1 (Zero Gemini calls for CV)
    LAPTOP_YOLO     // Laptop Local REST Server (vescurus_yolo_server.py)
}

object AppModule {
    /**
     * ACTIVE DETECTION ENGINE FOR CV:
     * Set to [DetectionEngine.ON_DEVICE_YOLO] for 100% Standalone On-Device YOLO Vision on Phone 1.
     * ZERO Gemini calls for Camera Frame Analysis!
     */
    val activeEngine = DetectionEngine.ON_DEVICE_YOLO

    // Laptop local server IP address if using LAPTOP_YOLO
    private const val LAPTOP_IP = "192.168.0.100"

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun getRepository(context: Context? = null): IngredientRepository {
        val ctx = context?.applicationContext ?: appContext
        return when (activeEngine) {
            DetectionEngine.ON_DEVICE_YOLO -> {
                OnDeviceYoloRepositoryImpl(ctx ?: throw IllegalStateException("Application Context required. Call AppModule.init(context)"))
            }
            DetectionEngine.LAPTOP_YOLO -> YoloRepositoryImpl(serverIp = LAPTOP_IP, serverPort = 5000)
        }
    }

    val analyzeImageUseCase: AnalyzeImageUseCase
        get() = AnalyzeImageUseCase(getRepository())
}

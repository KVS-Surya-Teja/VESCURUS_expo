package com.example.vescurus

import android.content.Context
import com.example.vescurus.data.preferences.AppPreferences
import com.example.vescurus.data.preferences.CookHistoryRepository
import com.example.vescurus.data.remote.GeminiClient
import com.example.vescurus.data.repository.GeminiRepositoryImpl
import com.example.vescurus.domain.repository.IngredientRepository
import com.example.vescurus.domain.usecase.AnalyzeImageUseCase

/**
 * Manual DI container. Attached to the Application; ViewModels resolve
 * dependencies via `application.container` rather than reaching into a static
 * `object AppModule` (which was the previous pattern and broke test seams).
 *
 * Every collaborator is created lazily so `Application.onCreate` stays cheap.
 */
class AppContainer(context: Context) {

    val geminiClient: GeminiClient by lazy {
        GeminiClient(BuildConfig.GEMINI_API_KEY)
    }

    val ingredientRepository: IngredientRepository by lazy {
        GeminiRepositoryImpl(geminiClient)
    }

    val analyzeImageUseCase: AnalyzeImageUseCase by lazy {
        AnalyzeImageUseCase(ingredientRepository)
    }

    val appPreferences: AppPreferences by lazy {
        AppPreferences(context.applicationContext)
    }

    val cookHistoryRepository: CookHistoryRepository by lazy {
        CookHistoryRepository(context.applicationContext)
    }
}

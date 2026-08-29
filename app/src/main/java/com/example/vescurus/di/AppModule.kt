package com.example.vescurus.di

import com.example.vescurus.data.repository.GeminiRepositoryImpl
import com.example.vescurus.domain.usecase.AnalyzeImageUseCase

object AppModule {
    private val repository by lazy { GeminiRepositoryImpl() }
    val analyzeImageUseCase by lazy { AnalyzeImageUseCase(repository) }
}

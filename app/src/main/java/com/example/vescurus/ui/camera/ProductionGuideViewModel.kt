package com.example.vescurus.ui.camera

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vescurus.domain.model.AnalysisResponse
import com.example.vescurus.domain.model.IngredientDetection
import com.example.vescurus.domain.usecase.AnalyzeImageUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ProductionGuideViewModel(
    private val analyzeImageUseCase: AnalyzeImageUseCase
) : ViewModel() {

    sealed class UiState {
        object Idle : UiState()
        object Processing : UiState()
        data class Success(val data: AnalysisResponse) : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state

    var selectedObjectId by mutableStateOf<String?>(null)
        private set

    fun analyze(rawBitmap: Bitmap, scaledBitmap: Bitmap) {
        viewModelScope.launch {
            _state.value = UiState.Processing
            val result = analyzeImageUseCase.execute(rawBitmap, scaledBitmap)
            _state.value = when (result) {
                is AnalyzeImageUseCase.Result.Success -> UiState.Success(result.data)
                is AnalyzeImageUseCase.Result.Failure -> UiState.Error(result.message)
            }
        }
    }

    fun selectObject(id: String) {
        selectedObjectId = id
    }

    fun clearSelection() {
        selectedObjectId = null
    }
}

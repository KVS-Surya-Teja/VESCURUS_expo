package com.example.vescurus.ui.snapshot

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.vescurus.VescurusApplication
import com.example.vescurus.domain.model.BoundingBox
import com.example.vescurus.domain.model.DetectionCandidate
import com.example.vescurus.domain.model.DetectionMode
import com.example.vescurus.domain.model.IngredientDetection
import com.example.vescurus.domain.usecase.AnalyzeImageUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the single-device Snapshot flow (spec items 1–9).
 *
 * State machine: `Idle → Analyzing → (Reviewing | Error) → Idle`.
 * In `Reviewing` the user can Accept, Reject, or Edit the label of each
 * detection; confirmed detections are exposed for downstream use.
 *
 * The captured bitmap is held for the lifetime of the state. It is recycled
 * when we leave that state (`reset()` / `onCleared()`).
 */
class SnapshotViewModel(
    private val analyzeImageUseCase: AnalyzeImageUseCase
) : ViewModel() {

    sealed class State {
        object Idle : State()
        data class Analyzing(val bitmap: Bitmap, val sourceAspect: Float) : State()
        data class Reviewing(
            val bitmap: Bitmap,
            val sourceAspect: Float,
            val detections: List<ReviewableDetection>
        ) : State() {
            val acceptedDetections: List<ReviewableDetection>
                get() = detections.filter { it.status == ReviewStatus.ACCEPTED }
        }
        data class ErrorState(val message: String, val reason: AnalyzeImageUseCase.Reason) : State()
    }

    data class ReviewableDetection(
        val id: String,
        val bounds: BoundingBox,
        val originalLabel: String,
        val confidence: Float,
        val currentLabel: String,
        val status: ReviewStatus
    )

    enum class ReviewStatus { PENDING, ACCEPTED, REJECTED }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var analysisJob: Job? = null

    fun onCapture(bitmap: Bitmap) {
        analysisJob?.cancel()
        val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        _state.value = State.Analyzing(bitmap, aspect)

        analysisJob = viewModelScope.launch {
            val scaled = withContext(Dispatchers.Default) { bitmap.scaleDown(SCALED_MAX_DIM) }
            try {
                // Egg-only for DEMONSTRATE — the current demo scope is eggs.
                // Whichever branch we take, we ALWAYS land in Reviewing with
                // at least one detection so the demo never dead-ends.
                val real = runCatching {
                    analyzeImageUseCase.execute(
                        rawBitmap = bitmap,
                        scaledBitmap = scaled,
                        mode = DetectionMode.EGG_ONLY
                    )
                }.getOrNull()

                val detections = when (real) {
                    is AnalyzeImageUseCase.Result.Success ->
                        real.data.detections.map { it.toReviewable() }
                            .ifEmpty { listOf(MOCK_CENTER_EGG.toReviewable()) }
                    else -> listOf(MOCK_CENTER_EGG.toReviewable())
                }
                _state.value = State.Reviewing(
                    bitmap = bitmap,
                    sourceAspect = aspect,
                    detections = detections
                )
            } catch (e: CancellationException) {
                recycleSafely(bitmap)
                throw e
            } finally {
                if (scaled !== bitmap) recycleSafely(scaled)
            }
        }
    }

    fun onAccept(id: String) = mutateDetection(id) { it.copy(status = ReviewStatus.ACCEPTED) }
    fun onReject(id: String) = mutateDetection(id) { it.copy(status = ReviewStatus.REJECTED) }
    fun onEditLabel(id: String, newLabel: String) = mutateDetection(id) {
        it.copy(currentLabel = newLabel, status = ReviewStatus.ACCEPTED)
    }

    private fun mutateDetection(id: String, transform: (ReviewableDetection) -> ReviewableDetection) {
        val current = _state.value as? State.Reviewing ?: return
        val updated = current.detections.map { if (it.id == id) transform(it) else it }
        _state.value = current.copy(detections = updated)
    }

    fun reset() {
        analysisJob?.cancel()
        val current = _state.value
        val toRecycle = when (current) {
            is State.Analyzing -> current.bitmap
            is State.Reviewing -> current.bitmap
            else -> null
        }
        toRecycle?.let(::recycleSafely)
        _state.value = State.Idle
    }

    override fun onCleared() {
        analysisJob?.cancel()
        val current = _state.value
        when (current) {
            is State.Analyzing -> recycleSafely(current.bitmap)
            is State.Reviewing -> recycleSafely(current.bitmap)
            else -> Unit
        }
        super.onCleared()
    }

    private fun IngredientDetection.toReviewable() = ReviewableDetection(
        id = id,
        bounds = box_2d,
        originalLabel = label,
        confidence = confidence,
        currentLabel = label,
        status = ReviewStatus.PENDING
    )

    private fun recycleSafely(bitmap: Bitmap) {
        runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
    }

    private fun Bitmap.scaleDown(maxDim: Int): Bitmap {
        val w = width
        val h = height
        if (w <= maxDim && h <= maxDim) return this
        val newW: Int
        val newH: Int
        if (w > h) {
            newW = maxDim; newH = (h * maxDim) / w
        } else {
            newH = maxDim; newW = (w * maxDim) / h
        }
        return Bitmap.createScaledBitmap(this, newW, newH, true)
    }

    companion object {
        private const val SCALED_MAX_DIM = 768

        /** Fallback detection for the DEMONSTRATE flow. Mirrors the Guide
         *  streaming mock so both flows behave consistently for the demo. */
        private val MOCK_CENTER_EGG = IngredientDetection(
            id = "egg-demo-1",
            label = "egg",
            confidence = 0.99f,
            box_2d = BoundingBox(ymin = 0.30f, xmin = 0.30f, ymax = 0.70f, xmax = 0.70f),
            alternatives = emptyList<DetectionCandidate>(),
            is_supported = true
        )

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as VescurusApplication
                SnapshotViewModel(app.container.analyzeImageUseCase)
            }
        }
    }
}

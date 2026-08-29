package com.example.vescurus.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class BoundingBox(
    val ymin: Float, // Normalized 0.0 - 1.0
    val xmin: Float,
    val ymax: Float,
    val xmax: Float
)

@Serializable
data class DetectionCandidate(
    val label: String,
    val confidence: Float
)

@Serializable
data class IngredientDetection(
    val id: String,
    val label: String,
    val confidence: Float,
    val box_2d: BoundingBox,
    val alternatives: List<DetectionCandidate> = emptyList(),
    val is_supported: Boolean = true
)

@Serializable
data class AnalysisResponse(
    val request_id: String,
    val detections: List<IngredientDetection> = emptyList(),
    val overall_confidence: Float
)

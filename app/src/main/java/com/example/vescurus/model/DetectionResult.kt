package com.example.vescurus.model

import kotlinx.serialization.Serializable

@Serializable
data class DetectionResponse(
    val detections: List<DetectionResult> = emptyList()
)

@Serializable
data class DetectionResult(
    val label: String,
    val confidence: Float,
    val recipe_class: Int = 0,
    val box_2d: BoundingBox,
    val supported: Boolean = true
)

@Serializable
data class BoundingBox(
    val top: Float,
    val left: Float,
    val bottom: Float,
    val right: Float
)

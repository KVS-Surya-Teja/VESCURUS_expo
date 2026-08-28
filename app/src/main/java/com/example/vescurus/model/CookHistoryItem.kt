package com.example.vescurus.model

import kotlinx.serialization.Serializable

@Serializable
data class CookHistoryItem(
    val id: String,
    val recipeId: String,
    val recipeName: String,
    val timestamp: Long,
    val calories: Int,
    val proteinG: Float,
    val carbsG: Float,
    val fatsG: Float
)

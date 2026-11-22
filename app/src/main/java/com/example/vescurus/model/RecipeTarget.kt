package com.example.vescurus.model
import kotlinx.serialization.Serializable
@Serializable data class RecipeTarget(val ingredient: String, val amount: Double?, val unit: String, val original: String)

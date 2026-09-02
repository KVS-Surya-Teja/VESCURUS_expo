package com.example.vescurus.data.repository

import android.util.Log
import com.example.vescurus.data.network.SpoonacularService
import com.example.vescurus.model.Recipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Recipe source for the V0 YOLO -> Spoonacular integration test.
 *
 * CV and recipe retrieval are deliberately separate:
 * CameraX -> on-device YOLO -> ingredient labels -> Spoonacular.
 */
class UniversalRecipeRepository {
    private val tag = "UniversalRecipeRepo"
    private val spoonacularService = SpoonacularService()

    suspend fun generateRecipesForIngredients(ingredients: List<String>): List<Recipe> = withContext(Dispatchers.IO) {
        if (ingredients.isEmpty()) return@withContext emptyList()

        try {
            val recipes = spoonacularService.fetchRecipesByIngredients(ingredients)
            Log.d(tag, "Spoonacular returned ${recipes.size} recipes for $ingredients")
            recipes
        } catch (e: Exception) {
            Log.e(tag, "Spoonacular recipe fetch failed", e)
            emptyList()
        }
    }
}

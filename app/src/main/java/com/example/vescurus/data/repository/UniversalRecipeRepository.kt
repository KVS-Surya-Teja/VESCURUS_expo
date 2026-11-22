package com.example.vescurus.data.repository
import com.example.vescurus.data.network.SpoonacularService
import com.example.vescurus.model.Recipe
class UniversalRecipeRepository {
    private val service = SpoonacularService()
    suspend fun generateRecipesForIngredients(ingredients: List<String>): List<Recipe> =
        if (ingredients.isEmpty()) emptyList() else service.fetchRecipesByIngredients(ingredients)
}

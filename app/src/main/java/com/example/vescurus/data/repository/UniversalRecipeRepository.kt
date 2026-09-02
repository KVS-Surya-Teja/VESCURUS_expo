package com.example.vescurus.data.repository

import android.util.Log
import com.example.vescurus.GeminiService
import com.example.vescurus.R
import com.example.vescurus.data.network.SpoonacularService
import com.example.vescurus.model.EGG_RECIPES
import com.example.vescurus.model.Recipe
import com.example.vescurus.model.RecipeStep
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class JsonRecipeStep(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val instruction: String,
    val ttsPrompt: String? = null
)

@Serializable
data class JsonRecipe(
    val id: String,
    val name: String,
    val description: String,
    val totalTimeMs: Long,
    val steps: List<JsonRecipeStep>,
    val calories: Int,
    val proteinG: Float,
    val carbsG: Float,
    val fatsG: Float
)

@Serializable
data class JsonRecipeList(
    val recipes: List<JsonRecipe> = emptyList()
)

class UniversalRecipeRepository {
    private val tag = "UniversalRecipeRepo"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val spoonacularService = SpoonacularService()

    suspend fun generateRecipesForIngredients(ingredients: List<String>): List<Recipe> = withContext(Dispatchers.IO) {
        if (ingredients.isEmpty()) return@withContext EGG_RECIPES

        // 1. Try Spoonacular API
        try {
            val spoonacularRecipes = spoonacularService.fetchRecipesByIngredients(ingredients)
            if (spoonacularRecipes.isNotEmpty()) {
                Log.d(tag, "Successfully loaded ${spoonacularRecipes.size} official recipes from Spoonacular API!")
                return@withContext spoonacularRecipes
            }
        } catch (e: Exception) {
            Log.w(tag, "Spoonacular API fetch failed: ${e.message}. Falling back to Gemini Generator.")
        }

        // 2. Fallback to Gemini Dynamic Recipe Generator
        generateWithGemini(ingredients)
    }

    private suspend fun generateWithGemini(ingredients: List<String>): List<Recipe> {
        val ingredientListStr = ingredients.joinToString(", ")
        val prompt = """
            You are VESCURUS's universal culinary recipe generator.
            The user has these available ingredients: $ingredientListStr.

            Generate 3 distinct, delicious, real-world cooking recipes that use these ingredients.
            Each recipe MUST include precise time-coded steps (with startTimeMs and endTimeMs in milliseconds) 
            suitable for a real-time guided cooking timer.

            Return ONLY valid JSON matching this schema:
            {
              "recipes": [
                {
                  "id": "recipe-1",
                  "name": "Recipe Title",
                  "description": "Short delicious summary",
                  "totalTimeMs": 180000,
                  "calories": 300,
                  "proteinG": 20.0,
                  "carbsG": 15.0,
                  "fatsG": 10.0,
                  "steps": [
                    {
                      "startTimeMs": 0,
                      "endTimeMs": 30000,
                      "instruction": "Prep and heat pan",
                      "ttsPrompt": "Heat pan on medium flame"
                    }
                  ]
                }
              ]
            }
            No markdown formatting. Return JSON only.
        """.trimIndent()

        return try {
            Log.d(tag, "Generating dynamic AI recipes for ingredients: $ingredientListStr")
            val response = GeminiService.model.generateContent(
                content { text(prompt) }
            )
            val text = response.text?.trim() ?: return EGG_RECIPES
            val cleanJson = extractJson(text)
            
            val jsonList = json.decodeFromString<JsonRecipeList>(cleanJson)
            val recipes = jsonList.recipes.map { jr ->
                Recipe(
                    id = jr.id,
                    categoryClass = 0,
                    name = jr.name,
                    description = jr.description,
                    thumbnail = selectDefaultThumbnail(jr.name, ingredients),
                    totalTimeMs = jr.totalTimeMs,
                    steps = jr.steps.map { js ->
                        RecipeStep(js.startTimeMs, js.endTimeMs, js.instruction, js.ttsPrompt)
                    },
                    calories = jr.calories,
                    proteinG = jr.proteinG,
                    carbsG = jr.carbsG,
                    fatsG = jr.fatsG
                )
            }
            if (recipes.isNotEmpty()) recipes else EGG_RECIPES
        } catch (e: Exception) {
            Log.e(tag, "Dynamic recipe generation failed: ${e.message}")
            EGG_RECIPES
        }
    }

    private fun selectDefaultThumbnail(name: String, ingredients: List<String>): Int {
        val lower = (name + " " + ingredients.joinToString(" ")).lowercase()
        return when {
            lower.contains("pancake") || lower.contains("banana") -> R.drawable.pancake
            lower.contains("omelette") || lower.contains("frittata") -> R.drawable.omelette
            lower.contains("sunny") || lower.contains("fried") -> R.drawable.sunny_side_up
            else -> R.drawable.scrambled_eggs
        }
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end <= start) return text
        return text.substring(start, end + 1)
    }
}

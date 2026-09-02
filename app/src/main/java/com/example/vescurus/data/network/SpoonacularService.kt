package com.example.vescurus.data.network

import android.util.Log
import com.example.vescurus.BuildConfig
import com.example.vescurus.model.Recipe
import com.example.vescurus.model.RecipeStep
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

@Serializable
data class SpoonacularSearchItem(
    val id: Int,
    val title: String,
    val image: String? = null
)

@Serializable
data class SpoonacularNutrient(
    val name: String,
    val amount: Double = 0.0,
    val unit: String = ""
)

@Serializable
data class SpoonacularNutrition(
    val nutrients: List<SpoonacularNutrient> = emptyList()
)

@Serializable
data class SpoonacularStep(
    val number: Int,
    val step: String
)

@Serializable
data class SpoonacularInstruction(
    val steps: List<SpoonacularStep> = emptyList()
)

@Serializable
data class SpoonacularRecipeDetail(
    val id: Int,
    val title: String,
    val readyInMinutes: Int = 15,
    val image: String? = null,
    val summary: String? = null,
    val nutrition: SpoonacularNutrition? = null,
    val analyzedInstructions: List<SpoonacularInstruction> = emptyList()
)

class SpoonacularService {
    private val tag = "SpoonacularService"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = HttpClient(CIO) {
        engine { requestTimeout = 5000 }
    }

    suspend fun fetchRecipesByIngredients(ingredients: List<String>): List<Recipe> = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.SPOONACULAR_API_KEY.trim()
        if (apiKey.isEmpty()) {
            Log.d(tag, "No SPOONACULAR_API_KEY found in BuildConfig")
            return@withContext emptyList()
        }

        try {
            val ingredientsParam = ingredients.joinToString(",") {
                URLEncoder.encode(it.trim(), "UTF-8")
            }
            val searchUrl = "https://api.spoonacular.com/recipes/findByIngredients?ingredients=$ingredientsParam&number=3&ranking=1&apiKey=$apiKey"
            Log.d(tag, "Querying Spoonacular for ${ingredients.joinToString(", ")}")

            val searchResponse = client.get(searchUrl).bodyAsText()
            val searchItems = json.decodeFromString<List<SpoonacularSearchItem>>(searchResponse)

            if (searchItems.isEmpty()) {
                Log.d(tag, "Spoonacular returned 0 recipes for ingredients: $ingredients")
                return@withContext emptyList()
            }

            val recipes = mutableListOf<Recipe>()
            for (item in searchItems) {
                try {
                    val detailUrl = "https://api.spoonacular.com/recipes/${item.id}/information?includeNutrition=true&apiKey=$apiKey"
                    val detailResponse = client.get(detailUrl).bodyAsText()
                    val detail = json.decodeFromString<SpoonacularRecipeDetail>(detailResponse)
                    recipes.add(mapDetailToRecipe(detail))
                } catch (e: Exception) {
                    Log.e(tag, "Failed to fetch Spoonacular details for recipe ${item.id}: ${e.message}")
                }
            }

            recipes
        } catch (e: Exception) {
            Log.e(tag, "Spoonacular API call failed: ${e.message}")
            emptyList()
        }
    }

    private fun mapDetailToRecipe(detail: SpoonacularRecipeDetail): Recipe {
        val rawSteps = detail.analyzedInstructions.flatMap { it.steps }
        val prepTimeMins = detail.readyInMinutes.coerceAtLeast(2)
        val totalMs = prepTimeMins * 60 * 1000L

        val stepsList = if (rawSteps.isNotEmpty()) {
            val stepDurationMs = totalMs / rawSteps.size
            rawSteps.mapIndexed { index, st ->
                val startMs = index * stepDurationMs
                val endMs = (index + 1) * stepDurationMs
                RecipeStep(
                    startTimeMs = startMs,
                    endTimeMs = endMs,
                    instruction = st.step,
                    ttsPrompt = st.step
                )
            }
        } else {
            listOf(
                RecipeStep(0, totalMs / 2, "Prepare ingredients and cook according to instructions.", "Prepare ingredients and cook according to instructions."),
                RecipeStep(totalMs / 2, totalMs, "Finish cooking, plate, and serve.", "Finish cooking, plate, and serve.")
            )
        }

        val nutrients = detail.nutrition?.nutrients ?: emptyList()
        val cals = nutrients.firstOrNull { it.name.equals("Calories", ignoreCase = true) }?.amount?.toInt() ?: 250
        val pro = nutrients.firstOrNull { it.name.equals("Protein", ignoreCase = true) }?.amount?.toFloat() ?: 15f
        val carbs = nutrients.firstOrNull { it.name.equals("Carbohydrates", ignoreCase = true) }?.amount?.toFloat() ?: 20f
        val fat = nutrients.firstOrNull { it.name.equals("Fat", ignoreCase = true) }?.amount?.toFloat() ?: 10f
        val cleanSummary = detail.summary?.replace(Regex("<[^>]*>"), "")?.take(100) ?: "Spoonacular recipe"

        return Recipe(
            id = "spoon-${detail.id}",
            categoryClass = 0,
            name = detail.title,
            description = cleanSummary,
            thumbnailUrl = detail.image,
            totalTimeMs = totalMs,
            steps = stepsList,
            calories = cals,
            proteinG = pro,
            carbsG = carbs,
            fatsG = fat
        )
    }
}

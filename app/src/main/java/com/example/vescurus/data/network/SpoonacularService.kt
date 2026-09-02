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

    /**
     * VESCURUS cooking surface:
     * a stationary electric tawa / flat griddle with slight rims and NO lid.
     *
     * We therefore deliberately select recipes whose instructions are compatible
     * with direct-contact griddle cooking and reject recipes that depend on an
     * oven, microwave, pressure cooker, steaming, or covered cooking.
     */
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

            // Pan/stovetop is the closest Spoonacular equipment constraint to our
            // physical cooking platform. Compatibility is checked again below.
            val searchUrl = "https://api.spoonacular.com/recipes/complexSearch" +
                    "?includeIngredients=$ingredientsParam" +
                    "&equipment=pan" +
                    "&number=8" +
                    "&instructionsRequired=true" +
                    "&addRecipeInformation=true" +
                    "&fillIngredients=false" +
                    "&apiKey=$apiKey"

            Log.d(tag, "Querying Spoonacular for tawa-compatible recipes: ${ingredients.joinToString(", ")}")

            val searchResponse = client.get(searchUrl).bodyAsText()
            val searchRoot = json.decodeFromString<SpoonacularComplexSearchResponse>(searchResponse)

            if (searchRoot.results.isEmpty()) {
                Log.d(tag, "Spoonacular returned 0 candidate recipes for ingredients: $ingredients")
                return@withContext emptyList()
            }

            val recipes = mutableListOf<Recipe>()
            for (candidate in searchRoot.results) {
                try {
                    val detailUrl = "https://api.spoonacular.com/recipes/${candidate.id}/information?includeNutrition=true&apiKey=$apiKey"
                    val detailResponse = client.get(detailUrl).bodyAsText()
                    val detail = json.decodeFromString<SpoonacularRecipeDetail>(detailResponse)
                    val recipe = mapDetailToRecipe(detail)

                    if (isTawaCompatible(recipe)) {
                        recipes.add(recipe)
                    } else {
                        Log.d(tag, "Rejected non-tawa recipe: ${detail.title}")
                    }

                    if (recipes.size >= 5) break
                } catch (e: Exception) {
                    Log.e(tag, "Failed to fetch Spoonacular details for recipe ${candidate.id}: ${e.message}")
                }
            }

            recipes
        } catch (e: Exception) {
            Log.e(tag, "Spoonacular API call failed: ${e.message}")
            emptyList()
        }
    }

    @Serializable
    private data class SpoonacularComplexSearchResponse(
        val results: List<SpoonacularSearchItem> = emptyList()
    )

    /**
     * Cooking operations that fit a flat electric tawa without requiring a lid.
     * This is intentionally broad: the tawa can do much more than omelettes/dosas.
     *
     * Examples include sautéing, stir-frying, pan-frying, shallow-frying, searing,
     * dry-roasting, toasting, scrambling, griddling, grilling, and batter-based
     * foods such as crepes, pancakes, dosas and flatbreads.
     */
    private val allowedCookingTerms = listOf(
        "saute", "sauté", "stir-fry", "stir fry", "pan-fry", "pan fry",
        "shallow-fry", "shallow fry", "fry", "sear", "brown", "caramelize",
        "caramelise", "griddle", "grill", "toast", "dry roast", "roast",
        "scramble", "omelet", "omelette", "crepe", "crêpe", "pancake",
        "dosa", "dosai", "uttapam", "cheela", "chilla", "pancake",
        "flatbread", "roti", "chapati", "paratha", "naan", "tortilla",
        "quesadilla", "sandwich", "burger", "patty", "cutlet", "kebab",
        "skillet", "pan", "tawa", "griddle"
    )

    /**
     * Processes/equipment that conflict with the physical VESCURUS platform.
     * These are checked against the complete step text, not merely the title.
     */
    private val forbiddenCookingTerms = listOf(
        "oven", "bake", "baking", "broil", "broiler", "microwave",
        "pressure cooker", "pressure-cook", "air fryer", "air-fry",
        "deep fryer", "deep-fry", "steamer", "steam", "steamed", "steaming",
        "double boiler", "slow cooker", "crockpot", "rice cooker",
        "sous vide", "covered pan", "cover the pan", "cover and cook",
        "place in the oven", "transfer to oven", "bake for", "baked"
    )

    private fun isTawaCompatible(recipe: Recipe): Boolean {
        if (recipe.steps.isEmpty()) return false

        val instructions = recipe.steps.joinToString(" ") { it.instruction.lowercase() }

        // Hard rejection: these operations require equipment/processes that the
        // VESCURUS tawa does not provide.
        if (forbiddenCookingTerms.any { instructions.contains(it) }) return false

        // Require at least one direct-contact/stovetop cue. This avoids selecting
        // recipes that happen not to mention an oven but are fundamentally based
        // on boiling, simmering, or another unsupported process.
        return allowedCookingTerms.any { instructions.contains(it) }
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
        val cleanSummary = detail.summary?.replace(Regex("<[^>]*>"), "")?.take(100) ?: "Tawa-compatible recipe"

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

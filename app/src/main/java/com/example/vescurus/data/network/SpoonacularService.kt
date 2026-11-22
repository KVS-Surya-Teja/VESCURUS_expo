package com.example.vescurus.data.network

import com.example.vescurus.BuildConfig
import com.example.vescurus.model.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class RecipeServiceException(val reason: String) : Exception(reason)
@Serializable data class SpoonacularNutrient(val name: String, val amount: Double? = null, val unit: String = "")
@Serializable data class SpoonacularNutrition(val nutrients: List<SpoonacularNutrient> = emptyList())
@Serializable data class SpoonacularStep(val number: Int, val step: String)
@Serializable data class SpoonacularInstruction(val steps: List<SpoonacularStep> = emptyList())
@Serializable data class SpoonacularIngredient(val name: String = "",val amount: Double? = null,val unit: String = "",val original: String = "")
@Serializable data class SpoonacularRecipeDetail(val id: Int,val title: String,val readyInMinutes: Int = 0,
    val image: String? = null,val summary: String? = null,val sourceUrl: String? = null,val servings: Int? = null,
    val nutrition: SpoonacularNutrition? = null,val extendedIngredients: List<SpoonacularIngredient> = emptyList(),
    val analyzedInstructions: List<SpoonacularInstruction> = emptyList())
@Serializable data class SpoonacularSearch(val results: List<SpoonacularRecipeDetail> = emptyList())

object RecipeMapper {
    fun map(d: SpoonacularRecipeDetail, now: Long): Recipe? {
        val instructions=d.analyzedInstructions.flatMap { it.steps }.filter { it.step.isNotBlank() }
        if(instructions.isEmpty()) return null
        val nutrients=d.nutrition?.nutrients.orEmpty()
        fun nutrient(name: String,unit: String): Double? = nutrients.firstOrNull { it.name.equals(name,true) && it.unit.equals(unit,true) }?.amount?.takeIf { it.isFinite() && it>=0 }
        val total=d.readyInMinutes.coerceAtLeast(1)*60_000L
        return Recipe(id="spoon-${d.id}",name=d.title,description=d.summary?.replace(Regex("<[^>]*>"),"")?.take(180).orEmpty(),
            thumbnailUrl=d.image,totalTimeMs=total,steps=instructions.mapIndexed { i,s -> RecipeStep(i*total/instructions.size,(i+1)*total/instructions.size,s.step) },
            calories=nutrient("Calories","kcal")?.toInt(),proteinG=nutrient("Protein","g")?.toFloat(),carbsG=nutrient("Carbohydrates","g")?.toFloat(),fatsG=nutrient("Fat","g")?.toFloat(),
            source="Spoonacular",sourceUrl=d.sourceUrl,servings=d.servings,targets=d.extendedIngredients.map { RecipeTarget(it.name,it.amount,it.unit,it.original) },savedAt=now)
    }
    fun panCompatible(r: Recipe): Boolean {
        val text=r.steps.joinToString(" ") { it.instruction.lowercase() }
        val forbidden=listOf("oven","bake","microwave","pressure cook","air fryer","deep-fry","steamer","steam ","cover the pan","cover and cook","boil","simmer","lid")
        if(forbidden.any { Regex("\\b"+Regex.escape(it)).containsMatchIn(text) }) return false
        if(Regex("\\b(cook|simmer|boil|poach)\\b[^.!?]{0,120}\\bwater\\b").containsMatchIn(text))return false
        return listOf("pan","skillet","fry","sauté","saute","griddle","tawa","toast","sear").any { Regex("\\b"+Regex.escape(it)+"\\b").containsMatchIn(text) }
    }
}

class SpoonacularService {
    companion object {
        private val client=HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis=15_000;connectTimeoutMillis=8_000 } }
        private val json=Json { ignoreUnknownKeys=true }
    }
    suspend fun fetchRecipesByIngredients(ingredients: List<String>): List<Recipe> = withContext(Dispatchers.IO) {
        if(BuildConfig.SPOONACULAR_API_KEY.isBlank()) throw RecipeServiceException("UNCONFIGURED")
        val response=client.get("https://api.spoonacular.com/recipes/complexSearch") {
            header("x-api-key",BuildConfig.SPOONACULAR_API_KEY)
            parameter("includeIngredients",ingredients.joinToString(","));parameter("equipment","pan")
            parameter("number",6);parameter("instructionsRequired",true);parameter("addRecipeInformation",true)
            parameter("addRecipeNutrition",true);parameter("fillIngredients",true)
        }
        when(response.status.value) {
            401,403 -> throw RecipeServiceException("CREDENTIALS")
            402 -> throw RecipeServiceException("QUOTA_EXHAUSTED")
            429 -> throw RecipeServiceException("RATE_LIMITED")
        }
        if(response.status.value !in 200..299) throw RecipeServiceException("SERVICE_UNAVAILABLE")
        json.decodeFromString<SpoonacularSearch>(response.bodyAsText()).results.mapNotNull { RecipeMapper.map(it,System.currentTimeMillis()) }.filter(RecipeMapper::panCompatible)
    }
}

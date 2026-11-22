package com.example.vescurus

import com.example.vescurus.data.network.*
import com.example.vescurus.model.Recipe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RecipeNutrientsTest {
    private fun recipe(nutrients: List<SpoonacularNutrient>) = RecipeMapper.map(
        SpoonacularRecipeDetail(12, "Test recipe", nutrition = SpoonacularNutrition(nutrients),
            analyzedInstructions = listOf(SpoonacularInstruction(listOf(SpoonacularStep(1, "Saute in a pan."))))), 1)!!

    @Test fun actualNutrientValuesKeepTheirOwnUnitsAndSurviveSerialization() {
        val result = recipe(listOf(SpoonacularNutrient(" Vitamin C ", 2.5, " MG "),
            SpoonacularNutrient("Vitamin B12", .003, "mcg"), SpoonacularNutrient("Calcium", 0.0, "mg")))
        assertEquals(listOf("Vitamin C", "Vitamin B12", "Calcium"), result.nutrients.map { it.name })
        assertEquals(listOf("mg", "µg", "mg"), result.nutrients.map { it.unit })
        assertEquals(.003, result.nutrients[1].amount, 0.0)
        assertEquals(result, Json.decodeFromString<Recipe>(Json.encodeToString(result)))
    }

    @Test fun conflictingUnitsOrAmountsAreNotCombinedOrArbitrarilyChosen() {
        val result = recipe(listOf(SpoonacularNutrient("Vitamin A", 100.0, "IU"),
            SpoonacularNutrient("Vitamin A", 30.0, "µg"),
            SpoonacularNutrient("Iron", 1.0, "mg"), SpoonacularNutrient("Iron", 2.0, "mg"),
            SpoonacularNutrient("Vitamin C", 3.0, "mg"), SpoonacularNutrient("Vitamin C", 3.0, "mg")))
        assertEquals(listOf("Vitamin C"), result.nutrients.map { it.name })
    }

    @Test fun unknownMalformedNegativeAndNonfiniteValuesAreOmitted() {
        val result = recipe(listOf(SpoonacularNutrient("Imaginary vitamin", 4.0, "mg"),
            SpoonacularNutrient("Vitamin C", -1.0, "mg"), SpoonacularNutrient("Iron", Double.NaN, "mg"),
            SpoonacularNutrient("Calcium", Double.POSITIVE_INFINITY, "mg"), SpoonacularNutrient("Vitamin B12", 1.0, "g"),
            SpoonacularNutrient("Potassium", null, "mg"), SpoonacularNutrient("x".repeat(100), 1.0, "mg")))
        assertTrue(result.nutrients.isEmpty())
    }

    @Test fun oldRecipesAndAbsentNutritionDoNotGainInventedValues() {
        val old = """{"id":"old","name":"Old recipe","description":"Saved before nutrients","totalTimeMs":1000,"steps":[]}"""
        assertTrue(Json.decodeFromString<Recipe>(old).nutrients.isEmpty())
        assertTrue(recipe(emptyList()).nutrients.isEmpty())
    }
}

package com.example.vescurus.model

import com.example.vescurus.session.CookingSession
import com.example.vescurus.session.EntryStatus
import com.example.vescurus.session.QuantityUnit
import kotlin.math.roundToInt

/** Offline recipe for three confirmed raw vegetables. Nutrition uses edible grams and SR Legacy values. */
internal fun quickChoppedSalad(session: CookingSession): Recipe? {
    val profiles = mapOf(
        "cucumber" to doubleArrayOf(15.0, .65, 3.63, .11, 2.8),
        "carrot" to doubleArrayOf(41.0, .93, 9.58, .24, 5.9),
        "beetroot" to doubleArrayOf(43.0, 1.61, 9.56, .17, 4.9)
    )
    fun vegetable(label: String): String? {
        val candidate = label.lowercase().replace(Regex("\\b(chopped|diced|sliced|raw|shredded|grated|julienned)\\b"), "").trim()
        return when (candidate) {
            "cucumber", "cucumbers" -> "cucumber"
            "carrot", "carrots" -> "carrot"
            "beet", "beets", "beetroot", "beetroots", "beet root" -> "beetroot"
            else -> null
        }
    }
    val entries = session.entries.filter { it.status != EntryStatus.UNDONE }
    val names = entries.flatMap { it.identities }.map { vegetable(it) ?: return null }
    if (names.toSet() != profiles.keys || entries.any {
        it.ingredientPreparation?.contains(Regex("cooked|fried|boiled|roasted|steamed", RegexOption.IGNORE_CASE)) == true
    }) return null
    val totals = DoubleArray(5)
    val hasAmounts = entries.all { it.identities.size == 1 && it.quantity?.unit == QuantityUnit.GRAMS }
    if (hasAmounts) entries.forEach { entry ->
        val values = profiles.getValue(vegetable(entry.identities.single())!!)
        for (index in totals.indices) totals[index] += values[index] * entry.quantity!!.value / 100
    }
    val source = "Offline salad · USDA FoodData Central estimates"
    return Recipe(
        id = "local-chopped-salad", name = "Cucumber, carrot & beetroot salad",
        description = if (hasAmounts) "One bowl. Nutrition estimates use your recorded vegetable weights and exclude seasoning."
            else "One bowl. Record separate gram amounts for all three vegetables to calculate nutrition.",
        totalTimeMs = 15_000, steps = listOf(
            RecipeStep(0, 5_000, "Mix the chopped cucumber, carrot and beetroot in a bowl for about five seconds."),
            RecipeStep(5_000, 15_000, "Add salt and pepper to taste. Toss briefly and serve.")
        ),
        calories = totals[0].roundToInt().takeIf { hasAmounts },
        proteinG = totals[1].toFloat().takeIf { hasAmounts },
        carbsG = totals[2].toFloat().takeIf { hasAmounts },
        fatsG = totals[3].toFloat().takeIf { hasAmounts },
        nutrients = if (hasAmounts) listOf(RecipeNutrient("Vitamin C", totals[4], "mg")) else emptyList(),
        servings = 1, source = source, sourceUrl = "https://fdc.nal.usda.gov/",
        targets = entries.map { RecipeTarget(it.label, it.quantity?.value, it.quantity?.unit?.label.orEmpty(),
            it.quantity?.let { q -> "${q.value} ${q.unit.label} ${it.label}" } ?: it.label) }
    )
}

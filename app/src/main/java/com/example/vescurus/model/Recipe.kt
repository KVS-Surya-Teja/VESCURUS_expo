package com.example.vescurus.model

import androidx.annotation.DrawableRes
import com.example.vescurus.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class RecipeStep(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val instruction: String,
    val ttsPrompt: String? = null
)

@Serializable
data class RecipeNutrient(val name: String, val amount: Double, val unit: String)

@Serializable
data class Recipe(
    val id: String,
    val categoryClass: Int = 0,
    val name: String,
    val description: String,
    @Transient @DrawableRes val thumbnail: Int = R.drawable.ic_vescurus_logo,
    val thumbnailUrl: String? = null,
    val totalTimeMs: Long,
    val steps: List<RecipeStep>,
    val calories: Int? = null,
    val proteinG: Float? = null,
    val carbsG: Float? = null,
    val fatsG: Float? = null,
    val source: String = "VESCURUS built-in guide",
    val sourceUrl: String? = null,
    val servings: Int? = null,
    val targets: List<RecipeTarget> = emptyList(),
    val savedAt: Long? = null,
    val nutrients: List<RecipeNutrient> = emptyList()
)

val EGG_RECIPES = listOf(
    Recipe(
        id = "omelette",
        categoryClass = 1,
        name = "Street-Style Tomato Pepper Omelette",
        description = "A classic spicy and soft omelette.",
        thumbnail = R.drawable.omelette,
        totalTimeMs = 210000L, // 3.5 mins
        steps = listOf(
            RecipeStep(0, 30000, "Heat 1 tsp oil/butter in a tawa on medium heat.", "Heat one teaspoon of oil or butter in a pan on medium heat."),
            RecipeStep(30000, 90000, "Add 2 tbsp finely chopped onions & saute until translucent.", "Add two tablespoons of chopped onions and saute them until they are translucent."),
            RecipeStep(90000, 120000, "Add 2 tbsp chopped tomatoes & cook until soft.", "Add two tablespoons of chopped tomatoes and cook until they soften."),
            RecipeStep(120000, 150000, "Pour 2 whisked eggs evenly over the sautéed vegetables.", "Pour two whisked eggs evenly over the sautéed vegetables."),
            RecipeStep(150000, 210000, "Sprinkle a pinch of salt & black pepper on top, flip once set, and serve.", "Sprinkle salt and black pepper, flip it once it's set, and you're ready to serve.")
        ),
        calories = 240, proteinG = 14f, carbsG = 6f, fatsG = 18f
    ),
    Recipe(
        id = "scrambled",
        categoryClass = 2,
        name = "Scrambled Eggs with Green Chilli & Onion",
        description = "Soft curds with a spicy kick.",
        thumbnail = R.drawable.scrambled_eggs,
        totalTimeMs = 90000L, // 1.5 mins
        steps = listOf(
            RecipeStep(0, 30000, "Warm a lightly oiled pan over medium-low heat. Add chopped onion and green chilli; stir until softened. Whisk the eggs in a bowl.", "Warm a lightly oiled pan over medium low heat. Soften the chopped onion and green chilli, then whisk the eggs."),
            RecipeStep(30000, 40000, "Pour in the whisked eggs and add salt and pepper.", "Pour in the whisked eggs and add salt and pepper."),
            RecipeStep(40000, 60000, "Gently stir and fold the eggs as soft curds form. Lower the heat if they brown quickly.", "Gently stir and fold the eggs. Lower the heat if they brown quickly."),
            RecipeStep(60000, 90000, "Continue cooking until the eggs are set with no runny liquid remaining, then serve. Allow more time if needed; the timer is only a reminder.", "Continue until the eggs are set with no runny liquid, then serve. Take more time if needed.")
        ),
        calories = 220, proteinG = 13f, carbsG = 4f, fatsG = 16f
    ),
    Recipe(
        id = "sunny_side",
        categoryClass = 3,
        name = "Crispy Sunny Side-Up with Chili-Garlic Oil",
        description = "Crispy edges with a rich chili-garlic infusion.",
        thumbnail = R.drawable.sunny_side_up,
        totalTimeMs = 180000L, // 3 mins
        steps = listOf(
            RecipeStep(0, 45000, "Add 1 tbsp oil and 1 tsp minced garlic; sizzle on low-medium until fragrant.", "Add one tablespoon of oil and one teaspoon of minced garlic. Sizzle on low medium until fragrant."),
            RecipeStep(45000, 75000, "Add ½ tsp red chilli flakes directly into the hot oil.", "Add half a teaspoon of red chilli flakes directly into the hot oil."),
            RecipeStep(75000, 150000, "Crack 1–2 eggs directly into the infused chili-garlic oil.", "Crack one or two eggs directly into the infused oil."),
            RecipeStep(150000, 180000, "Spoon hot spiced oil over the egg whites to set them, season with salt, and plate.", "Spoon the hot spiced oil over the whites to set them, season with salt, and plate it.")
        ),
        calories = 260, proteinG = 12f, carbsG = 2f, fatsG = 22f
    ),
    Recipe(
        id = "pancake",
        categoryClass = 4,
        name = "3-Ingredient Banana Tawa Pancake",
        description = "Sweet, healthy, and easy pancakes.",
        thumbnail = R.drawable.pancake,
        totalTimeMs = 240000L, // 4 mins
        steps = listOf(
            RecipeStep(0, 60000, "Peel 1 ripe banana and mash thoroughly with a fork until smooth.", "Peel a ripe banana and mash it thoroughly with a fork until smooth."),
            RecipeStep(60000, 105000, "Crack in 1 whole egg and whisk until fully blended into the mashed banana.", "Crack in one egg and whisk until fully blended into the banana."),
            RecipeStep(105000, 135000, "Add 2 tbsp all-purpose / wheat flour and stir briefly until a thick batter forms.", "Add two tablespoons of flour and stir until a thick batter forms."),
            RecipeStep(135000, 150000, "Grease the non-stick tawa with ½ tsp butter/oil on medium heat.", "Grease the pan with half a teaspoon of butter or oil on medium heat."),
            RecipeStep(150000, 210000, "Pour the batter onto the center; cook until surface bubbles and edges firm up.", "Pour the batter onto the pan. Cook until bubbles form and the edges firm up."),
            RecipeStep(210000, 240000, "Flip gently, cook the other side for 30–45 seconds until golden brown, and serve.", "Flip it gently, cook for another thirty to forty five seconds until golden brown, and serve.")
        ),
        calories = 310, proteinG = 8f, carbsG = 42f, fatsG = 12f
    )
)

package com.example.vescurus.model

import androidx.annotation.DrawableRes
import com.example.vescurus.R

data class RecipeStep(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val instruction: String,
    val ttsPrompt: String? = null
)

/**
 * Scripted chatbot beat — an AI-authored coaching message that appears in
 * the chat panel when the cook timer crosses [atMs]. Independent of the
 * step announcements so we can time cheerleading/warnings independently of
 * when a new step starts.
 */
data class ScriptedChatBeat(
    val atMs: Long,
    val text: String
)

data class Recipe(
    val id: String,
    val categoryClass: Int, // 1: Omelette, 2: Scramble
    val name: String,
    val description: String,
    @DrawableRes val thumbnail: Int,
    val totalTimeMs: Long,
    val steps: List<RecipeStep>,
    val chatBeats: List<ScriptedChatBeat> = emptyList(),
    val calories: Int,
    val proteinG: Float,
    val carbsG: Float,
    val fatsG: Float,
    /** Free-text "micros" line for the Track screen — decorative for the demo. */
    val micros: String = ""
)

val EGG_RECIPES = listOf(
    Recipe(
        id = "omelette",
        categoryClass = 1,
        name = "Street-Style Onion & Chili Omelette",
        description = "Frothy 2-egg omelette with onions, green chili, coriander, and warm spices.",
        thumbnail = R.drawable.omelette,
        totalTimeMs = 270_000L, // 4:30
        steps = listOf(
            RecipeStep(
                0L, 30_000L,
                "Crack 2–3 eggs into a bowl. Add 2 tbsp chopped onions, 1 chopped green chili, 1 tbsp coriander, a pinch of salt, pepper, and red chili powder. Whisk until frothy.",
                "Crack the eggs, add onion, chili, coriander, salt, pepper, and chili powder. Whisk hard until frothy."
            ),
            RecipeStep(
                30_000L, 90_000L,
                "Turn the Glen tawa to medium-high. Wait until the green ready-light glows.",
                "Preheat the tawa on medium high. Wait for the green ready light to come on."
            ),
            RecipeStep(
                90_000L, 105_000L,
                "Melt 1 tsp butter — or brush a light layer of oil — across the center of the plate.",
                "Melt a teaspoon of butter across the center of the plate."
            ),
            RecipeStep(
                105_000L, 135_000L,
                "Pour the egg mixture into the center. Spread it into a neat, wide circle with a wooden spatula.",
                "Pour the eggs into the center and spread them into a neat circle."
            ),
            RecipeStep(
                135_000L, 225_000L,
                "Turn the temperature down to medium-low. Cook 1–2 minutes until the edges lift and the top looks set.",
                "Turn the heat down to medium low. Let it cook until the edges lift and the top is set."
            ),
            RecipeStep(
                225_000L, 270_000L,
                "Slide the bamboo spatula underneath. Flip carefully and cook the other side for 30–45 seconds.",
                "Slide the spatula under, flip carefully, and cook the other side for thirty to forty five seconds."
            )
        ),
        chatBeats = listOf(
            ScriptedChatBeat(12_000L, "Whisk vigorously — the frothier the mix, the fluffier the omelette."),
            ScriptedChatBeat(60_000L, "Wait for the green ready-light before you pour. Rushing the preheat is the #1 sticking cause."),
            ScriptedChatBeat(115_000L, "Spread it thin and wide — the outer edges set fastest."),
            ScriptedChatBeat(190_000L, "Watch the edges — when they curl and lift on their own, you're ready to flip."),
            ScriptedChatBeat(245_000L, "Only 30 seconds on the second side. Overcooking dries it out.")
        ),
        calories = 240,
        proteinG = 14f,
        carbsG = 4f,
        fatsG = 19f,
        micros = "Vit B12 · Selenium · Choline · Vit A"
    ),
    Recipe(
        id = "scrambled",
        categoryClass = 2,
        name = "Soft Buttery Scrambled Eggs",
        description = "Slow-folded scrambled eggs with butter, finished at 85 % on residual heat.",
        thumbnail = R.drawable.scrambled_eggs,
        totalTimeMs = 180_000L, // 3:00
        steps = listOf(
            RecipeStep(
                0L, 30_000L,
                "Crack 2 eggs into a bowl. Add 1 tbsp milk or cream (optional), a pinch of salt and pepper. Whisk hard for 30 seconds until frothy.",
                "Crack two eggs. Add a splash of milk if using, plus salt and pepper. Whisk hard for thirty seconds."
            ),
            RecipeStep(
                30_000L, 75_000L,
                "Turn the Glen tawa to medium-low. Let it heat for 30–45 seconds.",
                "Preheat the tawa on medium low for thirty to forty five seconds."
            ),
            RecipeStep(
                75_000L, 90_000L,
                "Drop 1 tbsp butter in the center. It should sizzle gently — not smoke.",
                "Drop a tablespoon of butter in the center. It should sizzle, not smoke."
            ),
            RecipeStep(
                90_000L, 105_000L,
                "Pour the egg mix onto the hot plate. Let it sit UNTOUCHED for 5 seconds so a base forms.",
                "Pour the eggs onto the plate. Let them sit untouched for five seconds."
            ),
            RecipeStep(
                105_000L, 150_000L,
                "Use a wooden or silicone spatula (never metal). Gently fold cooked egg from the edges to the center in slow, sweeping motions.",
                "Fold gently from the edges to the center with slow sweeping motions."
            ),
            RecipeStep(
                150_000L, 180_000L,
                "When the eggs look glossy and about 85 % done, switch off the heat and scoop them off immediately. Residual heat finishes them.",
                "Turn off the heat when the eggs look glossy and about eighty five percent done. Scoop them off immediately."
            )
        ),
        chatBeats = listOf(
            ScriptedChatBeat(12_000L, "Frothy equals fluffy. Whisk hard for the full 30 seconds."),
            ScriptedChatBeat(50_000L, "Medium-low only. High heat makes rubbery, tight scrambles."),
            ScriptedChatBeat(95_000L, "Don't touch it yet — those 5 seconds let the base set."),
            ScriptedChatBeat(125_000L, "Slow folds make big soft curds. Fast stirring makes small hard ones."),
            ScriptedChatBeat(155_000L, "Turn the heat off NOW while they still look wet. They'll finish on the plate.")
        ),
        calories = 260,
        proteinG = 13f,
        carbsG = 1f,
        fatsG = 22f,
        micros = "Vit B12 · Vit D · Riboflavin · Choline"
    )
)

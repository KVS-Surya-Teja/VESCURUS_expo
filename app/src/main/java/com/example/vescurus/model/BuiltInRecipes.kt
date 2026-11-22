package com.example.vescurus.model

/** Explicit offline guides, not API results or claims about what has been added. */
val BUILT_IN_RECIPES: List<Recipe> = listOf(
    Recipe(id="local-tomato-onion",name="Tomato & onion sauté",description="A simple pan guide with tomato, onion and oil.",totalTimeMs=15*60_000L,
        steps=listOf(RecipeStep(0,60_000,"Wash and chop the tomatoes and onion. Confirm your preparation quantities."),
            RecipeStep(60_000,180_000,"Warm the oil in a pan on medium heat."),
            RecipeStep(180_000,480_000,"Add onion and stir until softened; adjust heat as needed."),
            RecipeStep(480_000,900_000,"Add tomato and salt. Stir and cook to your preferred texture, then confirm completion.")),
        servings=2,targets=listOf(RecipeTarget("Tomato",200.0,"g","200 g tomato"),RecipeTarget("Onion",100.0,"g","100 g onion"),RecipeTarget("Oil",10.0,"ml","10 ml oil"))),
    Recipe(id="local-carrot-pepper",name="Carrot & pepper stir-fry",description="A colourful vegetable pan guide.",totalTimeMs=20*60_000L,
        steps=listOf(RecipeStep(0,180_000,"Wash and slice carrot and bell pepper into similar sized pieces."),
            RecipeStep(180_000,300_000,"Warm oil in a pan. Add the carrots and stir."),
            RecipeStep(300_000,900_000,"Add peppers, season, and stir-fry. Adjust heat to prevent burning."),
            RecipeStep(900_000,1_200_000,"Check the texture yourself. Continue cooking if needed, then confirm serving.")),
        servings=2,targets=listOf(RecipeTarget("Carrot",150.0,"g","150 g carrot"),RecipeTarget("Bell pepper",150.0,"g","150 g bell pepper"))),
    Recipe(id="local-potato-skillet",name="Potato skillet",description="Small potato pieces cooked in a pan.",totalTimeMs=30*60_000L,
        steps=listOf(RecipeStep(0,300_000,"Wash the potato, trim as needed, and cut into small even pieces."),
            RecipeStep(300_000,420_000,"Warm oil in a pan on medium heat."),
            RecipeStep(420_000,1_500_000,"Add potatoes in a single layer. Turn regularly and adjust the heat."),
            RecipeStep(1_500_000,1_800_000,"Check that the potato pieces are cooked through. Continue if needed; confirm completion yourself.")),
        servings=2,targets=listOf(RecipeTarget("Potato",300.0,"g","300 g potato"),RecipeTarget("Oil",15.0,"ml","15 ml oil")))
) + EGG_RECIPES.map { it.copy(calories=null,proteinG=null,carbsG=null,fatsG=null,source="VESCURUS built-in guide") }

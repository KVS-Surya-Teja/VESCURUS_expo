package com.example.vescurus.model

import java.util.Locale

/**
 * Normalizes a Gemini-supplied label to VESCURUS's canonical ingredient
 * vocabulary. Returns null for anything we don't yet cook with — the caller
 * should filter these out rather than treat them as edible.
 *
 * The V0 detector prompt only returns "egg"; the rest of the vocabulary is
 * here so downstream code (recipe matching, chat) can uniformly canonicalize
 * whatever the user says or types.
 */
fun canonicalizeIngredientLabel(rawLabel: String): String? {
    val normalized = rawLabel.trim().lowercase(Locale.US)
    return when {
        normalized.contains("unsupported") -> "Unsupported object"
        normalized.contains("egg") -> "egg"
        normalized.contains("onion") -> "onion"
        normalized.contains("green chili") || normalized.contains("green chilli") ||
            normalized == "chili" || normalized == "chilli" -> "green chili"
        normalized.contains("tomato") -> "tomato"
        normalized.contains("banana") -> "banana"
        normalized.contains("flour") -> "flour"
        normalized.contains("salt") -> "salt"
        normalized.contains("black pepper") || normalized == "pepper" -> "black pepper"
        normalized.contains("oil") -> "oil"
        normalized.contains("butter") -> "butter"
        normalized.contains("milk") -> "milk"
        normalized.contains("turmeric") -> "turmeric powder"
        normalized.contains("red chilli") || normalized.contains("red chili") ||
            normalized.contains("chilli powder") || normalized.contains("chili powder") -> "red chilli powder"
        else -> null
    }
}

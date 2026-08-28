package com.example.vescurus.model

import java.util.Locale

val SUPPORTED_INGREDIENT_LABELS = setOf(
    "egg",
    "onion",
    "green chili",
    "tomato",
    "banana",
    "flour",
    "salt",
    "black pepper",
    "oil",
    "butter",
    "milk",
    "turmeric powder",
    "red chilli powder"
)

fun canonicalizeIngredientLabel(rawLabel: String): String? {
    val normalized = rawLabel.trim().lowercase(Locale.US)
    return when {
        normalized.contains("unsupported") -> "Unsupported object"
        normalized.contains("egg") -> "egg"
        normalized.contains("onion") -> "onion"
        normalized.contains("green chili") || normalized.contains("green chilli") || normalized == "chili" || normalized == "chilli" -> "green chili"
        normalized.contains("tomato") -> "tomato"
        normalized.contains("banana") -> "banana"
        normalized.contains("flour") -> "flour"
        normalized.contains("salt") -> "salt"
        normalized.contains("black pepper") || normalized == "pepper" -> "black pepper"
        normalized.contains("oil") -> "oil"
        normalized.contains("butter") -> "butter"
        normalized.contains("milk") -> "milk"
        normalized.contains("turmeric") -> "turmeric powder"
        normalized.contains("red chilli") || normalized.contains("red chili") || normalized.contains("chilli powder") || normalized.contains("chili powder") -> "red chilli powder"
        else -> null
    }
}

fun deriveRecipeClass(labels: Collection<String>): Int {
    val labelSet = labels.toSet()
    return when {
        "banana" in labelSet || "flour" in labelSet || "milk" in labelSet -> 4
        "tomato" in labelSet -> 1
        "onion" in labelSet || "green chili" in labelSet -> 2
        "egg" in labelSet -> 3
        else -> 0
    }
}

fun normalizeDetectionBox(box: BoundingBox): BoundingBox? {
    var top = box.top
    var left = box.left
    var bottom = box.bottom
    var right = box.right

    if (top > 1.1f || left > 1.1f || bottom > 1.1f || right > 1.1f) {
        top /= 1000f
        left /= 1000f
        bottom /= 1000f
        right /= 1000f
    }

    return if (top in 0f..1f && left in 0f..1f && bottom in 0f..1f && right in 0f..1f && bottom > top && right > left) {
        BoundingBox(top = top, left = left, bottom = bottom, right = right)
    } else {
        null
    }
}

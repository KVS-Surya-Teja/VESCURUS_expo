package com.example.vescurus.model

import java.util.Locale

/**
 * Universal canonicalizer for open-world ingredient and food labels.
 */
fun canonicalizeIngredientLabel(rawLabel: String): String? {
    val normalized = rawLabel.trim().lowercase(Locale.US)
    if (normalized.isEmpty()) return null
    
    if (normalized.contains("unsupported") || normalized.contains("hazard") || 
        normalized.contains("phone") || normalized.contains("cable") || normalized.contains("pen")) {
        return "Unsupported object"
    }

    // Capitalize words cleanly for UI presentation (e.g. "chicken breast", "cherry tomato")
    return normalized.split(" ").joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
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

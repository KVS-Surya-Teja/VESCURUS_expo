package com.example.vescurus.model

/** Read cooking prose naturally, rather than reading display markup or abbreviated units. */
internal fun spokenGuidance(text: String): String = text
    .replace(Regex("""\[([^]]+)]\([^)]*\)"""), "$1")
    .replace(Regex("""https?://\S+"""), "")
    .replace(Regex("""[`*_#]"""), "")
    .replace(Regex("""(?m)^\s*[-+]\s+"""), "")
    .replace(Regex("""(\d[\d.,]*)\s*g\b"""), "$1 grams")
    .replace(Regex("""(\d[\d.,]*)\s*ml\b""", RegexOption.IGNORE_CASE), "$1 milliliters")
    .replace("°C", " degrees Celsius").replace("°F", " degrees Fahrenheit")
    .replace(Regex("""\s+"""), " ").trim()

package com.example.vescurus.domain.model

/**
 * Which prompt/sanitizer the analyzer should use.
 *
 * - [EGG_ONLY] drives the Guide streaming loop. High-recall on eggs, filters
 *   everything else out. Used for the two-phone cooking demo.
 * - [GENERAL_INGREDIENTS] drives the single-device Snapshot flow. Returns
 *   any ingredient the prompt supports; the UI lets the user review each one.
 */
enum class DetectionMode { EGG_ONLY, GENERAL_INGREDIENTS }

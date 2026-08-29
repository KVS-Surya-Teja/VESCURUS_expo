package com.example.vescurus.core.quality

import android.graphics.Bitmap
import android.graphics.Color

object ImageQualityManager {
    
    data class QualityResult(
        val isSuitable: Boolean,
        val message: String? = null
    )

    fun assess(bitmap: Bitmap): QualityResult {
        val brightness = calculateLuminance(bitmap)
        if (brightness < 40) return QualityResult(false, "Too dark. Improve lighting.")
        if (brightness > 220) return QualityResult(false, "Too bright. Reduce glare.")
        
        return QualityResult(true, null)
    }

    private fun calculateLuminance(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        var totalLuminance = 0.0
        for (pixel in pixels) {
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            // Digital CCIR 601 formula
            totalLuminance += (0.299 * r + 0.587 * g + 0.114 * b)
        }
        return (totalLuminance / (width * height)).toInt()
    }
}

package com.example.vescurus.core.quality

import android.graphics.Bitmap
import android.graphics.Color

object ImageQualityManager {

    data class QualityResult(
        val isSuitable: Boolean,
        val message: String? = null
    )

    /**
     * V0 detection must not be blocked by a global average-brightness gate.
     * The camera frame may legitimately contain a bright table/background and
     * a darker hand while the egg itself is perfectly visible. Keep the
     * assessor permissive and reserve hard rejection for extreme frames.
     */
    fun assess(bitmap: Bitmap): QualityResult {
        val brightness = calculateLuminance(bitmap)

        if (brightness < 10) {
            return QualityResult(false, "Frame is extremely dark.")
        }
        if (brightness > 250) {
            return QualityResult(false, "Frame is extremely overexposed.")
        }

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
            totalLuminance += (0.299 * r + 0.587 * g + 0.114 * b)
        }
        return (totalLuminance / (width * height)).toInt()
    }
}

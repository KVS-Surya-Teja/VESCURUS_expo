package com.example.vescurus.debug

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object VescurusLogger {
    private const val TAG = "VescurusLogger"
    private var sessionDir: File? = null
    private var sequence = 0

    fun initSession(context: Context) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val baseDir = context.getExternalFilesDir("debug")
        sessionDir = File(baseDir, "session_$timestamp").apply { mkdirs() }
        sequence = 0
        Log.d(TAG, "New debug session started at: ${sessionDir?.absolutePath}")
    }

    fun logInference(rawBitmap: Bitmap, cloudBitmap: Bitmap, jsonResponse: String) {
        val currentSeq = sequence++
        saveBitmap(rawBitmap, "seq_${currentSeq}_1_raw.jpg")
        saveBitmap(cloudBitmap, "seq_${currentSeq}_2_cloud.jpg")
        saveText(jsonResponse, "seq_${currentSeq}_3_response.json")
    }

    private fun saveBitmap(bitmap: Bitmap, fileName: String) {
        val file = File(sessionDir, fileName)
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save bitmap $fileName: ${e.message}")
        }
    }

    private fun saveText(text: String, fileName: String) {
        val file = File(sessionDir, fileName)
        try {
            file.writeText(text)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save text $fileName: ${e.message}")
        }
    }
}

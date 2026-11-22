package com.example.vescurus.debug

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.vescurus.BuildConfig
import com.example.vescurus.model.Observation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object VescurusLogger {
    private const val TAG = "VescurusLogger"
    private var sessionDir: File? = null
    private var sequence = 0
    private var evidenceDir: File? = null

    fun initSession(context: Context) {
        evidenceDir=File(context.cacheDir,"vision-evidence").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val baseDir = context.getExternalFilesDir("debug")
        sessionDir = File(baseDir, "session_$timestamp").apply { mkdirs() }
        sequence = 0
        Log.d(TAG, "New debug session started at: ${sessionDir?.absolutePath}")
    }

    @Synchronized fun retainObservation(observation: Observation) {
        if(!BuildConfig.DEBUG)return
        val dir=evidenceDir ?: return
        try {
            File(dir,"${observation.streamId.take(8)}-${observation.frameId}.json").writeText(Json.encodeToString(observation))
            dir.listFiles()?.filter { it.extension=="json" }?.sortedByDescending { it.lastModified() }?.drop(12)?.forEach { it.delete() }
        } catch(_: Exception) { Log.w(TAG,"Could not retain debug frame evidence") }
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

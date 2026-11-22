package com.example.vescurus.model

import kotlinx.serialization.Serializable

@Serializable
data class SessionContext(val sessionId: String, val version: Long = 0, val recipe: String? = null, val step: Int = 0, val paused: Boolean = false, val ingredients: List<String> = emptyList())

/** Upright, uncropped, unmirrored JPEG. Normalized YXYX boxes refer to exactly these pixels. */
@Serializable
data class Observation(
    val sessionId: String, val streamId: String, val frameId: Long,
    val captureUptimeMs: Long, val width: Int, val height: Int,
    val engine: String, val status: String, val inferenceMs: Long, val sourceAgeMs: Long,
    val jpegBase64: String, val detections: List<DetectionResult> = emptyList(),
    val rotationApplied: Int = 0, val mirrored: Boolean = false,
    val coordinates: String = "normalized-yxyx-upright", val message: String? = null,
    val requestId: String? = null
) {
    fun valid(): Boolean = width in 1..4096 && height in 1..4096 && frameId > 0 &&
        inferenceMs >= 0 && sourceAgeMs >= 0 && coordinates == "normalized-yxyx-upright" && !mirrored &&
        jpegBase64.length <= 2_000_000 && detections.size <= 100
}

class ObservationGate(private val ttlMs: Long = 15_000) {
    private var stream: String? = null
    private val retiredStreams = mutableSetOf<String>()
    private var lastFrame = 0L
    fun accept(o: Observation, sessionId: String): Boolean {
        if (!o.valid() || o.sessionId != sessionId || o.sourceAgeMs >= ttlMs || o.streamId in retiredStreams) return false
        if (stream != o.streamId) {
            stream?.let { retiredStreams.add(it) }; stream = o.streamId; lastFrame = 0
        }
        if (o.frameId <= lastFrame) return false
        lastFrame = o.frameId
        return true
    }
    fun fresh(o: Observation, receivedMs: Long, nowMs: Long): Boolean =
        nowMs >= receivedMs && o.sourceAgeMs + nowMs - receivedMs < ttlMs
}

@Serializable data class PairRequest(val code: String, val context: SessionContext)
@Serializable data class VideoFrame(val sessionId: String,val streamId: String,val frameId: Long,
    val captureUptimeMs: Long,val width: Int,val height: Int,val jpegBase64: String,val sourceAgeMs: Long=0) {
    fun asGateObservation()=Observation(sessionId,streamId,frameId,captureUptimeMs,width,height,"camera","VIDEO",0,sourceAgeMs,jpegBase64)
    fun valid()=asGateObservation().valid()
}

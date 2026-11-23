package com.example.vescurus.ui

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.vescurus.session.ScaleMeasurementGate
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

@Serializable
internal data class ScaleReading(
    val deviceId: String, val bootId: String, val sequence: Long,
    val senderUptimeMillis: Long, val sampleAgeMillis: Long, val grams: Double,
    val calibrationEpoch: Long, val tareEpoch: Long, val calibrated: Boolean,
    val stable: Boolean, val status: String
)

class CookScale(private val prefs: SharedPreferences) {
    private val pollLock = Mutex()
    private val networkLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private fun newGate() = ScaleMeasurementGate(ScaleMeasurementGate.Config(
        minimumIncreaseGrams = 3.0, stabilityToleranceGrams = 1.0, zeroToleranceGrams = 1.0
    ))
    private var gate = newGate()
    var address by mutableStateOf(prefs.getString("scaleAddress", "").orEmpty())
        private set
    internal var reading by mutableStateOf<ScaleReading?>(null)
        private set
    var status by mutableStateOf("Scale disconnected")
        private set
    var measurement by mutableStateOf<ScaleMeasurementGate.Measurement?>(null)
        private set
    var weighing by mutableStateOf(false)
        private set
    var taring by mutableStateOf(false)
        private set

    fun connect(input: String): Boolean {
        val host = input.trim()
        val octets = host.split('.').map { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it == null || it !in 0..255 }) return false
        val local = octets[0] == 10 || (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && (octets[1] ?: 0) in 16..31)
        if (!local || host.any { !it.isDigit() && it != '.' }) return false
        address = octets.joinToString(".")
        prefs.edit().putString("scaleAddress", address).apply()
        return true
    }

    fun disconnect() {
        address = ""
        prefs.edit().remove("scaleAddress").apply()
        resetWeighing()
        reading = null
        status = "Scale disconnected"
    }

    fun beginWeighing(): Boolean {
        if (taring || !gate.beginAtZero(SystemClock.elapsedRealtime())) return false
        measurement = null
        weighing = true
        return true
    }

    fun resetWeighing() {
        gate.clearPendingAndRequireZero()
        measurement = null
        weighing = false
    }

    suspend fun monitor(host: String, onMeasured: (Double) -> Unit = {}) = pollLock.withLock {
        if (host.isBlank()) return@withLock
        gate = newGate()
        try {
            while (currentCoroutineContext().isActive && host == address) {
                val started = SystemClock.elapsedRealtime()
                try {
                    val packet = json.decodeFromString<ScaleReading>(request(host, "/weight").second)
                    val now = SystemClock.elapsedRealtime()
                    require(packet.grams.isFinite() && packet.grams in -5000.0..5000.0)
                    require(packet.sampleAgeMillis in 0..1000 && now - started <= 1000)
                    if (host != address) break
                    reading = packet
                    status = when {
                        packet.status == "taring" -> "Taring…"
                        packet.status == "tare_required" -> "Empty the holder and tare"
                        packet.status != "ready" -> "Check the scale sensor"
                        !packet.calibrated -> "Calibration pending"
                        !packet.stable -> "Settling…"
                        else -> "Stable"
                    }
                    if (packet.status == "ready") {
                        val result = gate.observe(ScaleMeasurementGate.Packet(
                            packet.deviceId, packet.bootId, packet.sequence, packet.senderUptimeMillis,
                            started - packet.sampleAgeMillis, packet.calibrationEpoch, packet.tareEpoch,
                            packet.calibrated, packet.stable, packet.grams
                        ), now)
                        when (result) {
                            is ScaleMeasurementGate.Result.Ready -> {
                                measurement = result.measurement
                                onMeasured(result.measurement.grams)
                            }
                            is ScaleMeasurementGate.Result.Invalidated -> resetWeighing()
                            else -> Unit
                        }
                        if (!gate.checkFreshness(now)) resetWeighing()
                    } else {
                        resetWeighing()
                        gate.onDisconnected()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    reading = null
                    status = "Scale unavailable"
                    resetWeighing()
                    gate.onDisconnected()
                }
                delay((250 - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(25))
            }
        } finally {
            resetWeighing()
            reading = null
            status = "Scale disconnected"
        }
    }

    suspend fun tare(): String {
        val previous = reading ?: return "Connect to the scale first."
        val host = address
        if (taring || host.isEmpty()) return "Wait for the current tare."
        taring = true
        resetWeighing()
        return try {
            val response = request(host, "/tare?id=${UUID.randomUUID()}", post = true)
            if (response.first != 202) "Scale did not accept tare. Check the holder and try again."
            else {
                val confirmed = withTimeoutOrNull(8000) {
                    while (true) {
                        val current = reading
                        if (host != address || current?.bootId?.let { it != previous.bootId } == true) break
                        if (current != null && current.tareEpoch > previous.tareEpoch && current.status == "ready")
                            return@withTimeoutOrNull true
                        delay(150)
                    }
                    false
                } == true
                if (confirmed) "Tare complete." else "Tare could not be confirmed. Check the scale before trying again."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            "Tare could not be confirmed. Check the scale before trying again."
        } finally {
            taring = false
        }
    }

    private suspend fun request(host: String, path: String, post: Boolean = false): Pair<Int, String> =
        networkLock.withLock {
            withContext(Dispatchers.IO) {
                val connection = URL("http://$host$path").openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
                try {
                    connection.connectTimeout = 900
                    connection.readTimeout = 900
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.requestMethod = if (post) "POST" else "GET"
                    val code = connection.responseCode
                    if (post) return@withContext code to ""
                    require(code == 200)
                    val bytes = ByteArray(1025)
                    val count = connection.inputStream.use { stream ->
                        var size = 0
                        while (size < bytes.size) {
                            val read = stream.read(bytes, size, bytes.size - size)
                            if (read < 0) break
                            size += read
                        }
                        size
                    }
                    require(count < bytes.size)
                    code to bytes.decodeToString(0, count)
                } finally {
                    connection.disconnect()
                }
            }
        }
}

package com.example.vescurus.session

import kotlin.math.abs

/** Pure measurement gate. Tolerances must come from the mounted scale's observed capability. */
class ScaleMeasurementGate(private val config: Config) {
    data class Config(
        val minimumIncreaseGrams: Double,
        val stabilityToleranceGrams: Double,
        val zeroToleranceGrams: Double,
        val stableDurationMillis: Long = 5_000,
        val maximumPacketGapMillis: Long = 1_500,
        val maximumPacketAgeMillis: Long = 1_500,
    ) {
        init {
            require(minimumIncreaseGrams.isFinite() && minimumIncreaseGrams > 0)
            require(stabilityToleranceGrams.isFinite() && stabilityToleranceGrams >= 0)
            require(zeroToleranceGrams.isFinite() && zeroToleranceGrams >= 0)
            require(stableDurationMillis >= 5_000)
            require(maximumPacketGapMillis > 0 && maximumPacketAgeMillis > 0)
        }
    }
    data class Packet(
        val deviceId: String,
        val bootId: String,
        val sequence: Long,
        val senderUptimeMillis: Long,
        /** Assigned by the receiver's monotonic clock, never copied from the ESP32 clock. */
        val receivedElapsedMillis: Long,
        val calibrationEpoch: Long,
        val tareEpoch: Long,
        val calibrated: Boolean,
        val stable: Boolean,
        val grams: Double,
    )
    data class Measurement(
        val id: String,
        val grams: Double,
        val deviceId: String,
        val bootId: String,
        val calibrationEpoch: Long,
        val tareEpoch: Long,
        val baselineSequence: Long,
        val finalSequence: Long,
        val stableForMillis: Long,
        val baselineGrams: Double,
    )
    sealed class Result {
        data class Rejected(val reason: String) : Result()
        data class Invalidated(val reason: String) : Result()
        data class Ready(val measurement: Measurement) : Result()
        data class Pending(val measurement: Measurement) : Result()
        object Waiting : Result()
    }

    private var last: Packet? = null
    private var baseline: Packet? = null
    private val retiredBoots = mutableSetOf<String>()
    private var connected = false
    private var windowStart: Long? = null
    private var windowMinimum = 0.0
    private var windowMaximum = 0.0
    private var windowSum = 0.0
    private var windowCount = 0L
    var pending: Measurement? = null
        private set

    /** Explicit user action, after tare/calibration: this does not pretend an unknown bowl is zero. */
    fun beginAtZero(nowElapsedMillis: Long): Boolean {
        val packet = last ?: return false
        if (!connected || !fresh(packet, nowElapsedMillis) || !packet.calibrated || !packet.stable ||
            abs(packet.grams) > config.zeroToleranceGrams) return false
        baseline = packet
        pending = null
        resetWindow()
        return true
    }

    fun observe(packet: Packet, nowElapsedMillis: Long): Result {
        if (packet.deviceId.isBlank() || packet.bootId.isBlank() || packet.sequence < 0 ||
            packet.senderUptimeMillis < 0 || packet.calibrationEpoch < 0 || packet.tareEpoch < 0 ||
            !packet.grams.isFinite()) return Result.Rejected("Invalid measurement packet")
        if (!fresh(packet, nowElapsedMillis)) return Result.Rejected("Stale measurement packet")
        val previous = last
        if (packet.bootId in retiredBoots) return Result.Rejected("Retired scale boot")
        if (previous != null) {
            if (packet.deviceId != previous.deviceId) return Result.Rejected("Different scale device")
            if (packet.bootId == previous.bootId) {
                if (packet.sequence <= previous.sequence) return Result.Rejected("Duplicate or reordered packet")
                if (packet.senderUptimeMillis < previous.senderUptimeMillis) {
                    invalidate()
                    return Result.Invalidated("Scale clock restarted without a new boot identity")
                }
                if (packet.receivedElapsedMillis < previous.receivedElapsedMillis)
                    return Result.Rejected("Receiver timestamp moved backwards")
            }
        }
        if (previous != null && packet.bootId != previous.bootId) retiredBoots += previous.bootId
        last = packet
        connected = true
        if (previous != null && (packet.bootId != previous.bootId ||
                packet.calibrationEpoch != previous.calibrationEpoch || packet.tareEpoch != previous.tareEpoch)) {
            invalidate()
            return Result.Invalidated("Scale restarted, was tared, or calibration changed; establish zero again")
        }
        if (!packet.calibrated) {
            invalidate()
            return Result.Invalidated("Calibration pending")
        }
        if (previous != null && (baseline != null || pending != null) &&
            packet.receivedElapsedMillis - previous.receivedElapsedMillis > config.maximumPacketGapMillis) {
            invalidate()
            return Result.Invalidated("Scale readings were interrupted; establish zero again")
        }
        pending?.let { return Result.Pending(it) }
        val zero = baseline ?: return Result.Waiting
        val increase = packet.grams - zero.grams
        if (increase < -config.zeroToleranceGrams) {
            invalidate()
            return Result.Invalidated("Negative change or bowl removal; establish zero again")
        }
        if (!packet.stable || increase < config.minimumIncreaseGrams) {
            resetWindow()
            return Result.Waiting
        }
        if (windowStart == null || maxOf(windowMaximum, increase) - minOf(windowMinimum, increase) > config.stabilityToleranceGrams) {
            windowStart = packet.receivedElapsedMillis
            windowMinimum = increase
            windowMaximum = increase
            windowSum = increase
            windowCount = 1
        } else {
            windowMinimum = minOf(windowMinimum, increase)
            windowMaximum = maxOf(windowMaximum, increase)
            windowSum += increase
            windowCount++
        }
        val duration = packet.receivedElapsedMillis - windowStart!!
        if (duration < config.stableDurationMillis) return Result.Waiting
        val measurement = Measurement(
            "${packet.deviceId}:${packet.bootId}:${packet.calibrationEpoch}:${packet.tareEpoch}:${zero.sequence}:${packet.sequence}",
            windowSum / windowCount, packet.deviceId, packet.bootId, packet.calibrationEpoch,
            packet.tareEpoch, zero.sequence, packet.sequence, duration, zero.grams,
        )
        pending = measurement
        return Result.Ready(measurement)
    }

    /** Acknowledges capture only. Ingredient identity, association and addition need separate user confirmation. */
    fun clearPendingAndRequireZero() = invalidate()
    fun onDisconnected() {
        connected = false
        invalidate()
    }

    /** Call from a receiver timer as well as observe; silence cannot count as stable time. */
    fun checkFreshness(nowElapsedMillis: Long): Boolean {
        val packet = last ?: return false
        if (fresh(packet, nowElapsedMillis)) return true
        invalidate()
        return false
    }

    private fun fresh(packet: Packet, now: Long): Boolean =
        now >= packet.receivedElapsedMillis && now - packet.receivedElapsedMillis <= config.maximumPacketAgeMillis

    private fun invalidate() {
        baseline = null
        pending = null
        resetWindow()
    }
    private fun resetWindow() {
        windowStart = null
        windowCount = 0
        windowSum = 0.0
    }
}

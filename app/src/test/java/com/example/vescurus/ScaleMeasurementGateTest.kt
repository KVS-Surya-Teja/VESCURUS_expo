package com.example.vescurus

import com.example.vescurus.session.ScaleMeasurementGate
import org.junit.Assert.*
import org.junit.Test

class ScaleMeasurementGateTest {
    private fun gate() = ScaleMeasurementGate(ScaleMeasurementGate.Config(
        minimumIncreaseGrams = 5.0, stabilityToleranceGrams = 0.5, zeroToleranceGrams = 0.5,
    ))
    private fun packet(sequence: Long, time: Long, grams: Double = 0.0, boot: String = "boot-a",
        tare: Long = 0, calibrated: Boolean = true, stable: Boolean = true) = ScaleMeasurementGate.Packet(
        "scale-a", boot, sequence, time, time, 1, tare, calibrated, stable, grams,
    )
    private fun arm(gate: ScaleMeasurementGate) {
        gate.observe(packet(0, 0), 0)
        assertTrue(gate.beginAtZero(0))
    }
    @Test fun requiresExplicitCalibratedZeroAndFiveContinuousSeconds() {
        val gate = gate()
        gate.observe(packet(0, 0, calibrated = false), 0)
        assertFalse(gate.beginAtZero(0))
        gate.observe(packet(1, 100, grams = 8.0), 100)
        assertFalse(gate.beginAtZero(100))
        gate.observe(packet(2, 200), 200)
        assertTrue(gate.beginAtZero(200))
        for (i in 1L..5L) assertEquals(ScaleMeasurementGate.Result.Waiting,
            gate.observe(packet(i + 2, i * 1_000, 10.0), i * 1_000))
        val ready = gate.observe(packet(8, 6_000, 10.0), 6_000) as ScaleMeasurementGate.Result.Ready
        assertEquals(10.0, ready.measurement.grams, 0.001)
        assertEquals(5_000L, ready.measurement.stableForMillis)
        assertTrue(gate.observe(packet(9, 7_000, 10.0), 7_000) is ScaleMeasurementGate.Result.Pending)
    }
    @Test fun duplicatesAndStalePacketsCannotAdvanceWindow() {
        val gate = gate(); arm(gate)
        gate.observe(packet(1, 1_000, 10.0), 1_000)
        assertTrue(gate.observe(packet(1, 2_000, 10.0), 2_000) is ScaleMeasurementGate.Result.Rejected)
        assertTrue(gate.observe(packet(2, 2_000, 10.0), 5_000) is ScaleMeasurementGate.Result.Rejected)
        assertNull(gate.pending)
    }
    @Test fun gapRequiresNewZeroAndMovementRestartsTheStableWindow() {
        val gate = gate(); arm(gate)
        gate.observe(packet(1, 1_000, 10.0), 1_000)
        gate.observe(packet(2, 2_000, 10.0), 2_000)
        assertTrue(gate.observe(packet(3, 4_000, 10.0), 4_000) is ScaleMeasurementGate.Result.Invalidated)
        for (i in 5L..10L) assertEquals(ScaleMeasurementGate.Result.Waiting,
            gate.observe(packet(i, i * 1_000, 10.0), i * 1_000))
        assertFalse(gate.beginAtZero(10_000))
        gate.observe(packet(11, 11_000), 11_000)
        assertTrue(gate.beginAtZero(11_000))
        gate.observe(packet(12, 12_000, 10.0), 12_000)
        gate.observe(packet(13, 13_000, 11.0), 13_000)
        for (i in 14L..17L) assertEquals(ScaleMeasurementGate.Result.Waiting,
            gate.observe(packet(i, i * 1_000, 11.0), i * 1_000))
        assertTrue(gate.observe(packet(18, 18_000, 11.0), 18_000) is ScaleMeasurementGate.Result.Ready)
    }
    @Test fun restartTareAndDisconnectionInvalidateBaseline() {
        val gate = gate(); arm(gate)
        assertTrue(gate.observe(packet(1, 1_000, boot = "boot-b"), 1_000) is ScaleMeasurementGate.Result.Invalidated)
        assertTrue(gate.observe(packet(2, 2_000, boot = "boot-a"), 2_000) is ScaleMeasurementGate.Result.Rejected)
        assertTrue(gate.beginAtZero(1_000))
        assertTrue(gate.observe(packet(2, 2_000, boot = "boot-b", tare = 1), 2_000) is ScaleMeasurementGate.Result.Invalidated)
        assertNull(gate.pending)
        assertTrue(gate.beginAtZero(2_000))
        gate.onDisconnected()
        assertFalse(gate.beginAtZero(2_000))
    }
    @Test fun negativeChangesAndSilenceCannotProduceReadyMass() {
        val gate = gate(); arm(gate)
        assertTrue(gate.observe(packet(1, 1_000, -8.0), 1_000) is ScaleMeasurementGate.Result.Invalidated)
        assertNull(gate.pending)
        val next = gate(); arm(next)
        next.observe(packet(1, 1_000, 10.0), 1_000)
        assertFalse(next.checkFreshness(5_000))
        assertNull(next.pending)
    }
}

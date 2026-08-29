package com.example.vescurus.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BackoffPolicyTest {

    @Test
    fun `attempt 0 always returns delay in 0 to 1s`() {
        val rnd = Random(seed = 42)
        val delays = List(1_000) { BackoffPolicy.compute(attempt = 0, random = rnd) }
        assertTrue("all delays should be within [0, 1000]", delays.all { it in 0L..1_000L })
    }

    @Test
    fun `attempt 1 always returns delay in 0 to 2s`() {
        val rnd = Random(seed = 7)
        val delays = List(1_000) { BackoffPolicy.compute(attempt = 1, random = rnd) }
        assertTrue("all delays should be within [0, 2000]", delays.all { it in 0L..2_000L })
    }

    @Test
    fun `attempt 3 always returns delay in 0 to 8s`() {
        val rnd = Random(seed = 99)
        val delays = List(1_000) { BackoffPolicy.compute(attempt = 3, random = rnd) }
        assertTrue("all delays should be within [0, 8000]", delays.all { it in 0L..8_000L })
    }

    @Test
    fun `large attempt still caps at DEFAULT_CAP_MS`() {
        val rnd = Random(seed = 1)
        val delays = List(1_000) { BackoffPolicy.compute(attempt = 100, random = rnd) }
        assertTrue(
            "even after 100 attempts, no delay should exceed the cap",
            delays.all { it in 0L..BackoffPolicy.DEFAULT_CAP_MS }
        )
    }

    @Test
    fun `sampling many attempts approaches the ceiling`() {
        // Uniform sampling in [0, 8000] over 10k trials should approach 8000
        // for its maximum. Verify we're at least 95 percent of the way there.
        val rnd = Random(seed = 12345)
        val max = List(10_000) { BackoffPolicy.compute(attempt = 3, random = rnd) }.max()
        assertTrue("max of 10k trials at attempt=3 should be within 5% of 8000, was $max", max >= 7_600L)
    }

    @Test
    fun `jitter produces distinct delays across many random seeds`() {
        val distinctSeeded = (0 until 50)
            .map { BackoffPolicy.compute(attempt = 6, random = Random(seed = it.toLong())) }
            .distinct()
        assertNotEquals(
            "jitter should produce distinct delays across many seeds",
            1,
            distinctSeeded.size
        )
    }

    @Test
    fun `attempt 8 and beyond return the same ceiling MAX_EXPONENT`() {
        // 2^8 = 256s → hard-capped at 15s. Verify both attempt=8 and attempt=100
        // observe the same bound.
        val rnd = Random(seed = 0)
        val delays8 = List(500) { BackoffPolicy.compute(attempt = 8, random = rnd) }
        val delays100 = List(500) { BackoffPolicy.compute(attempt = 100, random = rnd) }
        assertTrue(delays8.all { it in 0L..BackoffPolicy.DEFAULT_CAP_MS })
        assertTrue(delays100.all { it in 0L..BackoffPolicy.DEFAULT_CAP_MS })
    }

    @Test
    fun `negative attempt throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackoffPolicy.compute(attempt = -1)
        }
    }

    @Test
    fun `non-positive cap throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackoffPolicy.compute(attempt = 3, capMs = 0L)
        }
    }

    @Test
    fun `custom cap enforced strictly`() {
        val rnd = Random(seed = 1)
        val delays = List(1_000) {
            BackoffPolicy.compute(attempt = 10, capMs = 500L, random = rnd)
        }
        assertTrue(delays.all { it in 0L..500L })
    }
}

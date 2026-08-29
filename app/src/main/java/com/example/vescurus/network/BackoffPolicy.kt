package com.example.vescurus.network

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Full-jitter exponential backoff (Amazon-style, "Exponential Backoff and
 * Jitter", March 2015). Returns a delay in `[0, capped_exponential]`.
 *
 * `attempt` is 0-indexed: attempt 0 → base 1000 ms, attempt 1 → 2000 ms, etc.
 * The exponential is clamped at `MAX_EXPONENT` and then hard-capped at
 * `capMs`, so no matter how many attempts stack up the delay never exceeds
 * `capMs`.
 */
object BackoffPolicy {

    const val MAX_EXPONENT = 8   // 2^8 = 256s ceiling before hard cap
    const val DEFAULT_CAP_MS = 15_000L

    fun compute(
        attempt: Int,
        capMs: Long = DEFAULT_CAP_MS,
        random: Random = Random.Default
    ): Long {
        require(attempt >= 0) { "attempt must be non-negative, was $attempt" }
        require(capMs > 0L) { "capMs must be positive, was $capMs" }
        val cappedExponent = attempt.coerceAtMost(MAX_EXPONENT)
        val exponentialMs = (2.0.pow(cappedExponent.toDouble()) * 1000L).toLong()
        val hardCapped = min(exponentialMs, capMs)
        return random.nextLong(0L, hardCapped + 1L)
    }
}

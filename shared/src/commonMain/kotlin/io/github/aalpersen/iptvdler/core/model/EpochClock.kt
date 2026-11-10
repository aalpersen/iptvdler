package io.github.aalpersen.iptvdler.core.model

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Wall-clock time in epoch milliseconds; injectable so time-based logic stays testable. */
fun interface EpochClock {
    fun nowMillis(): Long

    companion object {
        @OptIn(ExperimentalTime::class)
        val System = EpochClock { Clock.System.now().toEpochMilliseconds() }
    }
}

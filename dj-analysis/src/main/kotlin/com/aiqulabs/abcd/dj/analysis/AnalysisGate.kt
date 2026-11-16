package com.aiqulabs.abcd.dj.analysis

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lets only one track at a time be decoded and analysed, across the whole app (session preparation,
 * library analysis and the Lab).
 *
 * A decoded song lives in memory as one large float array (about 46 MB for 4 minutes at 48 kHz).
 * Session preparation works on two tracks at once, and library analysis can run beside it; without
 * this gate several such arrays were alive together and the app ran out of memory. Downloads still
 * overlap: callers take the gate only around decode + analyse.
 */
object AnalysisGate {
    private val lock = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = lock.withLock { block() }
}

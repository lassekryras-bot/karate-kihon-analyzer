package dk.lasse.karatecliprecorder.training

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ProcessingYield : java.util.concurrent.CancellationException("Processing yielded; resume from saved MP4")

/** A single heavy slot. Capture signals before waiting, so a worker yields at its next frame. */
object ProcessingCoordinator {
    val heavy = ReentrantLock(true)
    val publication = Any()
    private val captures = AtomicInteger()
    val foregroundActivities = AtomicInteger()
    private val deleted = ConcurrentHashMap.newKeySet<String>()
    val capturing get() = captures.get() > 0
    fun reserveCapture(): AutoCloseable {
        captures.incrementAndGet()
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        return AutoCloseable { if (closed.compareAndSet(false, true)) captures.decrementAndGet() }
    }
    fun awaitCapturePriority() { heavy.withLock { } }
    fun markDeleted(id: String) { deleted.add(id) }
    fun check(id: String, cancelled: Boolean = false) {
        if (capturing || id in deleted || cancelled || Thread.currentThread().isInterrupted) throw ProcessingYield()
    }
}

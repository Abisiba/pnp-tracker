package dev.pnptracker.ui

import dev.pnptracker.domain.games.GameOrder
import dev.pnptracker.domain.games.GameOrderStore
import dev.pnptracker.domain.games.TableSizes
import dev.pnptracker.domain.games.TableSizesStore
import java.util.concurrent.atomic.AtomicInteger

/**
 * Says when the product has finished writing, so a test waits on that and not on the file.
 *
 * Reading a file over and over while it is being written is not a harmless way
 * to wait on Windows: the store replaces the file with an atomic move, and
 * Windows refuses to move over a file that is open, even for reading. Measured
 * on the Windows runner, a reader polling the order file refused 106 of 300
 * writes with `AccessDeniedException`, where no reader refused none. The store
 * records such a refusal and does not raise it, so a test polling the file could
 * make the very write it was waiting for never arrive.
 *
 * So a test waits here until no write is in flight and the last one it finished
 * is the one it expects — and only then reads the file, once, to check what
 * reached the disk. A write that failed still counts as finished, so a failure
 * shows up in that read instead of being hidden by the wait.
 */
class WatchedWrites<T>(
    private val write: suspend (T) -> Unit,
) {
    private val inFlight = AtomicInteger(0)

    @Volatile
    var lastFinished: T? = null
        private set

    /** True when no write is running and the last one to finish wrote [expected]. */
    fun settledOn(expected: T): Boolean = inFlight.get() == 0 && lastFinished == expected

    /** True when no write is running and at least one has finished. */
    val settled: Boolean get() = inFlight.get() == 0 && lastFinished != null

    suspend fun run(value: T) {
        inFlight.incrementAndGet()
        try {
            write(value)
        } finally {
            lastFinished = value
            inFlight.decrementAndGet()
        }
    }
}

/** [real], with its writes watched. */
class WatchedGameOrderStore(
    private val real: GameOrderStore,
) : GameOrderStore {
    val writes = WatchedWrites<GameOrder>(real::write)

    override suspend fun read(): GameOrder = real.read()

    override suspend fun write(order: GameOrder) = writes.run(order)
}

/** [real], with its writes watched. */
class WatchedTableSizesStore(
    private val real: TableSizesStore,
) : TableSizesStore {
    val writes = WatchedWrites<TableSizes>(real::write)

    override suspend fun read(): TableSizes = real.read()

    override suspend fun write(sizes: TableSizes) = writes.run(sizes)
}

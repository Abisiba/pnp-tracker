package dev.pnptracker.data.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * A real database that refuses one chosen write.
 *
 * The point is to make a transaction fail **where it actually writes**, half way
 * through, rather than to hand a repository a pretend exception. A fake that
 * threw before the DAO was reached would prove only that the test can throw; the
 * question worth answering is what a real SQLite transaction leaves behind when
 * its third statement will not go in, and nothing but a real one can answer it.
 *
 * The trap is armed by SQL and by how many times that statement has run, so a
 * test can say "the second colour" or "the piece of the cell" without knowing
 * anything about how Room words its statements beyond the table it is writing.
 */
class FailingSqliteDriver(
    private val delegate: SQLiteDriver = BundledSQLiteDriver(),
) : SQLiteDriver {
    private var trap: Trap? = null

    private class Trap(
        val matches: (String) -> Boolean,
        val occurrence: Int,
    ) {
        var seen: Int = 0
    }

    /**
     * Arms the trap on the [occurrence]'th run of a statement [matches] accepts.
     *
     * Counting from one, and counted only while the trap is armed, so a fixture
     * built before this is called never spends one of the occurrences.
     */
    fun failOn(
        occurrence: Int = 1,
        matches: (String) -> Boolean,
    ) {
        synchronized(this) { trap = Trap(matches, occurrence) }
    }

    /** Takes the trap away; every statement runs normally again. */
    fun disarm() {
        synchronized(this) { trap = null }
    }

    private fun refuseIfTrapped(sql: String) {
        val armed =
            synchronized(this) {
                val trap = trap ?: return
                if (!trap.matches(sql)) return
                trap.seen += 1
                trap.seen == trap.occurrence
            }
        if (armed) throw SQLiteException("the trap refused this write: $sql")
    }

    override fun open(fileName: String): SQLiteConnection = Trapped(delegate.open(fileName))

    private inner class Trapped(
        private val connection: SQLiteConnection,
    ) : SQLiteConnection {
        override fun prepare(sql: String): SQLiteStatement = Guarded(connection.prepare(sql), sql)

        override fun inTransaction(): Boolean = connection.inTransaction()

        override fun close() = connection.close()
    }

    private inner class Guarded(
        private val statement: SQLiteStatement,
        private val sql: String,
    ) : SQLiteStatement by statement {
        private var running = false

        override fun step(): Boolean {
            if (!running) {
                running = true
                refuseIfTrapped(sql)
            }
            return statement.step()
        }

        override fun reset() {
            running = false
            statement.reset()
        }

        override fun close() {
            running = false
            statement.close()
        }
    }
}

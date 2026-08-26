package dev.pnptracker.data.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * Every statement really run against a database, in the order they were run.
 *
 * Counting calls to a DAO would only measure what a test asked for. What matters
 * about a transaction is what it does to the database, and the only honest place
 * to see that is the driver the database is actually opened with.
 *
 * A **run** rather than a preparation. Room keeps prepared statements and reuses
 * them, so a query made once per row of a batch is prepared once and run many
 * times: counting preparations would report one and miss the very thing worth
 * watching. A run is counted at the first `step` after the statement was made or
 * reset, so stepping through many rows of one result still counts once.
 *
 * Recording is off until it is turned on, so setting a fixture up costs nothing,
 * and it is synchronised because Room works on its own dispatcher.
 */
class CountingSqliteDriver(
    private val delegate: SQLiteDriver = BundledSQLiteDriver(),
) : SQLiteDriver {
    private val runs = mutableListOf<String>()
    private var recording = false

    /** Starts again from nothing, and begins recording. */
    fun start() {
        synchronized(runs) {
            runs.clear()
            recording = true
        }
    }

    /** Stops recording and hands back what was run while it was on. */
    fun stop(): List<String> =
        synchronized(runs) {
            recording = false
            runs.toList()
        }

    private fun record(sql: String) {
        synchronized(runs) {
            if (recording) runs += sql
        }
    }

    override fun open(fileName: String): SQLiteConnection = Watched(delegate.open(fileName))

    private inner class Watched(
        private val connection: SQLiteConnection,
    ) : SQLiteConnection {
        override fun prepare(sql: String): SQLiteStatement = Counted(connection.prepare(sql), sql)

        override fun inTransaction(): Boolean = connection.inTransaction()

        override fun close() = connection.close()
    }

    private inner class Counted(
        private val statement: SQLiteStatement,
        private val sql: String,
    ) : SQLiteStatement by statement {
        /** True once this run has begun, so its later rows are not counted again. */
        private var running = false

        override fun step(): Boolean {
            if (!running) {
                running = true
                record(sql)
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

package dev.pnptracker.data.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * A real database that lets a test do something in the middle of a transaction.
 *
 * Reaching into a transaction from outside is the only way to ask what it is
 * worth: a snapshot that reads fifteen tables one after another is either one
 * reading of the database or it is not, and the difference only shows when
 * somebody writes while it is half way through.
 *
 * The interruption fires on the first run of a matching statement and then
 * disarms itself, so a test says "when it reaches the tasks" and gets exactly
 * one chance rather than one per row.
 */
class PausingSqliteDriver(
    private val delegate: SQLiteDriver = BundledSQLiteDriver(),
) : SQLiteDriver {
    private var matches: ((String) -> Boolean)? = null
    private var action: (() -> Unit)? = null

    /** Runs [action] once, on the database's own thread, before [matches] first steps. */
    fun interruptOnce(
        matches: (String) -> Boolean,
        action: () -> Unit,
    ) {
        synchronized(this) {
            this.matches = matches
            this.action = action
        }
    }

    private fun fireIfArmed(sql: String) {
        val armed =
            synchronized(this) {
                val test = matches ?: return
                if (!test(sql)) return
                val fire = action
                matches = null
                action = null
                fire
            }
        armed?.invoke()
    }

    override fun open(fileName: String): SQLiteConnection = Watched(delegate.open(fileName))

    private inner class Watched(
        private val connection: SQLiteConnection,
    ) : SQLiteConnection {
        override fun prepare(sql: String): SQLiteStatement = Interrupted(connection.prepare(sql), sql)

        override fun inTransaction(): Boolean = connection.inTransaction()

        override fun close() = connection.close()
    }

    private inner class Interrupted(
        private val statement: SQLiteStatement,
        private val sql: String,
    ) : SQLiteStatement by statement {
        private var running = false

        override fun step(): Boolean {
            if (!running) {
                running = true
                fireIfArmed(sql)
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

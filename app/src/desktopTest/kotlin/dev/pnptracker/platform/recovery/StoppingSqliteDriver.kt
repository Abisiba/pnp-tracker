package dev.pnptracker.platform.recovery

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.util.concurrent.atomic.AtomicInteger

/**
 * A real database that stops dead at one chosen statement and never goes on.
 *
 * [dev.pnptracker.data.database.PausingSqliteDriver]'s cousin, for a different
 * question. That one lets a test do something in the middle of a transaction and
 * then carries on; this one is for the moment a process is killed, so it does
 * not carry on at all. When the [nth] statement matching [stopsAt] is about to
 * run, [onReached] is handed the very connection that holds the transaction —
 * so it can ask what that transaction has written so far — and it is expected
 * never to return, because the statement it is standing in front of must never
 * reach SQLite.
 *
 * What stops is the statement, not the writing before it: every row the
 * transaction wrote up to here has really been written, on the connection that
 * holds the transaction open. That is exactly the state a process killed at this
 * line of the real code would leave, and nothing in production had to be told
 * about it.
 *
 * Everything else is the bundled driver the application uses, unchanged.
 */
class StoppingSqliteDriver(
    private val stopsAt: (String) -> Boolean,
    private val nth: Int,
    private val onReached: (connection: SQLiteConnection) -> Unit,
    private val delegate: SQLiteDriver = BundledSQLiteDriver(),
) : SQLiteDriver {
    private val seen = AtomicInteger()

    init {
        require(nth >= 1) { "A statement is counted from one, was: $nth" }
    }

    override fun open(fileName: String): SQLiteConnection = Watched(delegate.open(fileName))

    private inner class Watched(
        private val connection: SQLiteConnection,
    ) : SQLiteConnection {
        override fun prepare(sql: String): SQLiteStatement = Stoppable(connection.prepare(sql), sql, connection)

        override fun inTransaction(): Boolean = connection.inTransaction()

        override fun close() = connection.close()
    }

    private inner class Stoppable(
        private val statement: SQLiteStatement,
        private val sql: String,
        private val connection: SQLiteConnection,
    ) : SQLiteStatement by statement {
        private var running = false

        override fun step(): Boolean {
            // One count per run of the statement, not per row it returns.
            if (!running) {
                running = true
                if (stopsAt(normalisedSql(sql)) && seen.incrementAndGet() == nth) {
                    onReached(connection)
                    error("the process was supposed to stop at this statement and it went on")
                }
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

/** Room's SQL with the quoting and the spacing taken out, so a match reads like the statement. */
fun normalisedSql(sql: String): String =
    sql
        .replace("`", "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .uppercase()

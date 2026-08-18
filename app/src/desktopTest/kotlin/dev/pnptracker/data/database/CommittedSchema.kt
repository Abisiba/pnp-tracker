package dev.pnptracker.data.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.fail

/**
 * Builds old databases from the schema files that were actually committed.
 *
 * Migration tests must upgrade the shape that shipped, not a hand written copy of
 * it that can quietly drift, so the create statements come straight out of
 * `app/schemas/.../<version>.json`.
 */
object CommittedSchema {
    class Definition(
        val createStatements: List<String>,
        val setupQueries: List<String>,
    )

    fun read(version: Int): Definition {
        val file = directory().resolve("$version.json")
        val text = Files.readString(file)
        val createStatements = mutableListOf<String>()
        var currentTable = ""
        // Room writes each entity's "tableName" before the "createSql" entries that
        // belong to it, so tracking the latest name is enough to expand ${TABLE_NAME}.
        TABLE_OR_CREATE_SQL.findAll(text).forEach { match ->
            val (key, value) = match.destructured
            if (key == "tableName") {
                currentTable = value
            } else {
                createStatements += unescape(value).replace("\${$TABLE_NAME}", currentTable)
            }
        }
        val setupQueries =
            SETUP_QUERIES
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.let { body -> QUOTED.findAll(body).map { unescape(it.groupValues[1]) }.toList() }
                .orEmpty()
        check(createStatements.isNotEmpty() && setupQueries.isNotEmpty()) {
            "could not read the version $version schema from $file"
        }
        return Definition(createStatements = createStatements, setupQueries = setupQueries)
    }

    /**
     * Creates [databaseFile] as an empty database of [version], then hands the open
     * connection to [fill] so the test can add the rows it wants to migrate.
     */
    fun createDatabase(
        databaseFile: Path,
        version: Int,
        fill: (SQLiteConnection) -> Unit = {},
    ) {
        val definition = read(version)
        val connection = BundledSQLiteDriver().open(databaseFile.toAbsolutePath().toString())
        try {
            connection.execSQL("PRAGMA foreign_keys = ON")
            definition.createStatements.forEach(connection::execSQL)
            definition.setupQueries.forEach(connection::execSQL)
            connection.execSQL("PRAGMA user_version = $version")
            fill(connection)
        } finally {
            connection.close()
        }
    }

    /** Reads `PRAGMA user_version` without going through Room. */
    fun readVersion(databaseFile: Path): Long {
        val connection = BundledSQLiteDriver().open(databaseFile.toAbsolutePath().toString())
        return try {
            connection.prepare("PRAGMA user_version").use { statement ->
                statement.step()
                statement.getLong(0)
            }
        } finally {
            connection.close()
        }
    }

    private fun directory(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            listOf(candidate, candidate.resolve("app"))
                .map { it.resolve("schemas/dev.pnptracker.data.database.AppDatabase") }
                .firstOrNull { Files.isDirectory(it) }
                ?.let { return it }
            candidate = candidate.parent
        }
        fail("Could not locate the committed schema directory from ${Path.of("").toAbsolutePath()}")
    }

    private fun unescape(raw: String): String = raw.replace("\\\"", "\"").replace("\\\\", "\\")

    private const val TABLE_NAME = "TABLE_NAME"

    private val TABLE_OR_CREATE_SQL = Regex("\"(tableName|createSql)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val SETUP_QUERIES = Regex("\"setupQueries\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
    private val QUOTED = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
}

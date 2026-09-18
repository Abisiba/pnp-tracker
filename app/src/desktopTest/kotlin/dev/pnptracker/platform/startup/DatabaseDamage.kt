package dev.pnptracker.platform.startup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_URI
import dev.pnptracker.data.database.AppDatabase
import dev.pnptracker.data.database.StoppedClock
import dev.pnptracker.data.database.aCell
import dev.pnptracker.data.database.aGame
import dev.pnptracker.data.database.aTask
import dev.pnptracker.data.database.createdAt
import dev.pnptracker.data.database.updatedAt
import dev.pnptracker.domain.model.CellColumnType
import dev.pnptracker.domain.model.EntityId
import dev.pnptracker.domain.model.IdGenerator
import dev.pnptracker.domain.model.PoolType
import dev.pnptracker.domain.model.TrackingMode
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

/**
 * The four kinds of page-level damage PLAN 14.7.4 names, each put into a
 * **closed, throwaway** copy of a database by writing bytes where SQLite keeps
 * its structure — never through SQLite, which would refuse or repair.
 *
 * The measurement records whether `user_version` can still be read after each
 * one, because that decides which refusal a person meets: an unreadable version
 * is DATABASE_NOT_READABLE before any check runs (PLAN 14.7.4).
 */
enum class DatabaseDamage {
    /** A leaf page of the `tasks` table overwritten with garbage, as a torn write leaves. */
    TABLE_PAGE,

    /** A leaf page of an index on `tasks` overwritten the same way. */
    INDEX_PAGE,

    /** The file cut off halfway, with its header still claiming every page. */
    TRUNCATED_FILE,

    /** The header's freelist naming a page that is still in use. */
    FREELIST,
    ;

    fun applyTo(file: Path) {
        when (this) {
            TABLE_PAGE -> scramble(file, leafUnder(file, rootPageOf(file, "SELECT rootpage FROM sqlite_master WHERE name = 'tasks'")))

            INDEX_PAGE ->
                scramble(
                    file,
                    leafUnder(
                        file,
                        rootPageOf(
                            file,
                            "SELECT rootpage FROM sqlite_master WHERE type = 'index' AND tbl_name = 'tasks' ORDER BY name LIMIT 1",
                        ),
                    ),
                )

            TRUNCATED_FILE -> {
                val header = headerOf(file)
                RandomAccessFile(file.toFile(), "rw").use { it.setLength(header.pageSize.toLong() * (header.pageCount / 2)) }
            }

            FREELIST -> {
                // The application's databases are auto_vacuum=FULL (measured), so
                // their freelist is empty after every commit and there is no
                // trunk page to damage. What can go wrong is the header's
                // freelist: here it names one free page, and that page is a leaf
                // of `tasks`, still in use.
                val tasksLeaf = leafUnder(file, rootPageOf(file, "SELECT rootpage FROM sqlite_master WHERE name = 'tasks'"))
                RandomAccessFile(file.toFile(), "rw").use { raf ->
                    raf.seek(32)
                    raf.writeInt(tasksLeaf)
                    raf.writeInt(1)
                }
            }
        }
    }
}

/** What the first hundred bytes of a SQLite file say about its pages. */
private class Header(
    val pageSize: Int,
    val pageCount: Int,
) {
    fun offsetOf(page: Int): Long = (page - 1).toLong() * pageSize
}

private fun headerOf(file: Path): Header {
    val bytes = ByteArray(100)
    RandomAccessFile(file.toFile(), "r").use { it.readFully(bytes) }

    fun int(at: Int) =
        ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
    val size = ((bytes[16].toInt() and 0xFF) shl 8) or (bytes[17].toInt() and 0xFF)
    return Header(pageSize = if (size == 1) 65_536 else size, pageCount = int(28))
}

/**
 * A read-only connection that creates nothing: a plain one leaves an empty
 * `-wal` and a `-shm` beside a closed WAL database (measured in Dilim 8), and a
 * fixture must not start with files its database never had.
 */
private fun measuring(file: Path): SQLiteConnection =
    if (Files.notExists(Path.of("$file-wal"))) {
        BundledSQLiteDriver().open(
            "file:${file.toAbsolutePath().toString().replace("%", "%25").replace("?", "%3F").replace("#", "%23")}?immutable=1",
            SQLITE_OPEN_READONLY or SQLITE_OPEN_URI,
        )
    } else {
        BundledSQLiteDriver().open(file.toAbsolutePath().toString(), SQLITE_OPEN_READONLY)
    }

private fun rootPageOf(
    file: Path,
    query: String,
): Int {
    val connection = measuring(file)
    try {
        return connection.prepare(query).use { statement ->
            check(statement.step()) { "no such b-tree" }
            statement.getInt(0)
        }
    } finally {
        connection.close()
    }
}

/** Follows right-most children from [root] down to a leaf page (table 0x0D, index 0x0A). */
private fun leafUnder(
    file: Path,
    root: Int,
): Int {
    val header = headerOf(file)
    RandomAccessFile(file.toFile(), "r").use { raf ->
        var page = root
        while (true) {
            val start = header.offsetOf(page) + if (page == 1) 100 else 0
            raf.seek(start)
            when (val type = raf.readUnsignedByte()) {
                0x05, 0x02 -> {
                    raf.seek(start + 8)
                    page = raf.readInt()
                }
                0x0D, 0x0A -> return page
                else -> error("page $page is of type $type")
            }
        }
    }
}

/** Overwrites one whole page with the same garbage every run. */
private fun scramble(
    file: Path,
    page: Int,
) {
    check(page > 1) { "page 1 is left whole on purpose" }
    val header = headerOf(file)
    RandomAccessFile(file.toFile(), "rw").use { raf ->
        raf.seek(header.offsetOf(page))
        raf.write(Random(page).nextBytes(header.pageSize))
    }
}

/**
 * Every row a read-only connection answers to one PRAGMA, first column only.
 * Throws what SQLite throws. Used to measure, never by the gate.
 */
fun pragmaAnswerOf(
    file: Path,
    pragma: String,
): List<String> {
    val connection = measuring(file)
    try {
        return connection.prepare("PRAGMA $pragma").use { statement ->
            buildList { while (statement.step()) add(statement.getText(0)) }
        }
    } finally {
        connection.close()
    }
}

private fun trackingOf(pool: PoolType) =
    when (pool) {
        PoolType.THREE_D -> TrackingMode.THREE_D_BATCH
        PoolType.CARD, PoolType.BOARD -> TrackingMode.PIPELINE
        PoolType.SPECIAL -> TrackingMode.CHECKLIST
    }

/**
 * A library of [total] tasks in games of ten, written through the application's
 * own paths as İş 9's is: tasks added to cells, colours on the 3D ones, a
 * failure reported now and then.
 */
suspend fun libraryOf(
    database: AppDatabase,
    total: Int,
) {
    val palette =
        database
            .colorDao()
            .allColors()
            .take(4)
            .map { it.id }
    val clock = StoppedClock(updatedAt)
    var cells = emptyMap<CellColumnType, EntityId>()
    for (index in 0 until total) {
        if (index % 10 == 0) {
            val game = aGame(name = "Oyun ${index / 10}")
            database.gameDao().insert(game)
            cells =
                CellColumnType.entries.associateWith { column ->
                    aCell(gameId = game.id, columnType = column).also { database.gameCellDao().insert(it) }.id
                }
        }
        val pool = PoolType.entries[index % PoolType.entries.size]
        val task = aTask(poolType = pool, trackingMode = trackingOf(pool), name = "Görev #$index", requiredQuantity = index % 40 + 2)
        database.taskDao().addTaskToCell(task, cells.getValue(CellColumnType.of(pool)), IdGenerator.Random.newId(), createdAt)
        if (pool == PoolType.THREE_D) palette.take(index % 3).forEach { database.taskColorDao().addColorToTask(task.id, it) }
        if (pool == PoolType.THREE_D && index % 20 == 8) {
            database.taskProgressDao().reportFailure(IdGenerator.Random.newId(), task.id, 1, clock)
        }
    }
}

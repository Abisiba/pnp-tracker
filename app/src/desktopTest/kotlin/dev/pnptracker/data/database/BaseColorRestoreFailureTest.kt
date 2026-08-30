package dev.pnptracker.data.database

import androidx.sqlite.SQLiteException
import dev.pnptracker.data.database.entity.ColorEntity
import dev.pnptracker.data.repository.ColorCatalogueStore
import dev.pnptracker.domain.colors.BaseColorRestore
import dev.pnptracker.domain.colors.BaseColorRestoreBlock
import dev.pnptracker.domain.colors.BaseColorRestoreConflict
import dev.pnptracker.domain.colors.ColorSetupException
import dev.pnptracker.domain.colors.ColorSetupFailure
import dev.pnptracker.domain.colors.baseColors
import dev.pnptracker.domain.model.IdGenerator
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Why a base colour would not go back in, and the difference between the
 * answers.
 *
 * A refused insert says only that it was refused. Four different things can
 * refuse it and three of them are the user's to do something about: a colour
 * appeared on that identity, a colour took that name, a colour is known by that
 * name. The fourth — the write did not land — is not the user's, and telling
 * them a colour appeared underneath when the disk simply said no would send them
 * looking for a colour that is not there.
 *
 * Telling them apart by reading the exception's message would be a
 * classification that rots the first time SQLite words one differently, so the
 * production code asks the database three questions instead. These are those
 * questions, each against a real database left in the state that makes it true.
 *
 * **What is reachable from inside the application.** Nothing here can be reached
 * by two windows racing: Room writes through one connection and holds the write
 * lock for the whole of a transaction, so a second writer cannot land between
 * the check and the insert. The three conflicts are for a writer outside this
 * process, and the fourth is the one that really does happen — which is exactly
 * why it must not be reported as one of the other three.
 */
class BaseColorRestoreFailureTest {
    private lateinit var directory: TemporaryDatabaseDirectory
    private lateinit var database: AppDatabase
    private lateinit var store: ColorCatalogueStore
    private val driver = FailingSqliteDriver()
    private var realDatabaseExistedBefore = false

    private val white = baseColors[0]
    private val black = baseColors[1]

    @BeforeTest
    fun openDatabase() {
        realDatabaseExistedBefore = Files.exists(TemporaryDatabaseDirectory.realApplicationDatabaseFile())
        directory = TemporaryDatabaseDirectory()
        database = DatabaseFactory(driver = driver).open(directory.databaseFile)
        store = ColorCatalogueStore(database.colorDao())
    }

    @AfterTest
    fun closeDatabase() {
        driver.disarm()
        database.close()
        directory.assertRealApplicationDatabaseUntouched(realDatabaseExistedBefore)
        directory.delete()
    }

    /** A failure of the kind a driver hands back: no cause worth reading. */
    private fun aRefusedWrite() = SQLiteException("the write did not land")

    private suspend fun askWhy(
        base: dev.pnptracker.domain.colors.BaseColor,
        cause: SQLiteException = aRefusedWrite(),
    ) = database.colorDao().whyARestoreWouldNotTake(base, cause)

    // -------------------------------------------------- the three real answers

    @Test
    fun `a colour sitting on the identity is the identity being taken`() =
        runBlocking<Unit> {
            // Beyaz is in the catalogue, so its fixed identity is held.
            val conflict = assertIs<BaseColorRestoreConflict>(askWhy(white))

            assertEquals(BaseColorRestoreBlock.APPEARED_MEANWHILE, conflict.reason)
            assertEquals("Beyaz", conflict.canonicalName)
            assertEquals("#FFFFFF", conflict.hex)
        }

    @Test
    fun `an identity that is taken outranks a name that is also taken`() =
        runBlocking<Unit> {
            // Renaming the base colour leaves its identity held and frees its
            // name for somebody else. Both are in the way; only one of them is
            // the reason the insert could not go in.
            database.colorDao().renameAndRecolorTheUserHasConfirmed(
                id = white.id,
                expectedName = "Beyaz",
                expectedHex = "#FFFFFF",
                canonicalName = "Kar",
                hex = "#FAFAFA",
            )
            database.colorDao().addColorToEndOfCatalogue(IdGenerator.Random.newId(), "Beyaz", "#EEEEEE")

            val conflict = assertIs<BaseColorRestoreConflict>(askWhy(white))

            assertEquals(BaseColorRestoreBlock.APPEARED_MEANWHILE, conflict.reason)
        }

    @Test
    fun `a colour holding the name is the name being taken by a colour`() =
        runBlocking<Unit> {
            database.colorDao().deleteColorTheUserHasConfirmed(white.id)
            database.colorDao().addColorToEndOfCatalogue(IdGenerator.Random.newId(), "BEYAZ", "#EEEEEE")

            val conflict = assertIs<BaseColorRestoreConflict>(askWhy(white))

            assertEquals(BaseColorRestoreBlock.NAME_TAKEN_BY_COLOR, conflict.reason)
            assertEquals("Beyaz", conflict.canonicalName)
        }

    @Test
    fun `a spelling of another colour holding the name is told apart from a colour holding it`() =
        runBlocking<Unit> {
            database.colorDao().deleteColorTheUserHasConfirmed(white.id)
            val red = assertNotNull(database.colorDao().resolve("Kırmızı"))
            database.colorDao().addAlias(red.id, "beyaz")

            val conflict = assertIs<BaseColorRestoreConflict>(askWhy(white))

            assertEquals(BaseColorRestoreBlock.NAME_TAKEN_BY_ALIAS, conflict.reason)
            assertEquals("Beyaz", conflict.canonicalName)
        }

    // ------------------------------------------------------- and the fourth

    @Test
    fun `a write that simply did not land is handed back as it came`() =
        runBlocking<Unit> {
            database.colorDao().deleteColorTheUserHasConfirmed(white.id)
            val cause = aRefusedWrite()

            // Nothing is in the way: the identity is free, the name is free, no
            // colour is known by it. There is no race to report.
            assertSame(cause, askWhy(white, cause), "a failed write was dressed up as something in the way")
        }

    @Test
    fun `each base colour is asked about on its own account`() =
        runBlocking<Unit> {
            // Two missing, and only one of them has anything in its way. The
            // answer for one must not be given for the other.
            database.colorDao().deleteColorTheUserHasConfirmed(white.id)
            database.colorDao().deleteColorTheUserHasConfirmed(black.id)
            database.colorDao().addColorToEndOfCatalogue(IdGenerator.Random.newId(), "Siyah", "#222222")

            val cause = aRefusedWrite()
            assertSame(cause, askWhy(white, cause), "the other colour's problem was reported for this one")

            val forBlack = assertIs<BaseColorRestoreConflict>(askWhy(black))
            assertEquals(BaseColorRestoreBlock.NAME_TAKEN_BY_COLOR, forBlack.reason)
            assertEquals("Siyah", forBlack.canonicalName)
            assertEquals("#111111", forBlack.hex, "the base colour was described by the row that is in its way")
        }

    // ------------------------------------------------------ through the store

    @Test
    fun `a refused insert is a saving failure rather than a colour that appeared`() =
        runBlocking<Unit> {
            database.colorDao().deleteColorTheUserHasConfirmed(white.id)

            driver.failOn { it.trimStart().uppercase().startsWith("INSERT") && "COLORS" in it.uppercase() }
            val failure = assertFailsWith<ColorSetupException> { store.restoreMissingBaseColors() }
            driver.disarm()

            assertEquals(ColorSetupFailure.COULD_NOT_SAVE, failure.failure)
            assertNull(database.colorDao().colorById(white.id), "the colour went in anyway")
        }

    @Test
    fun `a refused second insert takes the first one back with it`() =
        runBlocking<Unit> {
            database.colorDao().deleteColorTheUserHasConfirmed(white.id)
            database.colorDao().deleteColorTheUserHasConfirmed(black.id)
            val before = database.colorDao().allColors()

            driver.failOn(occurrence = 2) { it.trimStart().uppercase().startsWith("INSERT") && "COLORS" in it.uppercase() }
            assertFailsWith<ColorSetupException> { store.restoreMissingBaseColors() }
            driver.disarm()

            assertEquals(before, database.colorDao().allColors(), "half a restore was left behind")
        }

    @Test
    fun `a restore refused once can be asked for again`() =
        runBlocking<Unit> {
            database.colorDao().deleteColorTheUserHasConfirmed(white.id)

            driver.failOn { it.trimStart().uppercase().startsWith("INSERT") && "COLORS" in it.uppercase() }
            assertFailsWith<ColorSetupException> { store.restoreMissingBaseColors() }
            driver.disarm()

            assertEquals(BaseColorRestore.Restored(listOf("Beyaz")), store.restoreMissingBaseColors())
            assertNotNull(database.colorDao().colorById(white.id))
        }

    @Test
    fun `a colour that really is in the way is answered rather than thrown`() =
        runBlocking<Unit> {
            // The identity is held by a row the plan cannot see as missing, so
            // this reaches the store the way a race would: as an answer naming
            // the colour, with nothing written.
            val conflict =
                BaseColorRestoreConflict(white.canonicalName, white.hex, BaseColorRestoreBlock.APPEARED_MEANWHILE)

            val shown = BaseColorRestore.Blocked(listOf(conflict.blocked)).blocked.single()
            assertEquals("Beyaz", shown.canonicalName)
            assertEquals("#FFFFFF", shown.hex)
            assertEquals(BaseColorRestoreBlock.APPEARED_MEANWHILE, shown.reason)
        }

    @Test
    fun `nothing the user is shown carries a statement or a driver's words`() =
        runBlocking<Unit> {
            database.colorDao().deleteColorTheUserHasConfirmed(white.id)
            val red = assertNotNull(database.colorDao().resolve("Kırmızı"))
            database.colorDao().addAlias(red.id, "beyaz")

            val shown = assertIs<BaseColorRestoreConflict>(askWhy(white)).blocked

            listOf(shown.canonicalName, shown.hex).forEach { said ->
                listOf("INSERT", "SELECT", "SQL", "constraint", "did not land", "/").forEach { leak ->
                    assertTrue(leak !in said, "a $leak reached the user in: $said")
                }
            }
        }

    @Test
    fun `an identity taken by a colour the user renamed is still the identity`() =
        runBlocking<Unit> {
            // The row carries the fixed identity under a name and a value of the
            // user's own. PLAN 5.7 makes that the base colour itself, so the
            // answer is that it is there — not that some name is taken.
            database.colorDao().deleteColorTheUserHasConfirmed(black.id)
            database.colorDao().insert(
                ColorEntity.of(id = black.id, canonicalName = "Koyu", hex = "#101820", sortOrder = 47),
            )

            val conflict = assertIs<BaseColorRestoreConflict>(askWhy(black))

            assertEquals(BaseColorRestoreBlock.APPEARED_MEANWHILE, conflict.reason)
            val stillThere = assertNotNull(database.colorDao().colorById(black.id))
            assertEquals("Koyu", stillThere.canonicalName, "the user's own colour was written over")
            assertEquals("#101820", stillThere.hex)
        }
}

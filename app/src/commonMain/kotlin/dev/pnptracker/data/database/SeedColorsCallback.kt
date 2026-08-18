package dev.pnptracker.data.database

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection

/**
 * Fills the color catalogue of a database that Room has just created.
 *
 * A database upgraded from version 1 gets the same rows from `Migration1To2`;
 * both call [insertSeedColors], so there is only one definition of the seed.
 */
object SeedColorsCallback : RoomDatabase.Callback() {
    override suspend fun onCreate(connection: SQLiteConnection) {
        insertSeedColors(connection)
    }
}

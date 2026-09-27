package dev.pnptracker.domain.games

/**
 * Where the table's sizes live, seen from the side that has no files.
 *
 * Read when the table opens, written when somebody finishes a drag. Nothing is
 * cached here for the same reason the settings are not (PLAN 14.4.12): a second
 * answer able to disagree with the disk is worse than reading a small file
 * twice.
 */
interface TableSizesStore {
    /**
     * The sizes as they stand, falling back to the defaults rather than failing.
     * Reading never creates the file.
     */
    suspend fun read(): TableSizes

    /**
     * Writes [sizes] as the whole document, atomically.
     *
     * The only thing that creates the file, and it happens because somebody
     * resized something. A failure is **not** raised to the caller: a layout that
     * could not be remembered is not worth interrupting anybody for, the table on
     * screen is already the size they asked for, and the reason is recorded once
     * for whoever reads the diagnostics.
     */
    suspend fun write(sizes: TableSizes)

    companion object {
        /**
         * A table with nowhere to remember its sizes.
         *
         * For a table that has no business with files: a test of the rows, a
         * screen built to be looked at once. It draws the defaults and forgets
         * every drag, which is exactly what having no file means.
         */
        val Forgetful: TableSizesStore =
            object : TableSizesStore {
                override suspend fun read(): TableSizes = TableSizes.Default

                override suspend fun write(sizes: TableSizes) = Unit
            }
    }
}

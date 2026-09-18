package dev.pnptracker.domain.backup.automatic

/**
 * The two files that stand in front of a migration, once both have been proved.
 *
 * A set is one thing with two halves and they answer different questions. The
 * raw `.db` is the old schema exactly as it was, cloned without the migration
 * code ever running — so it survives a fault in the very code it protects
 * against. The `.json` is an ordinary backup of the same data *after* the chain
 * has been run over a throwaway copy, which is what a person can actually put
 * back from the `Ayarlar` screen (PLAN 14.4.9).
 *
 * Neither replaces the other, so neither counts on its own: this exists only
 * when both halves are on disk and both have been verified, and there is no way
 * to make one otherwise. That is PLAN 14.4.10's step 13 — "the real database
 * cannot be opened before the set has succeeded" — as a property of the types
 * rather than a rule somebody has to remember.
 */
class MigrationSnapshotSet internal constructor(
    /** The name both halves share, without an extension. */
    val setName: String,
    /** The schema the database was on, which is in the name and in the raw file. */
    val fromSchemaVersion: Int,
    /** The schema the working copy reached, which is this build's. */
    val toSchemaVersion: Int,
)

/**
 * Why the application would not open the database it was started on.
 *
 * Every one of these leaves the user's database exactly as it was — not
 * migrated, not written to, in most cases not even opened — and every one of
 * them ends at the same place: a window that says what happened and what to do,
 * instead of the application (PLAN 14.4.13).
 *
 * They are kept apart rather than folded into one apology because the next step
 * differs. Another copy running is something to close; a database from a newer
 * version is something to update the application for; a snapshot that could not
 * be written is something to make room for; and a migration that failed is the
 * one where the snapshot on disk is the thing that matters.
 */
enum class StartupProblem {
    /** Another copy of the application is starting or migrating on this database. */
    ANOTHER_COPY_IS_RUNNING,

    /**
     * The folders this application keeps its data in could not be made.
     *
     * Decided before anything is opened, and the only reason here that is not
     * about the database at all: nothing of the user's has been read, written,
     * moved or removed, because there was nowhere yet to do it in.
     */
    FOLDERS_NOT_CREATED,

    /** The file is there and is not a database this application can read. */
    DATABASE_NOT_READABLE,

    /**
     * The file reads as a database of a version this build knows, and SQLite's
     * page-level check finds it damaged (PLAN 14.7.4). Nothing was opened,
     * migrated, repaired or written, and no backup was touched.
     */
    DATABASE_DAMAGED,

    /** The database was written by a newer version of the application. */
    SCHEMA_TOO_NEW,

    /** The consistent copy of the old database could not be made. */
    SNAPSHOT_NOT_CLONED,

    /** The copy was made and the migration chain would not run over it. */
    SNAPSHOT_NOT_MIGRATED,

    /** The backup document could not be produced or put on disk. */
    SNAPSHOT_NOT_WRITTEN,

    /** The set was written and could not be read back and proved. */
    SNAPSHOT_NOT_VERIFIED,

    /**
     * The set was proved and the real migration then failed.
     *
     * The only one of these where something was attempted on the user's own
     * database — and the one where both halves of the set are on disk, which is
     * what the window says.
     */
    MIGRATION_FAILED,
}

/**
 * Thrown when the application may not go on to its main window.
 *
 * Carries the reason and, for a developer, whatever caused it. It carries no
 * path, no SQL and nothing out of anybody's data; what the user is shown is
 * chosen from [problem] alone (PLAN 14.4.13).
 */
class StartupRefused(
    val problem: StartupProblem,
    cause: Throwable? = null,
) : Exception("The application did not open the database: $problem", cause)

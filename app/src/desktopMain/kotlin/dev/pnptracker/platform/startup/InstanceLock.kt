package dev.pnptracker.platform.startup

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** What the startup lock file is called, beside the database it guards. */
const val INSTANCE_LOCK_NAME: String = "pnp-baslangic.lock"

/**
 * The lock one copy of the application holds while it opens the database.
 *
 * PLAN 14.4.10 opens with it and step 16 holds it until the snapshot and the
 * real migration are both done. What it is for is narrow and important: two
 * copies started at once must not both decide the database needs migrating,
 * both clone it, and both run a migration chain over the same file.
 *
 * It is an operating system lock on a file, taken with [FileChannel.tryLock],
 * and that choice is the whole of the "stale lock" question. A lock file holding
 * a process id has to be reasoned about after a crash — is that process still
 * alive, is the id reused, how long do we wait — and every answer is a guess. An
 * OS lock has no such problem: the kernel releases it when the process ends,
 * however it ends. A crashed copy leaves the file behind and the lock free, so
 * the next start takes it immediately and there is nothing to clean up.
 *
 * The file is never deleted. Deleting it would open the race it exists to close:
 * two copies can hold descriptors to the same path while one of them unlinks it,
 * and then each would be locking a different file. It is a few bytes and it
 * stays.
 *
 * Failing to take it is not a thing to wait out. PLAN 14.4.10 has the database
 * not opened at all in that case, and the second copy says so and stops.
 */
class InstanceLock(
    private val lockFile: Path,
) {
    /**
     * Runs [held] with the lock, or answers null if another copy has it.
     *
     * The lock is released on every path out, including the one where [held]
     * throws — which is what makes a refused startup leave nothing behind for
     * the next attempt.
     */
    fun <T> withLock(held: () -> T): T? {
        val channel =
            try {
                FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            } catch (couldNotOpen: IOException) {
                throw StartupLockUnavailable(couldNotOpen)
            }
        return channel.use {
            val lock =
                try {
                    channel.tryLock()
                } catch (alreadyOurs: OverlappingFileLockException) {
                    // This very process already holds it. Two startups inside one
                    // process is a defect rather than a second copy, but it is
                    // answered the same way: the second one does not go on.
                    null
                } catch (couldNotLock: IOException) {
                    throw StartupLockUnavailable(couldNotLock)
                } ?: return@use null
            try {
                held()
            } finally {
                // Released explicitly rather than left to the channel's close, so
                // the order is visible: the lock goes first, the file handle
                // second.
                lock.release()
            }
        }
    }
}

/**
 * The lock file itself could not be opened — a read-only data directory, a full
 * disk, a name taken by something that is not a file.
 *
 * Separate from "another copy has it", because they call for different things:
 * one is a copy to close, the other is a directory to look at.
 */
class StartupLockUnavailable(
    cause: Throwable? = null,
) : Exception("The startup lock could not be opened", cause)

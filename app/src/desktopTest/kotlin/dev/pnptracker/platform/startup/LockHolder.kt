package dev.pnptracker.platform.startup

import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * A second process that holds the startup lock and waits to be killed.
 *
 * Two copies of an application racing at one migration is not something threads
 * can stand in for: a `FileLock` is held by the *process*, and the interesting
 * questions — does the second copy refuse, and does the lock come back when the
 * first one dies without releasing it — only have answers across a real process
 * boundary. So `StartupGateTest` starts this with the test's own classpath, waits
 * for it to say it has the lock, and then kills it.
 *
 * It prints one word and then blocks for ever. Being killed is how it ends, and
 * that is the point: the operating system is what releases the lock, which is
 * the whole reason the lock is an OS lock rather than a file holding a number.
 */
fun main(args: Array<String>) {
    val channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    val lock = channel.tryLock() ?: error("the lock was already taken")
    println("LOCKED")
    System.out.flush()
    try {
        // Blocks until the parent kills this process.
        Thread.sleep(Long.MAX_VALUE)
    } finally {
        lock.release()
        channel.close()
    }
}

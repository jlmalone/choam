package vision.salient.choam.sync

import mu.KotlinLogging
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val logger = KotlinLogging.logger {}

/**
 * Process-level lock for queue --run.
 *
 * Prevents two concurrent queue processors from running simultaneously,
 * which previously caused a corrupted partial transfer when two processors ran concurrently.
 *
 * Uses java.nio FileLock (OS-level flock) so the lock is automatically
 * released if the process crashes. A PID file is written inside the lock
 * for human-readable diagnostics.
 */
class QueueRunLock(
    private val lockPath: Path = Paths.get(
        System.getProperty("user.home"), ".choam", "queue-run.lock"
    )
) : AutoCloseable {

    private var raf: RandomAccessFile? = null
    private var lock: FileLock? = null

    /**
     * Try to acquire the lock. Returns true if acquired, false if another
     * processor already holds it.
     */
    fun tryAcquire(): Boolean {
        Files.createDirectories(lockPath.parent)
        val file = RandomAccessFile(lockPath.toFile(), "rw")
        val channel = file.channel
        val acquired = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            // Same JVM already holds this lock (e.g. daemon + CLI in same process)
            null
        }

        if (acquired == null) {
            file.close()

            // The kernel lock, not the PID text, is authoritative. Never unlink this
            // path while another process may hold its inode: doing so lets a third
            // process create and lock a different inode under the same pathname.
            // OS file locks are released automatically when their process exits.
            logger.warn { "Queue lock held by PID ${holderPid() ?: "unknown"}" }
            return false
        }

        // Write our PID into the lock file
        file.setLength(0)
        file.writeBytes(ProcessHandle.current().pid().toString())
        this.raf = file
        this.lock = acquired
        return true
    }

    /** Read the PID from the lock file, if any. */
    fun holderPid(): Long? {
        if (!Files.exists(lockPath)) return null
        return try {
            Files.readString(lockPath).trim().toLongOrNull()
        } catch (_: Exception) { null }
    }

    override fun close() {
        // Clear diagnostic data while the lock is still held. Keep the stable lock
        // file itself forever so all contenders always coordinate on one inode.
        try { raf?.setLength(0) } catch (_: Exception) {}
        try { lock?.release() } catch (_: Exception) {}
        try { raf?.close() } catch (_: Exception) {}
        lock = null
        raf = null
    }
}

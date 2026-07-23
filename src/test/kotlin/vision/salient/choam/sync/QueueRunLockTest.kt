package vision.salient.choam.sync

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path

class QueueRunLockTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `first acquire succeeds`() {
        val lock = QueueRunLock(tempDir.resolve("queue-run.lock"))
        assertTrue(lock.tryAcquire())
        lock.close()
    }

    @Test
    fun `second acquire fails while first is held`() {
        val lockPath = tempDir.resolve("queue-run.lock")
        val first = QueueRunLock(lockPath)
        assertTrue(first.tryAcquire())

        val second = QueueRunLock(lockPath)
        assertFalse(second.tryAcquire())

        first.close()
    }

    @Test
    fun `acquire succeeds after first lock is released`() {
        val lockPath = tempDir.resolve("queue-run.lock")
        val first = QueueRunLock(lockPath)
        assertTrue(first.tryAcquire())
        first.close()

        val second = QueueRunLock(lockPath)
        assertTrue(second.tryAcquire())
        second.close()
    }

    @Test
    fun `release preserves the stable lock inode and clears diagnostic PID`() {
        val lockPath = tempDir.resolve("queue-run.lock")
        val lock = QueueRunLock(lockPath)
        assertTrue(lock.tryAcquire())
        val fileKeyBefore = Files.readAttributes(lockPath, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()

        lock.close()

        assertTrue(Files.exists(lockPath))
        assertEquals("", Files.readString(lockPath))
        val fileKeyAfter = Files.readAttributes(lockPath, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
        assertEquals(fileKeyBefore, fileKeyAfter)
    }

    @Test
    fun `holderPid returns current PID when locked`() {
        val lockPath = tempDir.resolve("queue-run.lock")
        val lock = QueueRunLock(lockPath)
        assertTrue(lock.tryAcquire())

        val pid = lock.holderPid()
        assertNotNull(pid)
        assertEquals(ProcessHandle.current().pid(), pid)
        lock.close()
    }

    @Test
    fun `holderPid returns null when no lock file exists`() {
        val lock = QueueRunLock(tempDir.resolve("nonexistent.lock"))
        assertEquals(null, lock.holderPid())
    }
}

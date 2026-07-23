package vision.salient.choam.sync

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Integration tests for SourceGuard — simulates real-world race conditions
 * and failure modes that unit tests can't cover.
 *
 * These tests verify the fail-safe behavior: SourceGuard may not prevent
 * every race, but it MUST detect changes after the fact.
 */
class SourceGuardIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    // ── Scenario 1: Writer appears after first lsof check ──
    // SourceGuard does double lsof (before fingerprint + before rsync).
    // A writer that appears between the two checks should be caught by the second.
    // But if a writer appears AFTER both checks, the post-transfer fingerprint
    // comparison must catch it.

    @Test
    fun `writer modifying source after guard acquisition is detected by verify`() {
        val testFile = tempDir.resolve("race_write.txt").toFile()
        testFile.writeText("original content before transfer")

        val guard = SourceGuard.acquire(testFile.absolutePath, TransferMode.COPY, "race-1")

        // Simulate a writer modifying the file after guard was acquired
        // (after both lsof checks passed)
        Thread.sleep(1100) // Ensure mtime granularity
        testFile.writeText("modified by a rogue writer during transfer!!!")

        val result = guard.verifySourceUnchanged()
        assertFalse(result.passed, "Writer after guard should be detected: ${result.detail}")
        guard.close()
    }

    @Test
    fun `MOVE detects content change even when size is preserved`() {
        val testFile = tempDir.resolve("same_size_move.txt").toFile()
        testFile.writeText("AAAAAAAAAA") // 10 bytes

        val guard = SourceGuard.acquire(testFile.absolutePath, TransferMode.MOVE, "move-1")

        // Same size, different content — only SHA-256 catches this
        testFile.writeText("BBBBBBBBBB") // still 10 bytes

        val result = guard.verifySourceUnchanged()
        // MOVE uses SHA-256, so it should detect the content change even with same size
        assertFalse(result.passed, "MOVE should detect same-size content change via SHA-256: ${result.detail}")
        guard.close()
    }

    // NOTE: COPY mode has a known theoretical limitation — it only checks mtime + size,
    // not content. If content changes but mtime and size stay the same, COPY won't detect it.
    // This is by design (COPY trades consistency for speed). In practice, filesystem mtime
    // precision (nanoseconds on APFS) makes same-mtime overwrites nearly impossible without
    // kernel-level tools. MOVE mode uses SHA-256 and catches everything. See COPY_POLICY.md.

    // ── Scenario 2: Concurrent acquire attempts ──

    @Test
    fun `two threads racing to acquire same file — exactly one wins`() {
        val testFile = tempDir.resolve("concurrent.txt").toFile()
        testFile.writeText("contested resource")

        val winner = AtomicReference<String>(null)
        val loserGotException = AtomicBoolean(false)
        val latch = CountDownLatch(2)

        val thread1 = Thread {
            try {
                val guard = SourceGuard.acquire(testFile.absolutePath, TransferMode.COPY, "thread-1")
                winner.compareAndSet(null, "thread-1")
                // Hold the lock briefly
                Thread.sleep(500)
                guard.close()
            } catch (e: SourceGuardException) {
                loserGotException.set(true)
            } finally {
                latch.countDown()
            }
        }

        val thread2 = Thread {
            try {
                // Small delay to increase race probability
                Thread.sleep(10)
                val guard = SourceGuard.acquire(testFile.absolutePath, TransferMode.COPY, "thread-2")
                winner.compareAndSet(null, "thread-2")
                Thread.sleep(500)
                guard.close()
            } catch (e: SourceGuardException) {
                loserGotException.set(true)
            } finally {
                latch.countDown()
            }
        }

        thread1.start()
        thread2.start()
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Both threads should complete")

        assertNotNull(winner.get(), "At least one thread should win the lock")
        assertTrue(loserGotException.get(), "The losing thread should get SourceGuardException")
    }

    // ── Scenario 3: Lock cleanup on exception ──

    @Test
    fun `lock is cleaned up even if transfer fails`() {
        val testFile = tempDir.resolve("cleanup.txt").toFile()
        testFile.writeText("test content")

        val lockFile = File("${testFile.absolutePath}.choam_lock")

        val guard = SourceGuard.acquire(testFile.absolutePath, TransferMode.COPY, "cleanup-1")
        assertTrue(lockFile.exists(), "Lock should exist after acquire")

        // Simulate using guard in try-finally (the pattern SendCommand/QueueProcessor use)
        try {
            // Simulate transfer failure
            throw RuntimeException("rsync failed!")
        } catch (_: RuntimeException) {
            // Transfer failed
        } finally {
            guard.close()
        }

        assertFalse(lockFile.exists(), "Lock should be removed even after transfer failure")

        // Verify the file can be acquired again
        val guard2 = SourceGuard.acquire(testFile.absolutePath, TransferMode.COPY, "cleanup-2")
        assertTrue(lockFile.exists())
        guard2.close()
    }

    // ── Scenario 4: SQLite WAL/SHM changes during transfer ──

    @Test
    fun `SQLite MOVE fails if WAL appears during transfer`() {
        val dbFile = tempDir.resolve("transfer.db").toFile()
        dbFile.writeText("SQLite database content")

        val guard = SourceGuard.acquire(dbFile.absolutePath, TransferMode.MOVE, "wal-1")

        // Simulate a writer creating a WAL file mid-transfer
        val walFile = File("${dbFile.absolutePath}-wal")
        walFile.writeBytes(ByteArray(4096)) // Well beyond 32-byte header threshold

        val result = guard.verifySqliteMoveQuiescent()
        assertFalse(result.passed, "Should fail if WAL grew during transfer")
        assertTrue(result.detail.contains("WAL"), "Detail should mention WAL: ${result.detail}")

        walFile.delete()
        guard.close()
    }

    @Test
    fun `SQLite MOVE fails if SHM appears during transfer`() {
        val dbFile = tempDir.resolve("shm_test.db").toFile()
        dbFile.writeText("SQLite database content")

        val guard = SourceGuard.acquire(dbFile.absolutePath, TransferMode.MOVE, "shm-1")

        // Simulate a process creating an SHM file (indicates DB was opened)
        val shmFile = File("${dbFile.absolutePath}-shm")
        shmFile.writeBytes(ByteArray(32768)) // Standard SHM size

        val result = guard.verifySqliteMoveQuiescent()
        assertFalse(result.passed, "Should fail if SHM exists during MOVE")
        assertTrue(result.detail.contains("SHM"), "Detail should mention SHM: ${result.detail}")

        shmFile.delete()
        guard.close()
    }

    // ── Scenario 5: Directory mutation during transfer ──

    @Test
    fun `directory MOVE detects new file added during transfer`() {
        val dir = tempDir.resolve("dir_race").toFile()
        dir.mkdirs()
        File(dir, "existing.txt").writeText("pre-transfer content")

        val guard = SourceGuard.acquire(dir.absolutePath, TransferMode.MOVE, "dir-1")

        // A process adds a new file during transfer
        File(dir, "new_during_transfer.txt").writeText("this file appeared mid-transfer")

        val result = guard.verifySourceUnchanged()
        assertFalse(result.passed, "Should detect new file in directory: ${result.detail}")
        guard.close()
    }

    @Test
    fun `directory MOVE detects file deletion during transfer`() {
        val dir = tempDir.resolve("dir_delete").toFile()
        dir.mkdirs()
        File(dir, "a.txt").writeText("will be deleted")
        File(dir, "b.txt").writeText("will remain")

        val guard = SourceGuard.acquire(dir.absolutePath, TransferMode.MOVE, "dir-2")

        // A process deletes a file during transfer
        File(dir, "a.txt").delete()

        val result = guard.verifySourceUnchanged()
        assertFalse(result.passed, "Should detect file deletion in directory: ${result.detail}")
        guard.close()
    }

    // ── Scenario 6: Stale lock recovery under concurrent pressure ──

    @Test
    fun `stale lock from dead PID is replaced and new transfer succeeds`() {
        val testFile = tempDir.resolve("stale_recovery.txt").toFile()
        testFile.writeText("recoverable")

        // Create a stale lock with a definitely-dead PID
        val lockFile = File("${testFile.absolutePath}.choam_lock")
        lockFile.writeText("""{"pid":999999999,"transfer_id":"dead-transfer","started":"2026-01-01T00:00:00Z","mode":"COPY"}""")

        // New acquire should replace the stale lock
        val guard = SourceGuard.acquire(testFile.absolutePath, TransferMode.COPY, "replacement")
        val content = lockFile.readText()
        assertTrue(content.contains("replacement"), "Lock should be replaced with new transfer ID")
        assertFalse(content.contains("dead-transfer"), "Old transfer ID should be gone")

        // Verify the guard works normally
        val result = guard.verifySourceUnchanged()
        assertTrue(result.passed)
        guard.close()
        assertFalse(lockFile.exists(), "Lock cleaned up after close")
    }

    // ── Scenario 7: Error category in queue context ──

    @Test
    fun `SourceGuardException messages are suitable for queue error column`() {
        // Verify that exception messages don't contain characters that break SQL or display
        val testFile = tempDir.resolve("nonexistent_for_error.txt").toFile()
        // Don't create the file — acquire should fail

        val exception = assertThrows(SourceGuardException::class.java) {
            SourceGuard.acquire(testFile.absolutePath, TransferMode.COPY, "err-1")
        }

        assertNotNull(exception.message)
        assertTrue(exception.message!!.length < 500, "Error message should be reasonably sized for DB storage")
        assertFalse(exception.message!!.contains("\n"), "Error message should be single-line for display")
    }
}

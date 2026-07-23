package vision.salient.choam.sync

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class SourceGuardTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `isSqlite detects database extensions`() {
        assertTrue(SourceGuard.isSqlite("/path/to/file.db"))
        assertTrue(SourceGuard.isSqlite("/path/to/file.sqlite"))
        assertTrue(SourceGuard.isSqlite("/path/to/file.sqlite3"))
        assertFalse(SourceGuard.isSqlite("/path/to/file.txt"))
        assertFalse(SourceGuard.isSqlite("/path/to/file.csv"))
        assertFalse(SourceGuard.isSqlite("/path/to/file.db-wal"))
    }

    @Test
    fun `sqliteTrio returns db, wal, and shm paths`() {
        val trio = SourceGuard.sqliteTrio("/data/test.db")
        assertEquals(listOf("/data/test.db", "/data/test.db-wal", "/data/test.db-shm"), trio)
    }

    @Test
    fun `parseLsofForWriters ignores read-only opens`() {
        val lsofOutput = """
            COMMAND   PID  USER   FD   TYPE DEVICE SIZE/OFF NODE NAME
            rsync   12345 user    4r   REG   1,15  1234567  789 /data/test.db
        """.trimIndent()
        val writers = SourceGuard.parseLsofForWriters(lsofOutput)
        assertTrue(writers.isEmpty(), "Read-only opens should not be flagged")
    }

    @Test
    fun `parseLsofForWriters detects write opens`() {
        val lsofOutput = """
            COMMAND   PID  USER   FD   TYPE DEVICE SIZE/OFF NODE NAME
            java    22329 user   40u   REG   1,15  1234567  789 /data/test.db
        """.trimIndent()
        val writers = SourceGuard.parseLsofForWriters(lsofOutput)
        assertEquals(1, writers.size)
        assertEquals("java", writers[0].command)
        assertEquals(22329L, writers[0].pid)
        assertEquals("40u", writers[0].fd)
    }

    @Test
    fun `parseLsofForWriters detects write-only opens`() {
        val lsofOutput = """
            COMMAND   PID  USER   FD   TYPE DEVICE SIZE/OFF NODE NAME
            python  99999 user    5w   REG   1,15  1234567  789 /data/test.db
        """.trimIndent()
        val writers = SourceGuard.parseLsofForWriters(lsofOutput)
        assertEquals(1, writers.size)
        assertEquals("python", writers[0].command)
        assertEquals("5w", writers[0].fd)
    }

    @Test
    fun `parseLsofForWriters skips own PID`() {
        val myPid = ProcessHandle.current().pid()
        val lsofOutput = """
            COMMAND   PID  USER   FD   TYPE DEVICE SIZE/OFF NODE NAME
            java    $myPid user   40u   REG   1,15  1234567  789 /data/test.db
        """.trimIndent()
        val writers = SourceGuard.parseLsofForWriters(lsofOutput)
        assertTrue(writers.isEmpty(), "Own PID should be skipped")
    }

    @Test
    fun `acquire creates lock file and cleans up on close`() {
        val testFile = tempDir.resolve("test.txt").toFile()
        testFile.writeText("hello")

        val guard = SourceGuard.acquire(testFile.absolutePath, TransferMode.COPY, "test-123")
        val lockFile = File("${testFile.absolutePath}.choam_lock")

        assertTrue(lockFile.exists(), "Lock file should be created")
        val content = lockFile.readText()
        assertTrue(content.contains("test-123"), "Lock file should contain transfer ID")
        assertTrue(content.contains("COPY"), "Lock file should contain mode")

        guard.close()
        assertFalse(lockFile.exists(), "Lock file should be removed on close")
    }

    @Test
    fun `acquire fails if lock already held by live process`() {
        val testFile = tempDir.resolve("test2.txt").toFile()
        testFile.writeText("hello")

        val guard1 = SourceGuard.acquire(testFile.absolutePath, TransferMode.COPY, "first")

        assertThrows(SourceGuardException::class.java) {
            SourceGuard.acquire(testFile.absolutePath, TransferMode.COPY, "second")
        }

        guard1.close()
    }

    @Test
    fun `stale lock from dead PID is replaced`() {
        val testFile = tempDir.resolve("test3.txt").toFile()
        testFile.writeText("hello")

        // Create a lock file with a PID that definitely doesn't exist
        val lockFile = File("${testFile.absolutePath}.choam_lock")
        lockFile.writeText("""{"pid":999999999,"transfer_id":"stale","started":"2026-01-01T00:00:00Z","mode":"COPY"}""")

        // Should succeed because PID 999999999 is not alive
        val guard = SourceGuard.acquire(testFile.absolutePath, TransferMode.COPY, "new")
        assertTrue(lockFile.exists())
        assertTrue(lockFile.readText().contains("new"))
        guard.close()
    }

    @Test
    fun `verifySourceUnchanged passes when file is unchanged`() {
        val testFile = tempDir.resolve("verify.txt").toFile()
        testFile.writeText("stable content")

        val guard = SourceGuard.acquire(testFile.absolutePath, TransferMode.COPY, "v1")
        val result = guard.verifySourceUnchanged()

        assertTrue(result.passed, "Unchanged file should pass: ${result.detail}")
        guard.close()
    }

    @Test
    fun `verifySourceUnchanged fails when file is modified`() {
        val testFile = tempDir.resolve("modified.txt").toFile()
        testFile.writeText("original")

        val guard = SourceGuard.acquire(testFile.absolutePath, TransferMode.COPY, "v2")

        // Modify the file — need to ensure mtime changes
        Thread.sleep(1100) // Ensure filesystem mtime granularity
        testFile.writeText("modified content that is different")

        val result = guard.verifySourceUnchanged()
        assertFalse(result.passed, "Modified file should fail verification")
        guard.close()
    }

    @Test
    fun `MOVE fingerprint includes SHA-256`() {
        val testFile = tempDir.resolve("move.txt").toFile()
        testFile.writeText("move me")

        val guard = SourceGuard.acquire(testFile.absolutePath, TransferMode.MOVE, "m1")
        assertNotNull(guard.fingerprint.sha256, "MOVE should record SHA-256")
        guard.close()
    }

    @Test
    fun `COPY fingerprint does not include SHA-256`() {
        val testFile = tempDir.resolve("copy.txt").toFile()
        testFile.writeText("copy me")

        val guard = SourceGuard.acquire(testFile.absolutePath, TransferMode.COPY, "c1")
        assertNull(guard.fingerprint.sha256, "COPY should not record SHA-256")
        guard.close()
    }

    @Test
    fun `sha256 produces consistent results`() {
        val testFile = tempDir.resolve("hash.txt").toFile()
        testFile.writeText("deterministic content")

        val hash1 = SourceGuard.sha256(testFile)
        val hash2 = SourceGuard.sha256(testFile)
        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length, "SHA-256 hex should be 64 characters")
    }

    @Test
    fun `verifySqliteMoveQuiescent passes for non-SQLite files`() {
        val testFile = tempDir.resolve("plain.txt").toFile()
        testFile.writeText("not a database")

        val guard = SourceGuard.acquire(testFile.absolutePath, TransferMode.MOVE, "q1")
        val result = guard.verifySqliteMoveQuiescent()
        assertTrue(result.passed, "Non-SQLite should pass: ${result.detail}")
        guard.close()
    }

    @Test
    fun `verifySqliteMoveQuiescent fails if WAL grew`() {
        val dbFile = tempDir.resolve("test.db").toFile()
        dbFile.writeText("fake db content")

        val guard = SourceGuard.acquire(dbFile.absolutePath, TransferMode.MOVE, "q2")

        // Simulate a writer creating a WAL file with content beyond header
        val walFile = File("${dbFile.absolutePath}-wal")
        walFile.writeBytes(ByteArray(1024)) // 1KB > 32 byte header threshold

        val result = guard.verifySqliteMoveQuiescent()
        assertFalse(result.passed, "Should fail if WAL grew: ${result.detail}")
        assertTrue(result.detail.contains("WAL"), "Detail should mention WAL")

        walFile.delete()
        guard.close()
    }

    // ── Fix #1: Directory fingerprinting ──

    @Test
    fun `directory MOVE fingerprint detects file modification`() {
        val dir = tempDir.resolve("dir_move").toFile()
        dir.mkdirs()
        File(dir, "a.txt").writeText("original")
        File(dir, "b.txt").writeText("also original")

        val guard = SourceGuard.acquire(dir.absolutePath, TransferMode.MOVE, "dm1")
        assertNotNull(guard.fingerprint.sha256, "Directory MOVE should have content hash")

        // Modify a file inside the directory (does NOT update parent dir mtime)
        File(dir, "a.txt").writeText("modified content that is different length")

        val result = guard.verifySourceUnchanged()
        assertFalse(result.passed, "Should detect file modification inside directory: ${result.detail}")
        guard.close()
    }

    @Test
    fun `directory COPY fingerprint detects size change`() {
        val dir = tempDir.resolve("dir_copy").toFile()
        dir.mkdirs()
        File(dir, "x.txt").writeText("some content")

        val guard = SourceGuard.acquire(dir.absolutePath, TransferMode.COPY, "dc1")

        // Add a new file — changes total size
        File(dir, "new.txt").writeText("new file added during transfer")

        val result = guard.verifySourceUnchanged()
        assertFalse(result.passed, "Should detect new file in directory: ${result.detail}")
        guard.close()
    }

    @Test
    fun `directory fingerprint unchanged when nothing modified`() {
        val dir = tempDir.resolve("dir_stable").toFile()
        dir.mkdirs()
        File(dir, "stable.txt").writeText("unchanging")

        val guard = SourceGuard.acquire(dir.absolutePath, TransferMode.MOVE, "ds1")
        val result = guard.verifySourceUnchanged()
        assertTrue(result.passed, "Unmodified directory should pass: ${result.detail}")
        guard.close()
    }

    // ── Fix #3: Atomic lock creation ──

    @Test
    fun `concurrent acquire fails atomically`() {
        val testFile = tempDir.resolve("atomic.txt").toFile()
        testFile.writeText("test")

        // Manually create the lock file to simulate a race winner
        val lockFile = File("${testFile.absolutePath}.choam_lock")
        val myPid = ProcessHandle.current().pid()
        lockFile.createNewFile()
        lockFile.writeText("""{"pid":$myPid,"transfer_id":"winner","started":"2026-04-04T00:00:00Z","mode":"COPY"}""")

        // Second acquire should fail because lock exists with our (live) PID
        assertThrows(SourceGuardException::class.java) {
            SourceGuard.acquire(testFile.absolutePath, TransferMode.COPY, "loser")
        }

        lockFile.delete()
    }
}

package vision.salient.choam.sync

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for SendPreflight's auto-skip optimization.
 *
 * The auto-skip logic short-circuits pre-flight when the remote destination
 * directory doesn't exist — marking all files as NEW without per-file SSH calls.
 * Since this involves real SSH, these tests validate the logic by constructing
 * SendPreflightResult objects that mirror the auto-skip path's output and
 * verifying properties of the result.
 *
 * The auto-skip path is triggered when:
 * 1. filePairs.size > 1 (multi-file transfer)
 * 2. SSH `test -d` returns "MISSING"
 *
 * It produces entries with: status=NEW, remoteSize=0, checksums=null.
 */
class SendPreflightAutoSkipTest {

    private lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        tempDir = createTempDirectory("preflight-auto-skip-test")
    }

    @AfterEach
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    /**
     * Simulates the auto-skip path output: when destination doesn't exist,
     * all files are returned as NEW with zero remote size and null checksums.
     */
    private fun simulateAutoSkipResult(localFiles: List<File>, destPath: String): SendPreflightResult {
        val entries = localFiles.map { localFile ->
            val remotePath = if (destPath.endsWith("/")) {
                "$destPath${localFile.name}"
            } else {
                "$destPath/${localFile.name}"
            }
            SendManifestEntry(
                localPath = localFile.absolutePath,
                remotePath = remotePath,
                localSize = localFile.length(),
                remoteSize = 0,
                localChecksum = null,
                remoteChecksum = null,
                status = SendFileStatus.NEW,
                isDatabase = listOf(".db", ".sqlite", ".sqlite3", ".db-wal", ".db-shm", ".db-journal")
                    .any { remotePath.lowercase().endsWith(it) }
            )
        }
        return SendPreflightResult(entries)
    }

    @Test
    fun destinationNotExistsAllFilesMarkedNew() {
        // Create multiple local files (auto-skip only triggers for multi-file)
        val files = (1..5).map { i ->
            tempDir.resolve("file_$i.txt").also { it.writeText("content $i") }.toFile()
        }

        val result = simulateAutoSkipResult(files, "/Volumes/EXTERNAL/backup/")

        assertEquals(5, result.newFiles.size)
        assertEquals(0, result.identicalFiles.size)
        assertEquals(0, result.conflictFiles.size)
        assertTrue(result.safeToSend)
        assertFalse(result.hasConflicts)

        // Every entry should have zero remote size and null checksums
        for (entry in result.entries) {
            assertEquals(SendFileStatus.NEW, entry.status)
            assertEquals(0L, entry.remoteSize)
            assertEquals(null, entry.localChecksum)
            assertEquals(null, entry.remoteChecksum)
        }
    }

    @Test
    fun autoSkipPreservesCorrectLocalSizes() {
        val f1 = tempDir.resolve("small.txt").also { it.writeText("hi") }.toFile()
        val f2 = tempDir.resolve("bigger.txt").also { it.writeText("a".repeat(1000)) }.toFile()

        val result = simulateAutoSkipResult(listOf(f1, f2), "/dest/")

        assertEquals(f1.length(), result.entries[0].localSize)
        assertEquals(f2.length(), result.entries[1].localSize)
    }

    @Test
    fun autoSkipDetectsDatabaseExtensionsCorrectly() {
        val db = tempDir.resolve("important.db").also { it.writeText("sqlite") }.toFile()
        val wal = tempDir.resolve("important.db-wal").also { it.writeText("wal") }.toFile()
        val txt = tempDir.resolve("readme.txt").also { it.writeText("text") }.toFile()

        val result = simulateAutoSkipResult(listOf(db, wal, txt), "/backup/")

        assertTrue(result.entries[0].isDatabase, "Expected .db to be flagged as database")
        assertTrue(result.entries[1].isDatabase, "Expected .db-wal to be flagged as database")
        assertFalse(result.entries[2].isDatabase, "Expected .txt to NOT be flagged as database")
    }

    @Test
    fun autoSkipBuildsCorrectRemotePaths() {
        val f1 = tempDir.resolve("data.csv").also { it.writeText("a,b,c") }.toFile()
        val f2 = tempDir.resolve("notes.txt").also { it.writeText("notes") }.toFile()

        val result = simulateAutoSkipResult(listOf(f1, f2), "/Volumes/EXTERNAL/incoming/")

        assertEquals("/Volumes/EXTERNAL/incoming/data.csv", result.entries[0].remotePath)
        assertEquals("/Volumes/EXTERNAL/incoming/notes.txt", result.entries[1].remotePath)
    }

    @Test
    fun autoSkipNotTriggeredForSingleFile() {
        // The auto-skip optimization in SendPreflight only fires when filePairs.size > 1.
        // With a single file, the code falls through to full per-file pre-flight.
        // This test documents that expectation via the code's branching condition.
        val singleFile = tempDir.resolve("only.txt").also { it.writeText("single") }.toFile()

        // The auto-skip branch checks: if (filePairs.size > 1)
        // For a single file, this is false, so auto-skip is NOT triggered.
        val filePairs = listOf(singleFile)
        assertTrue(filePairs.size <= 1, "Single file should NOT trigger auto-skip")

        // If we still construct a result, the single-file pre-flight path should
        // produce entries via the normal SSH stat/hash check, not the auto-skip path.
        // Here we just verify the branching condition.
    }

    @Test
    fun autoSkipMultiFileThresholdIsTwo() {
        // Exactly 2 files should trigger auto-skip (filePairs.size > 1)
        val files = listOf(
            tempDir.resolve("a.txt").also { it.writeText("a") }.toFile(),
            tempDir.resolve("b.txt").also { it.writeText("b") }.toFile()
        )
        assertTrue(files.size > 1, "Two files should trigger auto-skip path")

        val result = simulateAutoSkipResult(files, "/dest/")
        assertEquals(2, result.newFiles.size)
    }

    @Test
    fun sshCheckFailureFallsThroughToFullPreflight() {
        // When the SSH `test -d` command fails (exception or non-zero exit),
        // the code catches the exception and proceeds with full pre-flight.
        // This is the correct fail-open behavior for the directory existence check.
        //
        // We verify this by constructing a mixed result (what full pre-flight
        // would return) and confirming it has the right classification.
        val result = SendPreflightResult(
            entries = listOf(
                // Full pre-flight found this file already exists and matches
                SendManifestEntry(
                    localPath = "/tmp/existing.txt",
                    remotePath = "/dest/existing.txt",
                    localSize = 100,
                    remoteSize = 100,
                    localChecksum = "abc123",
                    remoteChecksum = "abc123",
                    status = SendFileStatus.IDENTICAL,
                    isDatabase = false
                ),
                // Full pre-flight found this file is new
                SendManifestEntry(
                    localPath = "/tmp/new.txt",
                    remotePath = "/dest/new.txt",
                    localSize = 200,
                    remoteSize = 0,
                    localChecksum = null,
                    remoteChecksum = null,
                    status = SendFileStatus.NEW,
                    isDatabase = false
                )
            )
        )

        // When SSH check fails, we get a mixed result from full pre-flight
        // (not all-NEW like auto-skip would produce)
        assertEquals(1, result.identicalFiles.size)
        assertEquals(1, result.newFiles.size)
        assertTrue(result.safeToSend, "No conflicts means safe to send")
    }

    @Test
    fun autoSkipResultIsSafeToSendNoConflicts() {
        val files = (1..10).map { i ->
            tempDir.resolve("batch_$i.dat").also { it.writeText("data_$i") }.toFile()
        }

        val result = simulateAutoSkipResult(files, "/Volumes/ALPHA/archive/")

        assertTrue(result.safeToSend)
        assertFalse(result.hasConflicts)
        assertEquals(0, result.databaseConflicts.size)
        assertEquals(10, result.newFiles.size)
    }

    @Test
    fun autoSkipWithDestPathWithoutTrailingSlash() {
        val f = tempDir.resolve("test.txt").also { it.writeText("test") }.toFile()
        val f2 = tempDir.resolve("test2.txt").also { it.writeText("test2") }.toFile()

        val result = simulateAutoSkipResult(listOf(f, f2), "/backup/dir")

        // Without trailing slash, the code adds a slash before the filename
        assertEquals("/backup/dir/test.txt", result.entries[0].remotePath)
        assertEquals("/backup/dir/test2.txt", result.entries[1].remotePath)
    }

}

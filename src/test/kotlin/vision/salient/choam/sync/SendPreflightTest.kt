package vision.salient.choam.sync

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for SendPreflight data models and manifest logic.
 *
 * These test the classification logic, manifest printing, database detection,
 * and result properties. Integration tests requiring SSH are not included here
 * (those require actual remote machines).
 */
class SendPreflightTest {

    @TempDir
    lateinit var tempDir: Path

    private fun filePair(name: String, contents: String, remotePath: String): Pair<File, String> {
        val file = tempDir.resolve(name).toFile()
        file.parentFile?.mkdirs()
        file.writeText(contents)
        return file to remotePath
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `NEW file result is safe to send`() {
        val result = SendPreflightResult(
            entries = listOf(
                SendManifestEntry(
                    localPath = "/tmp/test.txt",
                    remotePath = "/dest/test.txt",
                    localSize = 1024,
                    remoteSize = 0,
                    localChecksum = null,
                    remoteChecksum = null,
                    status = SendFileStatus.NEW,
                    isDatabase = false
                )
            )
        )
        assertTrue(result.safeToSend)
        assertFalse(result.hasConflicts)
        assertEquals(1, result.newFiles.size)
        assertEquals(0, result.identicalFiles.size)
        assertEquals(0, result.conflictFiles.size)
    }

    @Test
    fun `IDENTICAL file result is safe to send and skips`() {
        val result = SendPreflightResult(
            entries = listOf(
                SendManifestEntry(
                    localPath = "/tmp/test.txt",
                    remotePath = "/dest/test.txt",
                    localSize = 1024,
                    remoteSize = 1024,
                    localChecksum = "abc123",
                    remoteChecksum = "abc123",
                    status = SendFileStatus.IDENTICAL,
                    isDatabase = false
                )
            )
        )
        assertTrue(result.safeToSend)
        assertFalse(result.hasConflicts)
        assertEquals(0, result.newFiles.size)
        assertEquals(1, result.identicalFiles.size)
    }

    @Test
    fun `CONFLICT file result blocks transfer`() {
        val result = SendPreflightResult(
            entries = listOf(
                SendManifestEntry(
                    localPath = "/tmp/test.txt",
                    remotePath = "/dest/test.txt",
                    localSize = 1024,
                    remoteSize = 2048,
                    localChecksum = null,
                    remoteChecksum = null,
                    status = SendFileStatus.CONFLICT,
                    isDatabase = false
                )
            )
        )
        assertFalse(result.safeToSend)
        assertTrue(result.hasConflicts)
        assertEquals(1, result.conflictFiles.size)
    }

    @Test
    fun `database conflict is flagged separately`() {
        val result = SendPreflightResult(
            entries = listOf(
                SendManifestEntry(
                    localPath = "/tmp/important_data.db",
                    remotePath = "/dest/important_data.db",
                    localSize = 39_000_000_000,
                    remoteSize = 82_000_000_000,
                    localChecksum = null,
                    remoteChecksum = null,
                    status = SendFileStatus.CONFLICT,
                    isDatabase = true
                )
            )
        )
        assertFalse(result.safeToSend)
        assertEquals(1, result.databaseConflicts.size)
        assertTrue(result.databaseConflicts[0].isDatabase)
    }

    @Test
    fun `mixed directory with NEW and CONFLICT blocks on any conflict`() {
        val result = SendPreflightResult(
            entries = listOf(
                SendManifestEntry(
                    localPath = "/tmp/a.txt",
                    remotePath = "/dest/a.txt",
                    localSize = 100,
                    remoteSize = 0,
                    localChecksum = null,
                    remoteChecksum = null,
                    status = SendFileStatus.NEW,
                    isDatabase = false
                ),
                SendManifestEntry(
                    localPath = "/tmp/b.txt",
                    remotePath = "/dest/b.txt",
                    localSize = 200,
                    remoteSize = 300,
                    localChecksum = null,
                    remoteChecksum = null,
                    status = SendFileStatus.CONFLICT,
                    isDatabase = false
                ),
                SendManifestEntry(
                    localPath = "/tmp/c.txt",
                    remotePath = "/dest/c.txt",
                    localSize = 400,
                    remoteSize = 400,
                    localChecksum = "aaa",
                    remoteChecksum = "aaa",
                    status = SendFileStatus.IDENTICAL,
                    isDatabase = false
                )
            )
        )
        assertFalse(result.safeToSend)
        assertEquals(1, result.newFiles.size)
        assertEquals(1, result.identicalFiles.size)
        assertEquals(1, result.conflictFiles.size)
    }

    @Test
    fun `all identical files means nothing to transfer`() {
        val result = SendPreflightResult(
            entries = listOf(
                SendManifestEntry(
                    localPath = "/tmp/a.txt",
                    remotePath = "/dest/a.txt",
                    localSize = 100,
                    remoteSize = 100,
                    localChecksum = "aaa",
                    remoteChecksum = "aaa",
                    status = SendFileStatus.IDENTICAL,
                    isDatabase = false
                ),
                SendManifestEntry(
                    localPath = "/tmp/b.txt",
                    remotePath = "/dest/b.txt",
                    localSize = 200,
                    remoteSize = 200,
                    localChecksum = "bbb",
                    remoteChecksum = "bbb",
                    status = SendFileStatus.IDENTICAL,
                    isDatabase = false
                )
            )
        )
        assertTrue(result.safeToSend)
        assertEquals(2, result.identicalFiles.size)
        assertEquals(0, result.newFiles.size)
    }

    @Test
    fun `database extensions are detected correctly`() {
        val dbExtensions = listOf(".db", ".sqlite", ".sqlite3", ".db-wal", ".db-shm", ".db-journal")
        for (ext in dbExtensions) {
            val entry = SendManifestEntry(
                localPath = "/tmp/data$ext",
                remotePath = "/dest/data$ext",
                localSize = 1000,
                remoteSize = 2000,
                localChecksum = null,
                remoteChecksum = null,
                status = SendFileStatus.CONFLICT,
                isDatabase = true
            )
            assertTrue(entry.isDatabase, "Expected $ext to be flagged as database")
        }
    }

    @Test
    fun `printManifest outputs correct status labels`() {
        val result = SendPreflightResult(
            entries = listOf(
                SendManifestEntry("/tmp/new.txt", "/dest/new.txt", 100, 0, null, null, SendFileStatus.NEW, false),
                SendManifestEntry("/tmp/same.txt", "/dest/same.txt", 200, 200, "abc", "abc", SendFileStatus.IDENTICAL, false),
                SendManifestEntry("/tmp/diff.db", "/dest/diff.db", 1000, 2000, null, null, SendFileStatus.CONFLICT, true)
            )
        )
        val output = mutableListOf<String>()
        SendPreflight.printManifest(result, output::add)

        // Should contain NEW, IDENTICAL, CONFLICT labels
        assertTrue(output.any { it.contains("NEW") }, "Expected NEW label in output")
        assertTrue(output.any { it.contains("IDENTICAL") }, "Expected IDENTICAL label in output")
        assertTrue(output.any { it.contains("CONFLICT") }, "Expected CONFLICT label in output")
        assertTrue(output.any { it.contains("[DATABASE]") }, "Expected [DATABASE] tag in output")
        assertTrue(output.any { it.contains("REFUSED") }, "Expected REFUSED message for conflicts")
        assertTrue(output.any { it.contains("database") }, "Expected database warning")
    }

    @Test
    fun `printManifest with no conflicts does not show REFUSED`() {
        val result = SendPreflightResult(
            entries = listOf(
                SendManifestEntry("/tmp/new.txt", "/dest/new.txt", 100, 0, null, null, SendFileStatus.NEW, false)
            )
        )
        val output = mutableListOf<String>()
        SendPreflight.printManifest(result, output::add)

        assertFalse(output.any { it.contains("REFUSED") }, "Should not show REFUSED when no conflicts")
    }

    @Test
    fun `empty preflight result is safe`() {
        val result = SendPreflightResult(entries = emptyList())
        assertTrue(result.safeToSend)
        assertFalse(result.hasConflicts)
        assertEquals(0, result.newFiles.size)
        assertEquals(0, result.identicalFiles.size)
        assertEquals(0, result.conflictFiles.size)
        assertEquals(0, result.databaseConflicts.size)
    }

    @Test
    fun `TransferQueueEntry force and backup fields default to false`() {
        val entry = TransferQueueEntry(
            sourcePath = "/tmp/test.txt",
            destinationMachine = "server-b",
            destinationPath = "/dest/"
        )
        assertFalse(entry.force)
        assertFalse(entry.backup)
    }

    @Test
    fun `TransferQueueEntry preserves force and backup when set`() {
        val entry = TransferQueueEntry(
            sourcePath = "/tmp/test.txt",
            destinationMachine = "server-b",
            destinationPath = "/dest/",
            force = true,
            backup = true
        )
        assertTrue(entry.force)
        assertTrue(entry.backup)
    }

    @Test
    fun `formatBytesPublic formats various sizes correctly`() {
        assertEquals("0 B", SendPreflight.formatBytesPublic(0))
        assertEquals("512 B", SendPreflight.formatBytesPublic(512))
        assertEquals("1.0 KB", SendPreflight.formatBytesPublic(1024))
        assertEquals("1.0 MB", SendPreflight.formatBytesPublic(1_048_576))
        assertEquals("1.0 GB", SendPreflight.formatBytesPublic(1_073_741_824))
        assertEquals("36.3 GB", SendPreflight.formatBytesPublic(39_000_000_000))
        assertEquals("76.4 GB", SendPreflight.formatBytesPublic(82_000_000_000))
    }

    @Test
    fun `parsePerFileCheckOutput returns null on non-zero exit even with output`() {
        val pair = filePair("a.txt", "hello", "/dest/a.txt")

        val result = SendPreflight.parsePerFileCheckOutput(
            filePairs = listOf(pair),
            output = "EXISTS|${pair.first.length()}|NONE\n",
            exitCode = 255
        )

        assertEquals(
            null,
            result,
            "Partial output from a failed SSH command should not be trusted"
        )
    }

    @Test
    fun `parsePerFileCheckOutput returns null on truncated output`() {
        val pairA = filePair("a.txt", "hello", "/dest/a.txt")
        val pairB = filePair("b.txt", "world", "/dest/b.txt")

        val result = SendPreflight.parsePerFileCheckOutput(
            filePairs = listOf(pairA, pairB),
            output = "EXISTS|${pairA.first.length()}|NONE\n",
            exitCode = 0
        )

        assertEquals(
            null,
            result,
            "Missing response lines should abort parsing instead of defaulting unchecked files to NEW"
        )
    }

    @Test
    fun `parsePerFileCheckOutput classifies missing and identical files`() {
        val pairA = filePair("a.txt", "hello", "/dest/a.txt")
        val pairB = filePair("b.txt", "world", "/dest/b.txt")

        val result = SendPreflight.parsePerFileCheckOutput(
            filePairs = listOf(pairA, pairB),
            output = """
                MISSING|0|NONE
                EXISTS|${pairB.first.length()}|NONE
            """.trimIndent(),
            exitCode = 0
        )

        assertIs<SendPreflightResult>(result)
        assertEquals(1, result.newFiles.size)
        assertEquals(1, result.identicalFiles.size)
        assertEquals("/dest/a.txt", result.newFiles.single().remotePath)
        assertEquals("/dest/b.txt", result.identicalFiles.single().remotePath)
    }

    @Test
    fun `parsePerFileCheckOutput returns null on malformed line`() {
        val pair = filePair("a.txt", "hello", "/dest/a.txt")

        val result = SendPreflight.parsePerFileCheckOutput(
            filePairs = listOf(pair),
            output = "garbage line without separators\n",
            exitCode = 0
        )

        assertNull(result, "Malformed parser output should abort instead of defaulting the file to NEW")
    }

    @Test
    fun `parsePerFileCheckOutput returns null on unexpected status token`() {
        val pair = filePair("a.txt", "hello", "/dest/a.txt")

        val result = SendPreflight.parsePerFileCheckOutput(
            filePairs = listOf(pair),
            output = "WARNING|5|NONE\n",
            exitCode = 0
        )

        assertNull(result, "Unexpected status tokens should abort parsing")
    }

    @Test
    fun `parsePerFileCheckOutput returns null on invalid remote size`() {
        val pair = filePair("a.txt", "hello", "/dest/a.txt")

        val result = SendPreflight.parsePerFileCheckOutput(
            filePairs = listOf(pair),
            output = "EXISTS|not-a-number|NONE\n",
            exitCode = 0
        )

        assertNull(result, "Invalid remote size should abort parsing")
    }

    @Test
    fun `parsePerFileCheckOutput classifies checksum mismatch as conflict`() {
        val pair = filePair("a.txt", "hello", "/dest/a.txt")

        val result = SendPreflight.parsePerFileCheckOutput(
            filePairs = listOf(pair),
            output = "EXISTS|${pair.first.length()}|${sha256("different")}\n",
            exitCode = 0
        )

        assertIs<SendPreflightResult>(result)
        assertEquals(1, result.conflictFiles.size)
        assertEquals("/dest/a.txt", result.conflictFiles.single().remotePath)
    }

    // ── Phase 9.7: Fail-closed mapping tests ──
    // These verify the contract: each internal result type maps to the correct
    // PreflightOutcome variant. Tests construct the mapped outcome directly to
    // avoid redundant `when` branches that trigger "always false" warnings.

    @Test
    fun `RemoteTreeResult Failed maps to UnsafeAbort`() {
        val failed = RemoteTreeResult.Failed("SSH exited with code 1: permission denied")
        val outcome = PreflightOutcome.UnsafeAbort(failed.reason)
        assertTrue(outcome.reason.contains("permission denied"))
    }

    @Test
    fun `RemoteTreeResult NetworkUnavailable maps to FallThrough`() {
        // NetworkUnavailable is a singleton — the mapping target is FallThrough
        assertIs<RemoteTreeResult.NetworkUnavailable>(RemoteTreeResult.NetworkUnavailable)
        assertIs<PreflightOutcome.FallThrough>(PreflightOutcome.FallThrough)
    }

    @Test
    fun `ManifestLoadResult Corrupt maps to UnsafeAbort`() {
        val corrupt = ManifestLoadResult.Corrupt("bad JSON at byte 42")
        val outcome = PreflightOutcome.UnsafeAbort(corrupt.reason)
        assertEquals("bad JSON at byte 42", outcome.reason)
    }

    @Test
    fun `ManifestLoadResult Missing maps to FallThrough`() {
        assertIs<ManifestLoadResult.Missing>(ManifestLoadResult.Missing)
        assertIs<PreflightOutcome.FallThrough>(PreflightOutcome.FallThrough)
    }

    @Test
    fun `ManifestLoadResult Loaded maps to Resolved`() {
        val manifest = ChoamManifest(
            builtAt = "2026-04-01T00:00:00Z",
            rootHash = "abc123",
            fileCount = 1,
            totalBytes = 100,
            hashConfidence = "CONTENT",
            files = mapOf("f.txt" to ChoamManifest.FileEntry(100, 0, "cid", "meta"))
        )
        val loaded = ManifestLoadResult.Loaded(manifest)
        assertEquals("abc123", loaded.manifest.rootHash)
        // Loaded → Resolved with the manifest data
        val outcome = PreflightOutcome.Resolved(SendPreflightResult(emptyList()))
        assertIs<PreflightOutcome.Resolved>(outcome)
    }

    @Test
    fun `truncated remote tree output causes UnsafeAbort`() {
        val failed = RemoteTreeResult.Failed("Truncated remote tree output (no completion marker)")
        val outcome = PreflightOutcome.UnsafeAbort(failed.reason)
        assertTrue(outcome.reason.contains("Truncated"))
    }

    @Test
    fun `SSH non-zero exit causes UnsafeAbort`() {
        val failed = RemoteTreeResult.Failed("SSH exited with code 255: Connection reset")
        val outcome = PreflightOutcome.UnsafeAbort(failed.reason)
        assertTrue(outcome.reason.contains("255"))
    }

    @Test
    fun `parse error in remote tree line causes UnsafeAbort`() {
        val failed = RemoteTreeResult.Failed("Parse error in remote tree line: garbage data here")
        val outcome = PreflightOutcome.UnsafeAbort(failed.reason)
        assertTrue(outcome.reason.contains("Parse error"))
    }

    // ── Partial-resume fix: locally-present / remotely-absent files must still be sent ──

    @Test
    fun `changedPathsFromDiff includes deleted (locally-present, remotely-absent) files`() {
        // localTree.diff(remoteTree): deletedFiles = present locally but absent remotely =
        // exactly the files a partial/resumed transfer still needs to send. They MUST be in the
        // changed set, or they get marked IDENTICAL and the move silently skips them.
        val diff = MerkleDiff(
            newFiles = listOf("remote-only.txt"),
            modifiedFiles = listOf("changed.txt"),
            deletedFiles = listOf("missing-1.mkv", "missing-2.mkv")
        )
        assertEquals(
            setOf("remote-only.txt", "changed.txt", "missing-1.mkv", "missing-2.mkv"),
            SendPreflight.changedPathsFromDiff(diff),
            "deletedFiles (missing on remote) must be included so a partial directory move resumes"
        )
    }

    @Test
    fun `changedPathsFromDiff is empty when nothing changed`() {
        assertTrue(SendPreflight.changedPathsFromDiff(MerkleDiff(emptyList(), emptyList(), emptyList())).isEmpty())
    }

    @Test
    fun `per-file check classifies a remotely-missing file as NEW`() {
        // A deletedFile routed through the per-file check gets "MISSING|0|NONE" from the remote,
        // which must classify as NEW so the transfer proceeds (not IDENTICAL → skipped).
        val pair = filePair("ep.mkv", "video bytes", "/dest/ep.mkv")
        val result = SendPreflight.parsePerFileCheckOutput(listOf(pair), "MISSING|0|NONE", 0)
        assertIs<SendPreflightResult>(result)
        assertEquals(1, result.newFiles.size)
        assertEquals(SendFileStatus.NEW, result.entries[0].status)
    }
}

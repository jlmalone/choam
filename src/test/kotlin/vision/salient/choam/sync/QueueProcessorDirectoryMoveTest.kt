package vision.salient.choam.sync

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import vision.salient.choam.receipt.TransferReceiptState
import java.io.File
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the directory move / verify-and-delete logic.
 *
 * Now tests SHA-256 content verification (not just size).
 * Uses local file simulation — no real SSH.
 */
class QueueProcessorDirectoryMoveTest {

    private lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        tempDir = createTempDirectory("queue-proc-dir-move-test")
    }

    @AfterEach
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    /**
     * Simulates directory verification with size + SHA-256 matching.
     * Mirrors PostTransferVerifier.verifyDirectory() logic but with local "remote" data.
     *
     * @param sourceDir local source directory
     * @param remoteData map of relativePath -> (size, sha256hex?) simulating remote state
     */
    private fun verifyAndDeleteDirectoryLocal(
        sourceDir: File,
        remoteData: Map<String, Pair<Long, String?>>
    ): DirectoryVerifyResult {
        val allFiles = sourceDir.walkTopDown().filter { it.isFile }.toList()
        if (allFiles.isEmpty()) {
            sourceDir.deleteRecursively()
            return DirectoryVerifyResult(0, emptyList(), emptyList())
        }

        val verified = mutableListOf<File>()
        val failed = mutableListOf<String>()
        val hashMismatches = mutableListOf<String>()

        for (file in allFiles) {
            val relPath = file.relativeTo(sourceDir).path
            val remote = remoteData[relPath]

            if (remote == null) {
                failed.add(relPath)
                continue
            }

            val (remoteSize, remoteHash) = remote

            if (remoteSize != file.length()) {
                failed.add(relPath)
                continue
            }

            if (remoteHash == null) {
                // Fail closed: no hash = not verified
                failed.add(relPath)
                continue
            }

            val localHash = PostTransferVerifier.computeSha256(file)
            if (localHash != remoteHash) {
                hashMismatches.add(relPath)
                failed.add(relPath)
                continue
            }

            verified.add(file)
        }

        // Delete only verified files
        for (file in verified) {
            file.delete()
        }

        // Clean up empty directories bottom-up
        sourceDir.walkBottomUp().filter { it.isDirectory }.forEach { dir ->
            val children = dir.listFiles()
            if (children != null && children.isEmpty()) {
                dir.delete()
            }
        }

        return DirectoryVerifyResult(verified.size, failed, hashMismatches)
    }

    /** Helper: compute remote data entry for a file (simulating correct remote state). */
    private fun correctRemoteEntry(file: File): Pair<Long, String?> =
        file.length() to PostTransferVerifier.computeSha256(file)

    @Test
    fun alreadyPresentReceiptStateRequiresAuthoritativeComparison() {
        assertEquals(TransferReceiptState.DEFERRED, alreadyPresentReceiptState(authoritativeComparison = false))
        assertEquals(TransferReceiptState.VERIFYING_FILES, alreadyPresentReceiptState(authoritativeComparison = true))
    }

    @Test
    fun receiptExpectationEligibilityRejectsEveryNonregularAttributeKind() {
        assertEquals(17, receiptExpectationsFor(attributes(regular = true, size = 17))?.bytes)
        listOf("directory", "symlink", "fifo", "socket", "device").forEach { kind ->
            assertEquals(null, receiptExpectationsFor(attributes(regular = false, kind = kind)))
        }
    }

    private fun attributes(regular: Boolean, size: Long = 0, kind: String = "file") = object : BasicFileAttributes {
        override fun lastModifiedTime(): FileTime = FileTime.fromMillis(0)
        override fun lastAccessTime(): FileTime = FileTime.fromMillis(0)
        override fun creationTime(): FileTime = FileTime.fromMillis(0)
        override fun isRegularFile(): Boolean = regular
        override fun isDirectory(): Boolean = kind == "directory"
        override fun isSymbolicLink(): Boolean = kind == "symlink"
        override fun isOther(): Boolean = kind in setOf("fifo", "socket", "device")
        override fun size(): Long = size
        override fun fileKey(): Any? = null
    }

    // ── All verified (size + hash match) ──

    @Test
    fun allFilesHashMatchDeletesEntireDirectory() {
        val srcDir = tempDir.resolve("source").toFile()
        srcDir.mkdirs()
        File(srcDir, "a.txt").writeText("hello")
        File(srcDir, "b.txt").writeText("world!")

        val remoteData = mapOf(
            "a.txt" to correctRemoteEntry(File(srcDir, "a.txt")),
            "b.txt" to correctRemoteEntry(File(srcDir, "b.txt"))
        )

        val result = verifyAndDeleteDirectoryLocal(srcDir, remoteData)

        assertEquals(2, result.verified)
        assertTrue(result.failed.isEmpty())
        assertTrue(result.hashMismatches.isEmpty())
        assertFalse(srcDir.exists(), "Source directory should be fully deleted")
    }

    // ── Size match, hash mismatch ──

    @Test
    fun sizeMatchButHashMismatchKeepsFile() {
        val srcDir = tempDir.resolve("hash-mismatch").toFile()
        srcDir.mkdirs()
        val file = File(srcDir, "data.txt")
        file.writeText("correct content")

        // Remote has same size but wrong hash (corrupt transfer)
        val wrongHash = "0000000000000000000000000000000000000000000000000000000000000000"
        val remoteData = mapOf(
            "data.txt" to (file.length() to wrongHash)
        )

        val result = verifyAndDeleteDirectoryLocal(srcDir, remoteData)

        assertEquals(0, result.verified)
        assertEquals(1, result.failed.size)
        assertEquals(1, result.hashMismatches.size)
        assertEquals("data.txt", result.hashMismatches[0])
        assertTrue(file.exists(), "File with hash mismatch must be kept")
    }

    // ── Mixed: some verified, some size mismatch, some hash mismatch ──

    @Test
    fun mixedVerificationOutcomesClassifiedCorrectly() {
        val srcDir = tempDir.resolve("mixed").toFile()
        srcDir.mkdirs()
        val good = File(srcDir, "good.txt")
        good.writeText("good content")
        val sizeMismatch = File(srcDir, "wrong-size.txt")
        sizeMismatch.writeText("some content")
        val hashMismatch = File(srcDir, "corrupt.txt")
        hashMismatch.writeText("original data")

        val remoteData = mapOf(
            "good.txt" to correctRemoteEntry(good),
            "wrong-size.txt" to (999L to "irrelevant"),  // size mismatch
            "corrupt.txt" to (hashMismatch.length() to "badhash0000000000000000000000000000000000000000000000000000000000")  // hash mismatch
        )

        val result = verifyAndDeleteDirectoryLocal(srcDir, remoteData)

        assertEquals(1, result.verified)
        assertEquals(2, result.failed.size)
        assertEquals(1, result.hashMismatches.size)
        assertTrue("corrupt.txt" in result.hashMismatches)
        assertTrue("wrong-size.txt" in result.failed)
        assertTrue("corrupt.txt" in result.failed)
        assertFalse(good.exists(), "Verified file should be deleted")
        assertTrue(sizeMismatch.exists(), "Size mismatch file should be kept")
        assertTrue(hashMismatch.exists(), "Hash mismatch file should be kept")
    }

    // ── Missing hash (fail closed — no size-only fallback) ──

    @Test
    fun missingRemoteHashFailsClosed() {
        val srcDir = tempDir.resolve("no-hash").toFile()
        srcDir.mkdirs()
        val file = File(srcDir, "file.txt")
        file.writeText("content")

        // Remote has correct size but null hash (tool missing)
        val remoteData = mapOf(
            "file.txt" to (file.length() to null)
        )

        val result = verifyAndDeleteDirectoryLocal(srcDir, remoteData)

        assertEquals(0, result.verified, "Missing hash must NOT verify")
        assertEquals(1, result.failed.size)
        assertTrue(file.exists(), "File must be kept when hash unavailable")
    }

    // ── SSH failure (no remote data) ──

    @Test
    fun sshFailureNoFilesDeletedAllFailed() {
        val srcDir = tempDir.resolve("ssh-fail").toFile()
        srcDir.mkdirs()
        File(srcDir, "file1.txt").writeText("content1")
        File(srcDir, "file2.txt").writeText("content2")

        // Empty remote data simulates SSH failure
        val result = verifyAndDeleteDirectoryLocal(srcDir, emptyMap())

        assertEquals(0, result.verified)
        assertEquals(2, result.failed.size)
        assertTrue(File(srcDir, "file1.txt").exists())
        assertTrue(File(srcDir, "file2.txt").exists())
    }

    // ── Deep nesting ──

    @Test
    fun deeplyNestedAllVerifiedDeletesEntireTree() {
        val srcDir = tempDir.resolve("deep").toFile()
        val nested = File(srcDir, "a/b/c")
        nested.mkdirs()
        File(nested, "deep.txt").writeText("deep content")
        File(srcDir, "a/top.txt").writeText("top level")

        val remoteData = mapOf(
            "a/b/c/deep.txt" to correctRemoteEntry(File(nested, "deep.txt")),
            "a/top.txt" to correctRemoteEntry(File(srcDir, "a/top.txt"))
        )

        val result = verifyAndDeleteDirectoryLocal(srcDir, remoteData)

        assertEquals(2, result.verified)
        assertTrue(result.failed.isEmpty())
        assertFalse(srcDir.exists())
    }

    @Test
    fun deeplyNestedPartialFailureKeepsUnverifiedBranch() {
        val srcDir = tempDir.resolve("deep-partial").toFile()
        File(srcDir, "a/b/c").mkdirs()
        File(srcDir, "a/b/d").mkdirs()
        File(srcDir, "a/b/c/good.txt").writeText("good")
        File(srcDir, "a/b/d/bad.txt").writeText("bad")

        val remoteData = mapOf(
            "a/b/c/good.txt" to correctRemoteEntry(File(srcDir, "a/b/c/good.txt")),
            "a/b/d/bad.txt" to (File(srcDir, "a/b/d/bad.txt").length() to "badhash0000000000000000000000000000000000000000000000000000000000")
        )

        val result = verifyAndDeleteDirectoryLocal(srcDir, remoteData)

        assertEquals(1, result.verified)
        assertEquals(1, result.hashMismatches.size)
        assertFalse(File(srcDir, "a/b/c/good.txt").exists())
        assertTrue(File(srcDir, "a/b/d/bad.txt").exists())
    }

    // ── Empty directory ──

    @Test
    fun emptyDirectoryDeletedImmediately() {
        val srcDir = tempDir.resolve("empty-dir").toFile()
        srcDir.mkdirs()

        val result = verifyAndDeleteDirectoryLocal(srcDir, emptyMap())

        assertEquals(0, result.verified)
        assertTrue(result.failed.isEmpty())
        assertFalse(srcDir.exists())
    }

    // ── Large batch ──

    @Test
    fun largeFileCountAllHashVerified() {
        val srcDir = tempDir.resolve("large-batch").toFile()
        srcDir.mkdirs()
        val remoteData = mutableMapOf<String, Pair<Long, String?>>()

        repeat(100) { i ->
            val f = File(srcDir, "file_$i.dat")
            f.writeText("content_$i padding_${i * 7}")
            remoteData["file_$i.dat"] = correctRemoteEntry(f)
        }

        val result = verifyAndDeleteDirectoryLocal(srcDir, remoteData)

        assertEquals(100, result.verified)
        assertTrue(result.failed.isEmpty())
        assertTrue(result.hashMismatches.isEmpty())
        assertFalse(srcDir.exists())
    }

    // ── Remote file missing entirely ──

    @Test
    fun remoteMissingFileTreatedAsFailure() {
        val srcDir = tempDir.resolve("missing-remote").toFile()
        srcDir.mkdirs()
        File(srcDir, "exists.txt").writeText("here")
        File(srcDir, "missing.txt").writeText("also here")

        val remoteData = mapOf(
            "exists.txt" to correctRemoteEntry(File(srcDir, "exists.txt"))
            // missing.txt has no remote entry
        )

        val result = verifyAndDeleteDirectoryLocal(srcDir, remoteData)

        assertEquals(1, result.verified)
        assertEquals(listOf("missing.txt"), result.failed)
        assertFalse(File(srcDir, "exists.txt").exists())
        assertTrue(File(srcDir, "missing.txt").exists())
    }
}

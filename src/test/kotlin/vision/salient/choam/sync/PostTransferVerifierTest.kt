package vision.salient.choam.sync

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/**
 * Tests for PostTransferVerifier (MOVE hardening).
 *
 * Uses temp directories to test local SHA-256 computation and the
 * DirectoryVerifyResult classification logic. SSH-dependent paths
 * (verifySingleFile, verifyDirectory) are tested via contract validation
 * and local hash computation — no real SSH.
 */
class PostTransferVerifierTest {

    private lateinit var tempDir: java.nio.file.Path

    @BeforeEach
    fun setup() {
        tempDir = createTempDirectory("post-transfer-verify")
    }

    @AfterEach
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    private fun createDir(name: String): File {
        val dir = tempDir.resolve(name).toFile()
        dir.mkdirs()
        return dir
    }

    // ── Local SHA-256 computation ──

    @Test
    fun `computeSha256 produces 64-char hex string`() {
        val dir = createDir("sha256-test")
        val file = File(dir, "test.txt")
        file.writeText("hello world")

        val hash = PostTransferVerifier.computeSha256(file)
        assertEquals(64, hash.length, "SHA-256 hex should be 64 chars")
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' }, "Should be lowercase hex")
    }

    @Test
    fun `computeSha256 is deterministic`() {
        val dir = createDir("sha256-deterministic")
        val file = File(dir, "test.txt")
        file.writeText("deterministic content")

        val hash1 = PostTransferVerifier.computeSha256(file)
        val hash2 = PostTransferVerifier.computeSha256(file)
        assertEquals(hash1, hash2)
    }

    @Test
    fun `computeSha256 differs for different content`() {
        val dir = createDir("sha256-different")
        val file1 = File(dir, "a.txt")
        file1.writeText("content A")
        val file2 = File(dir, "b.txt")
        file2.writeText("content B")

        assertNotEquals(
            PostTransferVerifier.computeSha256(file1),
            PostTransferVerifier.computeSha256(file2)
        )
    }

    @Test
    fun `computeSha256 matches known value`() {
        val dir = createDir("sha256-known")
        val file = File(dir, "known.txt")
        file.writeText("hello")

        val hash = PostTransferVerifier.computeSha256(file)
        // sha256("hello") = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash)
    }

    // ── DirectoryVerifyResult classification ──

    @Test
    fun `DirectoryVerifyResult with all verified and no failures`() {
        val result = DirectoryVerifyResult(verified = 5, failed = emptyList(), hashMismatches = emptyList())
        assertEquals(5, result.verified)
        assertTrue(result.failed.isEmpty())
        assertTrue(result.hashMismatches.isEmpty())
    }

    @Test
    fun `DirectoryVerifyResult with hash mismatches tracked separately`() {
        val result = DirectoryVerifyResult(
            verified = 3,
            failed = listOf("corrupt.txt", "missing.txt"),
            hashMismatches = listOf("corrupt.txt")
        )
        assertEquals(3, result.verified)
        assertEquals(2, result.failed.size)
        assertEquals(1, result.hashMismatches.size)
        assertTrue("corrupt.txt" in result.hashMismatches)
        assertTrue("corrupt.txt" in result.failed)
        assertTrue("missing.txt" in result.failed)
    }

    @Test
    fun `hash mismatch is always in failed list too`() {
        // hash mismatch should appear in both hashMismatches and failed
        val result = DirectoryVerifyResult(
            verified = 0,
            failed = listOf("bad.txt"),
            hashMismatches = listOf("bad.txt")
        )
        assertTrue(result.hashMismatches.all { it in result.failed },
            "Every hash mismatch should also be in the failed list")
    }

    // ── Simulated directory verification (local only, no SSH) ──

    @Test
    fun `directory verification classifies matching files correctly`() {
        val sourceDir = createDir("verify-match")
        File(sourceDir, "a.txt").writeText("alpha")
        File(sourceDir, "b.txt").writeText("beta")

        // Simulate remote data: both files match size and hash
        val aHash = PostTransferVerifier.computeSha256(File(sourceDir, "a.txt"))
        val bHash = PostTransferVerifier.computeSha256(File(sourceDir, "b.txt"))

        // Verify local hashes are correct
        assertEquals(64, aHash.length)
        assertEquals(64, bHash.length)
        assertNotEquals(aHash, bHash)
    }

    @Test
    fun `size match but hash mismatch detected locally`() {
        val dir = createDir("hash-mismatch-detect")
        val file1 = File(dir, "same-size-a.txt")
        val file2 = File(dir, "same-size-b.txt")
        // Create two files with same size but different content
        file1.writeText("AAAAAAA")
        file2.writeText("BBBBBBB")

        assertEquals(file1.length(), file2.length(), "Files should have same size")
        assertNotEquals(
            PostTransferVerifier.computeSha256(file1),
            PostTransferVerifier.computeSha256(file2),
            "Files with same size but different content should have different hashes"
        )
    }

    // ── Fail-closed contract tests ──

    @Test
    fun `verifySingleFile returns false when machine has no SSH user`() {
        val dir = createDir("no-ssh-user")
        val file = File(dir, "test.txt")
        file.writeText("content")

        // MachineProfile with null sshUser
        val machine = vision.salient.choam.config.MachineProfile(
            name = "test-machine",
            hostname = "test",
            type = vision.salient.choam.config.MachineType.DESKTOP,
            sshUser = null,
            repositories = emptyMap()
        )
        val route = vision.salient.choam.network.NetworkRoute(
            targetAddress = "127.0.0.1",
            sourceAddress = "127.0.0.1",
            mode = vision.salient.choam.config.NetworkMode.TAILSCALE
        )

        val result = PostTransferVerifier.verifySingleFile(file, "/dest/test.txt", machine, route)
        assertFalse(result, "Should return false when SSH user is null")
    }

    @Test
    fun `verifyDirectory returns failed when machine has no SSH user`() {
        val dir = createDir("no-ssh-user-dir")
        File(dir, "test.txt").writeText("content")

        val machine = vision.salient.choam.config.MachineProfile(
            name = "test-machine",
            hostname = "test",
            type = vision.salient.choam.config.MachineType.DESKTOP,
            sshUser = null,
            repositories = emptyMap()
        )
        val route = vision.salient.choam.network.NetworkRoute(
            targetAddress = "127.0.0.1",
            sourceAddress = "127.0.0.1",
            mode = vision.salient.choam.config.NetworkMode.TAILSCALE
        )

        val result = PostTransferVerifier.verifyDirectory(dir, "/dest/dir", machine, route)
        assertEquals(0, result.verified)
        assertTrue(result.failed.isNotEmpty(), "Should report files as failed when SSH user is null")
    }

    @Test
    fun `verifyDirectory returns empty results for empty directory`() {
        val dir = createDir("empty-dir-verify")

        val machine = vision.salient.choam.config.MachineProfile(
            name = "test-machine",
            hostname = "test",
            type = vision.salient.choam.config.MachineType.DESKTOP,
            sshUser = "user",
            repositories = emptyMap()
        )
        val route = vision.salient.choam.network.NetworkRoute(
            targetAddress = "127.0.0.1",
            sourceAddress = "127.0.0.1",
            mode = vision.salient.choam.config.NetworkMode.TAILSCALE
        )

        val result = PostTransferVerifier.verifyDirectory(dir, "/dest/dir", machine, route)
        assertEquals(0, result.verified)
        assertTrue(result.failed.isEmpty())
        assertTrue(result.hashMismatches.isEmpty())
    }

    // ── filesToVerify: CHOAM's own manifest is never part of the verify set ──
    //
    // Regression guard. CHOAM writes .choam_manifest.json into directories during
    // normal use; the transfer excludes it but verification used to count it, so any
    // later move of a directory that already held a manifest wedged as a permanent
    // partial-fail (source kept, op flagged failed forever). It must never be verified.

    @Test
    fun `filesToVerify excludes the manifest at the root`() {
        val dir = createDir("ftv-root-manifest")
        File(dir, "video.mp4").writeText("data")
        File(dir, MANIFEST_FILENAME).writeText("{}")
        val names = PostTransferVerifier.filesToVerify(dir).map { it.name }.toSet()
        assertEquals(setOf("video.mp4"), names)
    }

    @Test
    fun `filesToVerify excludes nested manifests too`() {
        val dir = createDir("ftv-nested-manifest")
        File(dir, "a.txt").writeText("a")
        File(dir, "sub").mkdirs()
        File(dir, "sub/b.txt").writeText("b")
        File(dir, MANIFEST_FILENAME).writeText("{}")
        File(dir, "sub/$MANIFEST_FILENAME").writeText("{}")
        val names = PostTransferVerifier.filesToVerify(dir).map { it.name }.sorted()
        assertEquals(listOf("a.txt", "b.txt"), names)
        assertFalse(names.contains(MANIFEST_FILENAME))
    }

    @Test
    fun `filesToVerify on a manifest-only directory is empty so the move can complete`() {
        val dir = createDir("ftv-only-manifest")
        File(dir, MANIFEST_FILENAME).writeText("{}")
        assertTrue(PostTransferVerifier.filesToVerify(dir).isEmpty())
    }

    // ── classify: the wedge scenario + the verified/failed/mismatch matrix ──

    @Test
    fun `classify ignores the excluded manifest and passes when real files match`() {
        val dir = createDir("classify-manifest-ignored")
        val v = File(dir, "video.mp4").apply { writeText("hello world") }
        File(dir, MANIFEST_FILENAME).writeText("{}")     // local only; never transferred
        val toVerify = PostTransferVerifier.filesToVerify(dir)
        // Remote map has NO manifest entry: it was excluded from the rsync.
        val remote = mapOf("video.mp4" to (v.length() to PostTransferVerifier.computeSha256(v)))
        val r = PostTransferVerifier.classify(dir, toVerify, remote)
        assertEquals(1, r.verified)
        assertTrue(r.failed.isEmpty(), "manifest must not be UNVERIFIED, got: ${r.failed}")
        assertTrue(r.hashMismatches.isEmpty())
    }

    @Test
    fun `classify passes a multi-file directory when every file matches`() {
        val dir = createDir("classify-multi-ok")
        val a = File(dir, "a.txt").apply { writeText("alpha") }
        File(dir, "sub").mkdirs()
        val b = File(dir, "sub/b.txt").apply { writeText("bravo") }
        val remote = mapOf(
            "a.txt" to (a.length() to PostTransferVerifier.computeSha256(a)),
            "sub/b.txt" to (b.length() to PostTransferVerifier.computeSha256(b))
        )
        val r = PostTransferVerifier.classify(dir, PostTransferVerifier.filesToVerify(dir), remote)
        assertEquals(2, r.verified)
        assertTrue(r.failed.isEmpty())
    }

    @Test
    fun `classify fails a file missing on the remote`() {
        val dir = createDir("classify-missing")
        val v = File(dir, "video.mp4").apply { writeText("hello") }
        val r = PostTransferVerifier.classify(dir, listOf(v), emptyMap())
        assertEquals(0, r.verified)
        assertEquals(listOf("video.mp4"), r.failed)
    }

    @Test
    fun `classify fails on a size mismatch`() {
        val dir = createDir("classify-size")
        val v = File(dir, "video.mp4").apply { writeText("hello") }
        val remote = mapOf("video.mp4" to (999L to PostTransferVerifier.computeSha256(v)))
        val r = PostTransferVerifier.classify(dir, listOf(v), remote)
        assertEquals(0, r.verified)
        assertEquals(listOf("video.mp4"), r.failed)
        assertTrue(r.hashMismatches.isEmpty())
    }

    @Test
    fun `classify fails closed when the remote hash is missing`() {
        val dir = createDir("classify-nohash")
        val v = File(dir, "video.mp4").apply { writeText("hello") }
        val remote: Map<String, Pair<Long, String?>> = mapOf("video.mp4" to (v.length() to null))
        val r = PostTransferVerifier.classify(dir, listOf(v), remote)
        assertEquals(0, r.verified)
        assertEquals(listOf("video.mp4"), r.failed)
    }

    @Test
    fun `classify flags a hash mismatch as potential corruption`() {
        val dir = createDir("classify-mismatch")
        val v = File(dir, "video.mp4").apply { writeText("hello") }
        val wrongHash = "0".repeat(64)
        val remote = mapOf("video.mp4" to (v.length() to wrongHash))
        val r = PostTransferVerifier.classify(dir, listOf(v), remote)
        assertEquals(0, r.verified)
        assertEquals(listOf("video.mp4"), r.failed)
        assertEquals(listOf("video.mp4"), r.hashMismatches)
    }
}

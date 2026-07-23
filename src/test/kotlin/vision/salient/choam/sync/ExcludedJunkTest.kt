package vision.salient.choam.sync

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression guard: volatile junk (.DS_Store, *.tmp, *.part) and the CHOAM manifest must be
 * invisible to every identity / conflict / manifest / verification decision — exactly as they are
 * invisible to rsync. Before this was centralized, only .choam_manifest.json was filtered out of
 * the tree/preflight/verify walks, so a stray `.DS_Store` (Finder rewrites them per-directory with
 * differing size/content) was hashed, mismatched the destination, and was classified CONFLICT —
 * refusing an otherwise byte-identical directory MOVE and wedging the queue in an infinite retry.
 *
 * These tests encode the invariant so the "phantom .DS_Store conflict" cannot come back.
 */
class ExcludedJunkTest {

    private lateinit var tempDir: java.nio.file.Path

    @BeforeEach
    fun setup() { tempDir = createTempDirectory("excluded-junk-test") }

    @AfterEach
    fun cleanup() { tempDir.toFile().deleteRecursively() }

    private fun createDir(name: String): File =
        tempDir.resolve(name).toFile().apply { mkdirs() }

    /**
     * Stamp every payload file under [dirs] with one fixed mtime. buildLocal() (METADATA
     * confidence, no manifest) folds size+mtime into each leaf hash, so two independently
     * written directories would otherwise differ by write-time alone, masking what these
     * tests actually assert: that junk presence/content does not move the rootHash. rsync
     * -a preserves mtime across machines, so equalizing it here mirrors a real transfer and
     * makes the comparison deterministic instead of racing the filesystem's mtime clock.
     */
    private fun stampMtimes(vararg dirs: File, ts: Long = 1_600_000_000_000L) {
        for (d in dirs) d.walkTopDown().filter { it.isFile }.forEach { it.setLastModified(ts) }
    }

    // ── isExcludedFromTree classification ──

    @Test
    fun `junk names and suffixes are excluded, real files are not`() {
        assertTrue(isExcludedFromTree(".DS_Store"))
        assertTrue(isExcludedFromTree(MANIFEST_FILENAME))
        assertTrue(isExcludedFromTree("partial.tmp"))
        assertTrue(isExcludedFromTree("download.part"))

        assertFalse(isExcludedFromTree("movie.mkv"))
        assertFalse(isExcludedFromTree("episode 01.mp4"))
        assertFalse(isExcludedFromTree("notes.txt"))
        // Suffix match must be at the end, not anywhere
        assertFalse(isExcludedFromTree("tmp.mkv"))
        assertFalse(isExcludedFromTree("part1.mkv"))
    }

    // ── The exact Rare scenario: a differing .DS_Store must not change directory identity ──

    @Test
    fun `rootHash identical whether or not a DS_Store is present`() {
        val clean = createDir("clean")
        File(clean, "a.mkv").writeText("payload-a")
        File(clean, "b.mkv").writeText("payload-b")

        val withJunk = createDir("withjunk")
        File(withJunk, "a.mkv").writeText("payload-a")
        File(withJunk, "b.mkv").writeText("payload-b")
        File(withJunk, ".DS_Store").writeText("finder-metadata-6148-bytes-ish")

        stampMtimes(clean, withJunk)
        assertEquals(
            DirectoryMerkleTree.buildLocal(clean).rootHash,
            DirectoryMerkleTree.buildLocal(withJunk).rootHash,
            "A .DS_Store must not affect the directory's Merkle rootHash"
        )
    }

    @Test
    fun `two sides that differ ONLY by DS_Store content produce the same rootHash`() {
        // This is precisely the wedge: EXTREME/.DS_Store was 8196 bytes locally, 6148 remotely.
        val local = createDir("local")
        File(local, "sub").mkdirs()
        File(local, "sub/clip.mkv").writeText("identical-payload")
        File(local, "sub/.DS_Store").writeText("AAAA-larger-8196-ish-content")

        val remote = createDir("remote")
        File(remote, "sub").mkdirs()
        File(remote, "sub/clip.mkv").writeText("identical-payload")
        File(remote, "sub/.DS_Store").writeText("B-smaller-6148")

        stampMtimes(local, remote)
        assertEquals(
            DirectoryMerkleTree.buildLocal(local).rootHash,
            DirectoryMerkleTree.buildLocal(remote).rootHash,
            "Directories differing only by .DS_Store content must be treated as identical"
        )
    }

    @Test
    fun `tmp and part files do not change rootHash`() {
        val clean = createDir("clean2")
        File(clean, "video.mkv").writeText("data")

        val withTemps = createDir("withtemps")
        File(withTemps, "video.mkv").writeText("data")
        File(withTemps, "video.mkv.part").writeText("in-progress-bytes")
        File(withTemps, "scratch.tmp").writeText("temp")

        stampMtimes(clean, withTemps)
        assertEquals(
            DirectoryMerkleTree.buildLocal(clean).rootHash,
            DirectoryMerkleTree.buildLocal(withTemps).rootHash,
            "*.tmp / *.part files must not affect the directory rootHash"
        )
    }

    @Test
    fun `a real file change still changes rootHash`() {
        // Guard against over-exclusion: real payload must remain sensitive.
        val a = createDir("real-a")
        File(a, "x.mkv").writeText("v1")
        File(a, ".DS_Store").writeText("junk")

        val b = createDir("real-b")
        File(b, "x.mkv").writeText("v2-different")
        File(b, ".DS_Store").writeText("junk")

        // Equal mtime on both sides: the change must be caught by size/content, not by a
        // stray mtime difference, so this proves the exclusion is not masking real edits.
        stampMtimes(a, b)
        org.junit.jupiter.api.Assertions.assertNotEquals(
            DirectoryMerkleTree.buildLocal(a).rootHash,
            DirectoryMerkleTree.buildLocal(b).rootHash,
            "A genuine payload change must still be detected"
        )
    }

    // ── PostTransferVerifier must not try to verify junk (rsync never sent it) ──

    @Test
    fun `filesToVerify skips junk but keeps real files`() {
        val dir = createDir("verify")
        File(dir, "keep.mkv").writeText("real")
        File(dir, "nested").mkdirs()
        File(dir, "nested/also.mkv").writeText("real2")
        File(dir, ".DS_Store").writeText("junk")
        File(dir, "nested/.DS_Store").writeText("junk")
        File(dir, "half.part").writeText("partial")
        File(dir, MANIFEST_FILENAME).writeText("{}")

        val names = PostTransferVerifier.filesToVerify(dir).map { it.name }.toSet()

        assertEquals(setOf("keep.mkv", "also.mkv"), names,
            "Only real payload files should be verified; junk + manifest are excluded")
    }

    // ── Shared exclusion lists stay in sync ──

    @Test
    fun `rsync patterns cover manifest and junk globs`() {
        val patterns = alwaysExcludedRsyncPatterns()
        assertTrue(MANIFEST_FILENAME in patterns)
        assertTrue(".DS_Store" in patterns)
        assertTrue("*.tmp" in patterns)
        assertTrue("*.part" in patterns)
    }

    @Test
    fun `find args exclude manifest and junk`() {
        val args = alwaysExcludedFindArgs()
        assertTrue(args.contains("-not -name '.DS_Store'"))
        assertTrue(args.contains("-not -name '*.tmp'"))
        assertTrue(args.contains("-not -name '*.part'"))
        assertTrue(args.contains("-not -name '$MANIFEST_FILENAME'"))
    }
}

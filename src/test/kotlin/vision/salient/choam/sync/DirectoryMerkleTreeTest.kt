package vision.salient.choam.sync

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TDD tests for DirectoryMerkleTree — Phase 9.7 prep.
 *
 * Tests cover:
 * - Deterministic root hash from directory contents
 * - Hash sensitivity to file addition, deletion, and content/size changes
 * - Subtree isolation (sibling subtrees unchanged when one changes)
 * - Diff detection for new, modified, and deleted files
 * - Performance on larger directories
 */
class DirectoryMerkleTreeTest {

    private lateinit var tempDir: java.nio.file.Path

    @BeforeEach
    fun setup() {
        tempDir = createTempDirectory("merkle-tree-test")
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

    // ── Determinism ──

    @Test
    fun buildTreeFromDirectoryWithFilesProducesDeterministicHash() {
        val dir = createDir("deterministic")
        File(dir, "a.txt").writeText("hello")
        File(dir, "b.txt").writeText("world")

        val tree1 = DirectoryMerkleTree.buildFrom(dir)
        val tree2 = DirectoryMerkleTree.buildFrom(dir)

        assertEquals(tree1.rootHash, tree2.rootHash)
        assertTrue(tree1.rootHash.isNotEmpty())
        // SHA-256 hex is always 64 chars
        assertEquals(64, tree1.rootHash.length)
    }

    @Test
    fun sameDirectoryContentsSameRootHash() {
        val dir1 = createDir("same-a")
        File(dir1, "file.txt").writeText("identical")

        val dir2 = createDir("same-b")
        File(dir2, "file.txt").writeText("identical")

        val tree1 = DirectoryMerkleTree.buildFrom(dir1)
        val tree2 = DirectoryMerkleTree.buildFrom(dir2)

        assertEquals(tree1.rootHash, tree2.rootHash,
            "Directories with identical file names and sizes should produce the same root hash")
    }

    // ── Hash sensitivity ──

    @Test
    fun changeOneFileContentSizeChangesRootHash() {
        val dir = createDir("change-content")
        File(dir, "a.txt").writeText("original")
        File(dir, "b.txt").writeText("stable")

        val hashBefore = DirectoryMerkleTree.buildFrom(dir).rootHash

        // Change a.txt to a different length
        File(dir, "a.txt").writeText("modified with more text")

        val hashAfter = DirectoryMerkleTree.buildFrom(dir).rootHash

        assertNotEquals(hashBefore, hashAfter,
            "Changing a file's content (size) should change the root hash")
    }

    @Test
    fun addFileChangesRootHash() {
        val dir = createDir("add-file")
        File(dir, "existing.txt").writeText("existing content")

        val hashBefore = DirectoryMerkleTree.buildFrom(dir).rootHash

        File(dir, "new.txt").writeText("new file")

        val hashAfter = DirectoryMerkleTree.buildFrom(dir).rootHash

        assertNotEquals(hashBefore, hashAfter,
            "Adding a file should change the root hash")
    }

    @Test
    fun deleteFileChangesRootHash() {
        val dir = createDir("delete-file")
        File(dir, "a.txt").writeText("keep")
        File(dir, "b.txt").writeText("delete me")

        val hashBefore = DirectoryMerkleTree.buildFrom(dir).rootHash

        File(dir, "b.txt").delete()

        val hashAfter = DirectoryMerkleTree.buildFrom(dir).rootHash

        assertNotEquals(hashBefore, hashAfter,
            "Deleting a file should change the root hash")
    }

    @Test
    fun changeFileInDeeplyNestedSubdirChangesRootHash() {
        val dir = createDir("deep-change")
        File(dir, "top.txt").writeText("top level")
        val nested = File(dir, "a/b/c")
        nested.mkdirs()
        File(nested, "deep.txt").writeText("deep content")

        val hashBefore = DirectoryMerkleTree.buildFrom(dir).rootHash

        File(nested, "deep.txt").writeText("deep content with more bytes added here")

        val hashAfter = DirectoryMerkleTree.buildFrom(dir).rootHash

        assertNotEquals(hashBefore, hashAfter,
            "Changing a file in a deeply nested subdir should change root hash")
    }

    @Test
    fun changingDeeplyNestedFileDoesNotChangeSiblingSubtreeHashes() {
        val dir = createDir("sibling-isolation")

        // Create two sibling subtrees: left/ and right/
        val left = File(dir, "left")
        left.mkdirs()
        File(left, "l.txt").writeText("left content")

        val right = File(dir, "right")
        right.mkdirs()
        File(right, "r.txt").writeText("right content")

        val treeBefore = DirectoryMerkleTree.buildFrom(dir)
        val rightHashBefore = treeBefore.root.children
            .find { it.name == "right" }!!.hash

        // Modify the left subtree only
        File(left, "l.txt").writeText("left content changed to something longer")

        val treeAfter = DirectoryMerkleTree.buildFrom(dir)
        val rightHashAfter = treeAfter.root.children
            .find { it.name == "right" }!!.hash

        // Root hash should change
        assertNotEquals(treeBefore.rootHash, treeAfter.rootHash,
            "Root hash should change when any file changes")

        // But the right subtree hash should remain the same
        assertEquals(rightHashBefore, rightHashAfter,
            "Sibling subtree hash should NOT change when only the other subtree is modified")
    }

    // ── Diff: identical trees ──

    @Test
    fun diffTwoIdenticalTreesEmptyDiff() {
        val dir1 = createDir("diff-same-a")
        File(dir1, "x.txt").writeText("content")
        File(dir1, "y.txt").writeText("other")

        val dir2 = createDir("diff-same-b")
        File(dir2, "x.txt").writeText("content")
        File(dir2, "y.txt").writeText("other")

        val tree1 = DirectoryMerkleTree.buildFrom(dir1)
        val tree2 = DirectoryMerkleTree.buildFrom(dir2)

        val diff = tree1.diff(tree2)

        assertTrue(diff.isEmpty, "Diff of identical trees should be empty")
        assertEquals(0, diff.newFiles.size)
        assertEquals(0, diff.modifiedFiles.size)
        assertEquals(0, diff.deletedFiles.size)
    }

    // ── Diff: additions ──

    @Test
    fun diffAfterAddingFileShowsAsNewFile() {
        val dir1 = createDir("diff-add-a")
        File(dir1, "existing.txt").writeText("existing")

        val dir2 = createDir("diff-add-b")
        File(dir2, "existing.txt").writeText("existing")
        File(dir2, "added.txt").writeText("new file")

        val treeBefore = DirectoryMerkleTree.buildFrom(dir1)
        val treeAfter = DirectoryMerkleTree.buildFrom(dir2)

        val diff = treeBefore.diff(treeAfter)

        assertFalse(diff.isEmpty)
        assertEquals(1, diff.newFiles.size)
        assertTrue(diff.newFiles.any { it.contains("added.txt") },
            "New file should appear in newFiles")
        assertEquals(0, diff.modifiedFiles.size)
        assertEquals(0, diff.deletedFiles.size)
    }

    // ── Diff: modifications ──

    @Test
    fun diffAfterModifyingFileShowsAsModifiedFile() {
        val dir1 = createDir("diff-mod-a")
        File(dir1, "data.txt").writeText("original")

        val dir2 = createDir("diff-mod-b")
        File(dir2, "data.txt").writeText("modified with more text for different size")

        val treeBefore = DirectoryMerkleTree.buildFrom(dir1)
        val treeAfter = DirectoryMerkleTree.buildFrom(dir2)

        val diff = treeBefore.diff(treeAfter)

        assertFalse(diff.isEmpty)
        assertEquals(0, diff.newFiles.size)
        assertEquals(1, diff.modifiedFiles.size)
        assertTrue(diff.modifiedFiles.any { it.contains("data.txt") },
            "Modified file should appear in modifiedFiles")
        assertEquals(0, diff.deletedFiles.size)
    }

    // ── Diff: deletions ──

    @Test
    fun diffAfterDeletingFileShowsAsDeletedFile() {
        val dir1 = createDir("diff-del-a")
        File(dir1, "keep.txt").writeText("keep")
        File(dir1, "removed.txt").writeText("will be removed")

        val dir2 = createDir("diff-del-b")
        File(dir2, "keep.txt").writeText("keep")
        // removed.txt is NOT in dir2

        val treeBefore = DirectoryMerkleTree.buildFrom(dir1)
        val treeAfter = DirectoryMerkleTree.buildFrom(dir2)

        val diff = treeBefore.diff(treeAfter)

        assertFalse(diff.isEmpty)
        assertEquals(0, diff.newFiles.size)
        assertEquals(0, diff.modifiedFiles.size)
        assertEquals(1, diff.deletedFiles.size)
        assertTrue(diff.deletedFiles.any { it.contains("removed.txt") },
            "Deleted file should appear in deletedFiles")
    }

    // ── Performance ──

    @Test
    fun largeDirectoryBuildsInUnderTwoSeconds() {
        val dir = createDir("large-perf")
        repeat(1000) { i ->
            // Spread across subdirectories for realism
            val subDir = File(dir, "sub_${i / 100}")
            subDir.mkdirs()
            File(subDir, "file_$i.dat").writeText("content_$i padding_${i * 7}")
        }

        val startMs = System.currentTimeMillis()
        val tree = DirectoryMerkleTree.buildFrom(dir)
        val elapsedMs = System.currentTimeMillis() - startMs

        assertTrue(tree.rootHash.isNotEmpty())
        assertTrue(elapsedMs < 2000,
            "Building tree from 1000 files took ${elapsedMs}ms — should be under 2000ms")
    }

    // ── Edge cases ──

    @Test
    fun emptyDirectoryProducesValidTree() {
        val dir = createDir("empty-dir")

        val tree = DirectoryMerkleTree.buildFrom(dir)

        assertTrue(tree.rootHash.isNotEmpty())
        assertEquals(64, tree.rootHash.length)
    }

    @Test
    fun directoryWithSingleFileProducesValidHash() {
        val dir = createDir("single-file")
        File(dir, "only.txt").writeText("solo")

        val tree = DirectoryMerkleTree.buildFrom(dir)

        assertTrue(tree.rootHash.isNotEmpty())
        assertEquals(64, tree.rootHash.length)
        assertEquals(1, tree.root.children.size)
    }

    @Test
    fun nestedEmptyDirectoriesProduceUniqueHashes() {
        val dir1 = createDir("nested-empty-a")
        File(dir1, "sub").mkdirs()

        val dir2 = createDir("nested-empty-b")
        File(dir2, "other").mkdirs()

        val tree1 = DirectoryMerkleTree.buildFrom(dir1)
        val tree2 = DirectoryMerkleTree.buildFrom(dir2)

        // Different subdirectory names should produce different root hashes
        assertNotEquals(tree1.rootHash, tree2.rootHash,
            "Directories with differently named empty subdirs should have different hashes")
    }

    @Test
    fun fileOrderDoesNotAffectHash() {
        // The tree sorts children by name, so creation order shouldn't matter.
        val dir1 = createDir("order-a")
        File(dir1, "b.txt").writeText("bb")
        File(dir1, "a.txt").writeText("aa")
        File(dir1, "c.txt").writeText("cc")

        val dir2 = createDir("order-b")
        File(dir2, "c.txt").writeText("cc")
        File(dir2, "a.txt").writeText("aa")
        File(dir2, "b.txt").writeText("bb")

        val tree1 = DirectoryMerkleTree.buildFrom(dir1)
        val tree2 = DirectoryMerkleTree.buildFrom(dir2)

        assertEquals(tree1.rootHash, tree2.rootHash,
            "File creation order should not affect the root hash (sorted by name)")
    }

    @Test
    fun diffWithNestedNewFiles() {
        val dir1 = createDir("nested-diff-a")
        File(dir1, "top.txt").writeText("top")

        val dir2 = createDir("nested-diff-b")
        File(dir2, "top.txt").writeText("top")
        val sub = File(dir2, "subdir")
        sub.mkdirs()
        File(sub, "nested_new.txt").writeText("nested new")

        val treeBefore = DirectoryMerkleTree.buildFrom(dir1)
        val treeAfter = DirectoryMerkleTree.buildFrom(dir2)

        val diff = treeBefore.diff(treeAfter)

        assertFalse(diff.isEmpty)
        assertTrue(diff.newFiles.any { it.contains("nested_new.txt") },
            "Nested new file should appear in diff newFiles")
    }

    @Test
    fun diffCombinedNewModifiedDeleted() {
        val dir1 = createDir("combined-a")
        File(dir1, "keep.txt").writeText("unchanged")
        File(dir1, "modify.txt").writeText("original")
        File(dir1, "remove.txt").writeText("will be gone")

        val dir2 = createDir("combined-b")
        File(dir2, "keep.txt").writeText("unchanged")
        File(dir2, "modify.txt").writeText("original plus some extra text for different size")
        File(dir2, "add.txt").writeText("brand new")
        // remove.txt is NOT in dir2

        val treeBefore = DirectoryMerkleTree.buildFrom(dir1)
        val treeAfter = DirectoryMerkleTree.buildFrom(dir2)

        val diff = treeBefore.diff(treeAfter)

        assertFalse(diff.isEmpty)
        assertEquals(1, diff.newFiles.size, "Should have 1 new file")
        assertEquals(1, diff.modifiedFiles.size, "Should have 1 modified file")
        assertEquals(1, diff.deletedFiles.size, "Should have 1 deleted file")

        assertTrue(diff.newFiles.any { it.contains("add.txt") })
        assertTrue(diff.modifiedFiles.any { it.contains("modify.txt") })
        assertTrue(diff.deletedFiles.any { it.contains("remove.txt") })
    }

    // ── Phase 9.7: HashConfidence tracking ──

    @Test
    fun buildLocalWithoutManifestProducesMetadataConfidence() {
        val dir = createDir("confidence-no-manifest")
        File(dir, "a.txt").writeText("hello")

        val tree = DirectoryMerkleTree.buildLocal(dir)
        val leaf = tree.root.children.first()
        assertEquals(HashConfidence.METADATA, leaf.confidence,
            "Without a manifest, leaf confidence should be METADATA")
    }

    @Test
    fun buildLocalWithManifestAndMatchingMtimeProducesContentConfidence() {
        val dir = createDir("confidence-with-manifest")
        val file = File(dir, "a.txt")
        file.writeText("hello")
        val mtime = file.lastModified()

        // Create a manifest with a contentHash for this file
        val manifest = ChoamManifest(
            builtAt = "2026-04-01T00:00:00Z",
            rootHash = "ignored",
            fileCount = 1,
            totalBytes = file.length(),
            hashConfidence = "CONTENT",
            files = mapOf(
                "a.txt" to ChoamManifest.FileEntry(
                    size = file.length(),
                    mtime = mtime,
                    contentHash = "bafkreihdwdce12345",
                    metadataHash = "meta"
                )
            )
        )

        val tree = DirectoryMerkleTree.buildLocal(dir, manifest)
        val leaf = tree.root.children.first()
        assertEquals(HashConfidence.CONTENT, leaf.confidence,
            "With matching manifest mtime and contentHash, confidence should be CONTENT")
    }

    @Test
    fun buildLocalWithManifestButChangedMtimeFallsBackToMetadata() {
        val dir = createDir("confidence-stale-manifest")
        val file = File(dir, "a.txt")
        file.writeText("hello")

        // Manifest has a different mtime (stale)
        val manifest = ChoamManifest(
            builtAt = "2026-04-01T00:00:00Z",
            rootHash = "ignored",
            fileCount = 1,
            totalBytes = file.length(),
            hashConfidence = "CONTENT",
            files = mapOf(
                "a.txt" to ChoamManifest.FileEntry(
                    size = file.length(),
                    mtime = file.lastModified() - 10000, // stale mtime
                    contentHash = "bafkreihdwdce12345",
                    metadataHash = "meta"
                )
            )
        )

        val tree = DirectoryMerkleTree.buildLocal(dir, manifest)
        val leaf = tree.root.children.first()
        assertEquals(HashConfidence.METADATA, leaf.confidence,
            "With mismatched mtime, should fall back to METADATA confidence")
    }

    // ── Phase 9.7: .choam_manifest.json exclusion ──

    @Test
    fun manifestFileIsExcludedFromLocalTree() {
        val dir = createDir("manifest-exclusion")
        File(dir, "real.txt").writeText("real content")
        File(dir, MANIFEST_FILENAME).writeText("{}")

        val tree = DirectoryMerkleTree.buildLocal(dir)
        assertEquals(1, tree.fileCount, "fileCount should exclude .choam_manifest.json")
        assertEquals(1, tree.root.children.size, "Only real.txt should be in the tree")
        assertEquals("real.txt", tree.root.children[0].name)
    }

    @Test
    fun manifestFileDoesNotAffectRootHash() {
        val dir1 = createDir("hash-no-manifest")
        File(dir1, "data.txt").writeText("content")

        val dir2 = createDir("hash-with-manifest")
        File(dir2, "data.txt").writeText("content")
        File(dir2, MANIFEST_FILENAME).writeText("""{"rootHash":"whatever"}""")

        // Use buildFrom (legacy, no mtime) to avoid mtime differences between directories
        val tree1 = DirectoryMerkleTree.buildFrom(dir1)
        val tree2 = DirectoryMerkleTree.buildFrom(dir2)

        assertEquals(tree1.rootHash, tree2.rootHash,
            "Presence of .choam_manifest.json should NOT affect the root hash")
    }

    // ── Phase 9.7: fileCount tracking ──

    @Test
    fun fileCountMatchesActualFileCount() {
        val dir = createDir("file-count")
        File(dir, "a.txt").writeText("a")
        File(dir, "b.txt").writeText("b")
        val sub = File(dir, "sub")
        sub.mkdirs()
        File(sub, "c.txt").writeText("c")

        val tree = DirectoryMerkleTree.buildLocal(dir)
        assertEquals(3, tree.fileCount)
    }

    @Test
    fun emptyDirectoryHasZeroFileCount() {
        val dir = createDir("empty-count")
        val tree = DirectoryMerkleTree.buildLocal(dir)
        assertEquals(0, tree.fileCount)
    }

    // ── Phase 9.7: toManifest ──

    @Test
    fun toManifestProducesCorrectFileCount() {
        val dir = createDir("to-manifest")
        File(dir, "x.txt").writeText("x")
        File(dir, "y.txt").writeText("y")

        val tree = DirectoryMerkleTree.buildLocal(dir)
        val manifest = tree.toManifest(dir)

        assertEquals(2, manifest.fileCount)
        assertEquals(tree.rootHash, manifest.rootHash)
        assertEquals(2, manifest.files.size)
    }

    @Test
    fun toManifestStoresRealMtimeFromDisk() {
        val dir = createDir("manifest-mtime")
        val file = File(dir, "a.txt")
        file.writeText("hello")
        val expectedMtime = file.lastModified()

        val tree = DirectoryMerkleTree.buildLocal(dir)
        val manifest = tree.toManifest(dir)

        val entry = manifest.files["a.txt"]
        assertNotNull(entry)
        assertEquals(expectedMtime, entry.mtime,
            "toManifest should store real file mtime from disk, not 0")
        assertTrue(entry.mtime > 0, "mtime should be a real timestamp")
    }

    @Test
    fun manifestRoundTripPreservesContentConfidence() {
        val dir = createDir("manifest-roundtrip")
        val file = File(dir, "data.txt")
        file.writeText("content here")

        // Build tree, write manifest, then rebuild with manifest
        val tree1 = DirectoryMerkleTree.buildLocal(dir)
        assertEquals(HashConfidence.METADATA, tree1.root.children.first().confidence)

        val manifest = tree1.toManifest(dir)
        // METADATA confidence → contentHash should be null
        assertNull(manifest.files["data.txt"]?.contentHash,
            "METADATA confidence nodes should have null contentHash in manifest")

        // Now simulate a manifest with a real CID (as if Sietch had computed one)
        val enrichedManifest = ChoamManifest(
            builtAt = manifest.builtAt,
            rootHash = "ignored",
            fileCount = 1,
            totalBytes = file.length(),
            hashConfidence = "CONTENT",
            files = mapOf(
                "data.txt" to ChoamManifest.FileEntry(
                    size = file.length(),
                    mtime = file.lastModified(),
                    contentHash = "bafkreiexamplecid123",
                    metadataHash = "meta"
                )
            )
        )

        // Rebuild with enriched manifest — should now have CONTENT confidence
        val tree2 = DirectoryMerkleTree.buildLocal(dir, enrichedManifest)
        val leaf2 = tree2.root.children.first()
        assertEquals(HashConfidence.CONTENT, leaf2.confidence)
        // And the contentId stored should be the raw CID
        assertEquals("bafkreiexamplecid123", leaf2.contentId)
    }

    // ── RemoteTreeResult types ──

    @Test
    fun remoteTreeResultBuiltWrapsTree() {
        val dir = createDir("remote-built")
        File(dir, "f.txt").writeText("data")
        val tree = DirectoryMerkleTree.buildLocal(dir)

        val result: RemoteTreeResult = RemoteTreeResult.Built(tree)
        assertTrue(result is RemoteTreeResult.Built)
        assertEquals(tree.rootHash, (result as RemoteTreeResult.Built).tree.rootHash)
    }

    @Test
    fun remoteTreeResultNetworkUnavailableIsSingleton() {
        val result: RemoteTreeResult = RemoteTreeResult.NetworkUnavailable
        assertTrue(result is RemoteTreeResult.NetworkUnavailable)
    }

    @Test
    fun remoteTreeResultFailedCarriesReason() {
        val result: RemoteTreeResult = RemoteTreeResult.Failed("SSH exit code 255")
        assertTrue(result is RemoteTreeResult.Failed)
        assertEquals("SSH exit code 255", (result as RemoteTreeResult.Failed).reason)
    }
}

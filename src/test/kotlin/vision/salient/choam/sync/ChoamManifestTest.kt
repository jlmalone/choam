package vision.salient.choam.sync

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/**
 * Tests for ChoamManifest (Phase 9.7 Step 2).
 *
 * Tests validate ManifestLoadResult (the internal type), NOT PreflightOutcome.
 * Covers: serialization roundtrip, corrupt JSON, missing file, staleness detection,
 * .choam_manifest.json self-exclusion from fileCount.
 */
class ChoamManifestTest {

    private lateinit var tempDir: java.nio.file.Path

    @BeforeEach
    fun setup() {
        tempDir = createTempDirectory("manifest-test")
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

    // ── Serialization roundtrip ──

    @Test
    fun `serialize and deserialize manifest roundtrip`() {
        val manifest = ChoamManifest(
            builtAt = "2026-04-01T12:00:00Z",
            rootHash = "abc123def456",
            fileCount = 3,
            totalBytes = 10000,
            hashConfidence = "CONTENT",
            files = mapOf(
                "file1.txt" to ChoamManifest.FileEntry(100, 1234567890, "cid1", "meta1"),
                "sub/file2.txt" to ChoamManifest.FileEntry(200, 1234567891, null, "meta2"),
                "file3.db" to ChoamManifest.FileEntry(9700, 1234567892, "cid3", "meta3")
            )
        )

        val dir = createDir("roundtrip")
        manifest.save(dir)

        val loaded = ChoamManifest.load(dir)
        assertNotNull(loaded)
        assertEquals(manifest.rootHash, loaded.rootHash)
        assertEquals(manifest.fileCount, loaded.fileCount)
        assertEquals(manifest.totalBytes, loaded.totalBytes)
        assertEquals(manifest.hashConfidence, loaded.hashConfidence)
        assertEquals(3, loaded.files.size)
        assertEquals(100L, loaded.files["file1.txt"]?.size)
        assertNull(loaded.files["sub/file2.txt"]?.contentHash)
        assertEquals("cid3", loaded.files["file3.db"]?.contentHash)
    }

    // ── Missing file ──

    @Test
    fun `load returns null for missing manifest`() {
        val dir = createDir("no-manifest")
        File(dir, "some_file.txt").writeText("content")

        val loaded = ChoamManifest.load(dir)
        assertNull(loaded, "load() should return null when .choam_manifest.json is missing")
    }

    // ── Corrupt JSON ──

    @Test
    fun `load returns null for corrupt JSON`() {
        val dir = createDir("corrupt")
        File(dir, MANIFEST_FILENAME).writeText("{ this is not valid json !!!")

        val loaded = ChoamManifest.load(dir)
        assertNull(loaded, "load() should return null for corrupt JSON (local load is lenient)")
    }

    @Test
    fun `load returns null for truncated JSON`() {
        val dir = createDir("truncated")
        File(dir, MANIFEST_FILENAME).writeText("""{"builtAt":"2026-04-01","rootHash":"abc""")

        val loaded = ChoamManifest.load(dir)
        assertNull(loaded, "load() should return null for truncated JSON")
    }

    @Test
    fun `load returns null for empty file`() {
        val dir = createDir("empty")
        File(dir, MANIFEST_FILENAME).writeText("")

        val loaded = ChoamManifest.load(dir)
        assertNull(loaded, "load() should return null for empty manifest file")
    }

    // ── .choam_manifest.json self-exclusion ──

    @Test
    fun `manifest fileCount excludes the manifest file itself`() {
        val dir = createDir("self-exclusion")
        File(dir, "a.txt").writeText("alpha")
        File(dir, "b.txt").writeText("beta")

        // Build tree (which excludes .choam_manifest.json)
        val tree = DirectoryMerkleTree.buildLocal(dir)
        assertEquals(2, tree.fileCount)

        // Convert to manifest and save
        val manifest = tree.toManifest(dir)
        assertEquals(2, manifest.fileCount)
        manifest.save(dir)

        // Now the directory has 3 files on disk (a.txt, b.txt, .choam_manifest.json)
        // But loading the manifest should still show fileCount=2
        val loaded = ChoamManifest.load(dir)
        assertNotNull(loaded)
        assertEquals(2, loaded.fileCount)

        // Rebuilding the tree should still produce the same hash
        val tree2 = DirectoryMerkleTree.buildLocal(dir)
        assertEquals(tree.rootHash, tree2.rootHash, "Root hash should not change after writing manifest")
        assertEquals(2, tree2.fileCount, "fileCount should still be 2 after manifest written")
    }

    @Test
    fun `manifest written then loaded has correct file entries`() {
        val dir = createDir("entries-check")
        File(dir, "foo.txt").writeText("foo content")
        val sub = File(dir, "sub")
        sub.mkdirs()
        File(sub, "bar.txt").writeText("bar content")

        val tree = DirectoryMerkleTree.buildLocal(dir)
        val manifest = tree.toManifest(dir)
        manifest.save(dir)

        val loaded = ChoamManifest.load(dir)
        assertNotNull(loaded)
        assertEquals(2, loaded.fileCount)
        assertTrue(loaded.files.containsKey("foo.txt") || loaded.files.any { it.key.endsWith("foo.txt") })
    }

    @Test
    fun `toManifest preserves contentHash for manifest-backed content entries`() {
        val dir = createDir("preserve-content-hash")
        val file = File(dir, "a.txt")
        file.writeText("hello")
        val mtime = file.lastModified()

        val sourceManifest = ChoamManifest(
            builtAt = "2026-04-01T00:00:00Z",
            rootHash = "ignored",
            fileCount = 1,
            totalBytes = file.length(),
            hashConfidence = "CONTENT",
            files = mapOf(
                "a.txt" to ChoamManifest.FileEntry(
                    size = file.length(),
                    mtime = mtime,
                    contentHash = "bafkreitestcontenthash",
                    metadataHash = "meta-a"
                )
            )
        )

        val tree = DirectoryMerkleTree.buildLocal(dir, sourceManifest)
        val generated = tree.toManifest(dir)
        val entry = generated.files["a.txt"]

        assertNotNull(entry)
        assertEquals(
            "bafkreitestcontenthash",
            entry.contentHash,
            "Manifest regeneration should preserve the original content hash for CONTENT-backed files"
        )
    }

    @Test
    fun `toManifest preserves mtime for manifest-backed content entries`() {
        val dir = createDir("preserve-mtime")
        val file = File(dir, "a.txt")
        file.writeText("hello")
        val mtime = file.lastModified()

        val sourceManifest = ChoamManifest(
            builtAt = "2026-04-01T00:00:00Z",
            rootHash = "ignored",
            fileCount = 1,
            totalBytes = file.length(),
            hashConfidence = "CONTENT",
            files = mapOf(
                "a.txt" to ChoamManifest.FileEntry(
                    size = file.length(),
                    mtime = mtime,
                    contentHash = "bafkreitestmtime",
                    metadataHash = "meta-a"
                )
            )
        )

        val tree = DirectoryMerkleTree.buildLocal(dir, sourceManifest)
        val generated = tree.toManifest(dir)
        val entry = generated.files["a.txt"]

        assertNotNull(entry)
        assertEquals(
            mtime,
            entry.mtime,
            "Manifest regeneration should preserve file mtime so the next build can reuse the content hash"
        )
    }

    @Test
    fun `saved manifest from content-backed tree can rebuild the same content-backed tree`() {
        val dir = createDir("content-roundtrip")
        val file = File(dir, "a.txt")
        file.writeText("hello")
        val mtime = file.lastModified()

        val sourceManifest = ChoamManifest(
            builtAt = "2026-04-01T00:00:00Z",
            rootHash = "ignored",
            fileCount = 1,
            totalBytes = file.length(),
            hashConfidence = "CONTENT",
            files = mapOf(
                "a.txt" to ChoamManifest.FileEntry(
                    size = file.length(),
                    mtime = mtime,
                    contentHash = "bafkreitestroundtrip",
                    metadataHash = "meta-a"
                )
            )
        )

        val initialTree = DirectoryMerkleTree.buildLocal(dir, sourceManifest)
        val generated = initialTree.toManifest(dir)
        generated.save(dir)

        val loaded = ChoamManifest.load(dir)
        assertNotNull(loaded)

        val rebuiltTree = DirectoryMerkleTree.buildLocal(dir, loaded)
        val rebuiltLeaf = rebuiltTree.root.children.first()

        assertEquals(
            initialTree.rootHash,
            rebuiltTree.rootHash,
            "Manifest save/load should preserve enough information to rebuild the same CONTENT-backed tree"
        )
        assertEquals(
            HashConfidence.CONTENT,
            rebuiltLeaf.confidence,
            "A manifest generated from a CONTENT-backed tree should remain CONTENT-backed after reload"
        )
    }

    // ── ManifestLoadResult types ──

    @Test
    fun `ManifestLoadResult Loaded wraps manifest correctly`() {
        val manifest = ChoamManifest(
            builtAt = "2026-04-01T00:00:00Z",
            rootHash = "abc",
            fileCount = 1,
            totalBytes = 100,
            hashConfidence = "CONTENT",
            files = mapOf("f.txt" to ChoamManifest.FileEntry(100, 0, "cid", "meta"))
        )

        val result: ManifestLoadResult = ManifestLoadResult.Loaded(manifest)
        assertIs<ManifestLoadResult.Loaded>(result)
        assertEquals("abc", result.manifest.rootHash)
    }

    @Test
    fun `ManifestLoadResult Missing is a singleton`() {
        val result: ManifestLoadResult = ManifestLoadResult.Missing
        assertIs<ManifestLoadResult.Missing>(result)
    }

    @Test
    fun `ManifestLoadResult Corrupt carries reason`() {
        val result: ManifestLoadResult = ManifestLoadResult.Corrupt("bad JSON at byte 42")
        assertIs<ManifestLoadResult.Corrupt>(result)
        assertEquals("bad JSON at byte 42", result.reason)
    }

    // ── JSON compatibility ──

    @Test
    fun `manifest ignores unknown keys for forward compatibility`() {
        val jsonStr = """
        {
            "builtAt": "2026-04-01T00:00:00Z",
            "rootHash": "abc",
            "fileCount": 1,
            "totalBytes": 100,
            "hashConfidence": "CONTENT",
            "files": {},
            "futureField": "should be ignored"
        }
        """.trimIndent()

        val json = Json { ignoreUnknownKeys = true }
        val manifest = json.decodeFromString(ChoamManifest.serializer(), jsonStr)
        assertEquals("abc", manifest.rootHash)
        assertEquals(1, manifest.fileCount)
    }

    // ── parseRemoteManifestOutput (marker parsing, no SSH) ──

    private fun manifestJsonNoTrailingNewline(): String {
        val manifest = ChoamManifest(
            builtAt = "2026-07-03T10:29:54Z",
            rootHash = "3a4014a5eba44cfc",
            fileCount = 1,
            totalBytes = 34,
            hashConfidence = "METADATA",
            files = mapOf("a.txt" to ChoamManifest.FileEntry(34, 1783061183614, null, "meta"))
        )
        return Json { prettyPrint = true; encodeDefaults = true }
            .encodeToString(ChoamManifest.serializer(), manifest)
    }

    @Test
    fun `parse tolerates END marker glued to a newline-less manifest`() {
        // Regression: manifests were written without a trailing newline, so the
        // reader script produced "}CHOAM_MANIFEST_END" and every re-transfer of a
        // completed directory failed "Truncated manifest output" forever.
        val output = "CHOAM_MANIFEST_START\n" +
            manifestJsonNoTrailingNewline() + "CHOAM_MANIFEST_END\n" +
            "CHOAM_FRESHNESS||1"
        val result = ChoamManifest.parseRemoteManifestOutput(output, exitCode = 0)
        assertIs<ManifestLoadResult.Loaded>(result)
        assertEquals(1, result.manifest.fileCount)
    }

    @Test
    fun `parse accepts END marker on its own line`() {
        val output = "CHOAM_MANIFEST_START\n" +
            manifestJsonNoTrailingNewline() + "\n" +
            "CHOAM_MANIFEST_END\n" +
            "CHOAM_FRESHNESS||1"
        assertIs<ManifestLoadResult.Loaded>(ChoamManifest.parseRemoteManifestOutput(output, 0))
    }

    @Test
    fun `parse returns Missing for the missing marker`() {
        assertIs<ManifestLoadResult.Missing>(
            ChoamManifest.parseRemoteManifestOutput("CHOAM_MANIFEST_MISSING\n", 0)
        )
    }

    @Test
    fun `parse returns Corrupt when markers are absent`() {
        assertIs<ManifestLoadResult.Corrupt>(
            ChoamManifest.parseRemoteManifestOutput("random noise\n", 0)
        )
    }

    @Test
    fun `parse flags stale manifest when a newer file exists`() {
        val output = "CHOAM_MANIFEST_START\n" +
            manifestJsonNoTrailingNewline() + "\n" +
            "CHOAM_MANIFEST_END\n" +
            "CHOAM_FRESHNESS|/dest/newer.bin|1"
        val result = ChoamManifest.parseRemoteManifestOutput(output, 0)
        assertIs<ManifestLoadResult.Corrupt>(result)
        assertTrue(result.reason.contains("Stale"))
    }
}

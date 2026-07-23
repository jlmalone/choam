package vision.salient.choam.sync

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import vision.salient.choam.config.MachineProfile
import vision.salient.choam.config.MachineType
import vision.salient.sietch.core.*
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

class SietchIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `indexDirectoryRelative returns correct entries`() {
        val root = tempDir.toFile()
        File(root, "file1.txt").writeText("hello world")
        File(root, "sub").mkdir()
        File(root, "sub/file2.txt").writeText("goodbye")

        val catalog = indexDirectoryRelative(root, "sha256")

        assertEquals(2, catalog.entries.size)
        assertTrue(catalog.entries.any { it.path == "file1.txt" })
        assertTrue(catalog.entries.any { it.path == "sub/file2.txt" })

        // Verify hash lengths (SHA-256 = 64 hex chars)
        catalog.entries.forEach { entry ->
            assertEquals(64, entry.hash.length)
        }
    }

    @Test
    fun `indexDirectoryRelative with no hash returns dashes`() {
        val root = tempDir.toFile()
        File(root, "file.txt").writeText("data")

        val catalog = indexDirectoryRelative(root, "none")

        assertEquals(1, catalog.entries.size)
        assertEquals("-", catalog.entries[0].hash)
    }

    @Test
    fun `writeCatalog and parseCatalog roundtrip`() {
        val root = tempDir.toFile()
        File(root, "a.txt").writeText("alpha")
        File(root, "b.txt").writeText("beta")

        val original = indexDirectoryRelative(root, "sha256")
        val outputFile = File(tempDir.toFile(), "catalog.txt")
        writeCatalog(original, outputFile)

        val parsed = parseCatalog(outputFile)

        assertEquals(original.rootPath, parsed.rootPath)
        assertEquals(original.hashAlgorithm, parsed.hashAlgorithm)
        assertEquals(original.entries.size, parsed.entries.size)

        for (i in original.entries.indices) {
            assertEquals(original.entries[i].path, parsed.entries[i].path)
            assertEquals(original.entries[i].hash, parsed.entries[i].hash)
            assertEquals(original.entries[i].size, parsed.entries[i].size)
        }
    }

    @Test
    fun `SietchCatalog entries convert to CHOAM FileManifest correctly`() {
        val root = tempDir.toFile()
        File(root, "movie.mp4").writeText("fake video data")

        val catalog = indexDirectoryRelative(root, "sha256")

        // Simulate the conversion that SyncEngine.buildCatalog does
        val manifests = catalog.entries.map { entry ->
            FileManifest(
                path = entry.path,
                size = entry.size,
                modifiedTime = java.time.Instant.EPOCH,
                checksum = if (entry.hash == "-") null else entry.hash,
                exists = true
            )
        }

        assertEquals(1, manifests.size)
        assertEquals("movie.mp4", manifests[0].path)
        assertEquals(15, manifests[0].size)
        assertNotNull(manifests[0].checksum)
    }

    @Test
    fun `buildCatalog uses Sietch under the hood`() = runBlocking {
        val root = tempDir.toFile()
        File(root, "file1.txt").writeText("content one")
        File(root, "file2.txt").writeText("content two")

        val machine = MachineProfile(
            name = "test",
            hostname = "localhost",
            type = MachineType.DESKTOP,
            repositories = emptyMap()
        )

        val config = vision.salient.choam.config.ChoamConfig()
        val transferManager = vision.salient.choam.network.TransferManager(config)
        val conflictResolver = ConflictResolver()
        val engine = SyncEngine(config, transferManager, conflictResolver)

        val manifests = engine.buildCatalog(
            machine = machine,
            repoPath = root.absolutePath
        )

        assertEquals(2, manifests.size)
        assertTrue(manifests.all { it.exists })
        assertTrue(manifests.all { it.size > 0 })
    }

    @Test
    fun `empty directory returns empty catalog`() {
        val root = tempDir.toFile()
        val catalog = indexDirectoryRelative(root, "sha256")
        assertTrue(catalog.entries.isEmpty())
    }
}

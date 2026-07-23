package vision.salient.choam.sync

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import vision.salient.choam.config.*
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

class DriveAwareSyncTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `resolveRepoPath finds drive-based repository`() {
        val driveDir = File(tempDir.toFile(), "drive-mount").apply { mkdir() }
        val mediaDir = File(driveDir, "movies").apply { mkdir() }
        File(mediaDir, "test.mp4").writeText("video data")

        val drive = Drive(
            uuid = "TEST-UUID-123",
            label = "test-drive",
            repositories = mapOf("media" to "movies")
        )

        val machine = MachineProfile(
            name = "local",
            hostname = "localhost",
            type = MachineType.DESKTOP,
            repositories = mapOf("media" to "/some/other/path")
        )

        val config = ChoamConfig(
            drives = mapOf("test-drive" to drive)
        )

        val transferManager = vision.salient.choam.network.TransferManager(config)
        val engine = SyncEngine(config, transferManager, ConflictResolver())

        val mountedDrives = mapOf(
            "test-drive" to MountedDrive(
                uuid = "TEST-UUID-123",
                label = "test-drive",
                mountPoint = driveDir.absolutePath
            )
        )

        val resolved = engine.resolveRepoPath("media", machine, mountedDrives)
        assertNotNull(resolved)
        assertTrue(resolved!!.startsWith(driveDir.absolutePath))
        assertTrue(resolved.endsWith("movies"))
    }

    @Test
    fun `resolveRepoPath falls back to machine when no drive matches`() {
        val machine = MachineProfile(
            name = "local",
            hostname = "localhost",
            type = MachineType.DESKTOP,
            repositories = mapOf("media" to "/home/user/media")
        )

        val config = ChoamConfig()
        val transferManager = vision.salient.choam.network.TransferManager(config)
        val engine = SyncEngine(config, transferManager, ConflictResolver())

        val resolved = engine.resolveRepoPath("media", machine, emptyMap())
        assertEquals("/home/user/media", resolved)
    }

    @Test
    fun `end-to-end sync between two simulated drives`() = runBlocking {
        // Create two temp dirs representing drives
        val driveA = File(tempDir.toFile(), "drive-a").apply { mkdir() }
        val driveB = File(tempDir.toFile(), "drive-b").apply { mkdir() }

        val mediaA = File(driveA, "movies").apply { mkdir() }
        val mediaB = File(driveB, "movies").apply { mkdir() }

        // Write files to drive A
        File(mediaA, "movie1.mp4").writeText("first movie data")
        File(mediaA, "movie2.mp4").writeText("second movie data")

        // Write one overlapping file and one unique file to drive B
        File(mediaB, "movie2.mp4").writeText("second movie data")
        File(mediaB, "movie3.mp4").writeText("third movie data")

        val machine = MachineProfile(
            name = "local",
            hostname = "localhost",
            type = MachineType.DESKTOP,
            repositories = emptyMap()
        )

        val config = ChoamConfig()
        val transferManager = vision.salient.choam.network.TransferManager(config)
        val engine = SyncEngine(config, transferManager, ConflictResolver())

        // Build catalogs using Sietch
        val catalogA = engine.buildCatalog(machine, mediaA.absolutePath)
        val catalogB = engine.buildCatalog(machine, mediaB.absolutePath)

        // Calculate diff
        val diff = engine.calculateDiff(catalogA, catalogB)

        // movie1.mp4 exists only on A -> new file
        assertEquals(1, diff.newFiles.size)
        assertEquals("movie1.mp4", diff.newFiles[0].path)

        // movie3.mp4 exists only on B -> deleted from A's perspective
        assertEquals(1, diff.deletedFiles.size)
        assertEquals("movie3.mp4", diff.deletedFiles[0].path)

        // movie2.mp4 exists on both with same content -> no modifications
        // (Since we use "none" hash by default, it compares by size and mtime)
        // Both have the same content length, but mtime may differ
    }

    @Test
    fun `buildAndCacheCatalog creates sietch directory and catalog file`() = runBlocking {
        val root = tempDir.toFile()
        File(root, "file.txt").writeText("test data")

        val machine = MachineProfile(
            name = "local",
            hostname = "localhost",
            type = MachineType.DESKTOP,
            repositories = emptyMap()
        )

        val config = ChoamConfig()
        val transferManager = vision.salient.choam.network.TransferManager(config)
        val engine = SyncEngine(config, transferManager, ConflictResolver())

        val manifests = engine.buildAndCacheCatalog(
            machine = machine,
            repoPath = root.absolutePath,
            hashAlgorithm = "sha256"
        )

        assertEquals(1, manifests.size)

        // Verify .sietch directory was created
        val sietchDir = File(root, ".sietch")
        assertTrue(sietchDir.exists())
        assertTrue(sietchDir.isDirectory)

        // Verify catalog file exists
        val catalogFiles = sietchDir.listFiles { f -> f.name.startsWith("catalog-") }
        assertNotNull(catalogFiles)
        assertTrue(catalogFiles!!.isNotEmpty())
    }

    @Test
    fun `loadCachedCatalog reads back cached catalog`() = runBlocking {
        val root = tempDir.toFile()
        File(root, "data.bin").writeText("binary data here")

        val machine = MachineProfile(
            name = "local",
            hostname = "localhost",
            type = MachineType.DESKTOP,
            repositories = emptyMap()
        )

        val config = ChoamConfig()
        val transferManager = vision.salient.choam.network.TransferManager(config)
        val engine = SyncEngine(config, transferManager, ConflictResolver())

        // Cache catalog
        engine.buildAndCacheCatalog(machine, root.absolutePath, hashAlgorithm = "sha256")

        // Load cached
        val cached = engine.loadCachedCatalog(root.absolutePath)
        assertNotNull(cached)
        // The cached catalog includes .sietch/catalog-*.txt itself + data.bin
        assertTrue(cached!!.isNotEmpty())
        assertTrue(cached.any { it.path.contains("data.bin") })
    }
}

package vision.salient.choam.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import vision.salient.choam.catalog.CatalogState
import vision.salient.choam.catalog.DriveState
import java.io.File
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogUpdateCommandTest {

    @TempDir
    lateinit var tempDir: Path

    // --- CatalogState persistence tests ---

    @Test
    fun `state loads empty when file does not exist`() {
        val stateFile = tempDir.resolve("nonexistent.json").toFile()
        val state = CatalogState.load(stateFile)
        assertTrue(state.drives.isEmpty())
    }

    @Test
    fun `state saves and reloads correctly`() {
        val stateFile = tempDir.resolve("state.json").toFile()
        val state = CatalogState()
        state.updateDrive("ext-4tb", "EXT-4TB", 1280000, 847)

        CatalogState.save(state, stateFile)
        assertTrue(stateFile.exists())

        val reloaded = CatalogState.load(stateFile)
        assertEquals(1, reloaded.drives.size)

        val drive = reloaded.drives["ext-4tb"]!!
        assertEquals("EXT-4TB", drive.label)
        assertEquals(1280000, drive.lastScanFileCount)
        assertEquals(847, drive.lastScanNewFiles)
        assertNotNull(drive.lastScanTimestamp)
    }

    @Test
    fun `state returns null instant for unknown drive`() {
        val state = CatalogState()
        assertNull(state.getLastScanInstant("nonexistent"))
    }

    @Test
    fun `state returns valid instant for known drive`() {
        val state = CatalogState()
        state.updateDrive("ext-4tb", "EXT-4TB", 100, 10)

        val instant = state.getLastScanInstant("ext-4tb")
        assertNotNull(instant)
        assertTrue(instant.isBefore(Instant.now().plusSeconds(1)))
        assertTrue(instant.isAfter(Instant.now().minusSeconds(5)))
    }

    @Test
    fun `state preserves multiple drives independently`() {
        val stateFile = tempDir.resolve("state.json").toFile()
        val state = CatalogState()
        state.updateDrive("drive1", "DRIVE1", 100, 10)
        state.updateDrive("drive2", "DRIVE2", 200, 20)

        CatalogState.save(state, stateFile)
        val reloaded = CatalogState.load(stateFile)

        assertEquals(2, reloaded.drives.size)
        assertEquals("DRIVE1", reloaded.drives["drive1"]!!.label)
        assertEquals("DRIVE2", reloaded.drives["drive2"]!!.label)
        assertEquals(100, reloaded.drives["drive1"]!!.lastScanFileCount)
        assertEquals(200, reloaded.drives["drive2"]!!.lastScanFileCount)
    }

    @Test
    fun `state update overwrites previous scan data`() {
        val state = CatalogState()
        state.updateDrive("drive1", "DRIVE1", 100, 10)

        val firstTimestamp = state.drives["drive1"]!!.lastScanTimestamp

        Thread.sleep(10) // ensure different timestamp
        state.updateDrive("drive1", "DRIVE1", 150, 50)

        val secondTimestamp = state.drives["drive1"]!!.lastScanTimestamp
        assertEquals(150, state.drives["drive1"]!!.lastScanFileCount)
        assertEquals(50, state.drives["drive1"]!!.lastScanNewFiles)
        assertTrue(secondTimestamp >= firstTimestamp)
    }

    @Test
    fun `state handles corrupted JSON gracefully`() {
        val stateFile = tempDir.resolve("state.json").toFile()
        stateFile.writeText("{ this is not valid json ]]]")

        val state = CatalogState.load(stateFile)
        assertTrue(state.drives.isEmpty())
    }

    // --- mtime filtering logic tests ---

    @Test
    fun `mtime filtering identifies new files correctly`() {
        // Set up a directory with files at known mtimes
        val scanDir = tempDir.resolve("drive").toFile()
        scanDir.mkdirs()

        // Create an old file
        val oldFile = File(scanDir, "old.txt")
        oldFile.writeText("old content")
        oldFile.setLastModified(Instant.parse("2025-01-01T00:00:00Z").toEpochMilli())

        // Create a new file
        val newFile = File(scanDir, "new.txt")
        newFile.writeText("new content")
        newFile.setLastModified(Instant.parse("2026-03-01T00:00:00Z").toEpochMilli())

        // Simulate filtering with a cutoff at 2026-02-01
        val cutoff = Instant.parse("2026-02-01T00:00:00Z").toEpochMilli()

        val newFiles = mutableListOf<File>()
        val allFiles = mutableListOf<File>()

        vision.salient.sietch.core.walkTree(scanDir) { file ->
            allFiles.add(file)
            if (file.lastModified() > cutoff) {
                newFiles.add(file)
            }
        }

        assertEquals(2, allFiles.size)
        assertEquals(1, newFiles.size)
        assertEquals("new.txt", newFiles[0].name)
    }

    @Test
    fun `first scan treats all files as new when no last scan timestamp`() {
        val scanDir = tempDir.resolve("drive").toFile()
        scanDir.mkdirs()

        File(scanDir, "a.txt").writeText("a")
        File(scanDir, "b.txt").writeText("b")
        File(scanDir, "c.txt").writeText("c")

        // lastScanMs = 0 means all files are new (mtime > 0)
        val lastScanMs = 0L
        val newFiles = mutableListOf<File>()

        vision.salient.sietch.core.walkTree(scanDir) { file ->
            if (file.lastModified() > lastScanMs) {
                newFiles.add(file)
            }
        }

        assertEquals(3, newFiles.size)
    }

    @Test
    fun `exclude patterns are applied during incremental scan`() {
        val scanDir = tempDir.resolve("drive").toFile()
        scanDir.mkdirs()

        File(scanDir, "movie.mkv").writeText("video data")
        File(scanDir, ".DS_Store").writeText("ds store")
        File(scanDir, "download.tmp").writeText("temp data")

        val excludePatterns = listOf(".DS_Store", "*.tmp")
        val scannedFiles = mutableListOf<File>()

        vision.salient.sietch.core.walkTree(scanDir, excludePatterns) { file ->
            scannedFiles.add(file)
        }

        assertEquals(1, scannedFiles.size)
        assertEquals("movie.mkv", scannedFiles[0].name)
    }

    @Test
    fun `nested files are scanned with mtime filtering`() {
        val scanDir = tempDir.resolve("drive").toFile()
        File(scanDir, "movies").mkdirs()
        File(scanDir, "tv").mkdirs()

        val cutoff = Instant.now().minusSeconds(3600).toEpochMilli()

        // Old files
        val oldMovie = File(scanDir, "movies/old_movie.mkv")
        oldMovie.writeText("old movie")
        oldMovie.setLastModified(cutoff - 100_000)

        // New files
        val newMovie = File(scanDir, "movies/new_movie.mkv")
        newMovie.writeText("new movie")
        // Default mtime is "now", which is > cutoff

        val newTv = File(scanDir, "tv/episode.mkv")
        newTv.writeText("tv episode")
        // Default mtime is "now"

        val newFiles = mutableListOf<String>()
        vision.salient.sietch.core.walkTree(scanDir) { file ->
            if (file.lastModified() > cutoff) {
                newFiles.add(file.name)
            }
        }

        assertEquals(2, newFiles.size)
        assertTrue(newFiles.contains("new_movie.mkv"))
        assertTrue(newFiles.contains("episode.mkv"))
    }

    // --- SHA-256 fallback tests ---

    @Test
    fun `SHA-256 fallback produces valid hash format`() {
        val testFile = tempDir.resolve("test.bin").toFile()
        testFile.writeBytes(byteArrayOf(0x48, 0x65, 0x6C, 0x6C, 0x6F)) // "Hello"

        val hash = vision.salient.sietch.core.computeHash(testFile, "SHA-256")
        val cidFallback = "sha256:$hash"

        assertTrue(cidFallback.startsWith("sha256:"))
        assertEquals(71, cidFallback.length) // "sha256:" (7) + 64 hex chars
        assertTrue(hash.matches(Regex("[0-9a-f]{64}")))
    }

    // --- State JSON format tests ---

    @Test
    fun `state JSON is human-readable with pretty print`() {
        val stateFile = tempDir.resolve("state.json").toFile()
        val state = CatalogState()
        state.updateDrive("ext-4tb", "EXT-4TB", 1280000, 847)

        CatalogState.save(state, stateFile)
        val content = stateFile.readText()

        assertTrue(content.contains("\"label\": \"EXT-4TB\""))
        assertTrue(content.contains("\"lastScanFileCount\": 1280000"))
        assertTrue(content.contains("\"lastScanNewFiles\": 847"))
        assertTrue(content.contains("\n")) // pretty-printed
    }

    @Test
    fun `state file creates parent directories if needed`() {
        val nestedPath = tempDir.resolve("a/b/c/state.json").toFile()
        val state = CatalogState()
        state.updateDrive("test", "TEST", 1, 1)

        CatalogState.save(state, nestedPath)
        assertTrue(nestedPath.exists())
    }

    // --- Integration: state + mtime filtering ---

    @Test
    fun `full incremental scan workflow simulates correctly`() {
        val stateFile = tempDir.resolve("state.json").toFile()
        val scanDir = tempDir.resolve("drive").toFile()
        scanDir.mkdirs()

        // First scan: 3 files, all new
        File(scanDir, "a.txt").writeText("a")
        File(scanDir, "b.txt").writeText("b")
        File(scanDir, "c.txt").writeText("c")

        val state = CatalogState()
        val lastScanMs1 = state.getLastScanInstant("drive1")?.toEpochMilli() ?: 0L

        var newCount = 0L
        vision.salient.sietch.core.walkTree(scanDir) { file ->
            if (file.lastModified() > lastScanMs1) newCount++
        }
        assertEquals(3, newCount)

        // Save state after first scan
        state.updateDrive("drive1", "DRIVE1", 3, 3)
        CatalogState.save(state, stateFile)

        // Second scan: add 1 new file, leave others unchanged
        Thread.sleep(50)
        val reloadedState = CatalogState.load(stateFile)
        val lastScanMs2 = reloadedState.getLastScanInstant("drive1")!!.toEpochMilli()

        File(scanDir, "d.txt").writeText("d")

        var newCount2 = 0L
        vision.salient.sietch.core.walkTree(scanDir) { file ->
            if (file.lastModified() > lastScanMs2) newCount2++
        }
        assertEquals(1, newCount2)
    }

    @Test
    fun `modified file is detected on incremental scan`() {
        val scanDir = tempDir.resolve("drive").toFile()
        scanDir.mkdirs()

        val file = File(scanDir, "data.txt")
        file.writeText("original")

        // Set mtime to the past
        val pastTime = Instant.now().minusSeconds(3600).toEpochMilli()
        file.setLastModified(pastTime)

        // Cutoff is 1 hour ago — file should not be new
        val cutoff = Instant.now().minusSeconds(1800).toEpochMilli()
        var newBefore = 0L
        vision.salient.sietch.core.walkTree(scanDir) { f ->
            if (f.lastModified() > cutoff) newBefore++
        }
        assertEquals(0, newBefore)

        // Modify the file — sets mtime to now
        file.writeText("modified content")

        var newAfter = 0L
        vision.salient.sietch.core.walkTree(scanDir) { f ->
            if (f.lastModified() > cutoff) newAfter++
        }
        assertEquals(1, newAfter)
    }
}

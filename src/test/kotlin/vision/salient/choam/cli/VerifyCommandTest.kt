package vision.salient.choam.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerifyCommandTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createUnifiedRegistry(path: String, entries: List<RegistryEntry>) {
        val conn = DriverManager.getConnection("jdbc:sqlite:$path")
        val stmt = conn.createStatement()
        stmt.executeUpdate("PRAGMA journal_mode=WAL")
        stmt.executeUpdate("""
            CREATE TABLE content_locations (
                cid TEXT NOT NULL,
                machine_name TEXT NOT NULL,
                file_path TEXT NOT NULL,
                file_size INTEGER,
                verified_at TEXT,
                registered_at TEXT NOT NULL DEFAULT (datetime('now')),
                last_synced_at TEXT NOT NULL DEFAULT (datetime('now')),
                PRIMARY KEY (cid, machine_name, file_path)
            )
        """)
        val insert = conn.prepareStatement(
            "INSERT INTO content_locations (cid, machine_name, file_path, file_size) VALUES (?, ?, ?, ?)"
        )
        for (entry in entries) {
            insert.setString(1, entry.cid)
            insert.setString(2, entry.machine)
            insert.setString(3, entry.filePath)
            insert.setLong(4, entry.fileSize)
            insert.executeUpdate()
        }
        insert.close()
        stmt.close()
        conn.close()
    }

    data class RegistryEntry(
        val cid: String,
        val machine: String,
        val filePath: String,
        val fileSize: Long = 1024
    )

    // ============================
    // loadRegisteredPaths tests
    // ============================

    @Test
    fun `loadRegisteredPaths returns all paths for machine`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, listOf(
            RegistryEntry("QmA", "server-a", "/Volumes/D/file1.mkv"),
            RegistryEntry("QmB", "server-a", "/Volumes/D/file2.mkv"),
            RegistryEntry("QmC", "server-b", "/Volumes/E/file3.pdf")
        ))

        val paths = VerifyCommand.loadRegisteredPaths(dbPath, "server-a", emptyMap(), 0)
        assertEquals(2, paths.size)
        assertTrue(paths.contains("/Volumes/D/file1.mkv"))
        assertTrue(paths.contains("/Volumes/D/file2.mkv"))
    }

    @Test
    fun `loadRegisteredPaths includes alias rows`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, listOf(
            RegistryEntry("QmA", "server-a", "/Volumes/D/file1.mkv"),
            RegistryEntry("QmB", "server-a-old", "/Volumes/D/file2.mkv"),
            RegistryEntry("QmC", "server-b", "/Volumes/E/file3.pdf")
        ))

        // "server-a-old" is an alias for "server-a"
        val machineNameMap = mapOf("server-a-old" to "server-a")
        val paths = VerifyCommand.loadRegisteredPaths(dbPath, "server-a", machineNameMap, 0)
        assertEquals(2, paths.size)
        assertTrue(paths.contains("/Volumes/D/file1.mkv"))
        assertTrue(paths.contains("/Volumes/D/file2.mkv"))
    }

    @Test
    fun `loadRegisteredPaths with sample limits results`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        val entries = (1..100).map { i ->
            RegistryEntry("Qm$i", "server-a", "/Volumes/D/file_$i.dat")
        }
        createUnifiedRegistry(dbPath, entries)

        val paths = VerifyCommand.loadRegisteredPaths(dbPath, "server-a", emptyMap(), 10)
        assertEquals(10, paths.size)
    }

    @Test
    fun `loadRegisteredPaths returns empty for unknown machine`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, listOf(
            RegistryEntry("QmA", "server-a", "/Volumes/D/file1.mkv")
        ))

        val paths = VerifyCommand.loadRegisteredPaths(dbPath, "nonexistent", emptyMap(), 0)
        assertTrue(paths.isEmpty())
    }

    @Test
    fun `loadRegisteredPaths returns empty for empty DB`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, emptyList())

        val paths = VerifyCommand.loadRegisteredPaths(dbPath, "server-a", emptyMap(), 0)
        assertTrue(paths.isEmpty())
    }

    @Test
    fun `loadRegisteredPaths with multiple aliases`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, listOf(
            RegistryEntry("QmA", "server-a", "/Volumes/D/file1.mkv"),
            RegistryEntry("QmB", "server-a-old", "/Volumes/D/file2.mkv"),
            RegistryEntry("QmC", "server-a-mac-mini", "/Volumes/D/file3.mkv"),
            RegistryEntry("QmD", "server-b", "/Volumes/E/other.pdf")
        ))

        val machineNameMap = mapOf("server-a-old" to "server-a", "server-a-mac-mini" to "server-a")
        val paths = VerifyCommand.loadRegisteredPaths(dbPath, "server-a", machineNameMap, 0)
        assertEquals(3, paths.size)
    }

    @Test
    fun `loadRegisteredPaths sample 0 returns all`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        val entries = (1..50).map { i ->
            RegistryEntry("Qm$i", "server-a", "/Volumes/D/file_$i.dat")
        }
        createUnifiedRegistry(dbPath, entries)

        val paths = VerifyCommand.loadRegisteredPaths(dbPath, "server-a", emptyMap(), 0)
        assertEquals(50, paths.size)
    }

    @Test
    fun `loadRegisteredPaths sample larger than total returns all`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, listOf(
            RegistryEntry("QmA", "server-a", "/Volumes/D/file1.mkv"),
            RegistryEntry("QmB", "server-a", "/Volumes/D/file2.mkv")
        ))

        val paths = VerifyCommand.loadRegisteredPaths(dbPath, "server-a", emptyMap(), 1000)
        assertEquals(2, paths.size)
    }

    // ============================
    // verifyLocalPaths tests
    // ============================

    @Test
    fun `verifyLocalPaths with all existing files`() {
        val dir = tempDir.toFile()
        File(dir, "file1.txt").writeText("hello")
        File(dir, "file2.txt").writeText("world")

        val paths = listOf(
            File(dir, "file1.txt").absolutePath,
            File(dir, "file2.txt").absolutePath
        )

        val result = VerifyCommand.verifyLocalPaths("local", paths)
        assertEquals(2, result.registered)
        assertEquals(2, result.verified)
        assertEquals(0, result.missing)
        assertTrue(result.allVerified)
        assertTrue(result.missingPaths.isEmpty())
    }

    @Test
    fun `verifyLocalPaths with some missing files`() {
        val dir = tempDir.toFile()
        File(dir, "exists.txt").writeText("here")

        val paths = listOf(
            File(dir, "exists.txt").absolutePath,
            File(dir, "gone1.txt").absolutePath,
            File(dir, "gone2.txt").absolutePath
        )

        val result = VerifyCommand.verifyLocalPaths("local", paths)
        assertEquals(3, result.registered)
        assertEquals(1, result.verified)
        assertEquals(2, result.missing)
        assertFalse(result.allVerified)
        assertEquals(2, result.missingPaths.size)
        assertTrue(result.missingPaths.any { it.endsWith("gone1.txt") })
        assertTrue(result.missingPaths.any { it.endsWith("gone2.txt") })
    }

    @Test
    fun `verifyLocalPaths with all missing files`() {
        val paths = listOf(
            "/nonexistent/path/a.mkv",
            "/nonexistent/path/b.mkv",
            "/nonexistent/path/c.mkv"
        )

        val result = VerifyCommand.verifyLocalPaths("local", paths)
        assertEquals(3, result.registered)
        assertEquals(0, result.verified)
        assertEquals(3, result.missing)
        assertFalse(result.allVerified)
    }

    @Test
    fun `verifyLocalPaths with empty list`() {
        val result = VerifyCommand.verifyLocalPaths("local", emptyList())
        assertEquals(0, result.registered)
        assertEquals(0, result.verified)
        assertEquals(0, result.missing)
        assertTrue(result.allVerified)
    }

    @Test
    fun `verifyLocalPaths detects directories as existing`() {
        // File.exists() returns true for directories too
        val dir = tempDir.toFile()
        val subDir = File(dir, "subdir")
        subDir.mkdir()

        val result = VerifyCommand.verifyLocalPaths("local", listOf(subDir.absolutePath))
        assertEquals(1, result.verified)
        assertEquals(0, result.missing)
    }

    @Test
    fun `verifyLocalPaths with nested subdirectory files`() {
        val dir = tempDir.toFile()
        val sub = File(dir, "movies/scifi")
        sub.mkdirs()
        File(sub, "Aliens.mkv").writeText("content")
        File(sub, "Blade Runner.mkv").writeText("content")

        val paths = listOf(
            File(sub, "Aliens.mkv").absolutePath,
            File(sub, "Blade Runner.mkv").absolutePath,
            File(sub, "Missing Movie.mkv").absolutePath
        )

        val result = VerifyCommand.verifyLocalPaths("server-a", paths)
        assertEquals(3, result.registered)
        assertEquals(2, result.verified)
        assertEquals(1, result.missing)
        assertEquals("server-a", result.machineName)
    }

    // ============================
    // VerifyResult tests
    // ============================

    @Test
    fun `VerifyResult allVerified true when no missing`() {
        val result = VerifyResult("server-a", 100, 100, 0, emptyList())
        assertTrue(result.allVerified)
    }

    @Test
    fun `VerifyResult allVerified false when any missing`() {
        val result = VerifyResult("server-a", 100, 99, 1, listOf("/missing"))
        assertFalse(result.allVerified)
    }

    @Test
    fun `VerifyResult preserves machine name`() {
        val result = VerifyResult("server-b", 50, 48, 2, listOf("/a", "/b"))
        assertEquals("server-b", result.machineName)
        assertEquals(50, result.registered)
        assertEquals(48, result.verified)
        assertEquals(2, result.missing)
        assertEquals(listOf("/a", "/b"), result.missingPaths)
    }

    // ============================
    // Integration: load paths + verify
    // ============================

    @Test
    fun `end-to-end local verify with real temp files`() {
        val dir = tempDir.toFile()
        val file1 = File(dir, "real_movie.mkv")
        file1.writeText("movie content")
        val file2 = File(dir, "real_doc.pdf")
        file2.writeText("pdf content")

        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, listOf(
            RegistryEntry("QmA", "local", file1.absolutePath),
            RegistryEntry("QmB", "local", file2.absolutePath),
            RegistryEntry("QmC", "local", File(dir, "deleted_file.mkv").absolutePath)
        ))

        val paths = VerifyCommand.loadRegisteredPaths(dbPath, "local", emptyMap(), 0)
        assertEquals(3, paths.size)

        val result = VerifyCommand.verifyLocalPaths("local", paths)
        assertEquals(3, result.registered)
        assertEquals(2, result.verified)
        assertEquals(1, result.missing)
        assertTrue(result.missingPaths[0].endsWith("deleted_file.mkv"))
    }

    @Test
    fun `end-to-end local verify with aliases resolves all rows`() {
        val dir = tempDir.toFile()
        val file1 = File(dir, "file1.mkv")
        file1.writeText("content")
        val file2 = File(dir, "file2.mkv")
        file2.writeText("content")

        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, listOf(
            RegistryEntry("QmA", "server-a", file1.absolutePath),
            RegistryEntry("QmB", "server-a-old", file2.absolutePath),
            RegistryEntry("QmC", "server-b", "/other/path.txt")
        ))

        val machineNameMap = mapOf("server-a-old" to "server-a")
        val paths = VerifyCommand.loadRegisteredPaths(dbPath, "server-a", machineNameMap, 0)
        assertEquals(2, paths.size)

        val result = VerifyCommand.verifyLocalPaths("server-a", paths)
        assertEquals(2, result.registered)
        assertEquals(2, result.verified)
        assertEquals(0, result.missing)
        assertTrue(result.allVerified)
    }

    @Test
    fun `large batch verify with 1000 files`() {
        val dir = tempDir.toFile()
        val entries = mutableListOf<RegistryEntry>()
        val expectedMissing = mutableListOf<String>()

        for (i in 1..1000) {
            val file = File(dir, "file_$i.dat")
            val path = file.absolutePath
            entries.add(RegistryEntry("Qm$i", "local", path))
            if (i <= 900) {
                // Create 900 files, leave 100 missing
                file.writeText("data $i")
            } else {
                expectedMissing.add(path)
            }
        }

        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, entries)

        val paths = VerifyCommand.loadRegisteredPaths(dbPath, "local", emptyMap(), 0)
        assertEquals(1000, paths.size)

        val result = VerifyCommand.verifyLocalPaths("local", paths)
        assertEquals(1000, result.registered)
        assertEquals(900, result.verified)
        assertEquals(100, result.missing)

        // All missing paths should be from file_901 through file_1000
        for (missingPath in result.missingPaths) {
            assertTrue(missingPath in expectedMissing, "Unexpected missing: $missingPath")
        }
    }
}

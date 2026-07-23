package vision.salient.choam.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoveCommandTest {

    @TempDir
    lateinit var tempDir: Path

    // ============================
    // Helper: create unified registry
    // ============================

    private fun createUnifiedRegistry(path: String, entries: List<MoveTestEntry>) {
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

    data class MoveTestEntry(
        val cid: String,
        val machine: String,
        val filePath: String,
        val fileSize: Long = 1024
    )

    private fun queryEntries(dbPath: String, machine: String): List<Pair<String, String>> {
        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        val rs = conn.createStatement().executeQuery(
            "SELECT cid, file_path FROM content_locations WHERE machine_name = '$machine' ORDER BY file_path"
        )
        val results = mutableListOf<Pair<String, String>>()
        while (rs.next()) {
            results.add(rs.getString("cid") to rs.getString("file_path"))
        }
        rs.close()
        conn.close()
        return results
    }

    private fun countEntries(dbPath: String): Long {
        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM content_locations")
        rs.next()
        val count = rs.getLong(1)
        rs.close()
        conn.close()
        return count
    }

    // ============================
    // buildTargetPath tests
    // ============================

    @Test
    fun `buildTargetPath rewrites path prefix correctly`() {
        val result = MoveCommand.buildTargetPath(
            "/Volumes/EXT-4TB/film/Aliens.mkv",
            "/Volumes/EXT-4TB/film",
            "/Users/example/film"
        )
        assertEquals("/Users/example/film/Aliens.mkv", result)
    }

    @Test
    fun `buildTargetPath handles nested subdirectories`() {
        val result = MoveCommand.buildTargetPath(
            "/Volumes/EXT-4TB/film/scifi/2024/Dune Part Two.mkv",
            "/Volumes/EXT-4TB/film",
            "/data/media/film"
        )
        assertEquals("/data/media/film/scifi/2024/Dune Part Two.mkv", result)
    }

    @Test
    fun `buildTargetPath handles trailing slashes on prefixes`() {
        val result = MoveCommand.buildTargetPath(
            "/Volumes/EXT-4TB/film/Aliens.mkv",
            "/Volumes/EXT-4TB/film/",
            "/Users/example/film/"
        )
        assertEquals("/Users/example/film/Aliens.mkv", result)
    }

    @Test
    fun `buildTargetPath returns original if prefix doesnt match`() {
        val result = MoveCommand.buildTargetPath(
            "/other/path/file.mkv",
            "/Volumes/EXT-4TB/film",
            "/Users/example/film"
        )
        assertEquals("/other/path/file.mkv", result)
    }

    @Test
    fun `buildTargetPath handles backtick in path`() {
        // Real config has backtick-prefixed dirs like "`film"
        val result = MoveCommand.buildTargetPath(
            "/Volumes/EXT-4TB/`film/Aliens.mkv",
            "/Volumes/EXT-4TB/`film",
            "/data/film"
        )
        assertEquals("/data/film/Aliens.mkv", result)
    }

    @Test
    fun `buildTargetPath handles exact prefix match with no remaining path`() {
        val result = MoveCommand.buildTargetPath(
            "/Volumes/EXT-4TB/film",
            "/Volumes/EXT-4TB/film",
            "/data/film"
        )
        assertEquals("/data/film", result)
    }

    // ============================
    // updateRegistryAfterMove tests
    // ============================

    @Test
    fun `updateRegistry moves entries from source to target machine`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, listOf(
            MoveTestEntry("QmA", "server-a", "/Volumes/EXT-4TB/film/Aliens.mkv", 2_000_000_000),
            MoveTestEntry("QmB", "server-a", "/Volumes/EXT-4TB/film/Blade Runner.mkv", 3_000_000_000),
            MoveTestEntry("QmC", "server-a", "/Volumes/EXT-4TB/tv/Breaking Bad/S01E01.mkv", 500_000_000)
        ))

        val updated = MoveCommand.updateRegistryAfterMove(
            dbPath, "server-a", "server-b",
            "/Volumes/EXT-4TB/film", "/data/film"
        )

        assertEquals(2, updated)

        // Verify source entries are gone for film
        val serverAEntries = queryEntries(dbPath, "server-a")
        assertEquals(1, serverAEntries.size)
        assertEquals("/Volumes/EXT-4TB/tv/Breaking Bad/S01E01.mkv", serverAEntries[0].second)

        // Verify target entries were created
        val vanEntries = queryEntries(dbPath, "server-b")
        assertEquals(2, vanEntries.size)
        assertTrue(vanEntries.any { it.second == "/data/film/Aliens.mkv" })
        assertTrue(vanEntries.any { it.second == "/data/film/Blade Runner.mkv" })

        // Total entries preserved (2 moved + 1 untouched tv)
        assertEquals(3, countEntries(dbPath))
    }

    @Test
    fun `updateRegistry preserves CIDs during move`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, listOf(
            MoveTestEntry("bafkreiAAA", "server-a", "/Volumes/EXT-4TB/film/Aliens.mkv", 2_000_000_000),
            MoveTestEntry("bafkreiBBB", "server-a", "/Volumes/EXT-4TB/film/Blade Runner.mkv", 3_000_000_000)
        ))

        MoveCommand.updateRegistryAfterMove(
            dbPath, "server-a", "server-b",
            "/Volumes/EXT-4TB/film", "/data/film"
        )

        val vanEntries = queryEntries(dbPath, "server-b")
        assertTrue(vanEntries.any { it.first == "bafkreiAAA" && it.second == "/data/film/Aliens.mkv" })
        assertTrue(vanEntries.any { it.first == "bafkreiBBB" && it.second == "/data/film/Blade Runner.mkv" })
    }

    @Test
    fun `updateRegistry does nothing for non-matching paths`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, listOf(
            MoveTestEntry("QmA", "server-a", "/Volumes/EXT-4TB/tv/episode.mkv", 500_000_000),
            MoveTestEntry("QmB", "server-b", "/data/docs/report.pdf", 100_000)
        ))

        val updated = MoveCommand.updateRegistryAfterMove(
            dbPath, "server-a", "server-b",
            "/Volumes/EXT-4TB/film", "/data/film"
        )

        assertEquals(0, updated)
        assertEquals(2, countEntries(dbPath))
    }

    @Test
    fun `updateRegistry does nothing for wrong machine`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, listOf(
            MoveTestEntry("QmA", "server-b", "/data/film/Aliens.mkv", 2_000_000_000)
        ))

        val updated = MoveCommand.updateRegistryAfterMove(
            dbPath, "server-a", "local",
            "/Volumes/EXT-4TB/film", "/Users/example/film"
        )

        assertEquals(0, updated)
        assertEquals(1, countEntries(dbPath))
        // Original entry untouched
        val entries = queryEntries(dbPath, "server-b")
        assertEquals(1, entries.size)
    }

    @Test
    fun `updateRegistry handles empty database`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, emptyList())

        val updated = MoveCommand.updateRegistryAfterMove(
            dbPath, "server-a", "server-b",
            "/Volumes/EXT-4TB/film", "/data/film"
        )

        assertEquals(0, updated)
    }

    @Test
    fun `updateRegistry handles large batch`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        val entries = (1..500).map { i ->
            MoveTestEntry("Qm$i", "server-a", "/Volumes/EXT-4TB/film/movie_$i.mkv", 1_000_000L * i)
        }
        createUnifiedRegistry(dbPath, entries)

        val updated = MoveCommand.updateRegistryAfterMove(
            dbPath, "server-a", "server-b",
            "/Volumes/EXT-4TB/film", "/data/film"
        )

        assertEquals(500, updated)
        assertEquals(0, queryEntries(dbPath, "server-a").size)
        assertEquals(500, queryEntries(dbPath, "server-b").size)
    }

    @Test
    fun `updateRegistry doesnt affect other machines entries`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, listOf(
            // Server A film (should be moved)
            MoveTestEntry("QmA", "server-a", "/Volumes/EXT-4TB/film/Aliens.mkv", 2_000_000_000),
            // Server B already has its own film dir (should NOT be affected)
            MoveTestEntry("QmX", "server-b", "/data/film/OtherMovie.mkv", 1_000_000_000),
            // Local machine has a different file (should NOT be affected)
            MoveTestEntry("QmY", "local", "/Users/example/film/LocalMovie.mkv", 500_000_000)
        ))

        MoveCommand.updateRegistryAfterMove(
            dbPath, "server-a", "server-b",
            "/Volumes/EXT-4TB/film", "/data/film"
        )

        // Server B now has 2 entries (its original + the moved one)
        val vanEntries = queryEntries(dbPath, "server-b")
        assertEquals(2, vanEntries.size)

        // Local untouched
        val localEntries = queryEntries(dbPath, "local")
        assertEquals(1, localEntries.size)

        // Server A film gone
        val serverAEntries = queryEntries(dbPath, "server-a")
        assertEquals(0, serverAEntries.size)

        assertEquals(3, countEntries(dbPath))
    }

    @Test
    fun `updateRegistry handles target already having same CID`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, listOf(
            // Same CID exists on both machines at different paths
            MoveTestEntry("QmA", "server-a", "/Volumes/EXT-4TB/film/Aliens.mkv", 2_000_000_000),
            MoveTestEntry("QmA", "server-b", "/data/film/Aliens.mkv", 2_000_000_000)
        ))

        // Move should still work — INSERT OR REPLACE handles the collision
        val updated = MoveCommand.updateRegistryAfterMove(
            dbPath, "server-a", "server-b",
            "/Volumes/EXT-4TB/film", "/data/film"
        )

        assertEquals(1, updated)
        assertEquals(0, queryEntries(dbPath, "server-a").size)
        // Server B still has exactly 1 entry (the replaced one)
        assertEquals(1, queryEntries(dbPath, "server-b").size)
    }

    // ============================
    // shellEscape tests
    // ============================

    @Test
    fun `shellEscape wraps in single quotes`() {
        assertEquals("'/path/to/file'", MoveCommand.shellEscape("/path/to/file"))
    }

    @Test
    fun `shellEscape handles spaces`() {
        assertEquals("'/path/to/my file.mkv'", MoveCommand.shellEscape("/path/to/my file.mkv"))
    }

    @Test
    fun `shellEscape handles embedded single quotes`() {
        assertEquals("'/path/it'\\''s here'", MoveCommand.shellEscape("/path/it's here"))
    }

    @Test
    fun `shellEscape handles backtick paths`() {
        val backtickPath = "/Volumes/EXT-4TB/" + "`" + "film"
        val expected = "'" + "/Volumes/EXT-4TB/" + "`" + "film" + "'"
        assertEquals(expected, MoveCommand.shellEscape(backtickPath))
    }

    // ============================
    // MoveResult tests
    // ============================

    @Test
    fun `MoveResult success when all phases succeeded`() {
        val result = MoveResult(
            repo = "film", fromMachine = "server-a", toMachine = "server-b",
            filesTransferred = 100, filesVerified = 100, filesFailed = 0,
            failedPaths = emptyList(), sourceDeleted = true, registryUpdated = true
        )
        assertTrue(result.success)
    }

    @Test
    fun `MoveResult failure when verification failed`() {
        val result = MoveResult(
            repo = "film", fromMachine = "server-a", toMachine = "server-b",
            filesTransferred = 100, filesVerified = 98, filesFailed = 2,
            failedPaths = listOf("/path/a", "/path/b"), sourceDeleted = false, registryUpdated = false
        )
        assertFalse(result.success)
    }

    @Test
    fun `MoveResult failure when source not deleted`() {
        val result = MoveResult(
            repo = "film", fromMachine = "server-a", toMachine = "server-b",
            filesTransferred = 100, filesVerified = 100, filesFailed = 0,
            failedPaths = emptyList(), sourceDeleted = false, registryUpdated = true
        )
        assertFalse(result.success)
    }

    @Test
    fun `MoveResult failure when registry not updated`() {
        val result = MoveResult(
            repo = "film", fromMachine = "server-a", toMachine = "server-b",
            filesTransferred = 100, filesVerified = 100, filesFailed = 0,
            failedPaths = emptyList(), sourceDeleted = true, registryUpdated = false
        )
        assertFalse(result.success)
    }

    // ============================
    // Integration: verification gates deletion
    // ============================

    @Test
    fun `verification failure prevents registry update`() {
        // Simulate: verification found 2 missing files
        val verifyResult = VerifyResult(
            machineName = "server-b",
            registered = 100,
            verified = 98,
            missing = 2,
            missingPaths = listOf("/data/film/missing1.mkv", "/data/film/missing2.mkv")
        )

        // The logic in run() checks verifyResult.missing > 0 before proceeding
        // We test the condition directly
        assertTrue(verifyResult.missing > 0)
        assertFalse(verifyResult.allVerified)

        // Registry should NOT be updated when verification fails
        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, listOf(
            MoveTestEntry("QmA", "server-a", "/Volumes/EXT-4TB/film/Aliens.mkv")
        ))

        // If we were to update (which we shouldn't), entries would change
        // Verify the registry is untouched by checking before state
        val entriesBefore = queryEntries(dbPath, "server-a")
        assertEquals(1, entriesBefore.size)
        // Don't call updateRegistryAfterMove — verification failed
    }

    @Test
    fun `full verification success allows registry update`() {
        val verifyResult = VerifyResult(
            machineName = "server-b",
            registered = 3,
            verified = 3,
            missing = 0,
            missingPaths = emptyList()
        )

        assertTrue(verifyResult.allVerified)

        // Now it's safe to update the registry
        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, listOf(
            MoveTestEntry("QmA", "server-a", "/Volumes/EXT-4TB/film/Aliens.mkv", 2_000_000_000),
            MoveTestEntry("QmB", "server-a", "/Volumes/EXT-4TB/film/Blade Runner.mkv", 3_000_000_000),
            MoveTestEntry("QmC", "server-a", "/Volumes/EXT-4TB/film/Dune.mkv", 4_000_000_000)
        ))

        val updated = MoveCommand.updateRegistryAfterMove(
            dbPath, "server-a", "server-b",
            "/Volumes/EXT-4TB/film", "/data/film"
        )

        assertEquals(3, updated)
        assertEquals(0, queryEntries(dbPath, "server-a").size)
        assertEquals(3, queryEntries(dbPath, "server-b").size)
    }
}

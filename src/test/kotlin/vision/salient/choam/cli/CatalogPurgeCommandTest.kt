package vision.salient.choam.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import vision.salient.sietch.core.DEFAULT_EXCLUDE_PATTERNS
import java.nio.file.FileSystems
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for CatalogPurgeCommand purge logic.
 * Since the command reads from a real DB, we test the purge logic
 * by setting up a unified registry DB and simulating the purge.
 */
class CatalogPurgeCommandTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createUnifiedRegistry(path: String, entries: List<PurgeTestEntry>) {
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

    data class PurgeTestEntry(
        val cid: String,
        val machine: String,
        val filePath: String,
        val fileSize: Long = 1024
    )

    /**
     * Simulates the purge logic from CatalogPurgeCommand:
     * reads all file_paths, matches against exclude patterns, deletes matches.
     */
    private fun executePurge(dbPath: String): Long {
        val choamExcludePatterns = listOf("._*")
        val allExcludePatterns = DEFAULT_EXCLUDE_PATTERNS + choamExcludePatterns
        val ignoreMatchers = allExcludePatterns.map { pattern ->
            FileSystems.getDefault().getPathMatcher("glob:$pattern")
        }

        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        val stmt = conn.createStatement()
        val rs = stmt.executeQuery("SELECT rowid, file_path FROM content_locations")
        val rowidsToDelete = mutableListOf<Long>()
        while (rs.next()) {
            val rowid = rs.getLong("rowid")
            val filePath = rs.getString("file_path")
            val filename = java.nio.file.Path.of(filePath.substringAfterLast("/").substringAfterLast("\\"))
            val pathSegments = filePath.split("/", "\\")

            val filenameExcluded = ignoreMatchers.any { it.matches(filename) }
            val dirExcluded = pathSegments.dropLast(1).any { segment ->
                if (segment.isEmpty()) return@any false
                val segPath = java.nio.file.Path.of(segment)
                ignoreMatchers.any { it.matches(segPath) }
            }

            if (filenameExcluded || dirExcluded) {
                rowidsToDelete.add(rowid)
            }
        }
        rs.close()

        if (rowidsToDelete.isEmpty()) {
            conn.close()
            return 0
        }

        conn.autoCommit = false
        val deleteStmt = conn.prepareStatement("DELETE FROM content_locations WHERE rowid = ?")
        for (rowid in rowidsToDelete) {
            deleteStmt.setLong(1, rowid)
            deleteStmt.executeUpdate()
        }
        conn.commit()
        conn.autoCommit = true
        deleteStmt.close()
        stmt.close()
        conn.close()
        return rowidsToDelete.size.toLong()
    }

    private fun countRows(dbPath: String): Long {
        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM content_locations")
        rs.next()
        val count = rs.getLong(1)
        rs.close()
        conn.close()
        return count
    }

    @Test
    fun `purge removes dot-underscore resource fork files`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, listOf(
            PurgeTestEntry("Qm1", "server-a", "/Volumes/EXT-4TB/movies/Aliens.mkv", 2_000_000_000),
            PurgeTestEntry("Qm2", "server-a", "/Volumes/EXT-4TB/movies/._Aliens.mkv", 4096),
            PurgeTestEntry("Qm3", "server-a", "/Volumes/EXT-4TB/photos/._photo.png", 4096)
        ))

        val deleted = executePurge(dbPath)
        assertEquals(2, deleted)
        assertEquals(1, countRows(dbPath))
    }

    @Test
    fun `purge removes DS_Store files`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, listOf(
            PurgeTestEntry("Qm1", "server-a", "/Volumes/EXT-4TB/movies/.DS_Store", 6148),
            PurgeTestEntry("Qm2", "server-a", "/Volumes/EXT-4TB/tv/.DS_Store", 6148),
            PurgeTestEntry("Qm3", "server-a", "/Volumes/EXT-4TB/movies/Aliens.mkv", 2_000_000_000)
        ))

        val deleted = executePurge(dbPath)
        assertEquals(2, deleted)
        assertEquals(1, countRows(dbPath))
    }

    @Test
    fun `purge removes Spotlight-V100 subtree entries`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, listOf(
            PurgeTestEntry("Qm1", "server-a", "/Volumes/EXT-4TB/.Spotlight-V100/Store-V2/abc/store.db", 50000),
            PurgeTestEntry("Qm2", "server-a", "/Volumes/EXT-4TB/.Spotlight-V100/VolumeConfiguration.plist", 1024),
            PurgeTestEntry("Qm3", "server-a", "/Volumes/EXT-4TB/movies/Aliens.mkv", 2_000_000_000)
        ))

        val deleted = executePurge(dbPath)
        assertEquals(2, deleted)
        assertEquals(1, countRows(dbPath))
    }

    @Test
    fun `purge removes tmp and part files`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, listOf(
            PurgeTestEntry("Qm1", "server-a", "/Volumes/EXT-4TB/download.tmp", 10000),
            PurgeTestEntry("Qm2", "server-a", "/Volumes/EXT-4TB/bigfile.part", 500000),
            PurgeTestEntry("Qm3", "server-a", "/Volumes/EXT-4TB/movies/Aliens.mkv", 2_000_000_000)
        ))

        val deleted = executePurge(dbPath)
        assertEquals(2, deleted)
        assertEquals(1, countRows(dbPath))
    }

    @Test
    fun `purge preserves real files`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, listOf(
            PurgeTestEntry("Qm1", "server-a", "/Volumes/EXT-4TB/movies/Aliens.mkv", 2_000_000_000),
            PurgeTestEntry("Qm2", "server-a", "/Volumes/EXT-4TB/docs/report.pdf", 500_000),
            PurgeTestEntry("Qm3", "server-a", "/Volumes/EXT-4TB/photos/vacation.jpg", 3_000_000),
            PurgeTestEntry("Qm4", "server-a", "/Volumes/EXT-4TB/music/song.mp3", 8_000_000),
            PurgeTestEntry("Qm5", "server-a", "/Volumes/EXT-4TB/code/main.kt", 5000)
        ))

        val deleted = executePurge(dbPath)
        assertEquals(0, deleted)
        assertEquals(5, countRows(dbPath))
    }

    @Test
    fun `purge on empty DB is safe`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, emptyList())

        val deleted = executePurge(dbPath)
        assertEquals(0, deleted)
        assertEquals(0, countRows(dbPath))
    }

    @Test
    fun `purge is idempotent - second run deletes 0`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, listOf(
            PurgeTestEntry("Qm1", "server-a", "/Volumes/EXT-4TB/movies/Aliens.mkv", 2_000_000_000),
            PurgeTestEntry("Qm2", "server-a", "/Volumes/EXT-4TB/movies/._Aliens.mkv", 4096),
            PurgeTestEntry("Qm3", "server-a", "/Volumes/EXT-4TB/.DS_Store", 6148),
            PurgeTestEntry("Qm4", "server-a", "/Volumes/EXT-4TB/download.tmp", 10000)
        ))

        val deleted1 = executePurge(dbPath)
        assertEquals(3, deleted1)
        assertEquals(1, countRows(dbPath))

        // Second purge — nothing left to delete
        val deleted2 = executePurge(dbPath)
        assertEquals(0, deleted2)
        assertEquals(1, countRows(dbPath))
    }

    @Test
    fun `purge row count before and after matches expectations`() {
        val dbPath = tempDir.resolve("unified.db").toString()

        val realFiles = listOf(
            PurgeTestEntry("Qm1", "server-a", "/Volumes/EXT-4TB/movies/Aliens.mkv", 2_000_000_000),
            PurgeTestEntry("Qm2", "server-a", "/Volumes/EXT-4TB/movies/Blade Runner.mkv", 3_000_000_000),
            PurgeTestEntry("Qm3", "server-b", "/Volumes/DATA/docs/report.pdf", 500_000)
        )
        val junkFiles = listOf(
            PurgeTestEntry("Qm4", "server-a", "/Volumes/EXT-4TB/movies/.DS_Store", 6148),
            PurgeTestEntry("Qm5", "server-a", "/Volumes/EXT-4TB/.Spotlight-V100/store.db", 50000),
            PurgeTestEntry("Qm6", "server-a", "/Volumes/EXT-4TB/movies/._Aliens.mkv", 4096),
            PurgeTestEntry("Qm7", "server-a", "/Volumes/EXT-4TB/temp.tmp", 10000),
            PurgeTestEntry("Qm8", "server-a", "/Volumes/EXT-4TB/download.part", 500000),
            PurgeTestEntry("Qm9", "server-a", "/Volumes/EXT-4TB/.fseventsd/0000000001", 1024),
            PurgeTestEntry("Qm10", "server-a", "/Volumes/EXT-4TB/.Trashes/old_file", 2048)
        )

        createUnifiedRegistry(dbPath, realFiles + junkFiles)
        val totalBefore = countRows(dbPath)
        assertEquals(10, totalBefore)

        val deleted = executePurge(dbPath)
        val totalAfter = countRows(dbPath)

        assertEquals(7, deleted)
        assertEquals(3, totalAfter)
        assertEquals(totalBefore - deleted, totalAfter)
    }

    @Test
    fun `purge removes fseventsd directory contents`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, listOf(
            PurgeTestEntry("Qm1", "server-a", "/Volumes/EXT-4TB/.fseventsd/0000000001", 1024),
            PurgeTestEntry("Qm2", "server-a", "/Volumes/EXT-4TB/.fseventsd/fseventsd-uuid", 36),
            PurgeTestEntry("Qm3", "server-a", "/Volumes/EXT-4TB/movies/Aliens.mkv", 2_000_000_000)
        ))

        val deleted = executePurge(dbPath)
        assertEquals(2, deleted)
        assertEquals(1, countRows(dbPath))
    }

    @Test
    fun `purge removes Trashes directory contents`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, listOf(
            PurgeTestEntry("Qm1", "server-a", "/Volumes/EXT-4TB/.Trashes/501/old_movie.mkv", 1_000_000),
            PurgeTestEntry("Qm2", "server-a", "/Volumes/EXT-4TB/movies/Aliens.mkv", 2_000_000_000)
        ))

        val deleted = executePurge(dbPath)
        assertEquals(1, deleted)
        assertEquals(1, countRows(dbPath))
    }

    @Test
    fun `purge handles mixed junk from multiple machines`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, listOf(
            // Server A real files
            PurgeTestEntry("Qm1", "server-a", "/Volumes/EXT-4TB/movies/Aliens.mkv", 2_000_000_000),
            // Server A junk
            PurgeTestEntry("Qm2", "server-a", "/Volumes/EXT-4TB/movies/._Aliens.mkv", 4096),
            PurgeTestEntry("Qm3", "server-a", "/Volumes/EXT-4TB/.DS_Store", 6148),
            // Server B real files
            PurgeTestEntry("Qm4", "server-b", "/Volumes/DATA/docs/report.pdf", 500_000),
            // Server B junk
            PurgeTestEntry("Qm5", "server-b", "/Volumes/DATA/docs/.DS_Store", 6148),
            PurgeTestEntry("Qm6", "server-b", "/Volumes/DATA/docs/._report.pdf", 4096)
        ))

        val deleted = executePurge(dbPath)
        assertEquals(4, deleted)
        assertEquals(2, countRows(dbPath))
    }

    @Test
    fun `purge removes TemporaryItems directory contents`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, listOf(
            PurgeTestEntry("Qm1", "server-a", "/Volumes/EXT-4TB/.TemporaryItems/cleanup.tmp", 1024),
            PurgeTestEntry("Qm2", "server-a", "/Volumes/EXT-4TB/movies/Aliens.mkv", 2_000_000_000)
        ))

        val deleted = executePurge(dbPath)
        assertEquals(1, deleted)
        assertEquals(1, countRows(dbPath))
    }

    @Test
    fun `purge removes Thumbs_db files`() {
        val dbPath = tempDir.resolve("unified.db").toString()
        createUnifiedRegistry(dbPath, listOf(
            PurgeTestEntry("Qm1", "server-a", "/Volumes/EXT-4TB/photos/Thumbs.db", 50000),
            PurgeTestEntry("Qm2", "server-a", "/Volumes/EXT-4TB/photos/vacation.jpg", 3_000_000)
        ))

        val deleted = executePurge(dbPath)
        assertEquals(1, deleted)
        assertEquals(1, countRows(dbPath))
    }
}

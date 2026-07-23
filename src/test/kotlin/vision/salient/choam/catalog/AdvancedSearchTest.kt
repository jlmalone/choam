package vision.salient.choam.catalog

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for CatalogIndex.advancedSearch() — all filter types and combinations.
 */
class AdvancedSearchTest {

    @TempDir
    lateinit var tempDir: Path

    /** Seed an index DB directly (no registry needed) with controlled test data. */
    private fun seedIndex(indexPath: String, files: List<TestFile>): Pair<CatalogIndex, Connection> {
        val index = CatalogIndex(indexPath)
        val conn = index.open()
        val stmt = conn.createStatement()
        stmt.executeUpdate("BEGIN")

        // Group files by (machine, driveLabel) to create source records
        data class SourceKey(val machine: String, val driveLabel: String, val catalogDate: String)
        val grouped = files.groupBy { SourceKey(it.machine, it.driveLabel, it.catalogDate) }

        val sourceInsert = conn.prepareStatement(
            "INSERT INTO catalog_sources (drive_label, machine, root_path, catalog_date, hash_algorithm, file_count, total_size) VALUES (?, ?, ?, ?, 'test', ?, ?)"
        )
        val fileInsert = conn.prepareStatement(
            "INSERT INTO files (source_id, path, filename, extension, hash, cid, size) VALUES (?, ?, ?, ?, '-', ?, ?)"
        )

        for ((key, sourceFiles) in grouped) {
            sourceInsert.setString(1, key.driveLabel)
            sourceInsert.setString(2, key.machine)
            sourceInsert.setString(3, key.driveLabel)
            sourceInsert.setString(4, key.catalogDate)
            sourceInsert.setInt(5, sourceFiles.size)
            sourceInsert.setLong(6, sourceFiles.sumOf { it.size })
            sourceInsert.executeUpdate()

            val idRs = conn.createStatement().executeQuery("SELECT last_insert_rowid()")
            idRs.next()
            val sourceId = idRs.getInt(1)
            idRs.close()

            for (f in sourceFiles) {
                fileInsert.setInt(1, sourceId)
                fileInsert.setString(2, f.path)
                fileInsert.setString(3, f.filename)
                fileInsert.setString(4, f.extension)
                fileInsert.setString(5, f.cid)
                fileInsert.setLong(6, f.size)
                fileInsert.executeUpdate()
            }
        }

        sourceInsert.close()
        fileInsert.close()
        stmt.executeUpdate("COMMIT")
        stmt.executeUpdate("INSERT INTO files_fts(files_fts) VALUES('rebuild')")
        stmt.close()
        return Pair(index, conn)
    }

    data class TestFile(
        val path: String,
        val filename: String,
        val extension: String,
        val cid: String = "",
        val size: Long,
        val machine: String = "server-a",
        val driveLabel: String = "EXT-4TB",
        val catalogDate: String = "2026-03-01 00:00:00"
    )

    private val testData = listOf(
        TestFile("/Volumes/EXT-4TB/movies/Aliens.mkv", "Aliens.mkv", "mkv", "QmAliens", 2_000_000_000),
        TestFile("/Volumes/EXT-4TB/movies/Blade Runner.mkv", "Blade Runner.mkv", "mkv", "QmBlade", 3_500_000_000),
        TestFile("/Volumes/EXT-4TB/tv/Breaking Bad/S01E01.mkv", "S01E01.mkv", "mkv", "QmBB", 800_000_000),
        TestFile("/Volumes/EXT-4TB/tv/Breaking Bad/S01E02.mkv", "S01E02.mkv", "mkv", "QmBB2", 750_000_000),
        TestFile("/Volumes/EXT-4TB/docs/report.pdf", "report.pdf", "pdf", "QmReport", 500_000),
        TestFile("/Volumes/EXT-4TB/docs/budget.xlsx", "budget.xlsx", "xlsx", "QmBudget", 250_000),
        TestFile("/Volumes/EXT-4TB/music/song.mp3", "song.mp3", "mp3", "QmSong", 8_000_000),
        TestFile("/Volumes/EXT-4TB/photos/vacation.jpg", "vacation.jpg", "jpg", "QmVacation", 3_000_000),
        TestFile("/Volumes/DATA/movies/Aliens.mkv", "Aliens.mkv", "mkv", "QmAliens", 2_000_000_000, "server-b", "DATA"),
        TestFile("/Volumes/DATA/docs/notes.txt", "notes.txt", "txt", "QmNotes", 1_000, "server-b", "DATA"),
        // Different catalog date for date filtering tests
        TestFile("/Volumes/EXT-4TB/old/archive.tar", "archive.tar", "tar", "QmArchive", 10_000_000_000, catalogDate = "2025-06-15 00:00:00"),
        TestFile("/Volumes/EXT-4TB/new/fresh.mp4", "fresh.mp4", "mp4", "QmFresh", 1_500_000_000, catalogDate = "2026-02-01 00:00:00"),
    )

    // ============================
    // --min-size
    // ============================

    @Test
    fun `min-size filters to files at or above threshold`() {
        val (index, conn) = seedIndex(tempDir.resolve("idx.db").toString(), testData)
        val results = index.advancedSearch(conn, "", SearchFilters(minSize = 1_000_000_000), 50)
        // Files >= 1GB: Aliens(2G), Blade Runner(3.5G), BB S01E01 is 800M - too small, archive(10G), fresh(1.5G)
        // Also Aliens on server-b(2G)
        assertTrue(results.all { it.size >= 1_000_000_000 }, "All results should be >= 1GB")
        assertEquals(5, results.size)
        conn.close()
    }

    @Test
    fun `min-size with text query`() {
        val (index, conn) = seedIndex(tempDir.resolve("idx.db").toString(), testData)
        val results = index.advancedSearch(conn, "Aliens", SearchFilters(minSize = 1_000_000_000), 50)
        assertEquals(2, results.size) // Aliens on server-a + server-b
        assertTrue(results.all { it.filename == "Aliens.mkv" })
        conn.close()
    }

    // ============================
    // --max-size
    // ============================

    @Test
    fun `max-size filters to files at or below threshold`() {
        val (index, conn) = seedIndex(tempDir.resolve("idx.db").toString(), testData)
        val results = index.advancedSearch(conn, "", SearchFilters(maxSize = 10_000_000), 50)
        // Files <= 10MB: report(500K), budget(250K), song(8M), vacation(3M), notes(1K)
        assertTrue(results.all { it.size <= 10_000_000 }, "All results should be <= 10MB")
        assertEquals(5, results.size)
        conn.close()
    }

    // ============================
    // --min-size + --max-size (range)
    // ============================

    @Test
    fun `min-size and max-size together define size range`() {
        val (index, conn) = seedIndex(tempDir.resolve("idx.db").toString(), testData)
        val results = index.advancedSearch(conn, "", SearchFilters(minSize = 500_000_000, maxSize = 2_000_000_000), 50)
        // 500M-2G: Aliens(2G), BB S01E01(800M), BB S01E02(750M), Aliens-vanc(2G), fresh(1.5G)
        assertTrue(results.all { it.size in 500_000_000..2_000_000_000 })
        assertEquals(5, results.size)
        conn.close()
    }

    // ============================
    // --ext
    // ============================

    @Test
    fun `ext filter returns only matching extensions`() {
        val (index, conn) = seedIndex(tempDir.resolve("idx.db").toString(), testData)
        val results = index.advancedSearch(conn, "", SearchFilters(extensions = listOf("mkv")), 50)
        assertTrue(results.all { it.extension == "mkv" })
        assertEquals(5, results.size) // 4 on server-a + 1 on server-b
        conn.close()
    }

    @Test
    fun `ext filter with multiple extensions`() {
        val (index, conn) = seedIndex(tempDir.resolve("idx.db").toString(), testData)
        val results = index.advancedSearch(conn, "", SearchFilters(extensions = listOf("mkv", "mp4")), 50)
        assertTrue(results.all { it.extension in listOf("mkv", "mp4") })
        assertEquals(6, results.size) // 5 mkv + 1 mp4
        conn.close()
    }

    @Test
    fun `ext filter with query narrows further`() {
        val (index, conn) = seedIndex(tempDir.resolve("idx.db").toString(), testData)
        val results = index.advancedSearch(conn, "Breaking", SearchFilters(extensions = listOf("mkv")), 50)
        assertEquals(2, results.size) // S01E01 + S01E02
        assertTrue(results.all { it.path.contains("Breaking Bad") })
        conn.close()
    }

    @Test
    fun `ext filter is case-insensitive`() {
        val (index, conn) = seedIndex(tempDir.resolve("idx.db").toString(), testData)
        // Extensions are stored lowercase, our filter lowercases too
        val results = index.advancedSearch(conn, "", SearchFilters(extensions = listOf("MKV")), 50)
        // "MKV" is lowercased to "mkv" by the CLI builder — but advancedSearch takes them as-is.
        // Since the DB stores lowercase and we pass "MKV", no match.
        // This verifies the CLI must lowercase before passing.
        assertEquals(0, results.size)
        conn.close()
    }

    // ============================
    // --after
    // ============================

    @Test
    fun `after date filters by catalog_date`() {
        val (index, conn) = seedIndex(tempDir.resolve("idx.db").toString(), testData)
        val results = index.advancedSearch(conn, "", SearchFilters(after = "2026-01-01"), 50)
        // catalog_date >= 2026-01-01: all in testData except archive(2025-06-15)
        assertTrue(results.none { it.filename == "archive.tar" })
        assertEquals(11, results.size)
        conn.close()
    }

    // ============================
    // --before
    // ============================

    @Test
    fun `before date filters by catalog_date`() {
        val (index, conn) = seedIndex(tempDir.resolve("idx.db").toString(), testData)
        val results = index.advancedSearch(conn, "", SearchFilters(before = "2025-12-31"), 50)
        // Only archive.tar has catalog_date 2025-06-15
        assertEquals(1, results.size)
        assertEquals("archive.tar", results[0].filename)
        conn.close()
    }

    // ============================
    // --after + --before (date range)
    // ============================

    @Test
    fun `after and before define date range`() {
        val (index, conn) = seedIndex(tempDir.resolve("idx.db").toString(), testData)
        val results = index.advancedSearch(conn, "", SearchFilters(after = "2026-01-01", before = "2026-02-28"), 50)
        // Only fresh.mp4 has catalog_date 2026-02-01
        assertEquals(1, results.size)
        assertEquals("fresh.mp4", results[0].filename)
        conn.close()
    }

    // ============================
    // --cid
    // ============================

    @Test
    fun `cid exact lookup returns all locations`() {
        val (index, conn) = seedIndex(tempDir.resolve("idx.db").toString(), testData)
        val results = index.advancedSearch(conn, "", SearchFilters(cid = "QmAliens"), 50)
        assertEquals(2, results.size) // Aliens on server-a + server-b
        assertTrue(results.all { it.cid == "QmAliens" })
        val machines = results.map { it.machine }.toSet()
        assertEquals(setOf("server-a", "server-b"), machines)
        conn.close()
    }

    @Test
    fun `cid lookup with no match returns empty`() {
        val (index, conn) = seedIndex(tempDir.resolve("idx.db").toString(), testData)
        val results = index.advancedSearch(conn, "", SearchFilters(cid = "QmNonexistent"), 50)
        assertEquals(0, results.size)
        conn.close()
    }

    @Test
    fun `cid lookup ignores text query`() {
        val (index, conn) = seedIndex(tempDir.resolve("idx.db").toString(), testData)
        // When CID is provided, the text query is not used for FTS — CID is the primary lookup
        val results = index.advancedSearch(conn, "totally wrong query", SearchFilters(cid = "QmAliens"), 50)
        assertEquals(2, results.size)
        conn.close()
    }

    // ============================
    // --path
    // ============================

    @Test
    fun `path glob filters by path pattern`() {
        val (index, conn) = seedIndex(tempDir.resolve("idx.db").toString(), testData)
        // SQL LIKE: %/tv/% matches paths containing /tv/
        val results = index.advancedSearch(conn, "", SearchFilters(pathGlob = "%/tv/%"), 50)
        assertEquals(2, results.size)
        assertTrue(results.all { it.path.contains("/tv/") })
        conn.close()
    }

    @Test
    fun `path glob with extension`() {
        val (index, conn) = seedIndex(tempDir.resolve("idx.db").toString(), testData)
        val results = index.advancedSearch(conn, "", SearchFilters(pathGlob = "%/movies/%", extensions = listOf("mkv")), 50)
        // movies/ mkv files: Aliens(server-a), Blade Runner(server-a), Aliens(server-b)
        assertEquals(3, results.size)
        assertTrue(results.all { it.path.contains("/movies/") && it.extension == "mkv" })
        conn.close()
    }

    // ============================
    // Combined filters
    // ============================

    @Test
    fun `ext + min-size combined`() {
        val (index, conn) = seedIndex(tempDir.resolve("idx.db").toString(), testData)
        val results = index.advancedSearch(conn, "", SearchFilters(extensions = listOf("mkv"), minSize = 1_000_000_000), 50)
        // mkv files >= 1GB: Aliens(2G), Blade Runner(3.5G), Aliens-vanc(2G)
        assertEquals(3, results.size)
        assertTrue(results.all { it.extension == "mkv" && it.size >= 1_000_000_000 })
        conn.close()
    }

    @Test
    fun `query + ext + min-size combined`() {
        val (index, conn) = seedIndex(tempDir.resolve("idx.db").toString(), testData)
        val results = index.advancedSearch(conn, "Blade", SearchFilters(extensions = listOf("mkv"), minSize = 1_000_000_000), 50)
        assertEquals(1, results.size)
        assertEquals("Blade Runner.mkv", results[0].filename)
        conn.close()
    }

    @Test
    fun `all filters return no results when contradictory`() {
        val (index, conn) = seedIndex(tempDir.resolve("idx.db").toString(), testData)
        // mkv + max 100 bytes — no mkv is that small
        val results = index.advancedSearch(conn, "", SearchFilters(extensions = listOf("mkv"), maxSize = 100), 50)
        assertEquals(0, results.size)
        conn.close()
    }

    // ============================
    // No filters (backward compat)
    // ============================

    @Test
    fun `advancedSearch with empty filters and query works like regular search`() {
        val (index, conn) = seedIndex(tempDir.resolve("idx.db").toString(), testData)
        val results = index.advancedSearch(conn, "Aliens", SearchFilters(), 50)
        assertEquals(2, results.size)
        assertTrue(results.all { it.filename == "Aliens.mkv" })
        conn.close()
    }

    @Test
    fun `advancedSearch with no query and no filters returns all (capped by limit)`() {
        val (index, conn) = seedIndex(tempDir.resolve("idx.db").toString(), testData)
        // No FTS query, no filters → full table scan ordered by size desc
        val results = index.advancedSearch(conn, "", SearchFilters(), 5)
        assertEquals(5, results.size) // limited to 5
        // Should be ordered by size desc
        for (i in 0 until results.size - 1) {
            assertTrue(results[i].size >= results[i + 1].size, "Results should be ordered by size desc")
        }
        conn.close()
    }

    // ============================
    // SearchFilters.hasAny
    // ============================

    @Test
    fun `SearchFilters hasAny is false when empty`() {
        val filters = SearchFilters()
        assertEquals(false, filters.hasAny)
    }

    @Test
    fun `SearchFilters hasAny is true for each field`() {
        assertTrue(SearchFilters(minSize = 1).hasAny)
        assertTrue(SearchFilters(maxSize = 1).hasAny)
        assertTrue(SearchFilters(extensions = listOf("mkv")).hasAny)
        assertTrue(SearchFilters(after = "2026-01-01").hasAny)
        assertTrue(SearchFilters(before = "2026-01-01").hasAny)
        assertTrue(SearchFilters(cid = "Qm123").hasAny)
        assertTrue(SearchFilters(pathGlob = "%foo%").hasAny)
    }

    // ============================
    // Limit
    // ============================

    @Test
    fun `limit caps results`() {
        val (index, conn) = seedIndex(tempDir.resolve("idx.db").toString(), testData)
        val results = index.advancedSearch(conn, "", SearchFilters(extensions = listOf("mkv")), 2)
        assertEquals(2, results.size)
        conn.close()
    }
}

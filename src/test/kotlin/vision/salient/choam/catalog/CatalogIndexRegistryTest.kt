package vision.salient.choam.catalog

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogIndexRegistryTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createUnifiedRegistry(path: String, entries: List<RegistryTestEntry>) {
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
            "INSERT INTO content_locations (cid, machine_name, file_path, file_size, last_synced_at) VALUES (?, ?, ?, ?, datetime('now'))"
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

    private fun createUnifiedRegistryWithTimestamps(path: String, entries: List<TimestampedRegistryEntry>) {
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
            "INSERT INTO content_locations (cid, machine_name, file_path, file_size, registered_at, last_synced_at) VALUES (?, ?, ?, ?, ?, datetime('now'))"
        )
        for (entry in entries) {
            insert.setString(1, entry.cid)
            insert.setString(2, entry.machine)
            insert.setString(3, entry.filePath)
            insert.setLong(4, entry.fileSize)
            insert.setString(5, entry.registeredAt)
            insert.executeUpdate()
        }
        insert.close()
        stmt.close()
        conn.close()
    }

    data class RegistryTestEntry(
        val cid: String,
        val machine: String,
        val filePath: String,
        val fileSize: Long = 1024
    )

    data class TimestampedRegistryEntry(
        val cid: String,
        val machine: String,
        val filePath: String,
        val fileSize: Long = 1024,
        val registeredAt: String
    )

    @Test
    fun `rebuilds FTS index from registry data`() {
        val registryPath = tempDir.resolve("unified.db").toString()
        val indexPath = tempDir.resolve("index.db").toString()

        createUnifiedRegistry(registryPath, listOf(
            RegistryTestEntry("QmAAA", "server-a", "/Volumes/EXT-4TB/movies/Aliens.mkv", 2_000_000_000),
            RegistryTestEntry("QmBBB", "server-a", "/Volumes/EXT-4TB/movies/Blade Runner.mkv", 3_000_000_000),
            RegistryTestEntry("QmCCC", "server-b", "/Volumes/DATA/docs/report.pdf", 500_000)
        ))

        val index = CatalogIndex(indexPath)
        val conn = index.open()
        val count = index.rebuildFromRegistry(conn, registryPath, emptyMap())

        assertEquals(3, count)

        // Verify FTS search works
        val results = index.search(conn, "Aliens", 10)
        assertEquals(1, results.size)
        assertEquals("Aliens.mkv", results[0].filename)

        conn.close()
    }

    @Test
    fun `derives drive_label from path prefix`() {
        val label = CatalogIndex.deriveDriveLabel(
            "/Volumes/EXT-4TB/movies/test.mkv",
            emptyMap(),
            "server-a"
        )
        assertEquals("EXT-4TB", label)
    }

    @Test
    fun `derives drive_label from config mount points`() {
        val mountPointMap = mapOf("/Volumes/MyDrive/" to "MyDrive")
        val label = CatalogIndex.deriveDriveLabel(
            "/Volumes/MyDrive/subfolder/file.txt",
            mountPointMap,
            "server-a"
        )
        assertEquals("MyDrive", label)
    }

    @Test
    fun `falls back to machine name for non-Volumes paths`() {
        val label = CatalogIndex.deriveDriveLabel(
            "/home/user/data/file.txt",
            emptyMap(),
            "server-a"
        )
        assertEquals("server-a", label)
    }

    @Test
    fun `groups by machine correctly`() {
        val registryPath = tempDir.resolve("unified.db").toString()
        val indexPath = tempDir.resolve("index.db").toString()

        createUnifiedRegistry(registryPath, listOf(
            RegistryTestEntry("QmAAA", "server-a", "/Volumes/EXT-4TB/file1.mkv"),
            RegistryTestEntry("QmBBB", "server-a", "/Volumes/EXT-4TB/file2.mkv"),
            RegistryTestEntry("QmCCC", "server-b", "/Volumes/DATA/file3.mkv"),
        ))

        val index = CatalogIndex(indexPath)
        val conn = index.open()
        index.rebuildFromRegistry(conn, registryPath, emptyMap())

        val stats = index.stats(conn)
        val serverASources = stats.sources.filter { it.machine == "server-a" }
        val serverBSources = stats.sources.filter { it.machine == "server-b" }

        assertTrue(serverASources.isNotEmpty(), "Should have server-a sources")
        assertTrue(serverBSources.isNotEmpty(), "Should have server-b sources")
        assertEquals(2, serverASources.sumOf { it.fileCount })
        assertEquals(1, serverBSources.sumOf { it.fileCount })

        conn.close()
    }

    @Test
    fun `handles missing drive config gracefully`() {
        val registryPath = tempDir.resolve("unified.db").toString()
        val indexPath = tempDir.resolve("index.db").toString()

        createUnifiedRegistry(registryPath, listOf(
            RegistryTestEntry("QmAAA", "server-a", "/Volumes/UNKNOWN_DRIVE/test.mkv")
        ))

        val index = CatalogIndex(indexPath)
        val conn = index.open()
        val count = index.rebuildFromRegistry(conn, registryPath, emptyMap())

        assertEquals(1, count)
        val stats = index.stats(conn)
        assertEquals("UNKNOWN_DRIVE", stats.sources[0].driveLabel)

        conn.close()
    }

    @Test
    fun `FTS search works after rebuild`() {
        val registryPath = tempDir.resolve("unified.db").toString()
        val indexPath = tempDir.resolve("index.db").toString()

        createUnifiedRegistry(registryPath, listOf(
            RegistryTestEntry("QmAAA", "server-a", "/Volumes/EXT-4TB/documentaries/isaac_arthur_megastructures.mkv", 5_000_000_000),
            RegistryTestEntry("QmBBB", "server-a", "/Volumes/EXT-4TB/documentaries/cosmos_s01e01.mkv", 3_000_000_000),
            RegistryTestEntry("QmCCC", "server-b", "/Volumes/DATA/talks/isaac_newton_lecture.mp4", 1_000_000_000)
        ))

        val index = CatalogIndex(indexPath)
        val conn = index.open()
        index.rebuildFromRegistry(conn, registryPath, emptyMap())

        val isaacResults = index.search(conn, "isaac", 10)
        assertEquals(2, isaacResults.size)

        val cosmosResults = index.search(conn, "cosmos", 10)
        assertEquals(1, cosmosResults.size)
        assertEquals("cosmos_s01e01.mkv", cosmosResults[0].filename)

        conn.close()
    }

    @Test
    fun `rebuildFromRegistry handles duplicate machine-path with different CIDs`() {
        val registryPath = tempDir.resolve("unified.db").toString()
        val indexPath = tempDir.resolve("index.db").toString()

        // Same (machine, path) with different CIDs — simulates .DS_Store or .Spotlight files
        // that get re-hashed with different content across runs.
        // The unified registry PK is (cid, machine_name, file_path) so both rows exist.
        val conn2 = DriverManager.getConnection("jdbc:sqlite:$registryPath")
        val stmt2 = conn2.createStatement()
        stmt2.executeUpdate("PRAGMA journal_mode=WAL")
        stmt2.executeUpdate("""
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
        val insert = conn2.prepareStatement(
            "INSERT INTO content_locations (cid, machine_name, file_path, file_size, registered_at, last_synced_at) VALUES (?, ?, ?, ?, ?, datetime('now'))"
        )
        // Two different CIDs for the same file path — older registered_at should be dropped
        insert.setString(1, "QmOLD111")
        insert.setString(2, "server-a")
        insert.setString(3, "/Volumes/EXT-4TB/movies/Aliens.mkv")
        insert.setLong(4, 2_000_000_000)
        insert.setString(5, "2026-01-01 10:00:00")
        insert.executeUpdate()

        insert.setString(1, "QmNEW222")
        insert.setString(2, "server-a")
        insert.setString(3, "/Volumes/EXT-4TB/movies/Aliens.mkv")
        insert.setLong(4, 2_000_000_000)
        insert.setString(5, "2026-02-15 10:00:00")
        insert.executeUpdate()

        // Normal non-duplicate entry
        insert.setString(1, "QmCCC333")
        insert.setString(2, "server-a")
        insert.setString(3, "/Volumes/EXT-4TB/movies/Blade Runner.mkv")
        insert.setLong(4, 3_000_000_000)
        insert.setString(5, "2026-02-15 10:00:00")
        insert.executeUpdate()
        insert.close()
        stmt2.close()
        conn2.close()

        val index = CatalogIndex(indexPath)
        val conn = index.open()
        // This should NOT crash with UNIQUE constraint violation
        val count = index.rebuildFromRegistry(conn, registryPath, emptyMap())

        // Should have 2 files (deduplicated), not 3
        assertEquals(2, count)

        // The kept entry should have the newer CID
        val results = index.search(conn, "Aliens", 10)
        assertEquals(1, results.size)
        assertEquals("QmNEW222", results[0].cid)

        conn.close()
    }

    @Test
    fun `rebuildFromRegistry filters ignored paths`() {
        val registryPath = tempDir.resolve("unified.db").toString()
        val indexPath = tempDir.resolve("index.db").toString()

        // Include macOS junk files that should be filtered out
        val conn2 = DriverManager.getConnection("jdbc:sqlite:$registryPath")
        val stmt2 = conn2.createStatement()
        stmt2.executeUpdate("PRAGMA journal_mode=WAL")
        stmt2.executeUpdate("""
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
        val insert = conn2.prepareStatement(
            "INSERT INTO content_locations (cid, machine_name, file_path, file_size, last_synced_at) VALUES (?, ?, ?, ?, datetime('now'))"
        )
        // Real file
        insert.setString(1, "QmAAA")
        insert.setString(2, "server-a")
        insert.setString(3, "/Volumes/EXT-4TB/movies/Aliens.mkv")
        insert.setLong(4, 2_000_000_000)
        insert.executeUpdate()

        // .DS_Store junk
        insert.setString(1, "QmDS1")
        insert.setString(2, "server-a")
        insert.setString(3, "/Volumes/EXT-4TB/movies/.DS_Store")
        insert.setLong(4, 6148)
        insert.executeUpdate()

        // .Spotlight-V100 directory contents
        insert.setString(1, "QmSPOT")
        insert.setString(2, "server-a")
        insert.setString(3, "/Volumes/EXT-4TB/.Spotlight-V100/Store-V2/abc/store.db")
        insert.setLong(4, 50000)
        insert.executeUpdate()

        // Temp file
        insert.setString(1, "QmTMP")
        insert.setString(2, "server-a")
        insert.setString(3, "/Volumes/EXT-4TB/download.tmp")
        insert.setLong(4, 10000)
        insert.executeUpdate()

        // macOS AppleDouble resource fork (._prefix)
        insert.setString(1, "QmRF1")
        insert.setString(2, "server-a")
        insert.setString(3, "/Volumes/EXT-4TB/movies/._Aliens.mkv")
        insert.setLong(4, 4096)
        insert.executeUpdate()

        // Another resource fork in a subdirectory
        insert.setString(1, "QmRF2")
        insert.setString(2, "server-a")
        insert.setString(3, "/Volumes/EXT-4TB/source_data/profile/._photo.png")
        insert.setLong(4, 4096)
        insert.executeUpdate()

        insert.close()
        stmt2.close()
        conn2.close()

        val index = CatalogIndex(indexPath)
        val conn = index.open()
        val count = index.rebuildFromRegistry(conn, registryPath, emptyMap())

        // Only the real file should survive — .DS_Store, .Spotlight-V100 contents, *.tmp, and ._* filtered
        assertEquals(1, count)
        val results = index.search(conn, "Aliens", 10)
        assertEquals(1, results.size)

        conn.close()
    }

    @Test
    fun `rebuildFromRegistry with machineNameMap remaps machine names in FTS index`() {
        val registryPath = tempDir.resolve("unified.db").toString()
        val indexPath = tempDir.resolve("index.db").toString()

        // Registry has old hostname "server-a-old"
        createUnifiedRegistry(registryPath, listOf(
            RegistryTestEntry("QmAAA", "server-a-old", "/Volumes/EXT-4TB/movies/Aliens.mkv", 2_000_000_000),
            RegistryTestEntry("QmBBB", "server-a-old", "/Volumes/EXT-4TB/movies/Blade Runner.mkv", 3_000_000_000),
            RegistryTestEntry("QmCCC", "server-b", "/Volumes/DATA/docs/report.pdf", 500_000)
        ))

        val machineNameMap = mapOf("server-a-old" to "server-a")
        val index = CatalogIndex(indexPath)
        val conn = index.open()
        val count = index.rebuildFromRegistry(conn, registryPath, emptyMap(), machineNameMap)

        assertEquals(3, count)

        // Verify FTS search returns remapped machine name
        val results = index.search(conn, "Aliens", 10)
        assertEquals(1, results.size)
        assertEquals("server-a", results[0].machine) // should be "server-a", not "server-a-old"

        // Server B should be untouched (not in the map)
        val reportResults = index.search(conn, "report", 10)
        assertEquals(1, reportResults.size)
        assertEquals("server-b", reportResults[0].machine)

        // Stats should show "server-a" not "server-a-old"
        val stats = index.stats(conn)
        val machineNames = stats.sources.map { it.machine }.toSet()
        assertTrue("server-a" in machineNames, "Should have 'server-a' in sources")
        assertTrue("server-a-old" !in machineNames, "Should NOT have 'server-a-old' in sources")

        conn.close()
    }

    @Test
    fun `incremental rebuild adds new entries`() {
        val registryPath = tempDir.resolve("unified.db").toString()
        val indexPath = tempDir.resolve("index.db").toString()

        // First round: 2 files
        createUnifiedRegistry(registryPath, listOf(
            RegistryTestEntry("QmAAA", "server-a", "/Volumes/EXT-4TB/file1.mkv"),
            RegistryTestEntry("QmBBB", "server-a", "/Volumes/EXT-4TB/file2.mkv")
        ))

        val index = CatalogIndex(indexPath)
        val conn = index.open()
        val count1 = index.rebuildFromRegistry(conn, registryPath, emptyMap())
        assertEquals(2, count1)

        // Add more data to registry and rebuild
        val regConn = DriverManager.getConnection("jdbc:sqlite:$registryPath")
        val insert = regConn.prepareStatement(
            "INSERT INTO content_locations (cid, machine_name, file_path, file_size, last_synced_at) VALUES (?, ?, ?, ?, datetime('now'))"
        )
        insert.setString(1, "QmCCC")
        insert.setString(2, "server-b")
        insert.setString(3, "/Volumes/DATA/file3.mkv")
        insert.setLong(4, 2048)
        insert.executeUpdate()
        insert.close()
        regConn.close()

        val count2 = index.rebuildFromRegistry(conn, registryPath, emptyMap())
        assertEquals(3, count2)

        // Verify search works for all 3 (search by extension token, since "file1"/"file2"/"file3" are distinct tokens)
        val results = index.search(conn, "mkv", 10)
        assertEquals(3, results.size)

        conn.close()
    }

    // ============================
    // NEW TESTS: machineNameMap with empty map (backward compat)
    // ============================

    @Test
    fun `rebuildFromRegistry with empty machineNameMap preserves original names`() {
        val registryPath = tempDir.resolve("unified.db").toString()
        val indexPath = tempDir.resolve("index.db").toString()

        createUnifiedRegistry(registryPath, listOf(
            RegistryTestEntry("QmAAA", "server-a-old", "/Volumes/EXT-4TB/movies/Aliens.mkv", 2_000_000_000),
            RegistryTestEntry("QmBBB", "server-b", "/Volumes/DATA/docs/report.pdf", 500_000)
        ))

        val index = CatalogIndex(indexPath)
        val conn = index.open()
        val count = index.rebuildFromRegistry(conn, registryPath, emptyMap(), emptyMap())

        assertEquals(2, count)

        val stats = index.stats(conn)
        val machineNames = stats.sources.map { it.machine }.toSet()
        assertTrue("server-a-old" in machineNames, "Original name 'server-a-old' should be preserved")
        assertTrue("server-b" in machineNames)

        conn.close()
    }

    // ============================
    // NEW TESTS: machineNameMap doesn't affect machines not in the map
    // ============================

    @Test
    fun `rebuildFromRegistry machineNameMap only remaps listed machines`() {
        val registryPath = tempDir.resolve("unified.db").toString()
        val indexPath = tempDir.resolve("index.db").toString()

        createUnifiedRegistry(registryPath, listOf(
            RegistryTestEntry("QmAAA", "server-a-old", "/Volumes/EXT-4TB/movies/Aliens.mkv"),
            RegistryTestEntry("QmBBB", "server-b", "/Volumes/DATA/docs/report.pdf"),
            RegistryTestEntry("QmCCC", "mini", "/Users/example/data/notes.txt")
        ))

        // Only remap server-a-old
        val machineNameMap = mapOf("server-a-old" to "server-a")
        val index = CatalogIndex(indexPath)
        val conn = index.open()
        index.rebuildFromRegistry(conn, registryPath, emptyMap(), machineNameMap)

        val stats = index.stats(conn)
        val machineNames = stats.sources.map { it.machine }.toSet()
        assertTrue("server-a" in machineNames)
        assertTrue("server-b" in machineNames, "server-b should be untouched")
        assertTrue("mini" in machineNames, "mini should be untouched")
        assertFalse("server-a-old" in machineNames)

        conn.close()
    }

    // ============================
    // NEW TESTS: filtering removes ALL exclude pattern types
    // ============================

    @Test
    fun `rebuildFromRegistry filters all exclude pattern types comprehensively`() {
        val registryPath = tempDir.resolve("unified.db").toString()
        val indexPath = tempDir.resolve("index.db").toString()

        val conn2 = DriverManager.getConnection("jdbc:sqlite:$registryPath")
        val stmt2 = conn2.createStatement()
        stmt2.executeUpdate("PRAGMA journal_mode=WAL")
        stmt2.executeUpdate("""
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
        val insert = conn2.prepareStatement(
            "INSERT INTO content_locations (cid, machine_name, file_path, file_size, last_synced_at) VALUES (?, ?, ?, ?, datetime('now'))"
        )

        // Real file (should survive)
        insert.setString(1, "QmReal"); insert.setString(2, "server-a"); insert.setString(3, "/Volumes/D/movies/Aliens.mkv"); insert.setLong(4, 2_000_000_000); insert.executeUpdate()

        // ._* resource forks
        insert.setString(1, "QmRF1"); insert.setString(2, "server-a"); insert.setString(3, "/Volumes/D/movies/._Aliens.mkv"); insert.setLong(4, 4096); insert.executeUpdate()

        // .DS_Store
        insert.setString(1, "QmDS"); insert.setString(2, "server-a"); insert.setString(3, "/Volumes/D/movies/.DS_Store"); insert.setLong(4, 6148); insert.executeUpdate()

        // .Spotlight-V100/*
        insert.setString(1, "QmSP"); insert.setString(2, "server-a"); insert.setString(3, "/Volumes/D/.Spotlight-V100/deep/store.db"); insert.setLong(4, 50000); insert.executeUpdate()

        // .fseventsd/*
        insert.setString(1, "QmFS"); insert.setString(2, "server-a"); insert.setString(3, "/Volumes/D/.fseventsd/00000001"); insert.setLong(4, 1024); insert.executeUpdate()

        // *.tmp
        insert.setString(1, "QmTMP"); insert.setString(2, "server-a"); insert.setString(3, "/Volumes/D/download.tmp"); insert.setLong(4, 10000); insert.executeUpdate()

        // *.part
        insert.setString(1, "QmPART"); insert.setString(2, "server-a"); insert.setString(3, "/Volumes/D/bigfile.part"); insert.setLong(4, 500000); insert.executeUpdate()

        // .Trashes
        insert.setString(1, "QmTR"); insert.setString(2, "server-a"); insert.setString(3, "/Volumes/D/.Trashes/501/old.mkv"); insert.setLong(4, 2000); insert.executeUpdate()

        // .TemporaryItems
        insert.setString(1, "QmTI"); insert.setString(2, "server-a"); insert.setString(3, "/Volumes/D/.TemporaryItems/item.dat"); insert.setLong(4, 1024); insert.executeUpdate()

        // .DocumentRevisions-V100
        insert.setString(1, "QmDR"); insert.setString(2, "server-a"); insert.setString(3, "/Volumes/D/.DocumentRevisions-V100/db.sqlite"); insert.setLong(4, 1024); insert.executeUpdate()

        // Thumbs.db
        insert.setString(1, "QmTB"); insert.setString(2, "server-a"); insert.setString(3, "/Volumes/D/photos/Thumbs.db"); insert.setLong(4, 50000); insert.executeUpdate()

        insert.close()
        stmt2.close()
        conn2.close()

        val index = CatalogIndex(indexPath)
        val conn = index.open()
        val count = index.rebuildFromRegistry(conn, registryPath, emptyMap())

        // Only the one real file should survive
        assertEquals(1, count)

        conn.close()
    }

    // ============================
    // NEW TESTS: dedup keeps latest registeredAt
    // ============================

    @Test
    fun `dedup keeps latest registeredAt when same machine-path has multiple CIDs`() {
        val registryPath = tempDir.resolve("unified.db").toString()
        val indexPath = tempDir.resolve("index.db").toString()

        val conn2 = DriverManager.getConnection("jdbc:sqlite:$registryPath")
        val stmt2 = conn2.createStatement()
        stmt2.executeUpdate("PRAGMA journal_mode=WAL")
        stmt2.executeUpdate("""
            CREATE TABLE content_locations (
                cid TEXT NOT NULL,
                machine_name TEXT NOT NULL,
                file_path TEXT NOT NULL,
                file_size INTEGER,
                verified_at TEXT,
                registered_at TEXT NOT NULL,
                last_synced_at TEXT NOT NULL DEFAULT (datetime('now')),
                PRIMARY KEY (cid, machine_name, file_path)
            )
        """)
        val insert = conn2.prepareStatement(
            "INSERT INTO content_locations (cid, machine_name, file_path, file_size, registered_at, last_synced_at) VALUES (?, ?, ?, ?, ?, datetime('now'))"
        )

        // Three CIDs for the same path — should keep only the one with latest registered_at
        insert.setString(1, "QmOLDEST"); insert.setString(2, "server-a"); insert.setString(3, "/Volumes/D/file.mkv")
        insert.setLong(4, 1000); insert.setString(5, "2025-01-01 00:00:00"); insert.executeUpdate()

        insert.setString(1, "QmNEWEST"); insert.setString(2, "server-a"); insert.setString(3, "/Volumes/D/file.mkv")
        insert.setLong(4, 1000); insert.setString(5, "2026-03-01 00:00:00"); insert.executeUpdate()

        insert.setString(1, "QmMIDDLE"); insert.setString(2, "server-a"); insert.setString(3, "/Volumes/D/file.mkv")
        insert.setLong(4, 1000); insert.setString(5, "2025-06-01 00:00:00"); insert.executeUpdate()

        // Different path — should be kept separately
        insert.setString(1, "QmOTHER"); insert.setString(2, "server-a"); insert.setString(3, "/Volumes/D/other.mkv")
        insert.setLong(4, 2000); insert.setString(5, "2026-01-01 00:00:00"); insert.executeUpdate()

        insert.close()
        stmt2.close()
        conn2.close()

        val index = CatalogIndex(indexPath)
        val conn = index.open()
        val count = index.rebuildFromRegistry(conn, registryPath, emptyMap())

        assertEquals(2, count) // file.mkv (deduped) + other.mkv

        val results = index.search(conn, "file", 10)
        assertEquals(1, results.size)
        assertEquals("QmNEWEST", results[0].cid) // kept the latest

        conn.close()
    }

    // ============================
    // NEW TESTS: FTS search works after rebuild with remap
    // ============================

    @Test
    fun `FTS search works after rebuild with remap and returns correct machine`() {
        val registryPath = tempDir.resolve("unified.db").toString()
        val indexPath = tempDir.resolve("index.db").toString()

        createUnifiedRegistry(registryPath, listOf(
            RegistryTestEntry("QmAAA", "server-a-old", "/Volumes/EXT-4TB/videos/sample_feature_one.mkv", 4_000_000_000),
            RegistryTestEntry("QmBBB", "vanc-old", "/Volumes/DATA/videos/sample_feature_two.mkv", 3_500_000_000),
            RegistryTestEntry("QmCCC", "mini", "/Users/example/sample_document.pdf", 1_000_000)
        ))

        val machineNameMap = mapOf("server-a-old" to "server-a", "vanc-old" to "server-b")
        val index = CatalogIndex(indexPath)
        val conn = index.open()
        index.rebuildFromRegistry(conn, registryPath, emptyMap(), machineNameMap)

        val results = index.search(conn, "sample", 10)
        assertEquals(3, results.size)

        val machines = results.map { it.machine }.toSet()
        assertTrue("server-a" in machines)
        assertTrue("server-b" in machines)
        assertTrue("mini" in machines) // not in map, preserved
        assertFalse("server-a-old" in machines)
        assertFalse("vanc-old" in machines)

        conn.close()
    }

    // ============================
    // NEW TESTS: stats show remapped machine names
    // ============================

    @Test
    fun `stats show remapped machine names after rebuild with machineNameMap`() {
        val registryPath = tempDir.resolve("unified.db").toString()
        val indexPath = tempDir.resolve("index.db").toString()

        createUnifiedRegistry(registryPath, listOf(
            RegistryTestEntry("QmAAA", "server-a-old", "/Volumes/EXT-4TB/file1.mkv"),
            RegistryTestEntry("QmBBB", "server-a-old", "/Volumes/EXT-4TB/file2.mkv"),
            RegistryTestEntry("QmCCC", "vanc-old", "/Volumes/DATA/file3.pdf"),
            RegistryTestEntry("QmDDD", "mini", "/Users/example/file4.txt")
        ))

        val machineNameMap = mapOf("server-a-old" to "server-a", "vanc-old" to "server-b")
        val index = CatalogIndex(indexPath)
        val conn = index.open()
        index.rebuildFromRegistry(conn, registryPath, emptyMap(), machineNameMap)

        val stats = index.stats(conn)
        val machineNames = stats.sources.map { it.machine }.toSet()
        assertTrue("server-a" in machineNames)
        assertTrue("server-b" in machineNames)
        assertTrue("mini" in machineNames)
        assertFalse("server-a-old" in machineNames)
        assertFalse("vanc-old" in machineNames)

        // File counts should be correct per remapped name
        val serverACount = stats.sources.filter { it.machine == "server-a" }.sumOf { it.fileCount }
        val serverBCount = stats.sources.filter { it.machine == "server-b" }.sumOf { it.fileCount }
        val miniCount = stats.sources.filter { it.machine == "mini" }.sumOf { it.fileCount }
        assertEquals(2, serverACount)
        assertEquals(1, serverBCount)
        assertEquals(1, miniCount)

        conn.close()
    }

    // ============================
    // NEW TESTS: rebuild from empty registry
    // ============================

    @Test
    fun `rebuildFromRegistry with empty registry returns 0`() {
        val registryPath = tempDir.resolve("unified.db").toString()
        val indexPath = tempDir.resolve("index.db").toString()

        createUnifiedRegistry(registryPath, emptyList())

        val index = CatalogIndex(indexPath)
        val conn = index.open()
        val count = index.rebuildFromRegistry(conn, registryPath, emptyMap())

        assertEquals(0, count)

        conn.close()
    }

    @Test
    fun `rebuildFromRegistry with nonexistent registry returns 0`() {
        val indexPath = tempDir.resolve("index.db").toString()
        val nonexistentPath = tempDir.resolve("nonexistent.db").toString()

        val index = CatalogIndex(indexPath)
        val conn = index.open()
        val count = index.rebuildFromRegistry(conn, nonexistentPath, emptyMap())

        assertEquals(0, count)

        conn.close()
    }

    // ============================
    // NEW TESTS: rebuild with multiple aliases
    // ============================

    @Test
    fun `rebuildFromRegistry with multiple aliases remaps all correctly`() {
        val registryPath = tempDir.resolve("unified.db").toString()
        val indexPath = tempDir.resolve("index.db").toString()

        createUnifiedRegistry(registryPath, listOf(
            RegistryTestEntry("QmAAA", "server-a-old", "/Volumes/EXT-4TB/file1.mkv"),
            RegistryTestEntry("QmBBB", "server-a-mac-mini", "/Volumes/EXT-4TB/file2.mkv"),
            RegistryTestEntry("QmCCC", "vanc-old", "/Volumes/DATA/file3.pdf"),
            RegistryTestEntry("QmDDD", "server-b-m4", "/Volumes/DATA/file4.txt")
        ))

        val machineNameMap = mapOf(
            "server-a-old" to "server-a",
            "server-a-mac-mini" to "server-a",
            "vanc-old" to "server-b",
            "server-b-m4" to "server-b"
        )
        val index = CatalogIndex(indexPath)
        val conn = index.open()
        val count = index.rebuildFromRegistry(conn, registryPath, emptyMap(), machineNameMap)

        assertEquals(4, count)

        val stats = index.stats(conn)
        val machineNames = stats.sources.map { it.machine }.toSet()
        assertEquals(setOf("server-a", "server-b"), machineNames)

        conn.close()
    }
}

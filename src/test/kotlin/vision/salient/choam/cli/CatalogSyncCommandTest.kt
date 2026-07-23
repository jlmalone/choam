package vision.salient.choam.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogSyncCommandTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createRemoteRegistry(path: String, entries: List<Triple<String, String, String>>): String {
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
                PRIMARY KEY (cid, machine_name, file_path)
            )
        """)
        val insert = conn.prepareStatement(
            "INSERT INTO content_locations (cid, machine_name, file_path, file_size, registered_at) VALUES (?, ?, ?, ?, datetime('now'))"
        )
        for ((cid, machine, filePath) in entries) {
            insert.setString(1, cid)
            insert.setString(2, machine)
            insert.setString(3, filePath)
            insert.setLong(4, 1024)
            insert.executeUpdate()
        }
        insert.close()
        stmt.close()
        conn.close()
        return path
    }

    private fun countUnifiedRows(unifiedDbPath: String): Long {
        val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedDbPath")
        val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM content_locations")
        rs.next()
        val count = rs.getLong(1)
        rs.close()
        conn.close()
        return count
    }

    private fun getUnifiedMachines(unifiedDbPath: String): Map<String, Long> {
        val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedDbPath")
        val rs = conn.createStatement().executeQuery(
            "SELECT machine_name, COUNT(*) as cnt FROM content_locations GROUP BY machine_name"
        )
        val map = mutableMapOf<String, Long>()
        while (rs.next()) {
            map[rs.getString("machine_name")] = rs.getLong("cnt")
        }
        rs.close()
        conn.close()
        return map
    }

    @Test
    fun `merge adds new rows from remote`() {
        val remotePath = tempDir.resolve("remote.db").toString()
        val unifiedPath = tempDir.resolve("unified.db").toString()

        createRemoteRegistry(remotePath, listOf(
            Triple("QmAAA", "server-a", "/Volumes/EXT-4TB/movies/Aliens.mkv"),
            Triple("QmBBB", "server-a", "/Volumes/EXT-4TB/movies/Blade Runner.mkv"),
            Triple("QmCCC", "server-a", "/Volumes/EXT-4TB/tv/Breaking Bad/S01E01.mkv")
        ))

        val merged = CatalogSyncCommand.mergeRegistry(remotePath, unifiedPath, "server-a")
        assertEquals(3, merged)
        assertEquals(3, countUnifiedRows(unifiedPath))
    }

    @Test
    fun `merge updates existing rows`() {
        val remotePath = tempDir.resolve("remote.db").toString()
        val unifiedPath = tempDir.resolve("unified.db").toString()

        createRemoteRegistry(remotePath, listOf(
            Triple("QmAAA", "server-a", "/Volumes/EXT-4TB/movies/Aliens.mkv")
        ))

        CatalogSyncCommand.mergeRegistry(remotePath, unifiedPath, "server-a")

        // Merge again - should update last_synced_at, not create duplicate
        val merged2 = CatalogSyncCommand.mergeRegistry(remotePath, unifiedPath, "server-a")
        assertEquals(1, merged2)
        assertEquals(1, countUnifiedRows(unifiedPath))

        // Verify last_synced_at was updated
        val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedPath")
        val rs = conn.createStatement().executeQuery("SELECT last_synced_at FROM content_locations")
        rs.next()
        val lastSynced = rs.getString("last_synced_at")
        assertTrue(lastSynced.isNotEmpty())
        rs.close()
        conn.close()
    }

    @Test
    fun `merge is idempotent`() {
        val remotePath = tempDir.resolve("remote.db").toString()
        val unifiedPath = tempDir.resolve("unified.db").toString()

        createRemoteRegistry(remotePath, listOf(
            Triple("QmAAA", "server-a", "/Volumes/EXT-4TB/movies/Aliens.mkv"),
            Triple("QmBBB", "server-a", "/Volumes/EXT-4TB/movies/Blade Runner.mkv")
        ))

        CatalogSyncCommand.mergeRegistry(remotePath, unifiedPath, "server-a")
        CatalogSyncCommand.mergeRegistry(remotePath, unifiedPath, "server-a")
        CatalogSyncCommand.mergeRegistry(remotePath, unifiedPath, "server-a")

        assertEquals(2, countUnifiedRows(unifiedPath))
    }

    @Test
    fun `merge preserves rows from other machines`() {
        val serverARemotePath = tempDir.resolve("server_a_remote.db").toString()
        val vancRemotePath = tempDir.resolve("vanc_remote.db").toString()
        val unifiedPath = tempDir.resolve("unified.db").toString()

        createRemoteRegistry(serverARemotePath, listOf(
            Triple("QmAAA", "server-a", "/Volumes/EXT-4TB/movies/Aliens.mkv"),
            Triple("QmBBB", "server-a", "/Volumes/EXT-4TB/movies/Blade Runner.mkv")
        ))

        createRemoteRegistry(vancRemotePath, listOf(
            Triple("QmCCC", "server-b", "/Volumes/DATA/docs/report.pdf"),
            Triple("QmDDD", "server-b", "/Volumes/DATA/docs/budget.xlsx")
        ))

        CatalogSyncCommand.mergeRegistry(serverARemotePath, unifiedPath, "server-a")
        CatalogSyncCommand.mergeRegistry(vancRemotePath, unifiedPath, "server-b")

        val machines = getUnifiedMachines(unifiedPath)
        assertEquals(2, machines["server-a"])
        assertEquals(2, machines["server-b"])
        assertEquals(4, countUnifiedRows(unifiedPath))

        // Re-sync server-a - should not affect server-b
        CatalogSyncCommand.mergeRegistry(serverARemotePath, unifiedPath, "server-a")
        val machines2 = getUnifiedMachines(unifiedPath)
        assertEquals(2, machines2["server-a"])
        assertEquals(2, machines2["server-b"])
    }

    @Test
    fun `staleness tracking works`() {
        val remotePath = tempDir.resolve("remote.db").toString()
        val unifiedPath = tempDir.resolve("unified.db").toString()

        createRemoteRegistry(remotePath, listOf(
            Triple("QmAAA", "server-a", "/Volumes/EXT-4TB/movies/Aliens.mkv")
        ))

        CatalogSyncCommand.mergeRegistry(remotePath, unifiedPath, "server-a")

        val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedPath")
        val rs = conn.createStatement().executeQuery("SELECT last_synced_at FROM content_locations WHERE cid='QmAAA'")
        assertTrue(rs.next())
        val lastSynced = rs.getString("last_synced_at")
        assertTrue(lastSynced.isNotEmpty(), "last_synced_at should be set")
        rs.close()
        conn.close()
    }

    @Test
    fun `empty remote DB produces no errors`() {
        val remotePath = tempDir.resolve("remote.db").toString()
        val unifiedPath = tempDir.resolve("unified.db").toString()

        createRemoteRegistry(remotePath, emptyList())

        val merged = CatalogSyncCommand.mergeRegistry(remotePath, unifiedPath, "server-a")
        assertEquals(0, merged)
    }

    // --- Helper for timestamp-aware registry entries ---

    data class TimestampedEntry(
        val cid: String,
        val machine: String,
        val filePath: String,
        val fileSize: Long = 1024,
        val registeredAt: String
    )

    private fun createRemoteRegistryWithTimestamps(path: String, entries: List<TimestampedEntry>): String {
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
                PRIMARY KEY (cid, machine_name, file_path)
            )
        """)
        val insert = conn.prepareStatement(
            "INSERT INTO content_locations (cid, machine_name, file_path, file_size, registered_at) VALUES (?, ?, ?, ?, ?)"
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
        return path
    }

    // --- Machine name remap tests ---

    @Test
    fun `merge with machineNameMap remaps alias to config key`() {
        val remotePath = tempDir.resolve("remote.db").toString()
        val unifiedPath = tempDir.resolve("unified.db").toString()

        // Remote registry has old hostname "server-a-old"
        createRemoteRegistry(remotePath, listOf(
            Triple("QmAAA", "server-a-old", "/Volumes/EXT-4TB/movies/Aliens.mkv"),
            Triple("QmBBB", "server-a-old", "/Volumes/EXT-4TB/movies/Blade Runner.mkv")
        ))

        val machineNameMap = mapOf("server-a-old" to "server-a")
        val merged = CatalogSyncCommand.mergeRegistry(remotePath, unifiedPath, "server-a", machineNameMap)
        assertEquals(2, merged)

        // Verify rows are stored with "server-a", not "server-a-old"
        val machines = getUnifiedMachines(unifiedPath)
        assertEquals(2, machines["server-a"])
        assertNull(machines["server-a-old"])
    }

    @Test
    fun `merge without machineNameMap preserves original names`() {
        val remotePath = tempDir.resolve("remote.db").toString()
        val unifiedPath = tempDir.resolve("unified.db").toString()

        createRemoteRegistry(remotePath, listOf(
            Triple("QmAAA", "server-a-old", "/Volumes/EXT-4TB/movies/Aliens.mkv")
        ))

        // Empty map = no remapping
        val merged = CatalogSyncCommand.mergeRegistry(remotePath, unifiedPath, "server-a")
        assertEquals(1, merged)

        val machines = getUnifiedMachines(unifiedPath)
        assertEquals(1, machines["server-a-old"])
        assertNull(machines["server-a"])
    }

    @Test
    fun `backfill canonicalizes existing alias rows`() {
        val remotePath = tempDir.resolve("remote.db").toString()
        val unifiedPath = tempDir.resolve("unified.db").toString()

        // First merge with no remap — rows stored as "server-a-old"
        createRemoteRegistry(remotePath, listOf(
            Triple("QmAAA", "server-a-old", "/Volumes/EXT-4TB/movies/Aliens.mkv"),
            Triple("QmBBB", "server-a-old", "/Volumes/EXT-4TB/movies/Blade Runner.mkv"),
            Triple("QmCCC", "server-b", "/Volumes/DATA/docs/report.pdf")
        ))
        CatalogSyncCommand.mergeRegistry(remotePath, unifiedPath, "server-a")

        // Verify "server-a-old" rows exist
        var machines = getUnifiedMachines(unifiedPath)
        assertEquals(2, machines["server-a-old"])
        assertEquals(1, machines["server-b"])

        // Now apply backfill via UPDATE
        val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedPath")
        val updateStmt = conn.prepareStatement(
            "UPDATE content_locations SET machine_name = ? WHERE machine_name = ?"
        )
        updateStmt.setString(1, "server-a")
        updateStmt.setString(2, "server-a-old")
        val updated = updateStmt.executeUpdate()
        assertEquals(2, updated)
        updateStmt.close()
        conn.close()

        // After backfill, all should be "server-a", none "server-a-old"
        machines = getUnifiedMachines(unifiedPath)
        assertEquals(2, machines["server-a"])
        assertNull(machines["server-a-old"])
        assertEquals(1, machines["server-b"]) // server-b untouched
    }

    // --- Delta sync (sinceTimestamp) tests ---

    @Test
    fun `merge with sinceTimestamp filters rows by registered_at`() {
        val remotePath = tempDir.resolve("remote.db").toString()
        val unifiedPath = tempDir.resolve("unified.db").toString()

        createRemoteRegistryWithTimestamps(remotePath, listOf(
            TimestampedEntry("QmAAA", "server-a", "/Volumes/EXT-4TB/old_file.mkv", registeredAt = "2026-01-01 10:00:00"),
            TimestampedEntry("QmBBB", "server-a", "/Volumes/EXT-4TB/new_file.mkv", registeredAt = "2026-02-15 10:00:00"),
            TimestampedEntry("QmCCC", "server-a", "/Volumes/EXT-4TB/newest_file.mkv", registeredAt = "2026-03-01 10:00:00")
        ))

        // Only merge rows from Feb 1 onwards
        val merged = CatalogSyncCommand.mergeRegistry(
            remotePath, unifiedPath, "server-a",
            sinceTimestamp = "2026-02-01 00:00:00"
        )
        assertEquals(2, merged) // QmBBB + QmCCC, not QmAAA
        assertEquals(2, countUnifiedRows(unifiedPath))
    }

    @Test
    fun `merge with sinceTimestamp at boundary second includes that seconds rows`() {
        val remotePath = tempDir.resolve("remote.db").toString()
        val unifiedPath = tempDir.resolve("unified.db").toString()

        createRemoteRegistryWithTimestamps(remotePath, listOf(
            TimestampedEntry("QmAAA", "server-a", "/Volumes/EXT-4TB/boundary.mkv", registeredAt = "2026-02-15 10:00:00"),
            TimestampedEntry("QmBBB", "server-a", "/Volumes/EXT-4TB/after.mkv", registeredAt = "2026-02-15 10:00:01"),
            TimestampedEntry("QmCCC", "server-a", "/Volumes/EXT-4TB/before.mkv", registeredAt = "2026-02-15 09:59:59")
        ))

        // sinceTimestamp = exact boundary second — should use >= so boundary is included
        val merged = CatalogSyncCommand.mergeRegistry(
            remotePath, unifiedPath, "server-a",
            sinceTimestamp = "2026-02-15 10:00:00"
        )
        assertEquals(2, merged) // QmAAA (boundary) + QmBBB (after), not QmCCC (before)
    }

    // --- Watermark read/write tests ---

    @Test
    fun `watermark read-write round-trip in sync_metadata`() {
        val unifiedPath = tempDir.resolve("unified.db").toString()

        // Initially no watermark
        assertNull(CatalogSyncCommand.getWatermark(unifiedPath, "server-a"))

        // Set a watermark
        CatalogSyncCommand.setWatermark(unifiedPath, "server-a", "2026-02-15 10:00:00")
        assertEquals("2026-02-15 10:00:00", CatalogSyncCommand.getWatermark(unifiedPath, "server-a"))

        // Update the watermark
        CatalogSyncCommand.setWatermark(unifiedPath, "server-a", "2026-03-01 12:00:00")
        assertEquals("2026-03-01 12:00:00", CatalogSyncCommand.getWatermark(unifiedPath, "server-a"))

        // Different machine has separate watermark
        assertNull(CatalogSyncCommand.getWatermark(unifiedPath, "server-b"))
        CatalogSyncCommand.setWatermark(unifiedPath, "server-b", "2026-02-20 08:30:00")
        assertEquals("2026-02-20 08:30:00", CatalogSyncCommand.getWatermark(unifiedPath, "server-b"))

        // Server A's watermark unchanged
        assertEquals("2026-03-01 12:00:00", CatalogSyncCommand.getWatermark(unifiedPath, "server-a"))
    }

    // ============================
    // NEW TESTS: machineNameMap with multiple aliases
    // ============================

    @Test
    fun `merge with multiple aliases remaps both old hostnames`() {
        val remotePath = tempDir.resolve("remote.db").toString()
        val unifiedPath = tempDir.resolve("unified.db").toString()

        createRemoteRegistry(remotePath, listOf(
            Triple("QmAAA", "server-a-old", "/Volumes/EXT-4TB/movies/Aliens.mkv"),
            Triple("QmBBB", "vanc-old", "/Volumes/DATA/docs/report.pdf"),
            Triple("QmCCC", "server-a-old", "/Volumes/EXT-4TB/tv/show.mkv"),
            Triple("QmDDD", "vanc-old", "/Volumes/DATA/docs/budget.xlsx"),
            Triple("QmEEE", "unknown-host", "/Volumes/OTHER/file.txt")
        ))

        val machineNameMap = mapOf(
            "server-a-old" to "server-a",
            "vanc-old" to "server-b"
        )
        val merged = CatalogSyncCommand.mergeRegistry(remotePath, unifiedPath, "sync", machineNameMap)
        assertEquals(5, merged)

        val machines = getUnifiedMachines(unifiedPath)
        assertEquals(2, machines["server-a"])
        assertEquals(2, machines["server-b"])
        assertEquals(1, machines["unknown-host"]) // not in map, preserved as-is
        assertNull(machines["server-a-old"])
        assertNull(machines["vanc-old"])
    }

    // ============================
    // NEW TESTS: sinceTimestamp edge cases
    // ============================

    @Test
    fun `sinceTimestamp with no matching rows returns 0`() {
        val remotePath = tempDir.resolve("remote.db").toString()
        val unifiedPath = tempDir.resolve("unified.db").toString()

        createRemoteRegistryWithTimestamps(remotePath, listOf(
            TimestampedEntry("QmAAA", "server-a", "/Volumes/EXT-4TB/old1.mkv", registeredAt = "2025-01-01 10:00:00"),
            TimestampedEntry("QmBBB", "server-a", "/Volumes/EXT-4TB/old2.mkv", registeredAt = "2025-06-15 10:00:00")
        ))

        // sinceTimestamp is far in the future — no rows match
        val merged = CatalogSyncCommand.mergeRegistry(
            remotePath, unifiedPath, "server-a",
            sinceTimestamp = "2099-01-01 00:00:00"
        )
        assertEquals(0, merged)
    }

    @Test
    fun `sinceTimestamp null performs full sync (backward compat)`() {
        val remotePath = tempDir.resolve("remote.db").toString()
        val unifiedPath = tempDir.resolve("unified.db").toString()

        createRemoteRegistryWithTimestamps(remotePath, listOf(
            TimestampedEntry("QmAAA", "server-a", "/Volumes/EXT-4TB/old1.mkv", registeredAt = "2020-01-01 10:00:00"),
            TimestampedEntry("QmBBB", "server-a", "/Volumes/EXT-4TB/old2.mkv", registeredAt = "2021-06-15 10:00:00"),
            TimestampedEntry("QmCCC", "server-a", "/Volumes/EXT-4TB/new.mkv", registeredAt = "2026-03-01 10:00:00")
        ))

        // sinceTimestamp = null → all rows merged
        val merged = CatalogSyncCommand.mergeRegistry(
            remotePath, unifiedPath, "server-a",
            sinceTimestamp = null
        )
        assertEquals(3, merged)
        assertEquals(3, countUnifiedRows(unifiedPath))
    }

    // ============================
    // NEW TESTS: large batch merge
    // ============================

    @Test
    fun `large batch merge with 1000+ rows`() {
        val remotePath = tempDir.resolve("remote.db").toString()
        val unifiedPath = tempDir.resolve("unified.db").toString()

        val entries = (1..1500).map { i ->
            Triple("QmCID_$i", "server-a", "/Volumes/EXT-4TB/files/file_$i.dat")
        }
        createRemoteRegistry(remotePath, entries)

        val merged = CatalogSyncCommand.mergeRegistry(remotePath, unifiedPath, "server-a")
        assertEquals(1500, merged)
        assertEquals(1500, countUnifiedRows(unifiedPath))

        // Merge again — idempotent, count is still 1500
        CatalogSyncCommand.mergeRegistry(remotePath, unifiedPath, "server-a")
        assertEquals(1500, countUnifiedRows(unifiedPath))
    }

    // ============================
    // NEW TESTS: sync_metadata table idempotency
    // ============================

    @Test
    fun `sync_metadata table creation is idempotent`() {
        val unifiedPath = tempDir.resolve("unified.db").toString()

        // Call setWatermark multiple times — each call ensures table exists
        CatalogSyncCommand.setWatermark(unifiedPath, "server-a", "2026-01-01 00:00:00")
        CatalogSyncCommand.setWatermark(unifiedPath, "server-a", "2026-02-01 00:00:00")
        CatalogSyncCommand.setWatermark(unifiedPath, "server-b", "2026-01-15 00:00:00")

        // Verify no duplicate tables or crashes
        assertEquals("2026-02-01 00:00:00", CatalogSyncCommand.getWatermark(unifiedPath, "server-a"))
        assertEquals("2026-01-15 00:00:00", CatalogSyncCommand.getWatermark(unifiedPath, "server-b"))

        // Also call mergeRegistry which also creates sync_metadata
        val remotePath = tempDir.resolve("remote.db").toString()
        createRemoteRegistry(remotePath, listOf(
            Triple("QmAAA", "server-a", "/Volumes/EXT-4TB/test.mkv")
        ))
        CatalogSyncCommand.mergeRegistry(remotePath, unifiedPath, "server-a")

        // Watermarks should survive the merge
        assertEquals("2026-02-01 00:00:00", CatalogSyncCommand.getWatermark(unifiedPath, "server-a"))
    }

    // ============================
    // NEW TESTS: getWatermark on fresh DB
    // ============================

    @Test
    fun `getWatermark on fresh DB returns null`() {
        val freshPath = tempDir.resolve("fresh.db").toString()
        // DB doesn't even exist yet
        assertNull(CatalogSyncCommand.getWatermark(freshPath, "server-a"))
        assertNull(CatalogSyncCommand.getWatermark(freshPath, "server-b"))
        assertNull(CatalogSyncCommand.getWatermark(freshPath, "nonexistent"))
    }

    // ============================
    // NEW TESTS: setWatermark overwrites previous value
    // ============================

    @Test
    fun `setWatermark overwrites previous value`() {
        val unifiedPath = tempDir.resolve("unified.db").toString()

        CatalogSyncCommand.setWatermark(unifiedPath, "server-a", "2026-01-01 00:00:00")
        assertEquals("2026-01-01 00:00:00", CatalogSyncCommand.getWatermark(unifiedPath, "server-a"))

        CatalogSyncCommand.setWatermark(unifiedPath, "server-a", "2026-02-01 00:00:00")
        assertEquals("2026-02-01 00:00:00", CatalogSyncCommand.getWatermark(unifiedPath, "server-a"))

        CatalogSyncCommand.setWatermark(unifiedPath, "server-a", "2026-03-01 00:00:00")
        assertEquals("2026-03-01 00:00:00", CatalogSyncCommand.getWatermark(unifiedPath, "server-a"))

        // Verify only one row exists for this key
        val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedPath")
        val rs = conn.createStatement().executeQuery(
            "SELECT COUNT(*) FROM sync_metadata WHERE key = 'watermark:server-a'"
        )
        rs.next()
        assertEquals(1, rs.getLong(1))
        rs.close()
        conn.close()
    }

    // ============================
    // NEW TESTS: watermark keys are per-machine
    // ============================

    @Test
    fun `watermark keys are per-machine and dont interfere`() {
        val unifiedPath = tempDir.resolve("unified.db").toString()

        val machines = listOf("server-a", "server-b", "mini", "server")
        for (machine in machines) {
            CatalogSyncCommand.setWatermark(unifiedPath, machine, "2026-01-01 ${machine.length}:00:00")
        }

        // Each machine has its own watermark
        for (machine in machines) {
            assertEquals("2026-01-01 ${machine.length}:00:00", CatalogSyncCommand.getWatermark(unifiedPath, machine))
        }

        // Update one, others unchanged
        CatalogSyncCommand.setWatermark(unifiedPath, "server-a", "2026-12-31 23:59:59")
        assertEquals("2026-12-31 23:59:59", CatalogSyncCommand.getWatermark(unifiedPath, "server-a"))
        assertEquals("2026-01-01 8:00:00", CatalogSyncCommand.getWatermark(unifiedPath, "server-b"))
        assertEquals("2026-01-01 4:00:00", CatalogSyncCommand.getWatermark(unifiedPath, "mini"))
        assertEquals("2026-01-01 6:00:00", CatalogSyncCommand.getWatermark(unifiedPath, "server"))
    }
}

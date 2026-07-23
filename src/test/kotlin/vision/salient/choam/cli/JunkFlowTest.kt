package vision.salient.choam.cli

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Extended tests for JunkCommand — mark/unjunk/re-mark flows,
 * multi-CID purge, purge history logging, lookupCid edge cases.
 */
class JunkFlowTest {

    @TempDir
    lateinit var tempDir: Path

    // ===========================================
    // Mark → Unjunk → Re-mark lifecycle
    // ===========================================

    @Test
    fun `full lifecycle mark then unjunk then re-mark`() {
        val conn = createDb()
        ensureJunkTable(conn)

        // Mark
        conn.createStatement().executeUpdate("INSERT INTO junk_list (cid, reason, file_sample) VALUES ('QmCycle', 'test', 'file.mkv')")
        assertEquals(1, junkCount(conn))

        // Unjunk
        conn.createStatement().executeUpdate("DELETE FROM junk_list WHERE cid = 'QmCycle'")
        assertEquals(0, junkCount(conn))

        // Re-mark (should succeed since PK is available again)
        conn.createStatement().executeUpdate("INSERT INTO junk_list (cid, reason, file_sample) VALUES ('QmCycle', 'changed mind', 'file.mkv')")
        assertEquals(1, junkCount(conn))

        // Verify reason updated
        val rs = conn.createStatement().executeQuery("SELECT reason FROM junk_list WHERE cid = 'QmCycle'")
        rs.next()
        assertEquals("changed mind", rs.getString("reason"))
        rs.close(); conn.close()
    }

    @Test
    fun `mark sets auto-timestamp`() {
        val conn = createDb()
        ensureJunkTable(conn)

        conn.createStatement().executeUpdate("INSERT INTO junk_list (cid, file_sample) VALUES ('QmTS', 'f.mkv')")

        val rs = conn.createStatement().executeQuery("SELECT marked_at FROM junk_list WHERE cid = 'QmTS'")
        rs.next()
        val markedAt = rs.getString("marked_at")
        assertNotNull(markedAt)
        assertTrue(markedAt.contains("20")) // Should be a datetime string
        rs.close(); conn.close()
    }

    // ===========================================
    // lookupCid edge cases
    // ===========================================

    @Test
    fun `lookupCid with same CID on same machine different paths`() {
        val conn = createRegistryDb()
        insertLocation(conn, "QmDup", "server-a", "/path1/file.mkv", 1000)
        insertLocation(conn, "QmDup", "server-a", "/path2/file.mkv", 1000)

        val locations = lookupCid(conn, "QmDup")
        assertEquals(2, locations.size)
        assertTrue(locations.all { it.machineName == "server-a" })
        assertEquals(setOf("/path1/file.mkv", "/path2/file.mkv"), locations.map { it.filePath }.toSet())
        conn.close()
    }

    @Test
    fun `lookupCid returns correct file sizes`() {
        val conn = createRegistryDb()
        insertLocation(conn, "QmBig", "server-a", "/big.mkv", 4_000_000_000)

        val locations = lookupCid(conn, "QmBig")
        assertEquals(1, locations.size)
        assertEquals(4_000_000_000, locations[0].fileSize)
        conn.close()
    }

    @Test
    fun `lookupCid with empty CID returns empty`() {
        val conn = createRegistryDb()
        insertLocation(conn, "QmReal", "server-a", "/real.mkv", 100)

        assertEquals(0, lookupCid(conn, "").size)
        conn.close()
    }

    @Test
    fun `lookupCid is case-sensitive`() {
        val conn = createRegistryDb()
        insertLocation(conn, "QmCaseSensitive", "server-a", "/a.mkv", 100)

        assertEquals(1, lookupCid(conn, "QmCaseSensitive").size)
        assertEquals(0, lookupCid(conn, "qmcasesensitive").size) // wrong case
        conn.close()
    }

    // ===========================================
    // Multi-CID purge simulation
    // ===========================================

    @Test
    fun `purge multiple CIDs removes all from both tables`() {
        val conn = createRegistryDb()
        ensureJunkTable(conn)

        // Create 3 CIDs across 2 machines
        insertLocation(conn, "QmA", "server-a", "/a.mkv", 1000)
        insertLocation(conn, "QmA", "server-b", "/a.mkv", 1000)
        insertLocation(conn, "QmB", "server-a", "/b.mkv", 2000)
        insertLocation(conn, "QmC", "server-a", "/c.mkv", 3000)

        // Mark all as junk
        conn.createStatement().executeUpdate("INSERT INTO junk_list (cid, file_sample) VALUES ('QmA', 'a.mkv')")
        conn.createStatement().executeUpdate("INSERT INTO junk_list (cid, file_sample) VALUES ('QmB', 'b.mkv')")
        conn.createStatement().executeUpdate("INSERT INTO junk_list (cid, file_sample) VALUES ('QmC', 'c.mkv')")

        // Simulate purge — get all junk CIDs, delete from both tables
        val junkRs = conn.createStatement().executeQuery("SELECT cid FROM junk_list")
        val junkCids = mutableListOf<String>()
        while (junkRs.next()) junkCids.add(junkRs.getString("cid"))
        junkRs.close()

        for (cid in junkCids) {
            conn.createStatement().executeUpdate("DELETE FROM content_locations WHERE cid = '$cid'")
            conn.createStatement().executeUpdate("DELETE FROM junk_list WHERE cid = '$cid'")
        }

        // Verify everything is gone
        assertEquals(0, junkCount(conn))
        val locRs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM content_locations")
        locRs.next(); assertEquals(0, locRs.getInt(1)); locRs.close()
        conn.close()
    }

    @Test
    fun `purge leaves non-junked CIDs untouched`() {
        val conn = createRegistryDb()
        ensureJunkTable(conn)

        insertLocation(conn, "QmJunked", "server-a", "/junked.mkv", 1000)
        insertLocation(conn, "QmSafe", "server-a", "/safe.mkv", 2000)
        conn.createStatement().executeUpdate("INSERT INTO junk_list (cid, file_sample) VALUES ('QmJunked', 'junked.mkv')")

        // Purge only junked
        conn.createStatement().executeUpdate("DELETE FROM content_locations WHERE cid = 'QmJunked'")
        conn.createStatement().executeUpdate("DELETE FROM junk_list WHERE cid = 'QmJunked'")

        // QmSafe still in registry
        val safeRs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM content_locations WHERE cid = 'QmSafe'")
        safeRs.next(); assertEquals(1, safeRs.getInt(1)); safeRs.close()
        conn.close()
    }

    // ===========================================
    // PurgeLogEntry serialization
    // ===========================================

    @Test
    fun `PurgeLogEntry round-trip serialization`() {
        val entry = PurgeLogEntry(
            cid = "QmPurged",
            fileSample = "movie.mkv",
            reason = "duplicate",
            purgedAt = "2026-03-03T12:00:00",
            deletedFrom = listOf("server-a:/a.mkv", "server-b:/b.mkv")
        )
        val json = Json.encodeToString(PurgeLogEntry.serializer(), entry)
        val decoded = Json.decodeFromString(PurgeLogEntry.serializer(), json)

        assertEquals(entry.cid, decoded.cid)
        assertEquals(entry.fileSample, decoded.fileSample)
        assertEquals(entry.reason, decoded.reason)
        assertEquals(entry.deletedFrom, decoded.deletedFrom)
    }

    @Test
    fun `PurgeLogEntry with empty deletedFrom serializes`() {
        val entry = PurgeLogEntry(cid = "QmX", fileSample = "x", reason = "", purgedAt = "now", deletedFrom = emptyList())
        val json = Json.encodeToString(PurgeLogEntry.serializer(), entry)
        assertTrue(json.contains("[]"))
    }

    @Test
    fun `multiple PurgeLogEntries in JSONL format`() {
        val logFile = tempDir.resolve("purge.jsonl").toFile()
        val compactJson = Json { prettyPrint = false }

        val e1 = compactJson.encodeToString(PurgeLogEntry.serializer(),
            PurgeLogEntry("Qm1", "a.mkv", "dup", "2026-03-01", listOf("server-a:/a")))
        val e2 = compactJson.encodeToString(PurgeLogEntry.serializer(),
            PurgeLogEntry("Qm2", "b.mkv", "", "2026-03-02", listOf("van:/b")))
        val e3 = compactJson.encodeToString(PurgeLogEntry.serializer(),
            PurgeLogEntry("Qm3", "c.mkv", "corrupt", "2026-03-03", listOf("server-a:/c", "van:/c")))

        logFile.writeText("$e1\n$e2\n$e3\n")

        val lines = logFile.readLines().filter { it.isNotBlank() }
        assertEquals(3, lines.size)

        // Each line should parse independently
        for (line in lines) {
            val parsed = compactJson.decodeFromString(PurgeLogEntry.serializer(), line)
            assertTrue(parsed.cid.startsWith("Qm"))
        }
    }

    // ===========================================
    // Junk list queries
    // ===========================================

    @Test
    fun `junk list ordered by marked_at ascending`() {
        val conn = createDb()
        ensureJunkTable(conn)

        // Insert with explicit timestamps to control order
        conn.createStatement().executeUpdate(
            "INSERT INTO junk_list (cid, file_sample, marked_at) VALUES ('QmOld', 'old.mkv', '2026-01-01 00:00:00')"
        )
        conn.createStatement().executeUpdate(
            "INSERT INTO junk_list (cid, file_sample, marked_at) VALUES ('QmNew', 'new.mkv', '2026-03-03 00:00:00')"
        )

        val rs = conn.createStatement().executeQuery("SELECT cid FROM junk_list ORDER BY marked_at")
        rs.next(); assertEquals("QmOld", rs.getString("cid"))
        rs.next(); assertEquals("QmNew", rs.getString("cid"))
        rs.close(); conn.close()
    }

    @Test
    fun `junk with null reason queries correctly`() {
        val conn = createDb()
        ensureJunkTable(conn)

        conn.createStatement().executeUpdate("INSERT INTO junk_list (cid, file_sample) VALUES ('QmNoReason', 'f.mkv')")

        val rs = conn.createStatement().executeQuery("SELECT reason FROM junk_list WHERE cid = 'QmNoReason'")
        rs.next()
        assertNull(rs.getString("reason"))
        rs.close(); conn.close()
    }

    // ===========================================
    // Helpers
    // ===========================================

    private fun createDb(): Connection {
        val path = tempDir.resolve("junk_${System.nanoTime()}.db").toString()
        return DriverManager.getConnection("jdbc:sqlite:$path")
    }

    private fun createRegistryDb(): Connection {
        val conn = createDb()
        conn.createStatement().executeUpdate("""
            CREATE TABLE IF NOT EXISTS content_locations (
                cid TEXT NOT NULL, machine_name TEXT NOT NULL, file_path TEXT NOT NULL,
                file_size INTEGER, verified_at TEXT,
                registered_at TEXT NOT NULL DEFAULT (datetime('now')),
                last_synced_at TEXT NOT NULL DEFAULT (datetime('now')),
                PRIMARY KEY (cid, machine_name, file_path)
            )
        """)
        return conn
    }

    private fun insertLocation(conn: Connection, cid: String, machine: String, path: String, size: Long) {
        conn.prepareStatement("INSERT INTO content_locations (cid, machine_name, file_path, file_size) VALUES (?, ?, ?, ?)").apply {
            setString(1, cid); setString(2, machine); setString(3, path); setLong(4, size)
            executeUpdate(); close()
        }
    }

    private fun junkCount(conn: Connection): Int {
        val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM junk_list")
        rs.next(); val c = rs.getInt(1); rs.close(); return c
    }
}

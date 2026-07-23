package vision.salient.choam.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import vision.salient.choam.config.*
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Comprehensive tests for Phase 4 ReportCommand — health dashboard logic.
 *
 * Covers: alias map, coverage queries, dedup-aware counting, risk detection,
 * geographic diversity, content classification, extension extraction edge cases,
 * staleness calculation, dedup detection, recommendations, and integration scenarios.
 */
class ReportCommandTest {

    @TempDir
    lateinit var tempDir: Path

    // ===========================================
    // Alias map building
    // ===========================================

    @Test
    fun `buildAliasMap extracts aliases from all machines`() {
        val config = buildTestConfig()
        val map = ReportCommand.buildAliasMap(config)
        assertEquals("server-a", map["server-a-old"])
        assertEquals(1, map.size)
    }

    @Test
    fun `buildAliasMap returns empty for config with no aliases`() {
        val config = ChoamConfig(
            machines = mapOf(
                "local" to MachineProfile(name = "local", hostname = "h", type = MachineType.DESKTOP, repositories = emptyMap())
            )
        )
        assertTrue(ReportCommand.buildAliasMap(config).isEmpty())
    }

    @Test
    fun `buildAliasMap handles multiple aliases per machine`() {
        val config = ChoamConfig(
            machines = mapOf(
                "server-a" to MachineProfile(
                    name = "server-a", hostname = "h", type = MachineType.DESKTOP,
                    repositories = emptyMap(), aliases = listOf("server-a-old", "server-a-mac-mini-old", "server-a-m2")
                )
            )
        )
        val map = ReportCommand.buildAliasMap(config)
        assertEquals(3, map.size)
        assertEquals("server-a", map["server-a-old"])
        assertEquals("server-a", map["server-a-mac-mini-old"])
        assertEquals("server-a", map["server-a-m2"])
    }

    @Test
    fun `buildAliasMap handles aliases across multiple machines`() {
        val config = ChoamConfig(
            machines = mapOf(
                "server-a" to MachineProfile(name = "server-a", hostname = "h1", type = MachineType.DESKTOP,
                    repositories = emptyMap(), aliases = listOf("server-a-old")),
                "server-b" to MachineProfile(name = "server-b", hostname = "h2", type = MachineType.DESKTOP,
                    repositories = emptyMap(), aliases = listOf("van-m4", "m4"))
            )
        )
        val map = ReportCommand.buildAliasMap(config)
        assertEquals(3, map.size)
        assertEquals("server-a", map["server-a-old"])
        assertEquals("server-b", map["van-m4"])
        assertEquals("server-b", map["m4"])
    }

    // ===========================================
    // Coverage queries
    // ===========================================

    @Test
    fun `coverage counts total files and unique CIDs`() {
        val conn = createRegistryDb()
        insert(conn, "QmA", "server-a", "/a.mkv", 1_000_000)
        insert(conn, "QmA", "server-b", "/a.mkv", 1_000_000)
        insert(conn, "QmB", "server-a", "/b.mkv", 2_000_000)

        val rs = conn.createStatement().executeQuery("""
            SELECT COUNT(*) as total_files, COUNT(DISTINCT cid) as unique_cids,
                   SUM(file_size) as total_size, COUNT(DISTINCT machine_name) as machine_count
            FROM content_locations
        """)
        rs.next()
        assertEquals(3, rs.getLong("total_files"))
        assertEquals(2, rs.getLong("unique_cids"))
        assertEquals(4_000_000, rs.getLong("total_size"))
        assertEquals(2, rs.getInt("machine_count"))
        rs.close(); conn.close()
    }

    @Test
    fun `empty registry produces zero counts`() {
        val conn = createRegistryDb()
        val rs = conn.createStatement().executeQuery(
            "SELECT COUNT(*) as c, COUNT(DISTINCT cid) as u, COALESCE(SUM(file_size),0) as s FROM content_locations"
        )
        rs.next()
        assertEquals(0, rs.getLong("c"))
        assertEquals(0, rs.getLong("u"))
        assertEquals(0, rs.getLong("s"))
        rs.close(); conn.close()
    }

    @Test
    fun `multi-copy CID counting is dedup-aware`() {
        val conn = createRegistryDb()
        insert(conn, "QmA", "server-a", "/a.mkv", 1000)
        insert(conn, "QmA", "server-b", "/a.mkv", 1000)
        insert(conn, "QmB", "server-a", "/b.mkv", 2000)
        insert(conn, "QmC", "server-a", "/c.mkv", 3000)
        insert(conn, "QmC", "server-b", "/c.mkv", 3000)
        insert(conn, "QmC", "local", "/c.mkv", 3000)

        val rs = conn.createStatement().executeQuery("""
            SELECT COUNT(*) FROM (
                SELECT cid FROM content_locations GROUP BY cid HAVING COUNT(DISTINCT machine_name) >= 2
            )
        """)
        rs.next()
        assertEquals(2, rs.getInt(1))  // QmA + QmC
        rs.close(); conn.close()
    }

    @Test
    fun `backup percentage calculation with zero CIDs returns zero`() {
        val uniqueCids = 0L
        val multiCopy = 0L
        val pct = if (uniqueCids > 0) "%.1f%%".format(multiCopy * 100.0 / uniqueCids) else "0%"
        assertEquals("0%", pct)
    }

    @Test
    fun `backup percentage calculation with partial replication`() {
        val uniqueCids = 100L
        val multiCopy = 25L
        val pct = "%.1f%%".format(multiCopy * 100.0 / uniqueCids)
        assertEquals("25.0%", pct)
    }

    @Test
    fun `backup percentage calculation with full replication`() {
        val uniqueCids = 50L
        val multiCopy = 50L
        val pct = "%.1f%%".format(multiCopy * 100.0 / uniqueCids)
        assertEquals("100.0%", pct)
    }

    // ===========================================
    // Risk detection
    // ===========================================

    @Test
    fun `at-risk identifies single-copy files over 100MB`() {
        val conn = createRegistryDb()
        val bigSize = 200_000_000L
        insert(conn, "QmBig", "server-a", "/big.mkv", bigSize)
        insert(conn, "QmSmall", "server-a", "/small.txt", 100)
        insert(conn, "QmSafe", "server-a", "/safe.mkv", bigSize)
        insert(conn, "QmSafe", "server-b", "/safe.mkv", bigSize)

        val rs = conn.createStatement().executeQuery("""
            SELECT COUNT(*) as cnt, COALESCE(SUM(file_size), 0) as sz FROM (
                SELECT cid, MAX(file_size) as file_size FROM content_locations
                WHERE file_size > 104857600
                GROUP BY cid HAVING COUNT(DISTINCT machine_name) = 1
            )
        """)
        rs.next()
        assertEquals(1, rs.getLong("cnt"))
        assertEquals(bigSize, rs.getLong("sz"))
        rs.close(); conn.close()
    }

    @Test
    fun `no at-risk when all large files are replicated`() {
        val conn = createRegistryDb()
        insert(conn, "QmBig", "server-a", "/big.mkv", 500_000_000)
        insert(conn, "QmBig", "server-b", "/big.mkv", 500_000_000)

        val rs = conn.createStatement().executeQuery("""
            SELECT COUNT(*) FROM (
                SELECT cid FROM content_locations WHERE file_size > 104857600
                GROUP BY cid HAVING COUNT(DISTINCT machine_name) = 1
            )
        """)
        rs.next()
        assertEquals(0, rs.getInt(1))
        rs.close(); conn.close()
    }

    @Test
    fun `at-risk excludes files exactly at 100MB boundary`() {
        val conn = createRegistryDb()
        insert(conn, "QmExact", "server-a", "/exact.mkv", 104857600)  // exactly 100MB

        val rs = conn.createStatement().executeQuery("""
            SELECT COUNT(*) FROM (
                SELECT cid FROM content_locations WHERE file_size > 104857600
                GROUP BY cid HAVING COUNT(DISTINCT machine_name) = 1
            )
        """)
        rs.next()
        assertEquals(0, rs.getInt(1))  // 100MB is NOT > 100MB
        rs.close(); conn.close()
    }

    @Test
    fun `at-risk includes files just over 100MB boundary`() {
        val conn = createRegistryDb()
        insert(conn, "QmOver", "server-a", "/over.mkv", 104857601)  // 100MB + 1 byte

        val rs = conn.createStatement().executeQuery("""
            SELECT COUNT(*) FROM (
                SELECT cid FROM content_locations WHERE file_size > 104857600
                GROUP BY cid HAVING COUNT(DISTINCT machine_name) = 1
            )
        """)
        rs.next()
        assertEquals(1, rs.getInt(1))
        rs.close(); conn.close()
    }

    @Test
    fun `at-risk sums size across multiple at-risk CIDs`() {
        val conn = createRegistryDb()
        insert(conn, "QmR1", "server-a", "/r1.mkv", 200_000_000)
        insert(conn, "QmR2", "server-a", "/r2.mkv", 300_000_000)
        insert(conn, "QmR3", "server-a", "/r3.mkv", 500_000_000)

        val rs = conn.createStatement().executeQuery("""
            SELECT COALESCE(SUM(file_size), 0) as sz FROM (
                SELECT cid, MAX(file_size) as file_size FROM content_locations
                WHERE file_size > 104857600
                GROUP BY cid HAVING COUNT(DISTINCT machine_name) = 1
            )
        """)
        rs.next()
        assertEquals(1_000_000_000, rs.getLong("sz"))
        rs.close(); conn.close()
    }

    // ===========================================
    // Geographic diversity
    // ===========================================

    @Test
    fun `geo diversity counts machines per CID correctly`() {
        val conn = createRegistryDb()
        insert(conn, "QmSingle", "server-a", "/a", 100)
        insert(conn, "QmDouble", "server-a", "/b", 100)
        insert(conn, "QmDouble", "server-b", "/b", 100)
        insert(conn, "QmTriple", "server-a", "/c", 100)
        insert(conn, "QmTriple", "server-b", "/c", 100)
        insert(conn, "QmTriple", "local", "/c", 100)

        val rs = conn.createStatement().executeQuery("""
            SELECT
                COUNT(CASE WHEN mc = 1 THEN 1 END) as s1,
                COUNT(CASE WHEN mc = 2 THEN 1 END) as s2,
                COUNT(CASE WHEN mc >= 3 THEN 1 END) as s3
            FROM (SELECT cid, COUNT(DISTINCT machine_name) as mc FROM content_locations GROUP BY cid)
        """)
        rs.next()
        assertEquals(1, rs.getInt("s1"))
        assertEquals(1, rs.getInt("s2"))
        assertEquals(1, rs.getInt("s3"))
        rs.close(); conn.close()
    }

    @Test
    fun `geo diversity empty registry has all zeros`() {
        val conn = createRegistryDb()
        val rs = conn.createStatement().executeQuery("""
            SELECT
                COUNT(CASE WHEN mc = 1 THEN 1 END) as s1,
                COUNT(CASE WHEN mc = 2 THEN 1 END) as s2,
                COUNT(CASE WHEN mc >= 3 THEN 1 END) as s3
            FROM (SELECT cid, COUNT(DISTINCT machine_name) as mc FROM content_locations GROUP BY cid)
        """)
        rs.next()
        assertEquals(0, rs.getInt("s1"))
        assertEquals(0, rs.getInt("s2"))
        assertEquals(0, rs.getInt("s3"))
        rs.close(); conn.close()
    }

    @Test
    fun `average spread calculation with mixed distribution`() {
        val conn = createRegistryDb()
        // 1 CID on 1 machine, 1 CID on 3 machines → avg = 2.0
        insert(conn, "QmOne", "server-a", "/a", 100)
        insert(conn, "QmThree", "server-a", "/b", 100)
        insert(conn, "QmThree", "server-b", "/b", 100)
        insert(conn, "QmThree", "local", "/b", 100)

        val rs = conn.createStatement().executeQuery(
            "SELECT AVG(mc) as avg_mc FROM (SELECT COUNT(DISTINCT machine_name) as mc FROM content_locations GROUP BY cid)"
        )
        rs.next()
        assertEquals(2.0, rs.getDouble("avg_mc"), 0.01)
        rs.close(); conn.close()
    }

    @Test
    fun `average spread with all single-copy is 1`() {
        val conn = createRegistryDb()
        insert(conn, "Qm1", "server-a", "/a", 100)
        insert(conn, "Qm2", "server-a", "/b", 100)
        insert(conn, "Qm3", "server-a", "/c", 100)

        val rs = conn.createStatement().executeQuery(
            "SELECT AVG(mc) as avg_mc FROM (SELECT COUNT(DISTINCT machine_name) as mc FROM content_locations GROUP BY cid)"
        )
        rs.next()
        assertEquals(1.0, rs.getDouble("avg_mc"), 0.01)
        rs.close(); conn.close()
    }

    // ===========================================
    // Content classification + extension extraction
    // ===========================================

    @Test
    fun `content class classification groups by extension type`() {
        val conn = createRegistryDb()
        insert(conn, "Qm1", "server-a", "/movies/film.mkv", 2_000_000_000)
        insert(conn, "Qm2", "server-a", "/docs/report.pdf", 500_000)
        insert(conn, "Qm3", "server-a", "/backup/data.tar", 1_000_000_000)
        insert(conn, "Qm4", "server-a", "/code/main.kt", 5_000)
        insert(conn, "Qm5", "server-a", "/misc/unknown.xyz", 1_000)

        val extCounts = queryExtCounts(conn)
        assertEquals(1, extCounts["mkv"])
        assertEquals(1, extCounts["pdf"])
        assertEquals(1, extCounts["tar"])
        assertEquals(1, extCounts["kt"])
        assertEquals(1, extCounts["xyz"])
        conn.close()
    }

    @Test
    fun `extension extraction handles dotfiles correctly`() {
        val conn = createRegistryDb()
        insert(conn, "Qm1", "server-a", "/path/.DS_Store", 4096)

        val extCounts = queryExtCounts(conn)
        // .DS_Store → ext should be "DS_Store" (everything after the only dot)
        assertTrue(extCounts.containsKey("DS_Store") || extCounts.containsKey("ds_store"),
            "Should extract extension from dotfiles: $extCounts")
        conn.close()
    }

    @Test
    fun `extension extraction handles multiple dots`() {
        val conn = createRegistryDb()
        insert(conn, "Qm1", "server-a", "/data/backup.tar.gz", 1000)

        val extCounts = queryExtCounts(conn)
        // REPLACE/RTRIM trick extracts "gz" (last extension)
        assertTrue(extCounts.containsKey("gz"), "Should extract last extension from multi-dot: $extCounts")
        conn.close()
    }

    @Test
    fun `extension extraction handles no extension`() {
        val conn = createRegistryDb()
        insert(conn, "Qm1", "server-a", "/bin/executable", 1000)

        // file_path doesn't contain a dot → excluded by WHERE clause
        val rs = conn.createStatement().executeQuery(
            "SELECT COUNT(*) FROM content_locations WHERE file_path LIKE '%.%'"
        )
        rs.next()
        assertEquals(0, rs.getInt(1))
        rs.close(); conn.close()
    }

    @Test
    fun `extension counts are case-insensitive`() {
        val conn = createRegistryDb()
        insert(conn, "Qm1", "server-a", "/a.MKV", 1000)
        insert(conn, "Qm2", "server-a", "/b.mkv", 2000)

        val extCounts = queryExtCounts(conn)
        // LOWER() makes both "mkv"
        assertEquals(2, extCounts["mkv"])
        conn.close()
    }

    @Test
    fun `same CID different extensions counted per extension`() {
        val conn = createRegistryDb()
        // Same CID at different paths with different extensions (unlikely but possible)
        insert(conn, "QmSame", "server-a", "/a.mkv", 1000)
        insert(conn, "QmSame", "server-a", "/a.mp4", 1000)

        val extCounts = queryExtCounts(conn)
        // COUNT(DISTINCT cid) per ext: mkv=1 (QmSame), mp4=1 (QmSame)
        assertEquals(1, extCounts["mkv"])
        assertEquals(1, extCounts["mp4"])
        conn.close()
    }

    // ===========================================
    // Dedup detection
    // ===========================================

    @Test
    fun `dedup detects same CID at multiple paths on same machine`() {
        val conn = createRegistryDb()
        insert(conn, "QmDup", "server-a", "/film/movie.mkv", 1000)
        insert(conn, "QmDup", "server-a", "/backup/movie.mkv", 1000)
        insert(conn, "QmUnique", "server-a", "/other.mkv", 2000)

        val rs = conn.createStatement().executeQuery("""
            SELECT COUNT(*) as dup_cids, COALESCE(SUM(extra_copies), 0) as wasted FROM (
                SELECT cid, COUNT(*) - 1 as extra_copies FROM content_locations
                GROUP BY cid HAVING COUNT(*) > COUNT(DISTINCT machine_name)
            )
        """)
        rs.next()
        assertEquals(1, rs.getLong("dup_cids"))
        assertEquals(1, rs.getLong("wasted"))  // 2 entries - 1 machine = 1 extra
        rs.close(); conn.close()
    }

    @Test
    fun `cross-machine replication counted as healthy not wasteful`() {
        val conn = createRegistryDb()
        insert(conn, "QmCross", "server-a", "/a.mkv", 1000)
        insert(conn, "QmCross", "server-b", "/a.mkv", 1000)

        // Same-machine dedup query should NOT flag this
        val dupRs = conn.createStatement().executeQuery("""
            SELECT COUNT(*) FROM (
                SELECT cid FROM content_locations GROUP BY cid
                HAVING COUNT(*) > COUNT(DISTINCT machine_name)
            )
        """)
        dupRs.next()
        assertEquals(0, dupRs.getInt(1))  // cross-machine is NOT same-machine dedup
        dupRs.close()

        // Cross-machine query should flag this
        val crossRs = conn.createStatement().executeQuery("""
            SELECT COUNT(*) FROM (
                SELECT cid FROM content_locations GROUP BY cid HAVING COUNT(DISTINCT machine_name) > 1
            )
        """)
        crossRs.next()
        assertEquals(1, crossRs.getInt(1))
        crossRs.close(); conn.close()
    }

    @Test
    fun `dedup with 3 copies on same machine counts 2 extra`() {
        val conn = createRegistryDb()
        insert(conn, "QmTrip", "server-a", "/a/file.mkv", 1000)
        insert(conn, "QmTrip", "server-a", "/b/file.mkv", 1000)
        insert(conn, "QmTrip", "server-a", "/c/file.mkv", 1000)

        val rs = conn.createStatement().executeQuery("""
            SELECT COALESCE(SUM(extra_copies), 0) FROM (
                SELECT cid, COUNT(*) - 1 as extra_copies FROM content_locations
                GROUP BY cid HAVING COUNT(*) > COUNT(DISTINCT machine_name)
            )
        """)
        rs.next()
        assertEquals(2, rs.getLong(1))  // 3 entries - 1 machine = 2 extra
        rs.close(); conn.close()
    }

    // ===========================================
    // Staleness
    // ===========================================

    @Test
    fun `staleness query groups by machine with max timestamp`() {
        val conn = createRegistryDb()
        val recent = LocalDateTime.now().minusHours(2).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val old = LocalDateTime.now().minusDays(45).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        insertWithTimestamp(conn, "QmA", "server-a", "/a", 100, recent)
        insertWithTimestamp(conn, "QmB", "server-a", "/b", 200, old)
        insertWithTimestamp(conn, "QmC", "server-b", "/c", 300, old)

        val rs = conn.createStatement().executeQuery(
            "SELECT machine_name, MAX(last_synced_at) as last_sync FROM content_locations GROUP BY machine_name ORDER BY machine_name"
        )

        rs.next()
        assertEquals("server-a", rs.getString("machine_name"))
        assertEquals(recent, rs.getString("last_sync"))  // MAX picks the recent one

        rs.next()
        assertEquals("server-b", rs.getString("machine_name"))
        assertEquals(old, rs.getString("last_sync"))

        rs.close(); conn.close()
    }

    // ===========================================
    // Recommendations
    // ===========================================

    @Test
    fun `recommendations detect pending copy requests`() {
        val conn = createRegistryDb()
        RequestCopyCommand.ensureCopyRequestsTable(conn)
        conn.createStatement().executeUpdate("INSERT INTO copy_requests (repository, target_machine) VALUES ('film', 'van')")
        conn.createStatement().executeUpdate("INSERT INTO copy_requests (repository, target_machine) VALUES ('tv', 'van')")

        val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM copy_requests WHERE status = 'pending'")
        rs.next()
        assertEquals(2, rs.getInt(1))
        rs.close(); conn.close()
    }

    @Test
    fun `recommendations ignore completed copy requests`() {
        val conn = createRegistryDb()
        RequestCopyCommand.ensureCopyRequestsTable(conn)
        conn.createStatement().executeUpdate("INSERT INTO copy_requests (repository, target_machine, status) VALUES ('film', 'van', 'completed')")

        val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM copy_requests WHERE status = 'pending'")
        rs.next()
        assertEquals(0, rs.getInt(1))
        rs.close(); conn.close()
    }

    @Test
    fun `recommendations detect junk awaiting purge`() {
        val conn = createRegistryDb()
        ensureJunkTable(conn)
        conn.createStatement().executeUpdate("INSERT INTO junk_list (cid, file_sample) VALUES ('QmJ', 'junk.mkv')")

        val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM junk_list")
        rs.next()
        assertEquals(1, rs.getInt(1))
        rs.close(); conn.close()
    }

    @Test
    fun `recommendations all clear when nothing is wrong`() {
        val conn = createRegistryDb()
        RequestCopyCommand.ensureCopyRequestsTable(conn)
        ensureJunkTable(conn)

        // No pending requests
        val pendRs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM copy_requests WHERE status = 'pending'")
        pendRs.next(); assertEquals(0, pendRs.getInt(1)); pendRs.close()

        // No junk
        val junkRs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM junk_list")
        junkRs.next(); assertEquals(0, junkRs.getInt(1)); junkRs.close()

        // No at-risk
        val riskRs = conn.createStatement().executeQuery("""
            SELECT COALESCE(SUM(file_size), 0) FROM (
                SELECT cid, MAX(file_size) as file_size FROM content_locations
                WHERE file_size > 104857600 GROUP BY cid HAVING COUNT(DISTINCT machine_name) = 1
            )
        """)
        riskRs.next(); assertEquals(0, riskRs.getLong(1)); riskRs.close()

        conn.close()
    }

    // ===========================================
    // Integration: realistic multi-machine scenario
    // ===========================================

    @Test
    fun `realistic scenario with mixed replication and risk`() {
        val conn = createRegistryDb()

        // Film repo: well replicated (3 machines)
        insert(conn, "QmFilm1", "server-a", "/Volumes/EXT-4TB/film/Aliens.mkv", 2_000_000_000)
        insert(conn, "QmFilm1", "server-b", "/Volumes/DATA/film/Aliens.mkv", 2_000_000_000)
        insert(conn, "QmFilm1", "local", "/media/film/Aliens.mkv", 2_000_000_000)

        // TV: only on server-a (at risk, big)
        insert(conn, "QmTV1", "server-a", "/Volumes/EXT-4TB/tv/Breaking.Bad.S01E01.mkv", 800_000_000)
        insert(conn, "QmTV2", "server-a", "/Volumes/EXT-4TB/tv/Breaking.Bad.S01E02.mkv", 750_000_000)

        // Small doc: only on local (not at risk because under 100MB)
        insert(conn, "QmDoc", "local", "/docs/notes.txt", 1_000)

        // Coverage
        val covRs = conn.createStatement().executeQuery(
            "SELECT COUNT(DISTINCT cid) as u, COUNT(DISTINCT machine_name) as m FROM content_locations"
        )
        covRs.next()
        assertEquals(4, covRs.getLong("u"))  // Film1, TV1, TV2, Doc
        assertEquals(3, covRs.getInt("m"))
        covRs.close()

        // At-risk (>100MB, single machine)
        val riskRs = conn.createStatement().executeQuery("""
            SELECT COUNT(*) FROM (
                SELECT cid FROM content_locations WHERE file_size > 104857600
                GROUP BY cid HAVING COUNT(DISTINCT machine_name) = 1
            )
        """)
        riskRs.next()
        assertEquals(2, riskRs.getInt(1))  // TV1 + TV2
        riskRs.close()

        // Geo: 1 CID on 3 machines, 3 CIDs on 1 machine
        val geoRs = conn.createStatement().executeQuery("""
            SELECT COUNT(CASE WHEN mc = 1 THEN 1 END) as s1,
                   COUNT(CASE WHEN mc >= 3 THEN 1 END) as s3
            FROM (SELECT cid, COUNT(DISTINCT machine_name) as mc FROM content_locations GROUP BY cid)
        """)
        geoRs.next()
        assertEquals(3, geoRs.getInt("s1"))  // TV1, TV2, Doc
        assertEquals(1, geoRs.getInt("s3"))  // Film1
        geoRs.close()

        conn.close()
    }

    @Test
    fun `single machine scenario has zero cross-machine replication`() {
        val conn = createRegistryDb()
        insert(conn, "Qm1", "server-a", "/a.mkv", 1000)
        insert(conn, "Qm2", "server-a", "/b.mkv", 2000)
        insert(conn, "Qm3", "server-a", "/c.pdf", 3000)

        val rs = conn.createStatement().executeQuery("""
            SELECT COUNT(*) FROM (
                SELECT cid FROM content_locations GROUP BY cid HAVING COUNT(DISTINCT machine_name) > 1
            )
        """)
        rs.next()
        assertEquals(0, rs.getInt(1))
        rs.close(); conn.close()
    }

    // ===========================================
    // Helpers
    // ===========================================

    private fun createRegistryDb(): Connection {
        val path = tempDir.resolve("reg_${System.nanoTime()}.db").toString()
        val conn = DriverManager.getConnection("jdbc:sqlite:$path")
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

    private fun insert(conn: Connection, cid: String, machine: String, path: String, size: Long) {
        conn.prepareStatement("INSERT INTO content_locations (cid, machine_name, file_path, file_size) VALUES (?, ?, ?, ?)").apply {
            setString(1, cid); setString(2, machine); setString(3, path); setLong(4, size)
            executeUpdate(); close()
        }
    }

    private fun insertWithTimestamp(conn: Connection, cid: String, machine: String, path: String, size: Long, timestamp: String) {
        conn.prepareStatement("INSERT INTO content_locations (cid, machine_name, file_path, file_size, last_synced_at) VALUES (?, ?, ?, ?, ?)").apply {
            setString(1, cid); setString(2, machine); setString(3, path); setLong(4, size); setString(5, timestamp)
            executeUpdate(); close()
        }
    }

    private fun queryExtCounts(conn: Connection): Map<String, Int> {
        val rs = conn.createStatement().executeQuery("""
            SELECT LOWER(CASE
                WHEN file_path LIKE '%.%'
                THEN REPLACE(file_path, RTRIM(file_path, REPLACE(file_path, '.', '')), '')
                ELSE '' END) as ext, COUNT(DISTINCT cid) as cnt
            FROM content_locations WHERE file_path LIKE '%.%' GROUP BY ext
        """)
        val map = mutableMapOf<String, Int>()
        while (rs.next()) map[rs.getString("ext").removePrefix(".")] = rs.getInt("cnt")
        rs.close()
        return map
    }

    private fun buildTestConfig(): ChoamConfig = ChoamConfig(
        machines = mapOf(
            "local" to MachineProfile(name = "local", hostname = "h1", type = MachineType.DESKTOP,
                repositories = mapOf("film" to "/film"), tailscaleIp = "100.64.0.1"),
            "server-a" to MachineProfile(name = "server-a", hostname = "h2", type = MachineType.DESKTOP,
                repositories = mapOf("film" to "/film", "tv" to "/tv"),
                sshUser = "user", tailscaleIp = "100.64.0.2", aliases = listOf("server-a-old")),
            "server-b" to MachineProfile(name = "server-b", hostname = "h3", type = MachineType.DESKTOP,
                repositories = mapOf("film" to "/film"),
                sshUser = "user", tailscaleIp = "100.64.0.3")
        ),
        repositories = mapOf(
            "film" to RepositoryConfig(name = "film", type = RepositoryType.MEDIA,
                replication = ReplicationPolicy(minCopies = 2, preferredCopies = 3))
        )
    )
}

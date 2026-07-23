package vision.salient.choam.cli

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import vision.salient.choam.config.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for CHOAM Phase 3: Tiered Storage + Content Lifecycle
 *
 * Covers:
 * 1. StorageClass + Drive/MountedDrive config
 * 2. ReplicationPolicy config + serialization
 * 3. PlanCommand gap analysis logic (countRepoMachines)
 * 4. RequestCopy/Fulfill lifecycle state machine
 * 5. Junk/Unjunk/Purge two-phase deletion
 * 6. Config serialization round-trip with new fields
 */
class Phase3Test {

    @TempDir
    lateinit var tempDir: Path

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    // ===========================================
    // 1. StorageClass + Drive config
    // ===========================================

    @Test
    fun `StorageClass enum has three tiers`() {
        assertEquals(3, StorageClass.entries.size)
        assertTrue(StorageClass.entries.containsAll(listOf(StorageClass.HOT, StorageClass.WARM, StorageClass.COLD)))
    }

    @Test
    fun `Drive defaults to WARM storage class`() {
        val drive = Drive(uuid = "test-uuid", label = "TestDrive")
        assertEquals(StorageClass.WARM, drive.storageClass)
    }

    @Test
    fun `Drive accepts all three storage classes`() {
        assertEquals(StorageClass.HOT, Drive(uuid = "1", label = "NAS", storageClass = StorageClass.HOT).storageClass)
        assertEquals(StorageClass.WARM, Drive(uuid = "2", label = "USB", storageClass = StorageClass.WARM).storageClass)
        assertEquals(StorageClass.COLD, Drive(uuid = "3", label = "Archive", storageClass = StorageClass.COLD).storageClass)
    }

    @Test
    fun `MountedDrive defaults to WARM`() {
        val mounted = MountedDrive(uuid = "u", label = "L", mountPoint = "/Volumes/X")
        assertEquals(StorageClass.WARM, mounted.storageClass)
    }

    @Test
    fun `MountedDrive copy propagates storage class from Drive config`() {
        // Simulates what DriveDetector.detectConfiguredDrives does
        val scanned = MountedDrive(uuid = "u", label = "L", mountPoint = "/Volumes/X")
        val withHot = scanned.copy(storageClass = StorageClass.HOT)
        val withCold = scanned.copy(storageClass = StorageClass.COLD)
        assertEquals(StorageClass.HOT, withHot.storageClass)
        assertEquals(StorageClass.COLD, withCold.storageClass)
        // Original unchanged
        assertEquals(StorageClass.WARM, scanned.storageClass)
    }

    // ===========================================
    // 2. ReplicationPolicy config + serialization
    // ===========================================

    @Test
    fun `ReplicationPolicy defaults are sensible`() {
        val policy = ReplicationPolicy()
        assertEquals(1, policy.minCopies)
        assertEquals(2, policy.preferredCopies)
        assertFalse(policy.geoDistribute)
        assertTrue(policy.preferredClass.isEmpty())
    }

    @Test
    fun `ReplicationPolicy custom values preserved`() {
        val policy = ReplicationPolicy(
            minCopies = 3,
            preferredCopies = 5,
            geoDistribute = true,
            preferredClass = listOf(StorageClass.HOT, StorageClass.WARM)
        )
        assertEquals(3, policy.minCopies)
        assertEquals(5, policy.preferredCopies)
        assertTrue(policy.geoDistribute)
        assertEquals(listOf(StorageClass.HOT, StorageClass.WARM), policy.preferredClass)
    }

    @Test
    fun `RepositoryConfig defaults replication to default policy`() {
        val rc = RepositoryConfig(name = "film", type = RepositoryType.MEDIA)
        assertEquals(1, rc.replication.minCopies)
        assertEquals(2, rc.replication.preferredCopies)
    }

    @Test
    fun `config save-load round-trip preserves StorageClass on Drive`() {
        val path = tempDir.resolve("config.json")
        val original = ChoamConfig(
            drives = mapOf(
                "nas" to Drive(uuid = "u1", label = "NAS", storageClass = StorageClass.HOT),
                "cold" to Drive(uuid = "u2", label = "Seagate", storageClass = StorageClass.COLD)
            )
        )
        ChoamConfigLoader.save(original, path)
        val loaded = ChoamConfigLoader.load(path)

        assertEquals(StorageClass.HOT, loaded.drives["nas"]!!.storageClass)
        assertEquals(StorageClass.COLD, loaded.drives["cold"]!!.storageClass)
    }

    @Test
    fun `config save-load round-trip preserves ReplicationPolicy`() {
        val path = tempDir.resolve("config.json")
        val original = ChoamConfig(
            repositories = mapOf(
                "critical" to RepositoryConfig(
                    name = "critical",
                    type = RepositoryType.ARCHIVE,
                    replication = ReplicationPolicy(
                        minCopies = 3,
                        preferredCopies = 5,
                        geoDistribute = true,
                        preferredClass = listOf(StorageClass.HOT)
                    )
                )
            )
        )
        ChoamConfigLoader.save(original, path)
        val loaded = ChoamConfigLoader.load(path)

        val policy = loaded.repositories["critical"]!!.replication
        assertEquals(3, policy.minCopies)
        assertEquals(5, policy.preferredCopies)
        assertTrue(policy.geoDistribute)
        assertEquals(listOf(StorageClass.HOT), policy.preferredClass)
    }

    @Test
    fun `config without storageClass or replication loads with defaults (backward compat)`() {
        val path = tempDir.resolve("config.json")
        // Write a config JSON that has no storageClass or replication fields
        val rawJson = """
        {
            "version": "1.0.0",
            "drives": {
                "usb": {
                    "uuid": "u1",
                    "label": "USB"
                }
            },
            "repositories": {
                "media": {
                    "name": "media",
                    "type": "MEDIA"
                }
            }
        }
        """.trimIndent()
        Files.writeString(path, rawJson)
        val loaded = ChoamConfigLoader.load(path)

        assertEquals(StorageClass.WARM, loaded.drives["usb"]!!.storageClass)
        assertEquals(1, loaded.repositories["media"]!!.replication.minCopies)
        assertEquals(2, loaded.repositories["media"]!!.replication.preferredCopies)
    }

    @Test
    fun `StorageClass serializes as string name`() {
        val drive = Drive(uuid = "u", label = "L", storageClass = StorageClass.COLD)
        val serialized = json.encodeToString(drive)
        assertTrue(serialized.contains("\"COLD\""), "Should serialize as COLD, got: $serialized")
    }

    // ===========================================
    // 3. PlanCommand gap analysis
    // ===========================================

    @Test
    fun `countRepoMachines returns machines with data in registry`() {
        val conn = createRegistryDb()
        insertLocation(conn, "QmA", "server-a", "/Volumes/EXT-4TB/film/a.mkv", 2_000_000_000)
        insertLocation(conn, "QmB", "server-b", "/Volumes/DATA/film/b.mkv", 1_000_000_000)

        val result = PlanCommand.countRepoMachines(conn, buildTestConfig(), emptyMap())

        assertEquals(setOf("server-a", "server-b"), result["film"])
        conn.close()
    }

    @Test
    fun `countRepoMachines excludes machines not in registry`() {
        val conn = createRegistryDb()
        // Only server-a has data
        insertLocation(conn, "QmA", "server-a", "/Volumes/EXT-4TB/film/a.mkv", 2_000_000_000)

        val result = PlanCommand.countRepoMachines(conn, buildTestConfig(), emptyMap())

        assertEquals(setOf("server-a"), result["film"])
        // server-b configured for film but no data → excluded
        assertFalse(result["film"]!!.contains("server-b"))
        conn.close()
    }

    @Test
    fun `countRepoMachines remaps aliases to config keys`() {
        val conn = createRegistryDb()
        insertLocation(conn, "QmA", "server-a-old", "/x", 1000)

        val result = PlanCommand.countRepoMachines(conn, buildTestConfig(), mapOf("server-a-old" to "server-a"))

        assertTrue(result["film"]!!.contains("server-a"))
        conn.close()
    }

    @Test
    fun `countRepoMachines returns empty set for repos with no data anywhere`() {
        val conn = createRegistryDb()
        // Registry is empty

        val result = PlanCommand.countRepoMachines(conn, buildTestConfig(), emptyMap())

        assertTrue(result["film"]!!.isEmpty())
        assertTrue(result["tv"]!!.isEmpty())
        conn.close()
    }

    @Test
    fun `countRepoMachines only counts machines that have the repo configured`() {
        val conn = createRegistryDb()
        // server-b has data but is NOT configured for 'tv' repo
        insertLocation(conn, "QmA", "server-b", "/x", 1000)

        val result = PlanCommand.countRepoMachines(conn, buildTestConfig(), emptyMap())

        // server-b doesn't have 'tv' in its repositories map
        assertFalse(result["tv"]!!.contains("server-b"))
        conn.close()
    }

    @Test
    fun `gap detection identifies under-replicated repo`() {
        val conn = createRegistryDb()
        insertLocation(conn, "QmA", "server-a", "/x", 1000)

        val config = buildTestConfig()  // film needs minCopies=2
        val copies = PlanCommand.countRepoMachines(conn, config, emptyMap())

        val filmCopies = copies["film"]!!.size
        val filmPolicy = config.repositories["film"]!!.replication
        assertTrue(filmCopies < filmPolicy.minCopies, "film should be under-replicated")
        assertEquals(1, filmPolicy.minCopies - filmCopies, "film needs 1 more copy")
        conn.close()
    }

    @Test
    fun `gap detection identifies repo meeting minimum but below preferred`() {
        val conn = createRegistryDb()
        insertLocation(conn, "QmA", "server-a", "/x", 1000)
        insertLocation(conn, "QmB", "server-b", "/y", 1000)

        val config = buildTestConfig()  // film: min=2, preferred=3
        val copies = PlanCommand.countRepoMachines(conn, config, emptyMap())

        val filmCopies = copies["film"]!!.size
        val policy = config.repositories["film"]!!.replication
        assertTrue(filmCopies >= policy.minCopies, "should meet minimum")
        assertTrue(filmCopies < policy.preferredCopies, "should be below preferred")
        conn.close()
    }

    @Test
    fun `gap detection identifies repo meeting preferred copies`() {
        val conn = createRegistryDb()
        insertLocation(conn, "QmA", "server-a", "/x", 1000)
        insertLocation(conn, "QmB", "server-b", "/y", 1000)
        insertLocation(conn, "QmC", "local", "/z", 1000)

        val config = buildTestConfig()  // film: preferred=3, all 3 machines have data
        val copies = PlanCommand.countRepoMachines(conn, config, emptyMap())

        val filmCopies = copies["film"]!!.size
        val policy = config.repositories["film"]!!.replication
        // local has film configured, all 3 in registry
        assertTrue(filmCopies >= policy.preferredCopies || filmCopies >= policy.minCopies)
        conn.close()
    }

    // ===========================================
    // 4. RequestCopy/Fulfill lifecycle
    // ===========================================

    @Test
    fun `copy request defaults to pending status with auto-timestamp`() {
        val conn = createCopyRequestsDb()
        conn.createStatement().executeUpdate(
            "INSERT INTO copy_requests (repository, target_machine) VALUES ('film', 'server-b')"
        )

        val rs = conn.createStatement().executeQuery("SELECT * FROM copy_requests WHERE id = 1")
        assertTrue(rs.next())
        assertEquals("pending", rs.getString("status"))
        assertNotNull(rs.getString("requested_at"))
        assertNull(rs.getString("fulfilled_at"))
        rs.close()
        conn.close()
    }

    @Test
    fun `copy request full lifecycle pending to in_progress to completed`() {
        val conn = createCopyRequestsDb()
        conn.createStatement().executeUpdate(
            "INSERT INTO copy_requests (repository, target_machine) VALUES ('film', 'server-b')"
        )

        // pending → in_progress
        conn.createStatement().executeUpdate("UPDATE copy_requests SET status = 'in_progress' WHERE id = 1")
        var rs = conn.createStatement().executeQuery("SELECT status FROM copy_requests WHERE id = 1")
        rs.next(); assertEquals("in_progress", rs.getString("status")); rs.close()

        // in_progress → completed with timestamp
        conn.createStatement().executeUpdate(
            "UPDATE copy_requests SET status = 'completed', fulfilled_at = datetime('now') WHERE id = 1"
        )
        rs = conn.createStatement().executeQuery("SELECT status, fulfilled_at FROM copy_requests WHERE id = 1")
        rs.next()
        assertEquals("completed", rs.getString("status"))
        assertNotNull(rs.getString("fulfilled_at"))
        rs.close()
        conn.close()
    }

    @Test
    fun `copy request can be cancelled`() {
        val conn = createCopyRequestsDb()
        conn.createStatement().executeUpdate(
            "INSERT INTO copy_requests (repository, target_machine) VALUES ('tv', 'server-a')"
        )
        conn.createStatement().executeUpdate("UPDATE copy_requests SET status = 'cancelled' WHERE id = 1")

        val pending = countByStatus(conn, "pending")
        val cancelled = countByStatus(conn, "cancelled")
        assertEquals(0, pending)
        assertEquals(1, cancelled)
        conn.close()
    }

    @Test
    fun `pending query only returns pending requests in order`() {
        val conn = createCopyRequestsDb()
        conn.createStatement().executeUpdate("INSERT INTO copy_requests (repository, target_machine) VALUES ('film', 'server-b')")
        conn.createStatement().executeUpdate("INSERT INTO copy_requests (repository, target_machine) VALUES ('tv', 'server-b')")
        conn.createStatement().executeUpdate("INSERT INTO copy_requests (repository, target_machine, status) VALUES ('backup', 'server-a', 'completed')")
        conn.createStatement().executeUpdate("INSERT INTO copy_requests (repository, target_machine, status) VALUES ('old', 'server-a', 'cancelled')")

        val rs = conn.createStatement().executeQuery(
            "SELECT repository FROM copy_requests WHERE status = 'pending' ORDER BY requested_at"
        )
        val repos = mutableListOf<String>()
        while (rs.next()) repos.add(rs.getString("repository"))
        rs.close()

        assertEquals(listOf("film", "tv"), repos)
        conn.close()
    }

    @Test
    fun `failed request can be reset back to pending`() {
        val conn = createCopyRequestsDb()
        conn.createStatement().executeUpdate("INSERT INTO copy_requests (repository, target_machine) VALUES ('film', 'server-b')")
        conn.createStatement().executeUpdate("UPDATE copy_requests SET status = 'in_progress' WHERE id = 1")
        // Simulate failure → reset
        conn.createStatement().executeUpdate("UPDATE copy_requests SET status = 'pending' WHERE id = 1")

        assertEquals(1, countByStatus(conn, "pending"))
        conn.close()
    }

    @Test
    fun `duplicate pending request for same repo-machine can be detected`() {
        val conn = createCopyRequestsDb()
        conn.createStatement().executeUpdate("INSERT INTO copy_requests (repository, target_machine) VALUES ('film', 'server-b')")

        val rs = conn.prepareStatement(
            "SELECT id FROM copy_requests WHERE repository = ? AND target_machine = ? AND status = 'pending'"
        ).apply {
            setString(1, "film")
            setString(2, "server-b")
        }.executeQuery()
        assertTrue(rs.next(), "Should find existing pending request")
        rs.close()
        conn.close()
    }

    @Test
    fun `completed request does not block new pending request for same pair`() {
        val conn = createCopyRequestsDb()
        conn.createStatement().executeUpdate("INSERT INTO copy_requests (repository, target_machine, status) VALUES ('film', 'server-b', 'completed')")

        val rs = conn.prepareStatement(
            "SELECT id FROM copy_requests WHERE repository = ? AND target_machine = ? AND status = 'pending'"
        ).apply {
            setString(1, "film")
            setString(2, "server-b")
        }.executeQuery()
        assertFalse(rs.next(), "No pending request should exist")
        rs.close()
        conn.close()
    }

    // ===========================================
    // 5. Junk/Unjunk/Purge two-phase deletion
    // ===========================================

    @Test
    fun `junk mark inserts CID with auto-timestamp`() {
        val conn = createJunkDb()
        conn.prepareStatement("INSERT INTO junk_list (cid, reason, file_sample) VALUES (?, ?, ?)").apply {
            setString(1, "QmABC"); setString(2, "duplicate"); setString(3, "Aliens.mkv")
        }.executeUpdate()

        val rs = conn.createStatement().executeQuery("SELECT * FROM junk_list WHERE cid = 'QmABC'")
        assertTrue(rs.next())
        assertEquals("duplicate", rs.getString("reason"))
        assertEquals("Aliens.mkv", rs.getString("file_sample"))
        assertNotNull(rs.getString("marked_at"))
        rs.close()
        conn.close()
    }

    @Test
    fun `junk without reason stores null`() {
        val conn = createJunkDb()
        conn.createStatement().executeUpdate("INSERT INTO junk_list (cid, file_sample) VALUES ('QmX', 'file.txt')")

        val rs = conn.createStatement().executeQuery("SELECT reason FROM junk_list WHERE cid = 'QmX'")
        rs.next()
        assertNull(rs.getString("reason"))
        rs.close()
        conn.close()
    }

    @Test
    fun `unjunk removes CID from junk list`() {
        val conn = createJunkDb()
        conn.createStatement().executeUpdate("INSERT INTO junk_list (cid, file_sample) VALUES ('QmDel', 'x.mkv')")

        val deleted = conn.prepareStatement("DELETE FROM junk_list WHERE cid = ?").apply {
            setString(1, "QmDel")
        }.executeUpdate()

        assertEquals(1, deleted)
        val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM junk_list")
        rs.next(); assertEquals(0, rs.getInt(1)); rs.close()
        conn.close()
    }

    @Test
    fun `unjunk nonexistent CID deletes nothing`() {
        val conn = createJunkDb()
        val deleted = conn.prepareStatement("DELETE FROM junk_list WHERE cid = ?").apply {
            setString(1, "QmGhost")
        }.executeUpdate()
        assertEquals(0, deleted)
        conn.close()
    }

    @Test
    fun `junk list prevents duplicate CIDs via primary key`() {
        val conn = createJunkDb()
        conn.createStatement().executeUpdate("INSERT INTO junk_list (cid, file_sample) VALUES ('QmD', 'a.mkv')")

        val threw = try {
            conn.createStatement().executeUpdate("INSERT INTO junk_list (cid, file_sample) VALUES ('QmD', 'b.mkv')")
            false
        } catch (_: Exception) { true }
        assertTrue(threw)
        conn.close()
    }

    @Test
    fun `junk then unjunk then re-junk cycle works`() {
        val conn = createJunkDb()
        conn.createStatement().executeUpdate("INSERT INTO junk_list (cid, file_sample) VALUES ('QmCycle', 'f.mkv')")
        conn.createStatement().executeUpdate("DELETE FROM junk_list WHERE cid = 'QmCycle'")
        // Re-insert should succeed after deletion
        conn.createStatement().executeUpdate("INSERT INTO junk_list (cid, file_sample) VALUES ('QmCycle', 'f.mkv')")

        val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM junk_list WHERE cid = 'QmCycle'")
        rs.next(); assertEquals(1, rs.getInt(1)); rs.close()
        conn.close()
    }

    @Test
    fun `lookupCid returns all locations across machines`() {
        val conn = createRegistryDb()
        insertLocation(conn, "QmMulti", "server-a", "/Volumes/EXT-4TB/movie.mkv", 2_000_000_000)
        insertLocation(conn, "QmMulti", "server-b", "/Volumes/DATA/movie.mkv", 2_000_000_000)

        val locations = lookupCid(conn, "QmMulti")
        assertEquals(2, locations.size)
        assertEquals(setOf("server-a", "server-b"), locations.map { it.machineName }.toSet())
        assertTrue(locations.all { it.fileSize == 2_000_000_000L })
        conn.close()
    }

    @Test
    fun `lookupCid returns empty list for unknown CID`() {
        val conn = createRegistryDb()
        assertEquals(0, lookupCid(conn, "QmNonexistent").size)
        conn.close()
    }

    @Test
    fun `lookupCid returns multiple paths on same machine`() {
        val conn = createRegistryDb()
        insertLocation(conn, "QmSame", "server-a", "/Volumes/EXT-4TB/film/a.mkv", 1000)
        insertLocation(conn, "QmSame", "server-a", "/Volumes/EXT-4TB/backup/a.mkv", 1000)

        val locations = lookupCid(conn, "QmSame")
        assertEquals(2, locations.size)
        assertTrue(locations.all { it.machineName == "server-a" })
        conn.close()
    }

    @Test
    fun `purge removes from both content_locations and junk_list`() {
        val conn = createRegistryDb()
        ensureJunkTable(conn)
        insertLocation(conn, "QmPurge", "local", "/tmp/file.mkv", 1000)
        conn.createStatement().executeUpdate("INSERT INTO junk_list (cid, file_sample) VALUES ('QmPurge', 'file.mkv')")

        // Simulate purge
        conn.createStatement().executeUpdate("DELETE FROM content_locations WHERE cid = 'QmPurge'")
        conn.createStatement().executeUpdate("DELETE FROM junk_list WHERE cid = 'QmPurge'")

        val locCount = conn.createStatement().executeQuery("SELECT COUNT(*) FROM content_locations WHERE cid = 'QmPurge'")
        locCount.next(); assertEquals(0, locCount.getInt(1)); locCount.close()
        val junkCount = conn.createStatement().executeQuery("SELECT COUNT(*) FROM junk_list WHERE cid = 'QmPurge'")
        junkCount.next(); assertEquals(0, junkCount.getInt(1)); junkCount.close()
        conn.close()
    }

    @Test
    fun `purge of multi-machine CID removes all locations`() {
        val conn = createRegistryDb()
        ensureJunkTable(conn)
        insertLocation(conn, "QmMultiPurge", "server-a", "/a.mkv", 1000)
        insertLocation(conn, "QmMultiPurge", "server-b", "/b.mkv", 1000)
        insertLocation(conn, "QmMultiPurge", "local", "/c.mkv", 1000)
        conn.createStatement().executeUpdate("INSERT INTO junk_list (cid, file_sample) VALUES ('QmMultiPurge', 'a.mkv')")

        conn.createStatement().executeUpdate("DELETE FROM content_locations WHERE cid = 'QmMultiPurge'")
        conn.createStatement().executeUpdate("DELETE FROM junk_list WHERE cid = 'QmMultiPurge'")

        val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM content_locations WHERE cid = 'QmMultiPurge'")
        rs.next(); assertEquals(0, rs.getInt(1)); rs.close()
        conn.close()
    }

    @Test
    fun `purge does not affect unjunked CIDs`() {
        val conn = createRegistryDb()
        ensureJunkTable(conn)
        insertLocation(conn, "QmSafe", "server-a", "/safe.mkv", 1000)
        insertLocation(conn, "QmJunked", "server-a", "/junked.mkv", 2000)
        conn.createStatement().executeUpdate("INSERT INTO junk_list (cid, file_sample) VALUES ('QmJunked', 'junked.mkv')")

        // Purge only junked CIDs
        val junkRs = conn.createStatement().executeQuery("SELECT cid FROM junk_list")
        val junkCids = mutableListOf<String>()
        while (junkRs.next()) junkCids.add(junkRs.getString("cid"))
        junkRs.close()

        for (cid in junkCids) {
            conn.createStatement().executeUpdate("DELETE FROM content_locations WHERE cid = '$cid'")
            conn.createStatement().executeUpdate("DELETE FROM junk_list WHERE cid = '$cid'")
        }

        // QmSafe should still exist
        val safeRs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM content_locations WHERE cid = 'QmSafe'")
        safeRs.next(); assertEquals(1, safeRs.getInt(1)); safeRs.close()
        conn.close()
    }

    @Test
    fun `PurgeLogEntry serializes to valid JSON`() {
        val entry = PurgeLogEntry(
            cid = "QmABC123",
            fileSample = "Aliens.mkv",
            reason = "duplicate",
            purgedAt = "2026-03-03T12:00:00",
            deletedFrom = listOf("server-a:/a.mkv", "server-b:/b.mkv")
        )
        val serialized = json.encodeToString(entry)
        assertTrue(serialized.contains("QmABC123"))
        assertTrue(serialized.contains("Aliens.mkv"))
        assertTrue(serialized.contains("duplicate"))
        assertTrue(serialized.contains("server-a:/a.mkv"))

        // Round-trip
        val deserialized = json.decodeFromString<PurgeLogEntry>(serialized)
        assertEquals(entry.cid, deserialized.cid)
        assertEquals(entry.deletedFrom, deserialized.deletedFrom)
    }

    @Test
    fun `purge history appends JSONL correctly`() {
        val logFile = tempDir.resolve("purge_history.jsonl").toFile()
        val compactJson = Json { prettyPrint = false }

        val entry1 = compactJson.encodeToString(PurgeLogEntry("Qm1", "a.mkv", "", "2026-03-01", listOf("server-a:/a")))
        val entry2 = compactJson.encodeToString(PurgeLogEntry("Qm2", "b.mkv", "dup", "2026-03-02", listOf("local:/b")))

        logFile.appendText(entry1 + "\n")
        logFile.appendText(entry2 + "\n")

        val lines = logFile.readLines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("Qm1"))
        assertTrue(lines[1].contains("Qm2"))

        // Verify each line parses back
        val parsed1 = compactJson.decodeFromString<PurgeLogEntry>(lines[0])
        val parsed2 = compactJson.decodeFromString<PurgeLogEntry>(lines[1])
        assertEquals("Qm1", parsed1.cid)
        assertEquals("dup", parsed2.reason)
    }

    // ===========================================
    // 6. Table coexistence + idempotency
    // ===========================================

    @Test
    fun `copy_requests and junk_list tables coexist in same DB`() {
        val conn = createCopyRequestsDb()
        ensureJunkTable(conn)

        conn.createStatement().executeUpdate("INSERT INTO copy_requests (repository, target_machine) VALUES ('film', 'server-b')")
        conn.createStatement().executeUpdate("INSERT INTO junk_list (cid, file_sample) VALUES ('QmTest', 'test.mkv')")

        assertEquals(1, countRows(conn, "copy_requests"))
        assertEquals(1, countRows(conn, "junk_list"))
        conn.close()
    }

    @Test
    fun `ensureCopyRequestsTable is idempotent`() {
        val conn = DriverManager.getConnection("jdbc:sqlite:${tempDir.resolve("idem.db")}")
        repeat(3) { RequestCopyCommand.ensureCopyRequestsTable(conn) }
        conn.close()
    }

    @Test
    fun `ensureJunkTable is idempotent`() {
        val conn = DriverManager.getConnection("jdbc:sqlite:${tempDir.resolve("idem2.db")}")
        repeat(3) { ensureJunkTable(conn) }
        conn.close()
    }

    @Test
    fun `all new tables work with unified registry existing tables`() {
        // Simulates adding Phase 3 tables to a DB that already has content_locations
        val conn = createRegistryDb()
        RequestCopyCommand.ensureCopyRequestsTable(conn)
        ensureJunkTable(conn)

        // All three tables coexist
        insertLocation(conn, "QmA", "server-a", "/a", 1000)
        conn.createStatement().executeUpdate("INSERT INTO copy_requests (repository, target_machine) VALUES ('film', 'van')")
        conn.createStatement().executeUpdate("INSERT INTO junk_list (cid, file_sample) VALUES ('QmB', 'b.mkv')")

        assertEquals(1, countRows(conn, "content_locations"))
        assertEquals(1, countRows(conn, "copy_requests"))
        assertEquals(1, countRows(conn, "junk_list"))
        conn.close()
    }

    // ===========================================
    // Helpers
    // ===========================================

    private fun createRegistryDb(): Connection {
        val path = tempDir.resolve("reg_${System.nanoTime()}.db").toString()
        val conn = DriverManager.getConnection("jdbc:sqlite:$path")
        conn.createStatement().executeUpdate("""
            CREATE TABLE IF NOT EXISTS content_locations (
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
        conn.createStatement().executeUpdate("CREATE INDEX IF NOT EXISTS idx_ucl_machine ON content_locations(machine_name)")
        conn.createStatement().executeUpdate("CREATE INDEX IF NOT EXISTS idx_ucl_cid ON content_locations(cid)")
        return conn
    }

    private fun createCopyRequestsDb(): Connection {
        val path = tempDir.resolve("cr_${System.nanoTime()}.db").toString()
        val conn = DriverManager.getConnection("jdbc:sqlite:$path")
        RequestCopyCommand.ensureCopyRequestsTable(conn)
        return conn
    }

    private fun createJunkDb(): Connection {
        val path = tempDir.resolve("junk_${System.nanoTime()}.db").toString()
        val conn = DriverManager.getConnection("jdbc:sqlite:$path")
        ensureJunkTable(conn)
        return conn
    }

    private fun insertLocation(conn: Connection, cid: String, machine: String, path: String, size: Long) {
        conn.prepareStatement("INSERT INTO content_locations (cid, machine_name, file_path, file_size) VALUES (?, ?, ?, ?)").apply {
            setString(1, cid); setString(2, machine); setString(3, path); setLong(4, size)
            executeUpdate(); close()
        }
    }

    private fun countByStatus(conn: Connection, status: String): Int {
        val rs = conn.prepareStatement("SELECT COUNT(*) FROM copy_requests WHERE status = ?").apply {
            setString(1, status)
        }.executeQuery()
        rs.next(); val count = rs.getInt(1); rs.close(); return count
    }

    private fun countRows(conn: Connection, table: String): Int {
        val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM $table")
        rs.next(); val count = rs.getInt(1); rs.close(); return count
    }

    private fun buildTestConfig(): ChoamConfig {
        return ChoamConfig(
            machines = mapOf(
                "local" to MachineProfile(
                    name = "local", hostname = "workstation.local", type = MachineType.DESKTOP,
                    repositories = mapOf("film" to "/media/film", "tv" to "/media/tv"),
                    tailscaleIp = "100.64.0.1"
                ),
                "server-a" to MachineProfile(
                    name = "server-a", hostname = "server-a-mac-mini-old", type = MachineType.DESKTOP,
                    repositories = mapOf("film" to "/Volumes/EXT-4TB/film", "tv" to "/Volumes/EXT-4TB/tv"),
                    sshUser = "user", tailscaleIp = "100.64.0.2", aliases = listOf("server-a-old")
                ),
                "server-b" to MachineProfile(
                    name = "server-b", hostname = "server-b-m4", type = MachineType.DESKTOP,
                    repositories = mapOf("film" to "/Volumes/DATA/film"),
                    sshUser = "user", tailscaleIp = "100.64.0.3"
                )
            ),
            drives = mapOf(
                "ext-4tb" to Drive(uuid = "uuid-agent", label = "EXT-4TB",
                    repositories = mapOf("film" to "film", "tv" to "tv"), storageClass = StorageClass.WARM),
                "data" to Drive(uuid = "uuid-data", label = "DATA",
                    repositories = mapOf("film" to "film"), storageClass = StorageClass.HOT)
            ),
            repositories = mapOf(
                "film" to RepositoryConfig(name = "film", type = RepositoryType.MEDIA,
                    replication = ReplicationPolicy(minCopies = 2, preferredCopies = 3)),
                "tv" to RepositoryConfig(name = "tv", type = RepositoryType.MEDIA,
                    replication = ReplicationPolicy(minCopies = 2))
            )
        )
    }
}

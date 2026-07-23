package vision.salient.choam.federation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import vision.salient.choam.cli.backupCommand
import vision.salient.choam.cli.houseCommand
import vision.salient.choam.cli.parseSize
import vision.salient.choam.cli.shareCommand
import vision.salient.choam.config.*
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Comprehensive tests for Phase 6: Federation (CHOAM Houses).
 *
 * Covers: config model, federation persistence, share grants lifecycle,
 * ACL enforcement, backup agreement state machine, audit logging,
 * size parsing, and config serialization.
 */
class FederationTest {

    @TempDir
    lateinit var tempDir: Path

    // ===========================================
    // Config model tests
    // ===========================================

    @Test
    fun `AccessLevel has three tiers matching VISION_DOC`() {
        assertEquals(3, AccessLevel.entries.size)
        assertTrue(AccessLevel.entries.containsAll(listOf(AccessLevel.STORE, AccessLevel.READ, AccessLevel.WRITE)))
    }

    @Test
    fun `HouseConfig defaults are sensible`() {
        val house = HouseConfig(name = "test-house")
        assertEquals("test-house", house.name)
        assertEquals("", house.houseId)
        assertEquals("", house.publicKey)
        assertTrue(house.peers.isEmpty())
    }

    @Test
    fun `PeerHouse stores all connection details`() {
        val peer = PeerHouse(
            name = "house-server-b", houseId = "abc123",
            publicKey = "pubkey", tailscaleIp = "100.64.0.3",
            sshUser = "user", sshPort = 22
        )
        assertEquals("100.64.0.3", peer.tailscaleIp)
        assertEquals("user", peer.sshUser)
    }

    @Test
    fun `ShareGrant stores all fields`() {
        val grant = ShareGrant(
            repository = "film", peerHouseId = "abc", access = AccessLevel.READ,
            grantedAt = "2026-03-03", note = "test share"
        )
        assertEquals("film", grant.repository)
        assertEquals(AccessLevel.READ, grant.access)
        assertEquals("test share", grant.note)
    }

    @Test
    fun `BackupStatus has five states`() {
        assertEquals(5, BackupStatus.entries.size)
        assertTrue(BackupStatus.entries.containsAll(listOf(
            BackupStatus.PROPOSED, BackupStatus.ACCEPTED, BackupStatus.ACTIVE,
            BackupStatus.SUSPENDED, BackupStatus.TERMINATED
        )))
    }

    @Test
    fun `ChoamConfig with house field saves and loads`() {
        val path = tempDir.resolve("config.json")
        val config = ChoamConfig(
            house = HouseConfig(
                name = "test", houseId = "abc123", publicKey = "pub",
                description = "Test house", createdAt = "2026-03-03",
                peers = mapOf("def456" to PeerHouse(name = "peer", houseId = "def456"))
            )
        )
        ChoamConfigLoader.save(config, path)
        val loaded = ChoamConfigLoader.load(path)

        assertNotNull(loaded.house)
        assertEquals("test", loaded.house!!.name)
        assertEquals("abc123", loaded.house!!.houseId)
        assertEquals(1, loaded.house!!.peers.size)
        assertEquals("peer", loaded.house!!.peers["def456"]?.name)
    }

    @Test
    fun `config without house loads with null (backward compat)`() {
        val path = tempDir.resolve("config.json")
        java.nio.file.Files.writeString(path, """{"version": "1.0.0"}""")
        val loaded = ChoamConfigLoader.load(path)
        assertNull(loaded.house)
    }

    // ===========================================
    // FederationStore table creation
    // ===========================================

    @Test
    fun `ensureTables creates all three tables`() {
        val conn = createFedDb()
        // Verify tables exist by querying them
        conn.createStatement().executeQuery("SELECT COUNT(*) FROM share_grants").close()
        conn.createStatement().executeQuery("SELECT COUNT(*) FROM backup_agreements").close()
        conn.createStatement().executeQuery("SELECT COUNT(*) FROM federation_log").close()
        conn.close()
    }

    @Test
    fun `ensureTables is idempotent`() {
        val conn = createFedDb()
        FederationStore.ensureTables(conn) // second call
        FederationStore.ensureTables(conn) // third call
        conn.close()
    }

    // ===========================================
    // Share grant lifecycle
    // ===========================================

    @Test
    fun `grantShare creates new share`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("fed.db").toString())

        store.grantShare(conn, ShareGrant("film", "peer1", AccessLevel.READ))

        val shares = store.listActiveShares(conn)
        assertEquals(1, shares.size)
        assertEquals("film", shares[0].repository)
        assertEquals("peer1", shares[0].peerHouseId)
        assertEquals(AccessLevel.READ, shares[0].access)
        conn.close()
    }

    @Test
    fun `grantShare upserts on same repo-peer pair`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("fed.db").toString())

        store.grantShare(conn, ShareGrant("film", "peer1", AccessLevel.READ))
        store.grantShare(conn, ShareGrant("film", "peer1", AccessLevel.WRITE)) // upgrade

        val shares = store.listActiveShares(conn)
        assertEquals(1, shares.size)
        assertEquals(AccessLevel.WRITE, shares[0].access) // upgraded
        conn.close()
    }

    @Test
    fun `revokeShare marks grant as revoked`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("fed.db").toString())

        store.grantShare(conn, ShareGrant("film", "peer1", AccessLevel.READ))
        val revoked = store.revokeShare(conn, "film", "peer1")

        assertTrue(revoked)
        assertEquals(0, store.listActiveShares(conn).size)
        conn.close()
    }

    @Test
    fun `revokeShare returns false for nonexistent grant`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("fed.db").toString())

        assertFalse(store.revokeShare(conn, "film", "ghost"))
        conn.close()
    }

    @Test
    fun `revokeShare is idempotent`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("fed.db").toString())

        store.grantShare(conn, ShareGrant("film", "peer1", AccessLevel.READ))
        assertTrue(store.revokeShare(conn, "film", "peer1"))
        assertFalse(store.revokeShare(conn, "film", "peer1")) // already revoked
        conn.close()
    }

    @Test
    fun `re-grant after revoke creates new active grant`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("fed.db").toString())

        store.grantShare(conn, ShareGrant("film", "peer1", AccessLevel.READ))
        store.revokeShare(conn, "film", "peer1")
        store.grantShare(conn, ShareGrant("film", "peer1", AccessLevel.WRITE))

        val shares = store.listActiveShares(conn)
        assertEquals(1, shares.size)
        assertEquals(AccessLevel.WRITE, shares[0].access)
        conn.close()
    }

    @Test
    fun `getSharesForPeer returns only that peers shares`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("fed.db").toString())

        store.grantShare(conn, ShareGrant("film", "peer1", AccessLevel.READ))
        store.grantShare(conn, ShareGrant("tv", "peer1", AccessLevel.READ))
        store.grantShare(conn, ShareGrant("film", "peer2", AccessLevel.WRITE))

        val peer1 = store.getSharesForPeer(conn, "peer1")
        assertEquals(2, peer1.size)

        val peer2 = store.getSharesForPeer(conn, "peer2")
        assertEquals(1, peer2.size)
        assertEquals(AccessLevel.WRITE, peer2[0].access)
        conn.close()
    }

    // ===========================================
    // ACL enforcement
    // ===========================================

    @Test
    fun `checkAccess returns correct level for active grant`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("fed.db").toString())

        store.grantShare(conn, ShareGrant("film", "peer1", AccessLevel.READ))

        assertEquals(AccessLevel.READ, store.checkAccess(conn, "film", "peer1"))
        conn.close()
    }

    @Test
    fun `checkAccess returns null for no grant`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("fed.db").toString())

        assertNull(store.checkAccess(conn, "film", "ghost"))
        conn.close()
    }

    @Test
    fun `checkAccess returns null for revoked grant`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("fed.db").toString())

        store.grantShare(conn, ShareGrant("film", "peer1", AccessLevel.READ))
        store.revokeShare(conn, "film", "peer1")

        assertNull(store.checkAccess(conn, "film", "peer1"))
        conn.close()
    }

    @Test
    fun `STORE access does not imply READ`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("fed.db").toString())

        store.grantShare(conn, ShareGrant("backup", "peer1", AccessLevel.STORE))

        val level = store.checkAccess(conn, "backup", "peer1")
        assertEquals(AccessLevel.STORE, level)
        // Application must enforce: STORE < READ < WRITE
        assertTrue(level!!.ordinal < AccessLevel.READ.ordinal)
        conn.close()
    }

    @Test
    fun `WRITE is highest access level`() {
        assertTrue(AccessLevel.WRITE.ordinal > AccessLevel.READ.ordinal)
        assertTrue(AccessLevel.READ.ordinal > AccessLevel.STORE.ordinal)
    }

    // ===========================================
    // Backup agreement state machine
    // ===========================================

    @Test
    fun `proposeBackup creates PROPOSED agreement`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("fed.db").toString())

        store.proposeBackup(conn, "peer1", 2_199_023_255_552) // 2TB

        val agreements = store.listBackupAgreements(conn)
        assertEquals(1, agreements.size)
        assertEquals(BackupStatus.PROPOSED, agreements[0].status)
        assertEquals(2_199_023_255_552, agreements[0].offeredBytes)
        conn.close()
    }

    @Test
    fun `acceptBackup transitions PROPOSED to ACCEPTED`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("fed.db").toString())

        store.proposeBackup(conn, "peer1", 2_199_023_255_552)
        val accepted = store.acceptBackup(conn, "peer1", 1_099_511_627_776) // they offer 1TB

        assertTrue(accepted)
        val agreements = store.listBackupAgreements(conn)
        assertEquals(BackupStatus.ACCEPTED, agreements[0].status)
        assertEquals(1_099_511_627_776, agreements[0].theirOfferedBytes)
        assertNotNull(agreements[0].acceptedAt)
        conn.close()
    }

    @Test
    fun `acceptBackup fails for nonexistent agreement`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("fed.db").toString())

        assertFalse(store.acceptBackup(conn, "ghost", 0))
        conn.close()
    }

    @Test
    fun `updateBackupStatus transitions correctly`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("fed.db").toString())

        store.proposeBackup(conn, "peer1", 1000)
        store.acceptBackup(conn, "peer1", 500)

        assertTrue(store.updateBackupStatus(conn, "peer1", BackupStatus.ACTIVE))
        assertEquals(BackupStatus.ACTIVE, store.listBackupAgreements(conn)[0].status)

        assertTrue(store.updateBackupStatus(conn, "peer1", BackupStatus.SUSPENDED))
        assertEquals(BackupStatus.SUSPENDED, store.listBackupAgreements(conn)[0].status)

        assertTrue(store.updateBackupStatus(conn, "peer1", BackupStatus.TERMINATED))
        assertEquals(BackupStatus.TERMINATED, store.listBackupAgreements(conn)[0].status)
        conn.close()
    }

    @Test
    fun `proposeBackup upserts existing agreement`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("fed.db").toString())

        store.proposeBackup(conn, "peer1", 1000)
        store.proposeBackup(conn, "peer1", 2000) // update offer

        val agreements = store.listBackupAgreements(conn)
        assertEquals(1, agreements.size)
        assertEquals(2000, agreements[0].offeredBytes)
        assertEquals(BackupStatus.PROPOSED, agreements[0].status) // reset
        conn.close()
    }

    // ===========================================
    // Audit log
    // ===========================================

    @Test
    fun `share operations are logged`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("fed.db").toString())

        store.grantShare(conn, ShareGrant("film", "peer1", AccessLevel.READ))
        store.revokeShare(conn, "film", "peer1")

        val rs = conn.createStatement().executeQuery(
            "SELECT event_type FROM federation_log ORDER BY id"
        )
        val events = mutableListOf<String>()
        while (rs.next()) events.add(rs.getString("event_type"))
        rs.close()

        assertEquals(listOf("SHARE_GRANTED", "SHARE_REVOKED"), events)
        conn.close()
    }

    @Test
    fun `backup operations are logged`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("fed.db").toString())

        store.proposeBackup(conn, "peer1", 1000)
        store.acceptBackup(conn, "peer1", 500)

        val rs = conn.createStatement().executeQuery(
            "SELECT event_type FROM federation_log ORDER BY id"
        )
        val events = mutableListOf<String>()
        while (rs.next()) events.add(rs.getString("event_type"))
        rs.close()

        assertEquals(listOf("BACKUP_PROPOSED", "BACKUP_ACCEPTED"), events)
        conn.close()
    }

    @Test
    fun `log entries have timestamps`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("fed.db").toString())

        store.logEvent(conn, "TEST_EVENT", "peer1", "film", "test detail")

        val rs = conn.createStatement().executeQuery("SELECT timestamp, detail FROM federation_log")
        rs.next()
        assertNotNull(rs.getString("timestamp"))
        assertEquals("test detail", rs.getString("detail"))
        rs.close(); conn.close()
    }

    // ===========================================
    // Size parsing
    // ===========================================

    @Test
    fun `parseSize handles TB`() {
        assertEquals(2_199_023_255_552, parseSize("2TB"))
    }

    @Test
    fun `parseSize handles GB`() {
        assertEquals(536_870_912_000, parseSize("500GB"))
    }

    @Test
    fun `parseSize handles MB`() {
        assertEquals(104_857_600, parseSize("100MB"))
    }

    @Test
    fun `parseSize is case-insensitive`() {
        assertEquals(parseSize("2TB"), parseSize("2tb"))
        assertEquals(parseSize("500GB"), parseSize("500gb"))
    }

    @Test
    fun `parseSize handles whitespace`() {
        assertEquals(parseSize("2TB"), parseSize("  2TB  "))
    }

    @Test
    fun `parseSize returns null for invalid input`() {
        assertNull(parseSize("abc"))
        assertNull(parseSize(""))
        assertNull(parseSize("TB"))
    }

    @Test
    fun `parseSize handles fractional values`() {
        assertNotNull(parseSize("1.5TB"))
        assertTrue(parseSize("1.5TB")!! > parseSize("1TB")!!)
        assertTrue(parseSize("1.5TB")!! < parseSize("2TB")!!)
    }

    // ===========================================
    // Integration: mixed operations
    // ===========================================

    @Test
    fun `shares and backups coexist for same peer`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("fed.db").toString())

        store.grantShare(conn, ShareGrant("film", "peer1", AccessLevel.READ))
        store.proposeBackup(conn, "peer1", 1000)

        assertEquals(1, store.listActiveShares(conn).size)
        assertEquals(1, store.listBackupAgreements(conn).size)
        conn.close()
    }

    @Test
    fun `multiple peers with different access levels`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("fed.db").toString())

        store.grantShare(conn, ShareGrant("film", "peer1", AccessLevel.READ))
        store.grantShare(conn, ShareGrant("film", "peer2", AccessLevel.WRITE))
        store.grantShare(conn, ShareGrant("film", "peer3", AccessLevel.STORE))

        assertEquals(AccessLevel.READ, store.checkAccess(conn, "film", "peer1"))
        assertEquals(AccessLevel.WRITE, store.checkAccess(conn, "film", "peer2"))
        assertEquals(AccessLevel.STORE, store.checkAccess(conn, "film", "peer3"))
        conn.close()
    }

    @Test
    fun `revoking one peer does not affect others`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("fed.db").toString())

        store.grantShare(conn, ShareGrant("film", "peer1", AccessLevel.READ))
        store.grantShare(conn, ShareGrant("film", "peer2", AccessLevel.WRITE))
        store.revokeShare(conn, "film", "peer1")

        assertNull(store.checkAccess(conn, "film", "peer1"))
        assertEquals(AccessLevel.WRITE, store.checkAccess(conn, "film", "peer2"))
        conn.close()
    }

    // ===========================================
    // Helpers
    // ===========================================

    private fun createFedDb(): Connection {
        val path = tempDir.resolve("fed_${System.nanoTime()}.db").toString()
        val conn = DriverManager.getConnection("jdbc:sqlite:$path")
        FederationStore.ensureTables(conn)
        return conn
    }
}

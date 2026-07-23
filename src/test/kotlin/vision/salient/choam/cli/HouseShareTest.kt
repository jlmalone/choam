package vision.salient.choam.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import vision.salient.choam.config.*
import vision.salient.choam.federation.FederationStore
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for HouseCommand config logic, ShareCommand ACL enforcement,
 * and parseSize edge cases.
 */
class HouseShareTest {

    @TempDir
    lateinit var tempDir: Path

    // ===========================================
    // House config model
    // ===========================================

    @Test
    fun `HouseConfig with peers serializes round-trip`() {
        val path = tempDir.resolve("config.json")
        val house = HouseConfig(
            name = "test-house", houseId = "abc123", publicKey = "pubhex",
            description = "Test", createdAt = "2026-03-03",
            peers = mapOf(
                "def456" to PeerHouse(name = "peer-1", houseId = "def456", tailscaleIp = "100.1.2.3"),
                "ghi789" to PeerHouse(name = "peer-2", houseId = "ghi789", sshUser = "joe", sshPort = 2222)
            )
        )
        val config = ChoamConfig(house = house)
        ChoamConfigLoader.save(config, path)
        val loaded = ChoamConfigLoader.load(path)

        assertEquals(2, loaded.house!!.peers.size)
        assertEquals("100.1.2.3", loaded.house!!.peers["def456"]?.tailscaleIp)
        assertEquals("joe", loaded.house!!.peers["ghi789"]?.sshUser)
        assertEquals(2222, loaded.house!!.peers["ghi789"]?.sshPort)
    }

    @Test
    fun `house null in config is backward compatible`() {
        val path = tempDir.resolve("config.json")
        java.nio.file.Files.writeString(path, """{"version":"1.0.0","machines":{},"repositories":{}}""")
        val loaded = ChoamConfigLoader.load(path)
        assertNull(loaded.house)
    }

    @Test
    fun `adding peer to house creates updated config`() {
        val house = HouseConfig(name = "my-house", houseId = "me123")
        val peer = PeerHouse(name = "friend", houseId = "friend456", tailscaleIp = "100.5.6.7")
        val updatedPeers = house.peers + ("friend456" to peer)
        val updatedHouse = house.copy(peers = updatedPeers)

        assertEquals(1, updatedHouse.peers.size)
        assertEquals("friend", updatedHouse.peers["friend456"]?.name)
    }

    @Test
    fun `peer deduplication by houseId`() {
        val house = HouseConfig(
            name = "my-house", houseId = "me123",
            peers = mapOf("p1" to PeerHouse(name = "peer", houseId = "p1"))
        )
        // Adding same ID overwrites
        val updated = house.copy(peers = house.peers + ("p1" to PeerHouse(name = "peer-renamed", houseId = "p1")))
        assertEquals(1, updated.peers.size)
        assertEquals("peer-renamed", updated.peers["p1"]?.name)
    }

    // ===========================================
    // Share grant + ACL enforcement via FederationStore
    // ===========================================

    @Test
    fun `grant then check access returns correct level`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("f.db").toString())

        store.grantShare(conn, ShareGrant("film", "peer1", AccessLevel.READ))
        assertEquals(AccessLevel.READ, store.checkAccess(conn, "film", "peer1"))
        conn.close()
    }

    @Test
    fun `upgrade access level via re-grant`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("f.db").toString())

        store.grantShare(conn, ShareGrant("film", "peer1", AccessLevel.READ))
        store.grantShare(conn, ShareGrant("film", "peer1", AccessLevel.WRITE))
        assertEquals(AccessLevel.WRITE, store.checkAccess(conn, "film", "peer1"))
        conn.close()
    }

    @Test
    fun `downgrade access level via re-grant`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("f.db").toString())

        store.grantShare(conn, ShareGrant("film", "peer1", AccessLevel.WRITE))
        store.grantShare(conn, ShareGrant("film", "peer1", AccessLevel.STORE))
        assertEquals(AccessLevel.STORE, store.checkAccess(conn, "film", "peer1"))
        conn.close()
    }

    @Test
    fun `revoked grant returns null access`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("f.db").toString())

        store.grantShare(conn, ShareGrant("film", "peer1", AccessLevel.WRITE))
        store.revokeShare(conn, "film", "peer1")
        assertNull(store.checkAccess(conn, "film", "peer1"))
        conn.close()
    }

    @Test
    fun `access to unshared repo returns null`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("f.db").toString())

        store.grantShare(conn, ShareGrant("film", "peer1", AccessLevel.READ))
        assertNull(store.checkAccess(conn, "tv", "peer1")) // different repo
        conn.close()
    }

    @Test
    fun `access by unshared peer returns null`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("f.db").toString())

        store.grantShare(conn, ShareGrant("film", "peer1", AccessLevel.READ))
        assertNull(store.checkAccess(conn, "film", "peer2")) // different peer
        conn.close()
    }

    @Test
    fun `STORE ordinal is less than READ which is less than WRITE`() {
        assertTrue(AccessLevel.STORE.ordinal < AccessLevel.READ.ordinal)
        assertTrue(AccessLevel.READ.ordinal < AccessLevel.WRITE.ordinal)
    }

    @Test
    fun `share note preserved across grant`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("f.db").toString())

        store.grantShare(conn, ShareGrant("film", "peer1", AccessLevel.READ, note = "for backup project"))
        val shares = store.listActiveShares(conn)
        assertEquals("for backup project", shares[0].note)
        conn.close()
    }

    @Test
    fun `list active shares excludes revoked`() {
        val conn = createFedDb()
        val store = FederationStore(tempDir.resolve("f.db").toString())

        store.grantShare(conn, ShareGrant("film", "peer1", AccessLevel.READ))
        store.grantShare(conn, ShareGrant("tv", "peer1", AccessLevel.WRITE))
        store.revokeShare(conn, "film", "peer1")

        val active = store.listActiveShares(conn)
        assertEquals(1, active.size)
        assertEquals("tv", active[0].repository)
        conn.close()
    }

    // ===========================================
    // parseSize extended edge cases
    // ===========================================

    @Test
    fun `parseSize handles B suffix`() {
        assertEquals(1024, parseSize("1024B"))
    }

    @Test
    fun `parseSize handles KB`() {
        assertEquals(1024, parseSize("1KB"))
    }

    @Test
    fun `parseSize rejects negative numbers`() {
        assertNull(parseSize("-1GB"))
    }

    @Test
    fun `parseSize rejects zero-length input`() {
        assertNull(parseSize(""))
    }

    @Test
    fun `parseSize rejects unit only`() {
        assertNull(parseSize("GB"))
    }

    @Test
    fun `parseSize rejects number only`() {
        assertNull(parseSize("500"))
    }

    @Test
    fun `parseSize handles 0GB`() {
        assertEquals(0, parseSize("0GB"))
    }

    @Test
    fun `parseSize handles decimal TB`() {
        val onePointFive = parseSize("1.5TB")!!
        val one = parseSize("1TB")!!
        val two = parseSize("2TB")!!
        assertTrue(onePointFive > one)
        assertTrue(onePointFive < two)
    }

    @Test
    fun `parseSize is case insensitive`() {
        assertEquals(parseSize("2TB"), parseSize("2tb"))
        assertEquals(parseSize("500GB"), parseSize("500gb"))
        assertEquals(parseSize("100MB"), parseSize("100mb"))
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

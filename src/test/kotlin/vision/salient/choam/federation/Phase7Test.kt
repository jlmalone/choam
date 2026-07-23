package vision.salient.choam.federation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import vision.salient.choam.config.*
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for Phase 7: Advanced — mobile nodes, gossip protocol, bandwidth economy, streaming.
 */
class Phase7Test {

    @TempDir
    lateinit var tempDir: Path

    // ===========================================
    // Node capability model
    // ===========================================

    @Test
    fun `NodeType has seven types covering all device classes`() {
        assertEquals(7, NodeType.entries.size)
        assertTrue(NodeType.entries.containsAll(listOf(
            NodeType.PHONE, NodeType.TABLET, NodeType.LAPTOP,
            NodeType.DESKTOP, NodeType.SERVER, NodeType.NAS, NodeType.CLOUD
        )))
    }

    @Test
    fun `SyncSchedule has five modes`() {
        assertEquals(5, SyncSchedule.entries.size)
    }

    @Test
    fun `NodeCapability defaults are desktop-appropriate`() {
        val cap = NodeCapability()
        assertEquals(NodeType.DESKTOP, cap.nodeType)
        assertEquals(false, cap.batteryPowered)
        assertEquals(false, cap.alwaysOn)
        assertEquals(SyncSchedule.ON_DEMAND, cap.syncSchedule)
    }

    @Test
    fun `MobileProfile defaults are conservative`() {
        val mobile = MobileProfile()
        assertEquals(1_073_741_824, mobile.maxCacheBytes) // 1GB
        assertTrue(mobile.requireWifi)
        assertEquals(false, mobile.requireCharging)
        assertTrue(mobile.autoPurgeWhenFull)
    }

    @Test
    fun `server node has always-on and continuous sync`() {
        val server = NodeCapability(
            nodeType = NodeType.SERVER,
            alwaysOn = true,
            syncSchedule = SyncSchedule.CONTINUOUS,
            canStream = true
        )
        assertTrue(server.alwaysOn)
        assertTrue(server.canStream)
        assertEquals(SyncSchedule.CONTINUOUS, server.syncSchedule)
    }

    @Test
    fun `phone node has battery and wifi constraints`() {
        val phone = NodeCapability(
            nodeType = NodeType.PHONE,
            batteryPowered = true,
            syncSchedule = SyncSchedule.ON_WIFI,
            storageCapacityBytes = 64_000_000_000 // 64GB
        )
        assertTrue(phone.batteryPowered)
        assertEquals(SyncSchedule.ON_WIFI, phone.syncSchedule)
    }

    // ===========================================
    // Gossip protocol
    // ===========================================

    @Test
    fun `gossip tables are created`() {
        val conn = createGossipDb()
        conn.createStatement().executeQuery("SELECT COUNT(*) FROM announcements").close()
        conn.close()
    }

    @Test
    fun `createAnnouncement stores announcement`() {
        val conn = createGossipDb()
        val gossip = GossipProtocol(tempDir.resolve("g.db").toString())

        gossip.createAnnouncement(
            conn, "house1", "test-house",
            NodeCapability(nodeType = NodeType.DESKTOP),
            cidCount = 1000, totalSizeBytes = 5_000_000_000,
            needsReplication = 50, sharedRepos = listOf("film", "tv")
        )

        val announcements = gossip.getLatestAnnouncements(conn)
        assertEquals(1, announcements.size)
        assertEquals("house1", announcements[0].houseId)
        assertEquals(1000, announcements[0].cidCount)
        assertEquals(listOf("film", "tv"), announcements[0].sharedRepos)
        conn.close()
    }

    @Test
    fun `receiveAnnouncement stores peer announcement`() {
        val conn = createGossipDb()
        val gossip = GossipProtocol(tempDir.resolve("g.db").toString())

        gossip.receiveAnnouncement(conn, GossipAnnouncement(
            houseId = "peer1", houseName = "peer-house",
            cidCount = 500, totalSizeBytes = 2_000_000_000,
            timestamp = "2026-03-03T12:00:00"
        ))

        val announcements = gossip.getLatestAnnouncements(conn)
        assertEquals(1, announcements.size)
        assertEquals("peer1", announcements[0].houseId)
        conn.close()
    }

    @Test
    fun `getLatestAnnouncements returns only most recent per house`() {
        val conn = createGossipDb()
        val gossip = GossipProtocol(tempDir.resolve("g.db").toString())

        // Older announcement
        gossip.receiveAnnouncement(conn, GossipAnnouncement(
            houseId = "peer1", houseName = "peer", cidCount = 100,
            timestamp = "2026-03-01T00:00:00"
        ))
        // Newer announcement
        gossip.receiveAnnouncement(conn, GossipAnnouncement(
            houseId = "peer1", houseName = "peer", cidCount = 200,
            timestamp = "2026-03-03T00:00:00"
        ))

        val latest = gossip.getLatestAnnouncements(conn)
        assertEquals(1, latest.size) // Only latest
        assertEquals(200, latest[0].cidCount)
        conn.close()
    }

    @Test
    fun `multiple peers each get their own latest`() {
        val conn = createGossipDb()
        val gossip = GossipProtocol(tempDir.resolve("g.db").toString())

        gossip.receiveAnnouncement(conn, GossipAnnouncement(
            houseId = "peer1", houseName = "alpha", cidCount = 100, timestamp = "2026-03-03T00:00:00"
        ))
        gossip.receiveAnnouncement(conn, GossipAnnouncement(
            houseId = "peer2", houseName = "beta", cidCount = 200, timestamp = "2026-03-03T01:00:00"
        ))

        val latest = gossip.getLatestAnnouncements(conn)
        assertEquals(2, latest.size)
        conn.close()
    }

    @Test
    fun `prune removes old announcements keeping latest N`() {
        val conn = createGossipDb()
        val gossip = GossipProtocol(tempDir.resolve("g.db").toString())

        // Insert 15 announcements for same peer
        for (i in 1..15) {
            gossip.receiveAnnouncement(conn, GossipAnnouncement(
                houseId = "peer1", houseName = "peer",
                cidCount = i.toLong(), timestamp = "2026-03-03T%02d:00:00".format(i)
            ))
        }

        val pruned = gossip.prune(conn, keepPerHouse = 5)
        assertEquals(10, pruned) // 15 - 5 = 10 pruned

        val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM announcements")
        rs.next()
        assertEquals(5, rs.getInt(1))
        rs.close(); conn.close()
    }

    @Test
    fun `empty gossip db returns empty announcements`() {
        val conn = createGossipDb()
        val gossip = GossipProtocol(tempDir.resolve("g.db").toString())
        assertTrue(gossip.getLatestAnnouncements(conn).isEmpty())
        conn.close()
    }

    // ===========================================
    // Bandwidth economy
    // ===========================================

    @Test
    fun `recordTransfer stores transfer event`() {
        val conn = createEconomyDb()
        val economy = BandwidthEconomy(tempDir.resolve("e.db").toString())

        economy.recordTransfer(conn, "peer1", TransferDirection.UPLOAD, 1_000_000, 10, "film")

        val balance = economy.getBalance(conn, "peer1")
        assertEquals(1_000_000, balance.bytesUploaded)
        assertEquals(0, balance.bytesDownloaded)
        assertEquals(1_000_000, balance.balance)
        assertEquals(1, balance.transferCount)
        conn.close()
    }

    @Test
    fun `balance tracks uploads vs downloads`() {
        val conn = createEconomyDb()
        val economy = BandwidthEconomy(tempDir.resolve("e.db").toString())

        economy.recordTransfer(conn, "peer1", TransferDirection.UPLOAD, 3_000_000_000)
        economy.recordTransfer(conn, "peer1", TransferDirection.DOWNLOAD, 1_000_000_000)

        val balance = economy.getBalance(conn, "peer1")
        assertEquals(3_000_000_000, balance.bytesUploaded)
        assertEquals(1_000_000_000, balance.bytesDownloaded)
        assertEquals(2_000_000_000, balance.balance) // net contributor
        conn.close()
    }

    @Test
    fun `negative balance when consuming more than contributing`() {
        val conn = createEconomyDb()
        val economy = BandwidthEconomy(tempDir.resolve("e.db").toString())

        economy.recordTransfer(conn, "peer1", TransferDirection.UPLOAD, 100_000_000)
        economy.recordTransfer(conn, "peer1", TransferDirection.DOWNLOAD, 5_000_000_000)

        val balance = economy.getBalance(conn, "peer1")
        assertTrue(balance.balance < 0)
        assertEquals(-4_900_000_000, balance.balance)
        conn.close()
    }

    @Test
    fun `getPriority returns HIGH for net contributors over 1GB`() {
        val conn = createEconomyDb()
        val economy = BandwidthEconomy(tempDir.resolve("e.db").toString())

        economy.recordTransfer(conn, "peer1", TransferDirection.UPLOAD, 2_000_000_000)

        assertEquals(TransferPriority.HIGH, economy.getPriority(conn, "peer1"))
        conn.close()
    }

    @Test
    fun `getPriority returns NORMAL for new peer with no history`() {
        val conn = createEconomyDb()
        val economy = BandwidthEconomy(tempDir.resolve("e.db").toString())

        assertEquals(TransferPriority.NORMAL, economy.getPriority(conn, "newpeer"))
        conn.close()
    }

    @Test
    fun `getPriority returns THROTTLED for heavy consumers`() {
        val conn = createEconomyDb()
        val economy = BandwidthEconomy(tempDir.resolve("e.db").toString())

        economy.recordTransfer(conn, "leech", TransferDirection.DOWNLOAD, 5_000_000_000)

        assertEquals(TransferPriority.THROTTLED, economy.getPriority(conn, "leech"))
        conn.close()
    }

    @Test
    fun `getAllBalances sorted by balance descending`() {
        val conn = createEconomyDb()
        val economy = BandwidthEconomy(tempDir.resolve("e.db").toString())

        economy.recordTransfer(conn, "contributor", TransferDirection.UPLOAD, 5_000_000_000)
        economy.recordTransfer(conn, "balanced", TransferDirection.UPLOAD, 1_000_000)
        economy.recordTransfer(conn, "balanced", TransferDirection.DOWNLOAD, 1_000_000)
        economy.recordTransfer(conn, "consumer", TransferDirection.DOWNLOAD, 3_000_000_000)

        val balances = economy.getAllBalances(conn)
        assertEquals(3, balances.size)
        assertEquals("contributor", balances[0].peerHouseId)
        assertEquals("consumer", balances[2].peerHouseId)
        assertTrue(balances[0].balance > balances[1].balance)
        assertTrue(balances[1].balance > balances[2].balance)
        conn.close()
    }

    @Test
    fun `empty economy returns empty balances`() {
        val conn = createEconomyDb()
        val economy = BandwidthEconomy(tempDir.resolve("e.db").toString())
        assertTrue(economy.getAllBalances(conn).isEmpty())
        conn.close()
    }

    // ===========================================
    // Streaming adapter helpers
    // ===========================================

    @Test
    fun `NodeCapability serializes for gossip storage`() {
        val cap = NodeCapability(
            nodeType = NodeType.SERVER,
            storageCapacityBytes = 4_000_000_000_000,
            canStream = true,
            alwaysOn = true,
            syncSchedule = SyncSchedule.CONTINUOUS
        )
        val json = kotlinx.serialization.json.Json.encodeToString(NodeCapability.serializer(), cap)
        assertTrue(json.contains("SERVER"))
        assertTrue(json.contains("CONTINUOUS"))

        val decoded = kotlinx.serialization.json.Json.decodeFromString(NodeCapability.serializer(), json)
        assertEquals(cap, decoded)
    }

    @Test
    fun `MobileProfile serializes round-trip`() {
        val profile = MobileProfile(
            maxCacheBytes = 2_147_483_648,
            uploadOnly = listOf("photos"),
            downloadOnly = listOf("music"),
            requireWifi = true,
            requireCharging = true
        )
        val json = kotlinx.serialization.json.Json.encodeToString(MobileProfile.serializer(), profile)
        val decoded = kotlinx.serialization.json.Json.decodeFromString(MobileProfile.serializer(), json)
        assertEquals(profile, decoded)
    }

    // ===========================================
    // Helpers
    // ===========================================

    private fun createGossipDb(): Connection {
        val path = tempDir.resolve("gossip_${System.nanoTime()}.db").toString()
        val conn = DriverManager.getConnection("jdbc:sqlite:$path")
        GossipProtocol.ensureTables(conn)
        return conn
    }

    private fun createEconomyDb(): Connection {
        val path = tempDir.resolve("econ_${System.nanoTime()}.db").toString()
        val conn = DriverManager.getConnection("jdbc:sqlite:$path")
        BandwidthEconomy.ensureTables(conn)
        return conn
    }
}

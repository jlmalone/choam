package vision.salient.choam.federation

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import vision.salient.choam.config.NodeCapability
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

private val logger = KotlinLogging.logger {}
private val json = Json { prettyPrint = false; encodeDefaults = true; ignoreUnknownKeys = true }

/**
 * Gossip protocol — peer inventory announcements for coordinated replication.
 *
 * Each node periodically announces:
 * - What content it has (CID inventory summary)
 * - What capabilities it offers (storage, streaming, transcoding)
 * - What content it needs (under-replicated CIDs)
 * - What content it can share (with access levels)
 *
 * Announcements are stored locally and exchanged via push/pull with peers.
 * Persistence: ~/.choam/gossip.db
 */
class GossipProtocol(
    private val dbPath: String = "${System.getProperty("user.home")}/.choam/gossip.db"
) {

    fun open(): Connection {
        File(dbPath).parentFile?.mkdirs()
        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        ensureTables(conn)
        return conn
    }

    companion object {
        fun ensureTables(conn: Connection) {
            conn.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS announcements (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    house_id TEXT NOT NULL,
                    house_name TEXT NOT NULL,
                    capabilities TEXT NOT NULL DEFAULT '{}',
                    cid_count INTEGER NOT NULL DEFAULT 0,
                    total_size_bytes INTEGER NOT NULL DEFAULT 0,
                    needs_replication INTEGER NOT NULL DEFAULT 0,
                    shared_repos TEXT NOT NULL DEFAULT '[]',
                    timestamp TEXT NOT NULL DEFAULT (datetime('now')),
                    received_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
            """)

            conn.createStatement().executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_announce_house ON announcements(house_id)"
            )
            conn.createStatement().executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_announce_ts ON announcements(timestamp)"
            )
        }
    }

    /**
     * Create an announcement for this node.
     */
    fun createAnnouncement(
        conn: Connection,
        houseId: String,
        houseName: String,
        capability: NodeCapability,
        cidCount: Long,
        totalSizeBytes: Long,
        needsReplication: Long,
        sharedRepos: List<String>
    ): GossipAnnouncement {
        val announcement = GossipAnnouncement(
            houseId = houseId,
            houseName = houseName,
            capabilities = capability,
            cidCount = cidCount,
            totalSizeBytes = totalSizeBytes,
            needsReplication = needsReplication,
            sharedRepos = sharedRepos
        )

        val stmt = conn.prepareStatement("""
            INSERT INTO announcements (house_id, house_name, capabilities, cid_count, total_size_bytes, needs_replication, shared_repos)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """)
        stmt.setString(1, houseId)
        stmt.setString(2, houseName)
        stmt.setString(3, json.encodeToString(capability))
        stmt.setLong(4, cidCount)
        stmt.setLong(5, totalSizeBytes)
        stmt.setLong(6, needsReplication)
        stmt.setString(7, json.encodeToString(sharedRepos))
        stmt.executeUpdate()
        stmt.close()

        return announcement
    }

    /**
     * Record a received announcement from a peer.
     */
    fun receiveAnnouncement(conn: Connection, announcement: GossipAnnouncement) {
        val stmt = conn.prepareStatement("""
            INSERT INTO announcements (house_id, house_name, capabilities, cid_count, total_size_bytes, needs_replication, shared_repos, timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """)
        stmt.setString(1, announcement.houseId)
        stmt.setString(2, announcement.houseName)
        stmt.setString(3, json.encodeToString(announcement.capabilities))
        stmt.setLong(4, announcement.cidCount)
        stmt.setLong(5, announcement.totalSizeBytes)
        stmt.setLong(6, announcement.needsReplication)
        stmt.setString(7, json.encodeToString(announcement.sharedRepos))
        stmt.setString(8, announcement.timestamp)
        stmt.executeUpdate()
        stmt.close()
    }

    /**
     * Get the latest announcement from each known peer.
     */
    fun getLatestAnnouncements(conn: Connection): List<GossipAnnouncement> {
        val rs = conn.createStatement().executeQuery("""
            SELECT a.* FROM announcements a
            INNER JOIN (
                SELECT house_id, MAX(timestamp) as max_ts FROM announcements GROUP BY house_id
            ) latest ON a.house_id = latest.house_id AND a.timestamp = latest.max_ts
            ORDER BY a.house_name
        """)

        val results = mutableListOf<GossipAnnouncement>()
        while (rs.next()) {
            results.add(GossipAnnouncement(
                houseId = rs.getString("house_id"),
                houseName = rs.getString("house_name"),
                capabilities = try { json.decodeFromString(rs.getString("capabilities")) } catch (_: Exception) { NodeCapability() },
                cidCount = rs.getLong("cid_count"),
                totalSizeBytes = rs.getLong("total_size_bytes"),
                needsReplication = rs.getLong("needs_replication"),
                sharedRepos = try { json.decodeFromString(rs.getString("shared_repos")) } catch (_: Exception) { emptyList() },
                timestamp = rs.getString("timestamp")
            ))
        }
        rs.close()
        return results
    }

    /**
     * Prune old announcements (keep only latest N per house).
     */
    fun prune(conn: Connection, keepPerHouse: Int = 10): Int {
        val rs = conn.createStatement().executeQuery("SELECT DISTINCT house_id FROM announcements")
        val houseIds = mutableListOf<String>()
        while (rs.next()) houseIds.add(rs.getString("house_id"))
        rs.close()

        var pruned = 0
        for (houseId in houseIds) {
            val stmt = conn.prepareStatement("""
                DELETE FROM announcements WHERE house_id = ? AND id NOT IN (
                    SELECT id FROM announcements WHERE house_id = ? ORDER BY timestamp DESC LIMIT ?
                )
            """)
            stmt.setString(1, houseId)
            stmt.setString(2, houseId)
            stmt.setInt(3, keepPerHouse)
            pruned += stmt.executeUpdate()
            stmt.close()
        }
        return pruned
    }
}

@Serializable
data class GossipAnnouncement(
    val houseId: String,
    val houseName: String,
    val capabilities: NodeCapability = NodeCapability(),
    val cidCount: Long = 0,
    val totalSizeBytes: Long = 0,
    val needsReplication: Long = 0,
    val sharedRepos: List<String> = emptyList(),
    val timestamp: String = ""
)

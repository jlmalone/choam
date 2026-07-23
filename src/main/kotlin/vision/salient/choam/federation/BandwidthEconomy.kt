package vision.salient.choam.federation

import kotlinx.serialization.Serializable
import mu.KotlinLogging
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

private val logger = KotlinLogging.logger {}

/**
 * Bandwidth economy — reciprocity tracking between Houses.
 *
 * Peers who store more data for others get priority when they need something.
 * No cryptocurrency — just tracking who contributes and who consumes.
 *
 * The balance for each peer is: bytes_stored_for_them - bytes_they_store_for_us
 * Positive = we're a net contributor (good standing)
 * Negative = we're a net consumer (lower priority)
 *
 * Persistence: stored in gossip.db alongside announcements.
 */
class BandwidthEconomy(
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
                CREATE TABLE IF NOT EXISTS transfer_ledger (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    peer_house_id TEXT NOT NULL,
                    direction TEXT NOT NULL,
                    bytes_transferred INTEGER NOT NULL,
                    cid_count INTEGER NOT NULL DEFAULT 0,
                    repository TEXT,
                    timestamp TEXT NOT NULL DEFAULT (datetime('now'))
                )
            """)

            conn.createStatement().executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_ledger_peer ON transfer_ledger(peer_house_id)"
            )
        }
    }

    /**
     * Record a transfer event (upload or download to/from a peer).
     */
    fun recordTransfer(
        conn: Connection,
        peerHouseId: String,
        direction: TransferDirection,
        bytesTransferred: Long,
        cidCount: Int = 0,
        repository: String? = null
    ) {
        val stmt = conn.prepareStatement("""
            INSERT INTO transfer_ledger (peer_house_id, direction, bytes_transferred, cid_count, repository)
            VALUES (?, ?, ?, ?, ?)
        """)
        stmt.setString(1, peerHouseId)
        stmt.setString(2, direction.name)
        stmt.setLong(3, bytesTransferred)
        stmt.setInt(4, cidCount)
        stmt.setString(5, repository)
        stmt.executeUpdate()
        stmt.close()
    }

    /**
     * Get the balance for a peer.
     * Positive = we've sent more than received (we're contributing)
     * Negative = we've received more than sent (we owe them)
     */
    fun getBalance(conn: Connection, peerHouseId: String): PeerBalance {
        val stmt = conn.prepareStatement("""
            SELECT
                COALESCE(SUM(CASE WHEN direction = 'UPLOAD' THEN bytes_transferred ELSE 0 END), 0) as uploaded,
                COALESCE(SUM(CASE WHEN direction = 'DOWNLOAD' THEN bytes_transferred ELSE 0 END), 0) as downloaded,
                COUNT(*) as transfer_count
            FROM transfer_ledger WHERE peer_house_id = ?
        """)
        stmt.setString(1, peerHouseId)
        val rs = stmt.executeQuery()
        rs.next()
        val uploaded = rs.getLong("uploaded")
        val downloaded = rs.getLong("downloaded")
        val count = rs.getInt("transfer_count")
        rs.close(); stmt.close()

        return PeerBalance(
            peerHouseId = peerHouseId,
            bytesUploaded = uploaded,
            bytesDownloaded = downloaded,
            balance = uploaded - downloaded,
            transferCount = count
        )
    }

    /**
     * Get balances for all peers, sorted by balance (most contributing first).
     */
    fun getAllBalances(conn: Connection): List<PeerBalance> {
        val rs = conn.createStatement().executeQuery("""
            SELECT peer_house_id,
                COALESCE(SUM(CASE WHEN direction = 'UPLOAD' THEN bytes_transferred ELSE 0 END), 0) as uploaded,
                COALESCE(SUM(CASE WHEN direction = 'DOWNLOAD' THEN bytes_transferred ELSE 0 END), 0) as downloaded,
                COUNT(*) as cnt
            FROM transfer_ledger GROUP BY peer_house_id
            ORDER BY (uploaded - downloaded) DESC
        """)

        val results = mutableListOf<PeerBalance>()
        while (rs.next()) {
            val up = rs.getLong("uploaded")
            val down = rs.getLong("downloaded")
            results.add(PeerBalance(
                peerHouseId = rs.getString("peer_house_id"),
                bytesUploaded = up,
                bytesDownloaded = down,
                balance = up - down,
                transferCount = rs.getInt("cnt")
            ))
        }
        rs.close()
        return results
    }

    /**
     * Determine transfer priority for a peer based on their balance.
     * Peers who contribute more get higher priority.
     */
    fun getPriority(conn: Connection, peerHouseId: String): TransferPriority {
        val balance = getBalance(conn, peerHouseId)
        return when {
            balance.transferCount == 0 -> TransferPriority.NORMAL          // New peer, no history
            balance.balance > 1_073_741_824 -> TransferPriority.HIGH       // Net contributor >1GB
            balance.balance > 0 -> TransferPriority.NORMAL                  // Slight contributor
            balance.balance > -1_073_741_824 -> TransferPriority.LOW       // Slight consumer
            else -> TransferPriority.THROTTLED                              // Heavy consumer >1GB deficit
        }
    }
}

enum class TransferDirection {
    UPLOAD,    // We sent data to peer
    DOWNLOAD   // We received data from peer
}

enum class TransferPriority {
    HIGH,      // Net contributor — prioritize their requests
    NORMAL,    // Balanced or new peer
    LOW,       // Net consumer — deprioritize
    THROTTLED  // Heavy consumer — bandwidth-limited
}

@Serializable
data class PeerBalance(
    val peerHouseId: String,
    val bytesUploaded: Long,
    val bytesDownloaded: Long,
    val balance: Long,           // uploaded - downloaded
    val transferCount: Int
)

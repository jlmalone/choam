package vision.salient.choam.dag

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

/**
 * SQLite persistence for the CHOAM DAG.
 *
 * Stores events in an append-only table. Tracks DAG heads (latest event per house).
 * Also stores local-only settings that don't belong in the DAG.
 */
class DagStore(
    private val dbPath: String = "${System.getProperty("user.home")}/.choam/dag.db"
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
                CREATE TABLE IF NOT EXISTS events (
                    id TEXT PRIMARY KEY,
                    version INTEGER NOT NULL DEFAULT 1,
                    parents TEXT NOT NULL,
                    lamport INTEGER NOT NULL,
                    wall TEXT NOT NULL,
                    house_id TEXT NOT NULL,
                    machine_id TEXT NOT NULL,
                    public_key TEXT,
                    type TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    signature TEXT,
                    received_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
            """)
            conn.createStatement().executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_events_type ON events(type)"
            )
            conn.createStatement().executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_events_house ON events(house_id)"
            )
            conn.createStatement().executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_events_lamport ON events(lamport)"
            )
            conn.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS heads (
                    house_id TEXT PRIMARY KEY,
                    head_id TEXT NOT NULL
                )
            """)
            conn.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS local_state (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
            """)
        }
    }

    fun append(conn: Connection, event: DagEvent): Boolean {
        val stmt = conn.prepareStatement("""
            INSERT OR IGNORE INTO events (id, version, parents, lamport, wall, house_id, machine_id, public_key, type, payload, signature)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """)
        stmt.setString(1, event.id)
        stmt.setInt(2, event.version)
        stmt.setString(3, json.encodeToString(event.parents))
        stmt.setLong(4, event.timestamp.lamport)
        stmt.setString(5, event.timestamp.wall)
        stmt.setString(6, event.author.houseId)
        stmt.setString(7, event.author.machineId)
        stmt.setString(8, event.author.publicKey)
        stmt.setString(9, event.type)
        stmt.setString(10, json.encodeToString(event.payload))
        stmt.setString(11, event.signature)
        val inserted = stmt.executeUpdate()
        stmt.close()

        if (inserted > 0) {
            // Update head for this house
            val headStmt = conn.prepareStatement("""
                INSERT INTO heads (house_id, head_id) VALUES (?, ?)
                ON CONFLICT(house_id) DO UPDATE SET head_id = excluded.head_id
            """)
            headStmt.setString(1, event.author.houseId)
            headStmt.setString(2, event.id)
            headStmt.executeUpdate()
            headStmt.close()
        }
        return inserted > 0
    }

    fun getEvent(conn: Connection, id: String): DagEvent? {
        val stmt = conn.prepareStatement("SELECT * FROM events WHERE id = ?")
        stmt.setString(1, id)
        val rs = stmt.executeQuery()
        val event = if (rs.next()) parseEvent(rs) else null
        rs.close(); stmt.close()
        return event
    }

    fun getHead(conn: Connection, houseId: String): String? {
        val stmt = conn.prepareStatement("SELECT head_id FROM heads WHERE house_id = ?")
        stmt.setString(1, houseId)
        val rs = stmt.executeQuery()
        val head = if (rs.next()) rs.getString("head_id") else null
        rs.close(); stmt.close()
        return head
    }

    fun getAllEvents(conn: Connection): List<DagEvent> {
        val rs = conn.createStatement().executeQuery(
            "SELECT * FROM events ORDER BY lamport ASC, wall ASC, id ASC"
        )
        val events = mutableListOf<DagEvent>()
        while (rs.next()) events.add(parseEvent(rs))
        rs.close()
        return events
    }

    fun getEventCount(conn: Connection): Long {
        val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM events")
        rs.next()
        val count = rs.getLong(1)
        rs.close()
        return count
    }

    fun getMaxLamport(conn: Connection): Long {
        val rs = conn.createStatement().executeQuery("SELECT COALESCE(MAX(lamport), 0) FROM events")
        rs.next()
        val max = rs.getLong(1)
        rs.close()
        return max
    }

    fun getEventsByType(conn: Connection, type: String): List<DagEvent> {
        val stmt = conn.prepareStatement("SELECT * FROM events WHERE type = ? ORDER BY lamport ASC")
        stmt.setString(1, type)
        val rs = stmt.executeQuery()
        val events = mutableListOf<DagEvent>()
        while (rs.next()) events.add(parseEvent(rs))
        rs.close(); stmt.close()
        return events
    }

    fun getAllEventIds(conn: Connection): Set<String> {
        val rs = conn.createStatement().executeQuery("SELECT id FROM events")
        val ids = mutableSetOf<String>()
        while (rs.next()) ids.add(rs.getString("id"))
        rs.close()
        return ids
    }

    fun getLocalState(conn: Connection, key: String): String? {
        val stmt = conn.prepareStatement("SELECT value FROM local_state WHERE key = ?")
        stmt.setString(1, key)
        val rs = stmt.executeQuery()
        val value = if (rs.next()) rs.getString("value") else null
        rs.close(); stmt.close()
        return value
    }

    fun setLocalState(conn: Connection, key: String, value: String) {
        val stmt = conn.prepareStatement("""
            INSERT INTO local_state (key, value) VALUES (?, ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
        """)
        stmt.setString(1, key)
        stmt.setString(2, value)
        stmt.executeUpdate()
        stmt.close()
    }

    private fun parseEvent(rs: java.sql.ResultSet): DagEvent {
        val parentsStr = rs.getString("parents")
        val parents: List<String> = try {
            json.decodeFromString(parentsStr)
        } catch (_: Exception) { emptyList() }

        val payloadStr = rs.getString("payload")
        val payload: Map<String, String> = try {
            json.decodeFromString(payloadStr)
        } catch (_: Exception) { emptyMap() }

        return DagEvent(
            id = rs.getString("id"),
            version = rs.getInt("version"),
            parents = parents,
            timestamp = DagTimestamp(
                lamport = rs.getLong("lamport"),
                wall = rs.getString("wall")
            ),
            author = DagAuthor(
                houseId = rs.getString("house_id"),
                machineId = rs.getString("machine_id"),
                publicKey = rs.getString("public_key")
            ),
            type = rs.getString("type"),
            payload = payload,
            signature = rs.getString("signature")
        )
    }
}

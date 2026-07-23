package vision.salient.choam.federation

import mu.KotlinLogging
import vision.salient.choam.config.AccessLevel
import vision.salient.choam.config.BackupAgreement
import vision.salient.choam.config.BackupStatus
import vision.salient.choam.config.ShareGrant
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

private val logger = KotlinLogging.logger {}

/**
 * SQLite persistence for federation state — share grants, peer houses, backup agreements.
 * All stored in ~/.choam/federation.db (separate from unified_registry to keep concerns clean).
 */
class FederationStore(
    private val dbPath: String = "${System.getProperty("user.home")}/.choam/federation.db"
) {
    fun open(): Connection {
        File(dbPath).parentFile?.mkdirs()
        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().executeUpdate("PRAGMA foreign_keys=ON")
        ensureTables(conn)
        return conn
    }

    companion object {
        fun ensureTables(conn: Connection) {
            conn.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS share_grants (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    repository TEXT NOT NULL,
                    peer_house_id TEXT NOT NULL,
                    access_level TEXT NOT NULL,
                    granted_at TEXT NOT NULL DEFAULT (datetime('now')),
                    expires_at TEXT,
                    note TEXT DEFAULT '',
                    revoked_at TEXT,
                    UNIQUE(repository, peer_house_id)
                )
            """)

            conn.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS backup_agreements (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    peer_house_id TEXT NOT NULL UNIQUE,
                    offered_bytes INTEGER NOT NULL DEFAULT 0,
                    received_bytes INTEGER NOT NULL DEFAULT 0,
                    their_offered_bytes INTEGER NOT NULL DEFAULT 0,
                    our_used_bytes INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL DEFAULT 'PROPOSED',
                    created_at TEXT NOT NULL DEFAULT (datetime('now')),
                    accepted_at TEXT
                )
            """)

            conn.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS federation_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    event_type TEXT NOT NULL,
                    peer_house_id TEXT,
                    repository TEXT,
                    detail TEXT,
                    timestamp TEXT NOT NULL DEFAULT (datetime('now'))
                )
            """)
        }
    }

    // === Share Grants ===

    fun grantShare(conn: Connection, grant: ShareGrant) {
        val stmt = conn.prepareStatement("""
            INSERT INTO share_grants (repository, peer_house_id, access_level, note)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(repository, peer_house_id) DO UPDATE SET
                access_level = excluded.access_level,
                note = excluded.note,
                granted_at = datetime('now'),
                revoked_at = NULL
        """)
        stmt.setString(1, grant.repository)
        stmt.setString(2, grant.peerHouseId)
        stmt.setString(3, grant.access.name)
        stmt.setString(4, grant.note)
        stmt.executeUpdate()
        stmt.close()

        logEvent(conn, "SHARE_GRANTED", grant.peerHouseId, grant.repository,
            "${grant.access} access granted")
    }

    fun revokeShare(conn: Connection, repository: String, peerHouseId: String): Boolean {
        val stmt = conn.prepareStatement(
            "UPDATE share_grants SET revoked_at = datetime('now') WHERE repository = ? AND peer_house_id = ? AND revoked_at IS NULL"
        )
        stmt.setString(1, repository)
        stmt.setString(2, peerHouseId)
        val updated = stmt.executeUpdate()
        stmt.close()

        if (updated > 0) {
            logEvent(conn, "SHARE_REVOKED", peerHouseId, repository, "Access revoked")
        }
        return updated > 0
    }

    fun listActiveShares(conn: Connection): List<ShareGrant> {
        val rs = conn.createStatement().executeQuery(
            "SELECT repository, peer_house_id, access_level, granted_at, expires_at, note FROM share_grants WHERE revoked_at IS NULL ORDER BY granted_at"
        )
        val grants = mutableListOf<ShareGrant>()
        while (rs.next()) {
            grants.add(ShareGrant(
                repository = rs.getString("repository"),
                peerHouseId = rs.getString("peer_house_id"),
                access = AccessLevel.valueOf(rs.getString("access_level")),
                grantedAt = rs.getString("granted_at"),
                expiresAt = rs.getString("expires_at"),
                note = rs.getString("note") ?: ""
            ))
        }
        rs.close()
        return grants
    }

    fun getSharesForPeer(conn: Connection, peerHouseId: String): List<ShareGrant> {
        val stmt = conn.prepareStatement(
            "SELECT repository, peer_house_id, access_level, granted_at, expires_at, note FROM share_grants WHERE peer_house_id = ? AND revoked_at IS NULL"
        )
        stmt.setString(1, peerHouseId)
        val rs = stmt.executeQuery()
        val grants = mutableListOf<ShareGrant>()
        while (rs.next()) {
            grants.add(ShareGrant(
                repository = rs.getString("repository"),
                peerHouseId = rs.getString("peer_house_id"),
                access = AccessLevel.valueOf(rs.getString("access_level")),
                grantedAt = rs.getString("granted_at"),
                expiresAt = rs.getString("expires_at"),
                note = rs.getString("note") ?: ""
            ))
        }
        rs.close(); stmt.close()
        return grants
    }

    fun checkAccess(conn: Connection, repository: String, peerHouseId: String): AccessLevel? {
        val stmt = conn.prepareStatement(
            "SELECT access_level FROM share_grants WHERE repository = ? AND peer_house_id = ? AND revoked_at IS NULL"
        )
        stmt.setString(1, repository)
        stmt.setString(2, peerHouseId)
        val rs = stmt.executeQuery()
        val level = if (rs.next()) AccessLevel.valueOf(rs.getString("access_level")) else null
        rs.close(); stmt.close()
        return level
    }

    // === Backup Agreements ===

    fun proposeBackup(conn: Connection, peerHouseId: String, offeredBytes: Long) {
        val stmt = conn.prepareStatement("""
            INSERT INTO backup_agreements (peer_house_id, offered_bytes, status)
            VALUES (?, ?, 'PROPOSED')
            ON CONFLICT(peer_house_id) DO UPDATE SET
                offered_bytes = excluded.offered_bytes,
                status = 'PROPOSED',
                created_at = datetime('now'),
                accepted_at = NULL
        """)
        stmt.setString(1, peerHouseId)
        stmt.setLong(2, offeredBytes)
        stmt.executeUpdate()
        stmt.close()

        logEvent(conn, "BACKUP_PROPOSED", peerHouseId, null,
            "Offered ${offeredBytes / (1024*1024*1024)}GB")
    }

    fun acceptBackup(conn: Connection, peerHouseId: String, theirOfferedBytes: Long): Boolean {
        val stmt = conn.prepareStatement("""
            UPDATE backup_agreements SET status = 'ACCEPTED', their_offered_bytes = ?,
                accepted_at = datetime('now')
            WHERE peer_house_id = ? AND status IN ('PROPOSED', 'ACCEPTED')
        """)
        stmt.setLong(1, theirOfferedBytes)
        stmt.setString(2, peerHouseId)
        val updated = stmt.executeUpdate()
        stmt.close()

        if (updated > 0) {
            logEvent(conn, "BACKUP_ACCEPTED", peerHouseId, null,
                "They offer ${theirOfferedBytes / (1024*1024*1024)}GB")
        }
        return updated > 0
    }

    fun listBackupAgreements(conn: Connection): List<BackupAgreement> {
        val rs = conn.createStatement().executeQuery(
            "SELECT * FROM backup_agreements ORDER BY created_at"
        )
        val agreements = mutableListOf<BackupAgreement>()
        while (rs.next()) {
            agreements.add(BackupAgreement(
                peerHouseId = rs.getString("peer_house_id"),
                offeredBytes = rs.getLong("offered_bytes"),
                receivedBytes = rs.getLong("received_bytes"),
                theirOfferedBytes = rs.getLong("their_offered_bytes"),
                ourUsedBytes = rs.getLong("our_used_bytes"),
                status = BackupStatus.valueOf(rs.getString("status")),
                createdAt = rs.getString("created_at"),
                acceptedAt = rs.getString("accepted_at")
            ))
        }
        rs.close()
        return agreements
    }

    fun updateBackupStatus(conn: Connection, peerHouseId: String, status: BackupStatus): Boolean {
        val stmt = conn.prepareStatement(
            "UPDATE backup_agreements SET status = ? WHERE peer_house_id = ?"
        )
        stmt.setString(1, status.name)
        stmt.setString(2, peerHouseId)
        val updated = stmt.executeUpdate()
        stmt.close()
        return updated > 0
    }

    // === Audit Log ===

    fun logEvent(conn: Connection, eventType: String, peerHouseId: String?, repository: String?, detail: String) {
        val stmt = conn.prepareStatement(
            "INSERT INTO federation_log (event_type, peer_house_id, repository, detail) VALUES (?, ?, ?, ?)"
        )
        stmt.setString(1, eventType)
        stmt.setString(2, peerHouseId)
        stmt.setString(3, repository)
        stmt.setString(4, detail)
        stmt.executeUpdate()
        stmt.close()
    }
}

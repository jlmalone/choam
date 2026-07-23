package vision.salient.choam.catalog

import mu.KotlinLogging
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

private val logger = KotlinLogging.logger {}

/**
 * Strategy for resolving conflicts when two registries have different CIDs
 * for the same (machine_name, file_path).
 */
enum class MergeConflictStrategy {
    /** Keep the row with the most recent registered_at timestamp. */
    NEWER_WINS,
    /** Keep the row already in the target (don't overwrite). */
    KEEP_EXISTING,
    /** Always overwrite with the incoming row. */
    INCOMING_WINS
}

data class MergeConflict(
    val machineName: String,
    val filePath: String,
    val existingCid: String,
    val existingRegisteredAt: String,
    val incomingCid: String,
    val incomingRegisteredAt: String,
    val resolution: String // "kept_existing", "used_incoming", "used_newer"
)

data class MergeResult(
    val inserted: Long,
    val updated: Long,
    val skipped: Long,
    val conflicts: List<MergeConflict>,
    val totalProcessed: Long
) {
    val hasConflicts: Boolean get() = conflicts.isNotEmpty()
}

/**
 * Merges catalog registries from multiple sources with conflict resolution.
 *
 * This is an evolution of CatalogSyncCommand.mergeRegistry() that handles the case
 * where two machines independently catalog the same content area and their registries
 * diverge. The simple INSERT OR REPLACE in mergeRegistry() silently overwrites — this
 * merger tracks conflicts and applies configurable resolution strategies.
 *
 * Use cases:
 * - Two machines scanned the same drive at different times
 * - A file was modified between scans (different CID, same path)
 * - Recovering from a registry corruption by merging a backup
 */
object CatalogMerger {

    /**
     * Merge a source registry into a target registry with conflict tracking.
     *
     * @param sourceDbPath Path to the source registry DB
     * @param targetDbPath Path to the target (unified) registry DB
     * @param machineNameMap Alias map for remapping old hostnames
     * @param strategy How to resolve CID conflicts for the same (machine, path)
     * @param trackConflicts When true, returns detailed conflict list (slower for large DBs)
     * @return MergeResult with counts and optional conflict details
     */
    fun merge(
        sourceDbPath: String,
        targetDbPath: String,
        machineNameMap: Map<String, String> = emptyMap(),
        strategy: MergeConflictStrategy = MergeConflictStrategy.NEWER_WINS,
        trackConflicts: Boolean = true
    ): MergeResult {
        val sourceFile = File(sourceDbPath)
        if (!sourceFile.exists()) {
            logger.warn { "Source registry not found: $sourceDbPath" }
            return MergeResult(0, 0, 0, emptyList(), 0)
        }

        val targetDir = File(targetDbPath).parentFile
        targetDir?.mkdirs()

        val targetConn = DriverManager.getConnection("jdbc:sqlite:$targetDbPath")
        val ts = targetConn.createStatement()
        ts.executeUpdate("PRAGMA journal_mode=WAL")
        ts.executeUpdate("PRAGMA synchronous=NORMAL")
        ts.executeUpdate("""
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
        ts.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ucl_machine ON content_locations(machine_name)")
        ts.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ucl_cid ON content_locations(cid)")
        // Index for conflict detection: same machine + path, different CID
        ts.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ucl_machine_path ON content_locations(machine_name, file_path)")
        ts.close()

        val sourceConn = DriverManager.getConnection("jdbc:sqlite:$sourceDbPath")
        sourceConn.createStatement().executeUpdate("PRAGMA query_only=ON")

        val rs = sourceConn.createStatement().executeQuery(
            "SELECT cid, machine_name, file_path, file_size, verified_at, registered_at FROM content_locations"
        )

        // Prepare statements
        val checkStmt = targetConn.prepareStatement(
            "SELECT cid, registered_at FROM content_locations WHERE machine_name = ? AND file_path = ? LIMIT 1"
        )
        val insertStmt = targetConn.prepareStatement("""
            INSERT OR REPLACE INTO content_locations
            (cid, machine_name, file_path, file_size, verified_at, registered_at, last_synced_at)
            VALUES (?, ?, ?, ?, ?, ?, datetime('now'))
        """)

        targetConn.autoCommit = false
        var inserted = 0L
        var updated = 0L
        var skipped = 0L
        var totalProcessed = 0L
        val conflicts = mutableListOf<MergeConflict>()

        while (rs.next()) {
            val incomingCid = rs.getString("cid")
            val rawMachine = rs.getString("machine_name")
            val finalMachine = machineNameMap[rawMachine] ?: rawMachine
            val filePath = rs.getString("file_path")
            val fileSize = rs.getLong("file_size")
            val wasNull = rs.wasNull()
            val verifiedAt = rs.getString("verified_at")
            val incomingRegisteredAt = rs.getString("registered_at") ?: ""
            totalProcessed++

            // Check if target already has an entry for this (machine, path)
            checkStmt.setString(1, finalMachine)
            checkStmt.setString(2, filePath)
            val existingRs = checkStmt.executeQuery()

            if (existingRs.next()) {
                val existingCid = existingRs.getString("cid")
                val existingRegisteredAt = existingRs.getString("registered_at") ?: ""

                if (existingCid == incomingCid) {
                    // Same CID — just update last_synced_at (no conflict)
                    insertStmt.setString(1, incomingCid)
                    insertStmt.setString(2, finalMachine)
                    insertStmt.setString(3, filePath)
                    if (wasNull) insertStmt.setNull(4, java.sql.Types.INTEGER)
                    else insertStmt.setLong(4, fileSize)
                    insertStmt.setString(5, verifiedAt)
                    insertStmt.setString(6, incomingRegisteredAt)
                    insertStmt.executeUpdate()
                    updated++
                } else {
                    // Different CID — conflict!
                    val resolution = resolveConflict(
                        strategy, existingRegisteredAt, incomingRegisteredAt
                    )

                    if (trackConflicts) {
                        conflicts.add(MergeConflict(
                            machineName = finalMachine,
                            filePath = filePath,
                            existingCid = existingCid,
                            existingRegisteredAt = existingRegisteredAt,
                            incomingCid = incomingCid,
                            incomingRegisteredAt = incomingRegisteredAt,
                            resolution = resolution
                        ))
                    }

                    when (resolution) {
                        "used_incoming", "used_newer_incoming" -> {
                            // Delete old CID row, insert new
                            val delStmt = targetConn.prepareStatement(
                                "DELETE FROM content_locations WHERE cid = ? AND machine_name = ? AND file_path = ?"
                            )
                            delStmt.setString(1, existingCid)
                            delStmt.setString(2, finalMachine)
                            delStmt.setString(3, filePath)
                            delStmt.executeUpdate()
                            delStmt.close()

                            insertStmt.setString(1, incomingCid)
                            insertStmt.setString(2, finalMachine)
                            insertStmt.setString(3, filePath)
                            if (wasNull) insertStmt.setNull(4, java.sql.Types.INTEGER)
                            else insertStmt.setLong(4, fileSize)
                            insertStmt.setString(5, verifiedAt)
                            insertStmt.setString(6, incomingRegisteredAt)
                            insertStmt.executeUpdate()
                            updated++
                        }
                        else -> {
                            skipped++
                        }
                    }
                }
            } else {
                // No existing entry — straight insert
                insertStmt.setString(1, incomingCid)
                insertStmt.setString(2, finalMachine)
                insertStmt.setString(3, filePath)
                if (wasNull) insertStmt.setNull(4, java.sql.Types.INTEGER)
                else insertStmt.setLong(4, fileSize)
                insertStmt.setString(5, verifiedAt)
                insertStmt.setString(6, incomingRegisteredAt)
                insertStmt.executeUpdate()
                inserted++
            }
            existingRs.close()

            if (totalProcessed % 100_000 == 0L) {
                targetConn.commit()
            }
        }

        targetConn.commit()
        targetConn.autoCommit = true

        checkStmt.close()
        insertStmt.close()
        rs.close()
        sourceConn.close()
        targetConn.close()

        return MergeResult(inserted, updated, skipped, conflicts, totalProcessed)
    }

    /**
     * Resolve a CID conflict based on the chosen strategy.
     * @return "kept_existing", "used_incoming", "used_newer_incoming", or "used_newer_existing"
     */
    internal fun resolveConflict(
        strategy: MergeConflictStrategy,
        existingRegisteredAt: String,
        incomingRegisteredAt: String
    ): String {
        return when (strategy) {
            MergeConflictStrategy.KEEP_EXISTING -> "kept_existing"
            MergeConflictStrategy.INCOMING_WINS -> "used_incoming"
            MergeConflictStrategy.NEWER_WINS -> {
                if (incomingRegisteredAt >= existingRegisteredAt) {
                    "used_newer_incoming"
                } else {
                    "used_newer_existing"
                }
            }
        }
    }
}

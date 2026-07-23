package vision.salient.choam.catalog

import mu.KotlinLogging
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

private val logger = KotlinLogging.logger {}

data class DiffEntry(
    val cid: String,
    val filePath: String,
    val fileSize: Long,
    val machineName: String
)

data class CatalogDiff(
    val machineA: String,
    val machineB: String,
    val onlyOnA: List<DiffEntry>,
    val onlyOnB: List<DiffEntry>,
    val onBoth: Long,
    val totalA: Long,
    val totalB: Long
) {
    val onlyOnASize: Long get() = onlyOnA.sumOf { it.fileSize }
    val onlyOnBSize: Long get() = onlyOnB.sumOf { it.fileSize }
}

/**
 * Compares catalog state between two machines using CID-based content matching.
 * A file on machine A is "shared" with machine B if the same CID exists on B,
 * regardless of path or filename.
 */
object CatalogDiffer {

    /**
     * Diff two machines by CID presence in the unified registry.
     *
     * @param unifiedDbPath Path to unified_registry.db
     * @param machineA First machine name (config key)
     * @param machineB Second machine name (config key)
     * @param machineNameMap Alias map for remapping old hostnames
     * @param minSize Minimum file size filter (0 = no filter)
     * @param limit Max entries to return in onlyOnA/onlyOnB lists (for display)
     */
    fun diffMachines(
        unifiedDbPath: String,
        machineA: String,
        machineB: String,
        machineNameMap: Map<String, String> = emptyMap(),
        minSize: Long = 0,
        limit: Int = 50
    ): CatalogDiff {
        val file = File(unifiedDbPath)
        if (!file.exists()) {
            logger.warn { "Unified registry not found at $unifiedDbPath" }
            return CatalogDiff(machineA, machineB, emptyList(), emptyList(), 0, 0, 0)
        }

        val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedDbPath")
        conn.createStatement().executeUpdate("PRAGMA query_only=ON")

        val aMachines = resolveAliases(machineA, machineNameMap)
        val bMachines = resolveAliases(machineB, machineNameMap)

        val aPlaceholders = aMachines.joinToString(",") { "?" }
        val bPlaceholders = bMachines.joinToString(",") { "?" }

        val totalA = countCids(conn, aMachines, aPlaceholders, minSize)
        val totalB = countCids(conn, bMachines, bPlaceholders, minSize)
        val onBoth = countSharedCids(conn, aMachines, bMachines, aPlaceholders, bPlaceholders, minSize)
        val onlyOnA = getExclusiveCids(conn, aMachines, bMachines, aPlaceholders, bPlaceholders, minSize, limit)
        val onlyOnB = getExclusiveCids(conn, bMachines, aMachines, bPlaceholders, aPlaceholders, minSize, limit)

        conn.close()

        return CatalogDiff(
            machineA = machineA,
            machineB = machineB,
            onlyOnA = onlyOnA,
            onlyOnB = onlyOnB,
            onBoth = onBoth,
            totalA = totalA,
            totalB = totalB
        )
    }

    /**
     * Resolve a machine name to all possible names in the DB (config key + any aliases that map to it).
     */
    internal fun resolveAliases(machineName: String, machineNameMap: Map<String, String>): List<String> {
        val names = mutableListOf(machineName)
        for ((alias, configKey) in machineNameMap) {
            if (configKey == machineName) {
                names.add(alias)
            }
        }
        return names.distinct()
    }

    private fun countCids(conn: Connection, machines: List<String>, placeholders: String, minSize: Long): Long {
        val sizeClause = if (minSize > 0) "AND file_size >= ?" else ""
        val stmt = conn.prepareStatement(
            "SELECT COUNT(DISTINCT cid) FROM content_locations WHERE machine_name IN ($placeholders) $sizeClause"
        )
        machines.forEachIndexed { i, m -> stmt.setString(i + 1, m) }
        if (minSize > 0) stmt.setLong(machines.size + 1, minSize)
        val rs = stmt.executeQuery()
        rs.next()
        val count = rs.getLong(1)
        rs.close()
        stmt.close()
        return count
    }

    private fun countSharedCids(
        conn: Connection, aMachines: List<String>, bMachines: List<String>,
        aPlaceholders: String, bPlaceholders: String, minSize: Long
    ): Long {
        val sizeClause = if (minSize > 0) "AND a.file_size >= ?" else ""
        val sql = """
            SELECT COUNT(DISTINCT a.cid) FROM content_locations a
            WHERE a.machine_name IN ($aPlaceholders) $sizeClause
            AND a.cid IN (SELECT DISTINCT cid FROM content_locations WHERE machine_name IN ($bPlaceholders))
        """
        val stmt = conn.prepareStatement(sql)
        var idx = 1
        aMachines.forEach { stmt.setString(idx++, it) }
        if (minSize > 0) stmt.setLong(idx++, minSize)
        bMachines.forEach { stmt.setString(idx++, it) }
        val rs = stmt.executeQuery()
        rs.next()
        val count = rs.getLong(1)
        rs.close()
        stmt.close()
        return count
    }

    private fun getExclusiveCids(
        conn: Connection, hasMachines: List<String>, lacksMachines: List<String>,
        hasPlaceholders: String, lacksPlaceholders: String, minSize: Long, limit: Int
    ): List<DiffEntry> {
        val sizeClause = if (minSize > 0) "AND a.file_size >= ?" else ""
        val sql = """
            SELECT a.cid, a.file_path, a.file_size, a.machine_name
            FROM content_locations a
            WHERE a.machine_name IN ($hasPlaceholders) $sizeClause
            AND a.cid NOT IN (SELECT DISTINCT cid FROM content_locations WHERE machine_name IN ($lacksPlaceholders))
            ORDER BY a.file_size DESC
            LIMIT ?
        """
        val stmt = conn.prepareStatement(sql)
        var idx = 1
        hasMachines.forEach { stmt.setString(idx++, it) }
        if (minSize > 0) stmt.setLong(idx++, minSize)
        lacksMachines.forEach { stmt.setString(idx++, it) }
        stmt.setInt(idx, limit)
        val rs = stmt.executeQuery()
        val results = mutableListOf<DiffEntry>()
        while (rs.next()) {
            results.add(DiffEntry(
                cid = rs.getString("cid"),
                filePath = rs.getString("file_path"),
                fileSize = rs.getLong("file_size"),
                machineName = rs.getString("machine_name")
            ))
        }
        rs.close()
        stmt.close()
        return results
    }
}

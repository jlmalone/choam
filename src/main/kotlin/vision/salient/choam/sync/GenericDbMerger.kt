package vision.salient.choam.sync

import mu.KotlinLogging
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

/**
 * Domain-agnostic SQLite database merger.
 *
 * Merges rows from a source database into a target database using primary key-based
 * deduplication. Works with any SQLite schema — no knowledge of table contents required.
 */
object GenericDbMerger {

    enum class MergeStrategy {
        INSERT_OR_IGNORE,   // Add rows missing from target, skip PK collisions
        INSERT_OR_REPLACE,  // Incoming data wins on PK collision
        TIMESTAMP_WINS      // Incoming data wins only when its timestamp is newer
    }

    data class MergeResult(
        val tablesProcessed: Int,
        val rowsInserted: Long,
        val rowsSkipped: Long,
        val tablesSkipped: List<String>,
        val tablesCreated: List<String>,
        val errors: List<String>,
        val rowsUpdated: Long = 0
    )

    /**
     * Merge tables from source DB into target DB.
     *
     * @param sourceDbPath Path to the source SQLite database (read-only)
     * @param targetDbPath Path to the target SQLite database (will be created if missing)
     * @param strategy INSERT_OR_IGNORE (default), INSERT_OR_REPLACE, or TIMESTAMP_WINS
     * @param includeTables If non-null, only merge these tables
     * @param excludeTables Tables to skip (always excludes sqlite_* internal tables)
     */
    fun merge(
        sourceDbPath: String,
        targetDbPath: String,
        strategy: MergeStrategy = MergeStrategy.INSERT_OR_IGNORE,
        includeTables: List<String>? = null,
        excludeTables: List<String> = emptyList()
    ): MergeResult {
        val sourceFile = File(sourceDbPath)
        if (!sourceFile.exists()) {
            return MergeResult(0, 0, 0, emptyList(), emptyList(), listOf("Source DB does not exist: $sourceDbPath"))
        }

        val targetFile = File(targetDbPath)
        if (!targetFile.exists()) {
            // First sync — just copy the source as the target
            targetFile.parentFile?.mkdirs()
            sourceFile.copyTo(targetFile)
            val tableCount = countTables(targetDbPath)
            logger.info { "First sync — copied source to target ($tableCount tables)" }
            return MergeResult(tableCount, 0, 0, emptyList(), emptyList(), emptyList())
        }

        var sourceConn: Connection? = null
        var targetConn: Connection? = null

        try {
            sourceConn = DriverManager.getConnection("jdbc:sqlite:$sourceDbPath").apply {
                createStatement().executeUpdate("PRAGMA query_only=ON")
            }
            targetConn = DriverManager.getConnection("jdbc:sqlite:$targetDbPath").apply {
                createStatement().executeUpdate("PRAGMA journal_mode=WAL")
                createStatement().executeUpdate("PRAGMA synchronous=NORMAL")
            }

            // Get source tables
            val sourceTables = getTables(sourceConn)
                .filter { includeTables == null || it in includeTables }
                .filter { it !in excludeTables }

            var totalInserted = 0L
            var totalUpdated = 0L
            var totalSkipped = 0L
            var tablesProcessed = 0
            val tablesSkipped = mutableListOf<String>()
            val tablesCreated = mutableListOf<String>()
            val errors = mutableListOf<String>()

            for (tableName in sourceTables) {
                try {
                    val result = mergeTable(sourceConn, targetConn, tableName, strategy)
                    when (result) {
                        is TableResult.Merged -> {
                            totalInserted += result.inserted
                            totalUpdated += result.updated
                            totalSkipped += result.skipped
                            tablesProcessed++
                            if (result.created) tablesCreated.add(tableName)
                        }
                        is TableResult.Skipped -> {
                            tablesSkipped.add(tableName)
                            logger.info { "Skipped table '$tableName': ${result.reason}" }
                        }
                        is TableResult.Error -> {
                            errors.add("Table '$tableName': ${result.message}")
                            logger.warn { "Error merging table '$tableName': ${result.message}" }
                        }
                    }
                } catch (e: Exception) {
                    errors.add("Table '$tableName': ${e.message}")
                    logger.warn(e) { "Exception merging table '$tableName'" }
                }
            }

            // Verify integrity
            try {
                targetConn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery("PRAGMA integrity_check")
                    if (rs.next() && rs.getString(1) != "ok") {
                        errors.add("Integrity check failed: ${rs.getString(1)}")
                    }
                }
            } catch (e: Exception) {
                errors.add("Integrity check error: ${e.message}")
            }

            return MergeResult(tablesProcessed, totalInserted, totalSkipped, tablesSkipped, tablesCreated, errors, totalUpdated)
        } finally {
            sourceConn?.close()
            targetConn?.close()
        }
    }

    private sealed class TableResult {
        data class Merged(val inserted: Long, val updated: Long, val skipped: Long, val created: Boolean) : TableResult()
        data class Skipped(val reason: String) : TableResult()
        data class Error(val message: String) : TableResult()
    }

    private fun mergeTable(
        sourceConn: Connection,
        targetConn: Connection,
        tableName: String,
        strategy: MergeStrategy
    ): TableResult {
        // Get source columns and PK
        val sourceColumns = getColumns(sourceConn, tableName)
        val sourcePk = getPrimaryKey(sourceConn, tableName)

        if (sourcePk.isEmpty()) {
            return TableResult.Skipped("no primary key — cannot deduplicate")
        }

        // Check for AUTOINCREMENT (single INTEGER PK named 'id' or 'rowid')
        if (sourcePk.size == 1 && isAutoincrement(sourceConn, tableName)) {
            return TableResult.Skipped("uses AUTOINCREMENT — IDs collide across machines")
        }

        // Check if table exists in target, create if not
        val targetTables = getTables(targetConn)
        var created = false
        if (tableName !in targetTables) {
            val createSql = getCreateTableSql(sourceConn, tableName)
                ?: return TableResult.Error("could not read CREATE TABLE statement")
            targetConn.createStatement().executeUpdate(createSql)
            created = true
            logger.info { "Created table '$tableName' in target" }
        }

        // Get columns that exist in both source and target
        val targetColumns = getColumns(targetConn, tableName)
        val commonColumns = sourceColumns.filter { it in targetColumns }

        if (commonColumns.isEmpty()) {
            return TableResult.Error("no common columns between source and target")
        }

        val missingPkColumns = sourcePk.filter { it !in commonColumns }
        if (missingPkColumns.isNotEmpty()) {
            return TableResult.Error("primary key columns missing in target: ${missingPkColumns.joinToString(", ")}")
        }

        if (strategy == MergeStrategy.TIMESTAMP_WINS) {
            return mergeTableByTimestamp(sourceConn, targetConn, tableName, sourcePk, commonColumns, created)
        }

        // Build merge SQL
        val colList = commonColumns.joinToString(", ")
        val placeholders = commonColumns.joinToString(", ") { "?" }
        val insertKeyword = when (strategy) {
            MergeStrategy.INSERT_OR_IGNORE -> "INSERT OR IGNORE"
            MergeStrategy.INSERT_OR_REPLACE -> "INSERT OR REPLACE"
            MergeStrategy.TIMESTAMP_WINS -> error("TIMESTAMP_WINS handled before insert SQL generation")
        }
        val insertSql = "$insertKeyword INTO $tableName ($colList) VALUES ($placeholders)"

        // Read from source, write to target in batches
        val selectSql = "SELECT $colList FROM $tableName"
        var inserted = 0L
        var total = 0L
        val batchSize = 10_000

        targetConn.autoCommit = false
        val insertStmt = targetConn.prepareStatement(insertSql)

        sourceConn.createStatement().use { stmt ->
            val rs = stmt.executeQuery(selectSql)
            while (rs.next()) {
                for ((idx, col) in commonColumns.withIndex()) {
                    insertStmt.setObject(idx + 1, rs.getObject(col))
                }
                insertStmt.addBatch()
                total++

                if (total % batchSize == 0L) {
                    val results = insertStmt.executeBatch()
                    inserted += results.count { it > 0 }
                    targetConn.commit()
                }
            }
        }

        // Final batch
        val results = insertStmt.executeBatch()
        inserted += results.count { it > 0 }
        targetConn.commit()
        targetConn.autoCommit = true
        insertStmt.close()

        val skipped = total - inserted
        return TableResult.Merged(inserted, 0, skipped, created)
    }

    private fun mergeTableByTimestamp(
        sourceConn: Connection,
        targetConn: Connection,
        tableName: String,
        pkColumns: List<String>,
        commonColumns: List<String>,
        created: Boolean
    ): TableResult {
        val timestampColumns = commonColumns.filter { it.isTimestampColumnName() }
        val colList = commonColumns.joinToString(", ")
        val selectSql = "SELECT $colList FROM $tableName"
        val insertSql = "INSERT INTO $tableName ($colList) VALUES (${commonColumns.joinToString(", ") { "?" }})"
        val updateColumns = commonColumns.filter { it !in pkColumns }
        val updateSql = if (updateColumns.isEmpty()) {
            null
        } else {
            "UPDATE $tableName SET ${updateColumns.joinToString(", ") { "$it = ?" }} WHERE ${nullSafeWhereClause(pkColumns)}"
        }

        var inserted = 0L
        var updated = 0L
        var skipped = 0L

        targetConn.autoCommit = false
        sourceConn.createStatement().use { sourceStmt ->
            val sourceRs = sourceStmt.executeQuery(selectSql)
            targetConn.prepareStatement(insertSql).use { insertStmt ->
                val updateStmt = updateSql?.let { targetConn.prepareStatement(it) }
                try {
                    while (sourceRs.next()) {
                        val pkValues = pkColumns.map { sourceRs.getObject(it) }
                        val exists = rowExists(targetConn, tableName, pkColumns, pkValues)

                        if (!exists) {
                            bindColumns(insertStmt, commonColumns, sourceRs)
                            insertStmt.executeUpdate()
                            inserted++
                            continue
                        }

                        if (timestampColumns.isEmpty() || updateStmt == null) {
                            skipped++
                            continue
                        }

                        val sourceTimestamp = rowTimestamp(sourceRs, timestampColumns)
                        val targetTimestamp = getTargetTimestamp(targetConn, tableName, pkColumns, pkValues, timestampColumns)

                        if (sourceTimestamp != null && (targetTimestamp == null || sourceTimestamp > targetTimestamp)) {
                            bindUpdate(updateStmt, updateColumns, pkColumns, pkValues, sourceRs)
                            updateStmt.executeUpdate()
                            updated++
                        } else {
                            skipped++
                        }
                    }
                } finally {
                    updateStmt?.close()
                }
            }
        }
        targetConn.commit()
        targetConn.autoCommit = true

        return TableResult.Merged(inserted, updated, skipped, created)
    }

    private fun rowExists(
        conn: Connection,
        tableName: String,
        pkColumns: List<String>,
        pkValues: List<Any?>
    ): Boolean {
        val sql = "SELECT 1 FROM $tableName WHERE ${nullSafeWhereClause(pkColumns)} LIMIT 1"
        conn.prepareStatement(sql).use { stmt ->
            bindPkValues(stmt, pkValues)
            val rs = stmt.executeQuery()
            return rs.next()
        }
    }

    private fun getTargetTimestamp(
        conn: Connection,
        tableName: String,
        pkColumns: List<String>,
        pkValues: List<Any?>,
        timestampColumns: List<String>
    ): Long? {
        val sql = """
            SELECT ${timestampColumns.joinToString(", ")}
            FROM $tableName
            WHERE ${nullSafeWhereClause(pkColumns)}
            LIMIT 1
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            bindPkValues(stmt, pkValues)
            val rs = stmt.executeQuery()
            return if (rs.next()) rowTimestamp(rs, timestampColumns) else null
        }
    }

    private fun bindColumns(stmt: java.sql.PreparedStatement, columns: List<String>, rs: ResultSet) {
        columns.forEachIndexed { idx, col ->
            stmt.setObject(idx + 1, rs.getObject(col))
        }
    }

    private fun bindUpdate(
        stmt: java.sql.PreparedStatement,
        updateColumns: List<String>,
        pkColumns: List<String>,
        pkValues: List<Any?>,
        rs: ResultSet
    ) {
        var idx = 1
        for (col in updateColumns) {
            stmt.setObject(idx++, rs.getObject(col))
        }
        for (value in pkValues) {
            stmt.setObject(idx++, value)
            stmt.setObject(idx++, value)
        }
    }

    private fun bindPkValues(stmt: java.sql.PreparedStatement, pkValues: List<Any?>) {
        var idx = 1
        for (value in pkValues) {
            stmt.setObject(idx++, value)
            stmt.setObject(idx++, value)
        }
    }

    private fun nullSafeWhereClause(pkColumns: List<String>): String =
        pkColumns.joinToString(" AND ") { "($it = ? OR ($it IS NULL AND ? IS NULL))" }

    private fun rowTimestamp(rs: ResultSet, timestampColumns: List<String>): Long? =
        timestampColumns.mapNotNull { parseTimestamp(rs.getObject(it)) }.maxOrNull()

    private fun String.isTimestampColumnName(): Boolean {
        val normalized = lowercase()
        return normalized in setOf(
            "updateddate",
            "updated_at",
            "modified_at",
            "modified_time",
            "last_modified",
            "timestamp",
            "updated",
            "modified",
            "removed_at",
            "downloadtimestamp",
            "download_timestamp",
            "lastattempttimestamp",
            "last_attempt_timestamp"
        )
    }

    private fun parseTimestamp(value: Any?): Long? {
        return when (value) {
            null -> null
            is Number -> normalizeEpochMillis(value.toLong())
            is String -> parseTimestampString(value)
            else -> null
        }
    }

    private fun parseTimestampString(value: String): Long? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null

        trimmed.toLongOrNull()?.let { return normalizeEpochMillis(it) }

        runCatching { Instant.parse(trimmed).toEpochMilli() }
            .getOrNull()
            ?.let { return it }
        runCatching { OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }
            .getOrNull()
            ?.let { return it }
        runCatching {
            LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        }.getOrNull()?.let { return it }
        runCatching {
            LocalDateTime.parse(trimmed, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        }.getOrNull()?.let { return it }

        return null
    }

    private fun normalizeEpochMillis(value: Long): Long =
        if (value in -9_999_999_999L..9_999_999_999L) value * 1000 else value

    private fun getTables(conn: Connection): List<String> {
        val tables = mutableListOf<String>()
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
            )
            while (rs.next()) {
                tables.add(rs.getString("name"))
            }
        }
        return tables
    }

    private fun getColumns(conn: Connection, tableName: String): List<String> {
        val columns = mutableListOf<String>()
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("PRAGMA table_info('$tableName')")
            while (rs.next()) {
                columns.add(rs.getString("name"))
            }
        }
        return columns
    }

    private fun getPrimaryKey(conn: Connection, tableName: String): List<String> {
        val pk = mutableListOf<String>()
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("PRAGMA table_info('$tableName')")
            while (rs.next()) {
                if (rs.getInt("pk") > 0) {
                    pk.add(rs.getString("name"))
                }
            }
        }
        return pk
    }

    private fun isAutoincrement(conn: Connection, tableName: String): Boolean {
        return try {
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT COUNT(*) FROM sqlite_sequence WHERE name = '$tableName'")
                rs.next() && rs.getInt(1) > 0
            }
        } catch (_: Exception) {
            // sqlite_sequence doesn't exist — no AUTOINCREMENT tables
            false
        }
    }

    private fun getCreateTableSql(conn: Connection, tableName: String): String? {
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='$tableName'")
            return if (rs.next()) rs.getString("sql") else null
        }
    }

    private fun countTables(dbPath: String): Int {
        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        val count = getTables(conn).size
        conn.close()
        return count
    }
}

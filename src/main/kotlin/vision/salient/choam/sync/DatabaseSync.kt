package vision.salient.choam.sync

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging

private val dbLogger = KotlinLogging.logger {}

class DatabaseSync {
    suspend fun syncDatabase(
        sourcePath: String,
        targetPath: String,
        strategy: DatabaseSyncStrategy
    ): DatabaseSyncResult {
        return when (strategy) {
            DatabaseSyncStrategy.REPLACE -> replaceDatabase(sourcePath, targetPath)
            DatabaseSyncStrategy.MERGE -> mergeDatabase(sourcePath, targetPath)
            DatabaseSyncStrategy.BACKUP_AND_REPLACE -> backupAndReplace(sourcePath, targetPath)
        }
    }

    private suspend fun replaceDatabase(source: String, target: String): DatabaseSyncResult =
        withContext(Dispatchers.IO) {
            val sourceFile = Paths.get(source)
            val targetFile = Paths.get(target)

            if (!Files.exists(sourceFile)) {
                return@withContext DatabaseSyncResult.Failure("Source database does not exist: $source")
            }

            try {
                Files.createDirectories(targetFile.parent)
                Files.copy(
                    sourceFile,
                    targetFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES
                )
                dbLogger.info { "Replaced database at $target from $source" }
                DatabaseSyncResult.Success
            } catch (e: IOException) {
                DatabaseSyncResult.Failure("Failed to replace database: ${e.message}", e)
            }
        }

    private suspend fun mergeDatabase(source: String, target: String): DatabaseSyncResult =
        withContext(Dispatchers.IO) {
            val sourceFile = Paths.get(source)
            val targetFile = Paths.get(target)

            if (!Files.exists(sourceFile)) {
                return@withContext DatabaseSyncResult.Failure("Source database does not exist: $source")
            }

            if (!Files.exists(targetFile)) {
                // If target doesn't exist, just copy source to target
                dbLogger.info { "Target database does not exist, copying source to target" }
                return@withContext replaceDatabase(source, target)
            }

            try {
                val report = performMerge(sourceFile, targetFile)
                dbLogger.info { "Database merge completed: $report" }
                DatabaseSyncResult.MergeSuccess(report)
            } catch (e: Exception) {
                dbLogger.error(e) { "Failed to merge databases: ${e.message}" }
                DatabaseSyncResult.Failure("Failed to merge database: ${e.message}", e)
            }
        }

    private fun performMerge(sourceFile: Path, targetFile: Path): DatabaseMergeReport {
        var sourceConn: Connection? = null
        var targetConn: Connection? = null

        try {
            sourceConn = openDatabase(sourceFile)
            targetConn = openDatabase(targetFile)

            val sourceSchema = extractSchema(sourceConn)
            val targetSchema = extractSchema(targetConn)

            // Compare schemas
            val schemaDifferences = compareSchemas(sourceSchema, targetSchema)

            if (schemaDifferences.isNotEmpty()) {
                val incompatible = schemaDifferences.any { it.isIncompatible() }
                if (incompatible) {
                    throw IllegalStateException(
                        "Incompatible schemas detected: ${schemaDifferences.joinToString(", ")}"
                    )
                } else {
                    dbLogger.warn { "Minor schema differences detected: $schemaDifferences" }
                    migrateSchema(sourceSchema, targetSchema, targetConn, schemaDifferences)
                }
            }

            // Merge tables
            val report = mergeTables(sourceConn, targetConn, sourceSchema)

            // Verify database integrity
            verifyIntegrity(targetConn)

            return report
        } finally {
            sourceConn?.close()
            targetConn?.close()
        }
    }

    private fun openDatabase(dbFile: Path): Connection {
        val url = "jdbc:sqlite:${dbFile.toAbsolutePath()}"
        return DriverManager.getConnection(url).apply {
            autoCommit = false
        }
    }

    private fun extractSchema(conn: Connection): DatabaseSchema {
        val tables = mutableListOf<TableSchema>()

        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
            )

            while (rs.next()) {
                val tableName = rs.getString("name")
                val columns = extractColumns(conn, tableName)
                val indexes = extractIndexes(conn, tableName)
                val primaryKey = extractPrimaryKey(conn, tableName)

                tables.add(
                    TableSchema(
                        name = tableName,
                        columns = columns,
                        indexes = indexes,
                        primaryKey = primaryKey
                    )
                )
            }
        }

        return DatabaseSchema(tables)
    }

    private fun extractColumns(conn: Connection, tableName: String): List<ColumnSchema> {
        val columns = mutableListOf<ColumnSchema>()

        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("PRAGMA table_info('$tableName')")

            while (rs.next()) {
                columns.add(
                    ColumnSchema(
                        name = rs.getString("name"),
                        type = rs.getString("type"),
                        notNull = rs.getInt("notnull") == 1,
                        defaultValue = rs.getString("dflt_value"),
                        isPrimaryKey = rs.getInt("pk") > 0
                    )
                )
            }
        }

        return columns
    }

    private fun extractIndexes(conn: Connection, tableName: String): List<IndexSchema> {
        val indexes = mutableListOf<IndexSchema>()

        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("PRAGMA index_list('$tableName')")

            while (rs.next()) {
                val indexName = rs.getString("name")
                if (!indexName.startsWith("sqlite_autoindex_")) {
                    indexes.add(
                        IndexSchema(
                            name = indexName,
                            unique = rs.getInt("unique") == 1,
                            columns = extractIndexColumns(conn, indexName)
                        )
                    )
                }
            }
        }

        return indexes
    }

    private fun extractIndexColumns(conn: Connection, indexName: String): List<String> {
        val columns = mutableListOf<String>()

        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("PRAGMA index_info('$indexName')")

            while (rs.next()) {
                columns.add(rs.getString("name"))
            }
        }

        return columns
    }

    private fun extractPrimaryKey(conn: Connection, tableName: String): List<String> {
        val pkColumns = mutableListOf<String>()

        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("PRAGMA table_info('$tableName')")

            while (rs.next()) {
                if (rs.getInt("pk") > 0) {
                    pkColumns.add(rs.getString("name"))
                }
            }
        }

        return pkColumns
    }

    private fun compareSchemas(
        source: DatabaseSchema,
        target: DatabaseSchema
    ): List<SchemaDifference> {
        val differences = mutableListOf<SchemaDifference>()
        val sourceTableMap = source.tables.associateBy { it.name }
        val targetTableMap = target.tables.associateBy { it.name }

        // Check for new tables in source
        for (sourceTable in source.tables) {
            if (sourceTable.name !in targetTableMap) {
                differences.add(SchemaDifference.TableMissing(sourceTable.name, inTarget = true))
            }
        }

        // Check for tables only in target
        for (targetTable in target.tables) {
            if (targetTable.name !in sourceTableMap) {
                differences.add(SchemaDifference.TableMissing(targetTable.name, inTarget = false))
            }
        }

        // Compare common tables
        for (sourceTable in source.tables) {
            val targetTable = targetTableMap[sourceTable.name] ?: continue

            // Compare columns
            val sourceColMap = sourceTable.columns.associateBy { it.name }
            val targetColMap = targetTable.columns.associateBy { it.name }

            for (sourceCol in sourceTable.columns) {
                val targetCol = targetColMap[sourceCol.name]
                if (targetCol == null) {
                    differences.add(
                        SchemaDifference.ColumnMissing(
                            sourceTable.name,
                            sourceCol.name,
                            inTarget = true
                        )
                    )
                } else if (sourceCol.type != targetCol.type) {
                    differences.add(
                        SchemaDifference.ColumnTypeMismatch(
                            sourceTable.name,
                            sourceCol.name,
                            sourceCol.type,
                            targetCol.type
                        )
                    )
                }
            }

            for (targetCol in targetTable.columns) {
                if (targetCol.name !in sourceColMap) {
                    differences.add(
                        SchemaDifference.ColumnMissing(
                            sourceTable.name,
                            targetCol.name,
                            inTarget = false
                        )
                    )
                }
            }

            // Compare primary keys
            if (sourceTable.primaryKey != targetTable.primaryKey) {
                differences.add(
                    SchemaDifference.PrimaryKeyMismatch(
                        sourceTable.name,
                        sourceTable.primaryKey,
                        targetTable.primaryKey
                    )
                )
            }
        }

        return differences
    }

    private fun migrateSchema(
        sourceSchema: DatabaseSchema,
        targetSchema: DatabaseSchema,
        targetConn: Connection,
        differences: List<SchemaDifference>
    ) {
        dbLogger.info { "Migrating schema with ${differences.size} differences" }

        for (diff in differences) {
            when (diff) {
                is SchemaDifference.TableMissing -> {
                    if (diff.inTarget) {
                        val sourceTable = sourceSchema.tables.find { it.name == diff.tableName }
                        if (sourceTable != null) {
                            createTable(targetConn, sourceTable)
                        }
                    }
                }
                is SchemaDifference.ColumnMissing -> {
                    if (diff.inTarget) {
                        val sourceTable = sourceSchema.tables.find { it.name == diff.tableName }
                        val column = sourceTable?.columns?.find { it.name == diff.columnName }
                        if (column != null) {
                            addColumn(targetConn, diff.tableName, column)
                        }
                    }
                }
                else -> {
                    dbLogger.warn { "Cannot auto-migrate: $diff" }
                }
            }
        }

        targetConn.commit()
    }

    private fun createTable(conn: Connection, table: TableSchema) {
        val columnDefs = table.columns.joinToString(", ") { col ->
            buildString {
                append(col.name)
                append(" ")
                append(col.type)
                if (col.notNull) append(" NOT NULL")
                col.defaultValue?.let { append(" DEFAULT $it") }
            }
        }

        val pkClause = if (table.primaryKey.isNotEmpty()) {
            ", PRIMARY KEY (${table.primaryKey.joinToString(", ")})"
        } else ""

        val sql = "CREATE TABLE ${table.name} ($columnDefs$pkClause)"
        dbLogger.info { "Creating table: $sql" }

        conn.createStatement().use { it.execute(sql) }
    }

    private fun addColumn(conn: Connection, tableName: String, column: ColumnSchema) {
        val sql = buildString {
            append("ALTER TABLE $tableName ADD COLUMN ${column.name} ${column.type}")
            if (column.notNull && column.defaultValue != null) {
                append(" NOT NULL DEFAULT ${column.defaultValue}")
            }
        }

        dbLogger.info { "Adding column: $sql" }
        conn.createStatement().use { it.execute(sql) }
    }

    private fun mergeTables(
        sourceConn: Connection,
        targetConn: Connection,
        sourceSchema: DatabaseSchema
    ): DatabaseMergeReport {
        var recordsInserted = 0
        var recordsUpdated = 0
        var recordsSkipped = 0
        val conflicts = mutableListOf<MergeConflict>()

        for (table in sourceSchema.tables) {
            dbLogger.info { "Merging table: ${table.name}" }

            val timestampColumn = findTimestampColumn(table)
            val pkColumns = table.primaryKey

            if (pkColumns.isEmpty()) {
                dbLogger.warn { "Table ${table.name} has no primary key, skipping merge" }
                continue
            }

            // Determine merge strategy based on table name
            val result = when {
                table.name.contains("operation", ignoreCase = true) -> {
                    mergeOperationsTable(sourceConn, targetConn, table, pkColumns)
                }
                else -> {
                    mergeDataTable(sourceConn, targetConn, table, pkColumns, timestampColumn)
                }
            }

            recordsInserted += result.inserted
            recordsUpdated += result.updated
            recordsSkipped += result.skipped
            conflicts.addAll(result.conflicts)
        }

        targetConn.commit()

        return DatabaseMergeReport(
            recordsInserted = recordsInserted,
            recordsUpdated = recordsUpdated,
            recordsSkipped = recordsSkipped,
            conflicts = conflicts,
            schemaDifferences = emptyList()
        )
    }

    private fun findTimestampColumn(table: TableSchema): String? {
        val timestampNames = listOf(
            "updated_at", "modified_at", "modified_time",
            "last_modified", "timestamp", "updated", "modified"
        )

        return table.columns.find { col ->
            timestampNames.any { it.equals(col.name, ignoreCase = true) }
        }?.name
    }

    private fun mergeDataTable(
        sourceConn: Connection,
        targetConn: Connection,
        table: TableSchema,
        pkColumns: List<String>,
        timestampColumn: String?
    ): TableMergeResult {
        var inserted = 0
        var updated = 0
        var skipped = 0
        val conflicts = mutableListOf<MergeConflict>()

        val columnNames = table.columns.map { it.name }
        val selectSql = "SELECT ${columnNames.joinToString(", ")} FROM ${table.name}"

        sourceConn.createStatement().use { sourceStmt ->
            val sourceRs = sourceStmt.executeQuery(selectSql)

            while (sourceRs.next()) {
                val pkValues = pkColumns.map { sourceRs.getObject(it) }
                val whereClause = pkColumns.joinToString(" AND ") { "$it = ?" }

                // Check if record exists in target
                val existsInTarget = targetConn.prepareStatement(
                    "SELECT COUNT(*) FROM ${table.name} WHERE $whereClause"
                ).use { stmt ->
                    pkValues.forEachIndexed { idx, value ->
                        stmt.setObject(idx + 1, value)
                    }
                    val rs = stmt.executeQuery()
                    rs.next() && rs.getInt(1) > 0
                }

                if (!existsInTarget) {
                    // Insert new record
                    insertRecord(targetConn, table, columnNames, sourceRs)
                    inserted++
                } else if (timestampColumn != null && table.columns.any { it.name == timestampColumn }) {
                    // Compare timestamps and update if source is newer
                    val sourceTimestamp = extractTimestamp(sourceRs, timestampColumn)
                    val targetTimestamp = getTargetTimestamp(
                        targetConn,
                        table.name,
                        pkColumns,
                        pkValues,
                        timestampColumn
                    )

                    when {
                        sourceTimestamp == null || targetTimestamp == null -> {
                            // Can't compare timestamps, skip
                            skipped++
                        }
                        sourceTimestamp > targetTimestamp -> {
                            // Source is newer, update target
                            updateRecord(targetConn, table, columnNames, pkColumns, sourceRs, pkValues)
                            updated++
                        }
                        sourceTimestamp < targetTimestamp -> {
                            // Target is newer, skip
                            skipped++
                        }
                        else -> {
                            // Same timestamp, check for data differences
                            if (recordsDiffer(sourceConn, targetConn, table, pkColumns, pkValues, columnNames)) {
                                conflicts.add(
                                    MergeConflict(
                                        tableName = table.name,
                                        primaryKey = pkColumns.zip(pkValues).toMap(),
                                        reason = "Records have same timestamp but different data"
                                    )
                                )
                            }
                            skipped++
                        }
                    }
                } else {
                    // No timestamp column, compare all fields
                    if (recordsDiffer(sourceConn, targetConn, table, pkColumns, pkValues, columnNames)) {
                        conflicts.add(
                            MergeConflict(
                                tableName = table.name,
                                primaryKey = pkColumns.zip(pkValues).toMap(),
                                reason = "No timestamp column to determine newer record"
                            )
                        )
                    }
                    skipped++
                }
            }
        }

        return TableMergeResult(inserted, updated, skipped, conflicts)
    }

    private fun mergeOperationsTable(
        sourceConn: Connection,
        targetConn: Connection,
        table: TableSchema,
        pkColumns: List<String>
    ): TableMergeResult {
        var inserted = 0
        var skipped = 0

        val columnNames = table.columns.map { it.name }
        val selectSql = "SELECT ${columnNames.joinToString(", ")} FROM ${table.name}"

        sourceConn.createStatement().use { sourceStmt ->
            val sourceRs = sourceStmt.executeQuery(selectSql)

            while (sourceRs.next()) {
                val pkValues = pkColumns.map { sourceRs.getObject(it) }
                val whereClause = pkColumns.joinToString(" AND ") { "$it = ?" }

                // Check if operation already exists (deduplicate by primary key)
                val existsInTarget = targetConn.prepareStatement(
                    "SELECT COUNT(*) FROM ${table.name} WHERE $whereClause"
                ).use { stmt ->
                    pkValues.forEachIndexed { idx, value ->
                        stmt.setObject(idx + 1, value)
                    }
                    val rs = stmt.executeQuery()
                    rs.next() && rs.getInt(1) > 0
                }

                if (!existsInTarget) {
                    insertRecord(targetConn, table, columnNames, sourceRs)
                    inserted++
                } else {
                    skipped++
                }
            }
        }

        return TableMergeResult(inserted, 0, skipped, emptyList())
    }

    private fun insertRecord(
        conn: Connection,
        table: TableSchema,
        columnNames: List<String>,
        sourceRs: ResultSet
    ) {
        val placeholders = columnNames.joinToString(", ") { "?" }
        val sql = "INSERT INTO ${table.name} (${columnNames.joinToString(", ")}) VALUES ($placeholders)"

        conn.prepareStatement(sql).use { stmt ->
            columnNames.forEachIndexed { idx, colName ->
                stmt.setObject(idx + 1, sourceRs.getObject(colName))
            }
            stmt.executeUpdate()
        }
    }

    private fun updateRecord(
        conn: Connection,
        table: TableSchema,
        columnNames: List<String>,
        pkColumns: List<String>,
        sourceRs: ResultSet,
        pkValues: List<Any?>
    ) {
        val setClauses = columnNames.filter { it !in pkColumns }.joinToString(", ") { "$it = ?" }
        val whereClause = pkColumns.joinToString(" AND ") { "$it = ?" }
        val sql = "UPDATE ${table.name} SET $setClauses WHERE $whereClause"

        conn.prepareStatement(sql).use { stmt ->
            var paramIdx = 1

            // Set values for SET clause
            columnNames.filter { it !in pkColumns }.forEach { colName ->
                stmt.setObject(paramIdx++, sourceRs.getObject(colName))
            }

            // Set values for WHERE clause
            pkValues.forEach { pkValue ->
                stmt.setObject(paramIdx++, pkValue)
            }

            stmt.executeUpdate()
        }
    }

    private fun extractTimestamp(rs: ResultSet, timestampColumn: String): Long? {
        return try {
            when (val value = rs.getObject(timestampColumn)) {
                is Long -> value
                is Int -> value.toLong()
                is String -> {
                    // Try to parse as ISO instant
                    try {
                        Instant.parse(value).toEpochMilli()
                    } catch (e: Exception) {
                        value.toLongOrNull()
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getTargetTimestamp(
        conn: Connection,
        tableName: String,
        pkColumns: List<String>,
        pkValues: List<Any?>,
        timestampColumn: String
    ): Long? {
        val whereClause = pkColumns.joinToString(" AND ") { "$it = ?" }
        val sql = "SELECT $timestampColumn FROM $tableName WHERE $whereClause"

        return conn.prepareStatement(sql).use { stmt ->
            pkValues.forEachIndexed { idx, value ->
                stmt.setObject(idx + 1, value)
            }
            val rs = stmt.executeQuery()
            if (rs.next()) {
                extractTimestamp(rs, timestampColumn)
            } else {
                null
            }
        }
    }

    private fun recordsDiffer(
        sourceConn: Connection,
        targetConn: Connection,
        table: TableSchema,
        pkColumns: List<String>,
        pkValues: List<Any?>,
        columnNames: List<String>
    ): Boolean {
        // Get source record
        val whereClause = pkColumns.joinToString(" AND ") { "$it = ?" }
        val selectSql = "SELECT ${columnNames.joinToString(", ")} FROM ${table.name} WHERE $whereClause"

        val sourceValues = sourceConn.prepareStatement(selectSql).use { stmt ->
            pkValues.forEachIndexed { idx, value ->
                stmt.setObject(idx + 1, value)
            }
            val rs = stmt.executeQuery()
            if (rs.next()) {
                columnNames.associateWith { rs.getObject(it) }
            } else {
                null
            }
        } ?: return false

        val targetValues = targetConn.prepareStatement(selectSql).use { stmt ->
            pkValues.forEachIndexed { idx, value ->
                stmt.setObject(idx + 1, value)
            }
            val rs = stmt.executeQuery()
            if (rs.next()) {
                columnNames.associateWith { rs.getObject(it) }
            } else {
                null
            }
        } ?: return false

        return sourceValues != targetValues
    }

    private fun verifyIntegrity(conn: Connection) {
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("PRAGMA integrity_check")
            if (rs.next()) {
                val result = rs.getString(1)
                if (result != "ok") {
                    throw IllegalStateException("Database integrity check failed: $result")
                }
            }
        }
    }

    private suspend fun backupAndReplace(source: String, target: String): DatabaseSyncResult =
        withContext(Dispatchers.IO) {
            val targetFile = Paths.get(target)
            if (Files.exists(targetFile)) {
                val backupFile = backupPath(targetFile)
                try {
                    Files.createDirectories(backupFile.parent)
                    Files.copy(
                        targetFile,
                        backupFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES
                    )
                    dbLogger.info { "Created backup of $target at $backupFile" }
                } catch (e: IOException) {
                    return@withContext DatabaseSyncResult.Failure(
                        "Failed to create backup at $backupFile: ${e.message}",
                        e
                    )
                }
            }

            replaceDatabase(source, target)
        }

    private fun backupPath(targetFile: Path): Path {
        val parent = targetFile.parent ?: Paths.get(".")
        val fileName = targetFile.fileName.toString()
        val backupName = "$fileName.bak"
        return parent.resolve(backupName)
    }
}

enum class DatabaseSyncStrategy {
    REPLACE,
    MERGE,
    BACKUP_AND_REPLACE
}

sealed class DatabaseSyncResult {
    object Success : DatabaseSyncResult()
    data class Failure(val message: String, val cause: Throwable? = null) : DatabaseSyncResult()
    data class Skipped(val reason: String) : DatabaseSyncResult()
    data class MergeSuccess(val report: DatabaseMergeReport) : DatabaseSyncResult()
}

/**
 * Report containing detailed information about a database merge operation.
 */
data class DatabaseMergeReport(
    val recordsInserted: Int,
    val recordsUpdated: Int,
    val recordsSkipped: Int,
    val conflicts: List<MergeConflict>,
    val schemaDifferences: List<SchemaDifference>
) {
    val totalRecordsProcessed: Int
        get() = recordsInserted + recordsUpdated + recordsSkipped

    val hasConflicts: Boolean
        get() = conflicts.isNotEmpty()

    val hasSchemaDifferences: Boolean
        get() = schemaDifferences.isNotEmpty()

    override fun toString(): String {
        return buildString {
            append("DatabaseMergeReport(")
            append("inserted=$recordsInserted, ")
            append("updated=$recordsUpdated, ")
            append("skipped=$recordsSkipped, ")
            append("conflicts=${conflicts.size}, ")
            append("schemaDifferences=${schemaDifferences.size}")
            append(")")
        }
    }
}

/**
 * Represents a conflict encountered during database merge.
 */
data class MergeConflict(
    val tableName: String,
    val primaryKey: Map<String, Any?>,
    val reason: String
)

/**
 * Database schema information.
 */
data class DatabaseSchema(
    val tables: List<TableSchema>
)

/**
 * Table schema information.
 */
data class TableSchema(
    val name: String,
    val columns: List<ColumnSchema>,
    val indexes: List<IndexSchema>,
    val primaryKey: List<String>
)

/**
 * Column schema information.
 */
data class ColumnSchema(
    val name: String,
    val type: String,
    val notNull: Boolean,
    val defaultValue: String?,
    val isPrimaryKey: Boolean
)

/**
 * Index schema information.
 */
data class IndexSchema(
    val name: String,
    val unique: Boolean,
    val columns: List<String>
)

/**
 * Represents differences between two database schemas.
 */
sealed class SchemaDifference {
    abstract fun isIncompatible(): Boolean

    data class TableMissing(val tableName: String, val inTarget: Boolean) : SchemaDifference() {
        override fun isIncompatible() = false
        override fun toString() = "Table '$tableName' missing in ${if (inTarget) "target" else "source"}"
    }

    data class ColumnMissing(
        val tableName: String,
        val columnName: String,
        val inTarget: Boolean
    ) : SchemaDifference() {
        override fun isIncompatible() = false
        override fun toString() =
            "Column '$columnName' in table '$tableName' missing in ${if (inTarget) "target" else "source"}"
    }

    data class ColumnTypeMismatch(
        val tableName: String,
        val columnName: String,
        val sourceType: String,
        val targetType: String
    ) : SchemaDifference() {
        override fun isIncompatible() = true
        override fun toString() =
            "Column '$columnName' in table '$tableName' has type mismatch: source=$sourceType, target=$targetType"
    }

    data class PrimaryKeyMismatch(
        val tableName: String,
        val sourcePk: List<String>,
        val targetPk: List<String>
    ) : SchemaDifference() {
        override fun isIncompatible() = true
        override fun toString() =
            "Primary key mismatch in table '$tableName': source=$sourcePk, target=$targetPk"
    }
}

/**
 * Internal result for merging a single table.
 */
internal data class TableMergeResult(
    val inserted: Int,
    val updated: Int,
    val skipped: Int,
    val conflicts: List<MergeConflict>
)

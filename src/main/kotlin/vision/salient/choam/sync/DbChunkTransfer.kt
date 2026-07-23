package vision.salient.choam.sync

import mu.KotlinLogging
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

private val logger = KotlinLogging.logger {}

// ── Schema Introspection ──────────────────────────────────────────────────────

data class TableInfo(
    val name: String,
    val rowCount: Long,
    val estimatedBytes: Long,
    val primaryKeyColumns: List<String>,
    val hasAutoIncrement: Boolean,
    val foreignKeys: List<ForeignKey>
)

data class ForeignKey(
    val fromTable: String,
    val fromColumn: String,
    val toTable: String,
    val toColumn: String
)

data class ChunkPlan(
    val table: String,
    val chunkIndex: Int,
    val rowidStart: Long,    // inclusive
    val rowidEnd: Long,      // inclusive
    val estimatedRows: Long,
    val estimatedBytes: Long
)

data class TransferPlan(
    val tables: List<TableInfo>,
    val chunks: List<ChunkPlan>,
    val smallTables: List<String>,  // bundled into chunk 0
    val totalRows: Long,
    val totalBytes: Long
)

/**
 * Introspect a SQLite database: tables, row counts, PKs, FKs, estimated sizes.
 */
object SchemaIntrospector {

    fun introspect(dbPath: String, includeTables: List<String>? = null): List<TableInfo> {
        val conn = connectReadOnly(dbPath)
        return conn.use { c ->
            val tables = listUserTables(c)
                .filter { includeTables == null || it in includeTables }

            val pageSize = c.createStatement().use { s ->
                s.executeQuery("PRAGMA page_size").use { if (it.next()) it.getLong(1) else 4096 }
            }
            // Total DB size is more reliable than per-table estimates from dbstat
            val dbFileSize = java.io.File(dbPath).length()

            // First pass: gather row counts
            val rawInfos = tables.map { tableName ->
                val maxRowid = maxRowid(c, tableName)
                val pk = primaryKeyColumns(c, tableName)
                val hasAutoInc = hasAutoIncrement(c, tableName)
                val fks = foreignKeys(c, tableName)
                TableInfo(tableName, maxRowid, 0, pk, hasAutoInc, fks)
            }

            // Second pass: estimate bytes proportional to row count
            val totalRows = rawInfos.sumOf { it.rowCount }.coerceAtLeast(1)
            rawInfos.map { info ->
                val estimatedBytes = if (info.rowCount == 0L) 0L
                    else (dbFileSize.toDouble() * info.rowCount / totalRows).toLong()
                info.copy(estimatedBytes = estimatedBytes)
            }.sortedByDescending { it.estimatedBytes }
        }
    }

    private fun listUserTables(conn: Connection): List<String> {
        val tables = mutableListOf<String>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
            ).use { rs ->
                while (rs.next()) tables.add(rs.getString("name"))
            }
        }
        return tables
    }

    /**
     * Fast row count upper bound using MAX(rowid).
     * O(1) index lookup vs O(n) full table scan for COUNT(*).
     * Overcounts if rows have been deleted (gaps), but close enough for chunk planning.
     */
    private fun maxRowid(conn: Connection, table: String): Long {
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT MAX(rowid) FROM \"$table\"").use { rs ->
                return if (rs.next()) rs.getLong(1) else 0
            }
        }
    }

    private fun primaryKeyColumns(conn: Connection, table: String): List<String> {
        val pkCols = mutableListOf<String>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info(\"$table\")").use { rs ->
                while (rs.next()) {
                    if (rs.getInt("pk") > 0) {
                        pkCols.add(rs.getString("name"))
                    }
                }
            }
        }
        return pkCols
    }

    private fun hasAutoIncrement(conn: Connection, table: String): Boolean {
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='$table' AND sql LIKE '%AUTOINCREMENT%'"
            ).use { rs ->
                return rs.next() && rs.getLong(1) > 0
            }
        }
    }

    private fun foreignKeys(conn: Connection, table: String): List<ForeignKey> {
        val fks = mutableListOf<ForeignKey>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA foreign_key_list(\"$table\")").use { rs ->
                while (rs.next()) {
                    fks.add(ForeignKey(
                        fromTable = table,
                        fromColumn = rs.getString("from"),
                        toTable = rs.getString("table"),
                        toColumn = rs.getString("to")
                    ))
                }
            }
        }
        return fks
    }
}

// ── Chunk Planning ────────────────────────────────────────────────────────────

/**
 * Plan how to split a database into transferable chunks.
 * Chunks are rowid-range-based for predictable sizes and fast extraction.
 */
object ChunkPlanner {

    private const val SMALL_TABLE_THRESHOLD = 10 * 1024 * 1024L  // 10 MB

    /**
     * Create a transfer plan for the given database.
     *
     * @param tables Schema introspection results
     * @param chunkSizeBytes Target chunk size (default 1 GB)
     */
    fun plan(tables: List<TableInfo>, chunkSizeBytes: Long = 1L * 1024 * 1024 * 1024): TransferPlan {
        val smallTables = tables.filter { it.estimatedBytes < SMALL_TABLE_THRESHOLD }.map { it.name }
        val largeTables = tables.filter { it.estimatedBytes >= SMALL_TABLE_THRESHOLD }

        val chunks = mutableListOf<ChunkPlan>()

        for (table in largeTables) {
            val numChunks = ((table.estimatedBytes + chunkSizeBytes - 1) / chunkSizeBytes).toInt()
                .coerceAtLeast(1)
            val rowsPerChunk = (table.rowCount + numChunks - 1) / numChunks

            for (i in 0 until numChunks) {
                val start = i * rowsPerChunk + 1  // rowid is 1-based
                val end = ((i + 1) * rowsPerChunk).coerceAtMost(table.rowCount)
                val estRows = end - start + 1
                val estBytes = if (table.rowCount > 0)
                    table.estimatedBytes * estRows / table.rowCount
                else 0

                chunks.add(ChunkPlan(
                    table = table.name,
                    chunkIndex = i,
                    rowidStart = start,
                    rowidEnd = end,
                    estimatedRows = estRows,
                    estimatedBytes = estBytes
                ))
            }
        }

        return TransferPlan(
            tables = tables,
            chunks = chunks,
            smallTables = smallTables,
            totalRows = tables.sumOf { it.rowCount },
            totalBytes = tables.sumOf { it.estimatedBytes }
        )
    }
}

// ── Chunk Export ──────────────────────────────────────────────────────────────

/**
 * Export row ranges from a source database into standalone sub-databases.
 * Each sub-DB is fully operable — can be queried, attached, inspected.
 */
object DbChunkExporter {

    data class ExportResult(
        val chunkPath: String,
        val table: String,
        val rowidStart: Long,
        val rowidEnd: Long,
        val rowsExported: Long
    )

    /**
     * Export small tables (all rows) into a single sub-database.
     */
    fun exportSmallTables(
        sourceDbPath: String,
        outputPath: String,
        tableNames: List<String>
    ): ExportResult {
        if (tableNames.isEmpty()) return ExportResult(outputPath, "(small tables)", 0, 0, 0)

        val outFile = File(outputPath)
        outFile.parentFile?.mkdirs()

        val sourceConn = connectReadOnly(sourceDbPath)
        val targetConn = DriverManager.getConnection("jdbc:sqlite:$outputPath")

        var totalRows = 0L
        sourceConn.use { src ->
            targetConn.use { tgt ->
                tgt.createStatement().execute("PRAGMA journal_mode=DELETE")
                tgt.createStatement().execute("PRAGMA synchronous=OFF")

                for (table in tableNames) {
                    val ddl = getCreateTableSql(src, table) ?: continue
                    tgt.createStatement().execute(ddl)

                    val cols = getColumnNames(src, table)
                    val placeholders = cols.joinToString(",") { "?" }
                    val colList = cols.joinToString(",") { "\"$it\"" }

                    val insertSql = "INSERT INTO \"$table\" ($colList) VALUES ($placeholders)"
                    val insertStmt = tgt.prepareStatement(insertSql)

                    tgt.autoCommit = false
                    var batch = 0

                    src.createStatement().use { stmt ->
                        stmt.executeQuery("SELECT $colList FROM \"$table\"").use { rs ->
                            while (rs.next()) {
                                for (i in cols.indices) {
                                    insertStmt.setObject(i + 1, rs.getObject(i + 1))
                                }
                                insertStmt.addBatch()
                                batch++
                                totalRows++
                                if (batch % 5000 == 0) {
                                    insertStmt.executeBatch()
                                    tgt.commit()
                                }
                            }
                        }
                    }
                    insertStmt.executeBatch()
                    tgt.commit()
                    tgt.autoCommit = true
                    insertStmt.close()

                    // Copy indexes for this table
                    copyIndexes(src, tgt, table)
                }

                writeTransferMetadata(tgt, sourceDbPath, -1, "(small tables)", 0, 0)
            }
        }

        logger.info { "Exported ${tableNames.size} small tables ($totalRows rows) to $outputPath" }
        return ExportResult(outputPath, "(small tables)", 0, 0, totalRows)
    }

    /**
     * Export a rowid range from a single table into a standalone sub-database.
     */
    fun exportChunk(
        sourceDbPath: String,
        outputPath: String,
        chunk: ChunkPlan
    ): ExportResult {
        val outFile = File(outputPath)
        outFile.parentFile?.mkdirs()

        val sourceConn = connectReadOnly(sourceDbPath)
        val targetConn = DriverManager.getConnection("jdbc:sqlite:$outputPath")

        var rowsExported = 0L
        sourceConn.use { src ->
            targetConn.use { tgt ->
                tgt.createStatement().execute("PRAGMA journal_mode=DELETE")
                tgt.createStatement().execute("PRAGMA synchronous=OFF")

                val ddl = getCreateTableSql(src, chunk.table)
                    ?: throw IllegalStateException("Table ${chunk.table} not found in source")
                tgt.createStatement().execute(ddl)

                val cols = getColumnNames(src, chunk.table)
                val colList = cols.joinToString(",") { "\"$it\"" }
                val placeholders = cols.joinToString(",") { "?" }

                val selectSql = "SELECT $colList FROM \"${chunk.table}\" WHERE rowid >= ? AND rowid <= ?"
                val insertSql = "INSERT INTO \"${chunk.table}\" ($colList) VALUES ($placeholders)"

                tgt.autoCommit = false
                val insertStmt = tgt.prepareStatement(insertSql)
                var batch = 0

                src.prepareStatement(selectSql).use { sel ->
                    sel.setLong(1, chunk.rowidStart)
                    sel.setLong(2, chunk.rowidEnd)
                    sel.executeQuery().use { rs ->
                        while (rs.next()) {
                            for (i in cols.indices) {
                                insertStmt.setObject(i + 1, rs.getObject(i + 1))
                            }
                            insertStmt.addBatch()
                            batch++
                            rowsExported++
                            if (batch % 5000 == 0) {
                                insertStmt.executeBatch()
                                tgt.commit()
                            }
                        }
                    }
                }
                insertStmt.executeBatch()
                tgt.commit()
                tgt.autoCommit = true
                insertStmt.close()

                copyIndexes(src, tgt, chunk.table)
                writeTransferMetadata(tgt, sourceDbPath, chunk.chunkIndex, chunk.table, chunk.rowidStart, chunk.rowidEnd)
            }
        }

        logger.info { "Exported chunk ${chunk.chunkIndex} of ${chunk.table}: rows ${chunk.rowidStart}-${chunk.rowidEnd} ($rowsExported rows) to $outputPath" }
        return ExportResult(outputPath, chunk.table, chunk.rowidStart, chunk.rowidEnd, rowsExported)
    }

    private fun writeTransferMetadata(
        conn: Connection, sourceDbPath: String,
        chunkIndex: Int, table: String, rowidStart: Long, rowidEnd: Long
    ) {
        conn.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS _transfer_metadata (
                key TEXT PRIMARY KEY,
                value TEXT
            )
        """.trimIndent())
        val meta = mapOf(
            "source_file" to File(sourceDbPath).absolutePath,
            "chunk_index" to chunkIndex.toString(),
            "table_name" to table,
            "rowid_start" to rowidStart.toString(),
            "rowid_end" to rowidEnd.toString(),
            "exported_at" to java.time.Instant.now().toString()
        )
        conn.prepareStatement("INSERT OR REPLACE INTO _transfer_metadata (key, value) VALUES (?, ?)").use { ps ->
            for ((k, v) in meta) {
                ps.setString(1, k)
                ps.setString(2, v)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private fun getCreateTableSql(conn: Connection, table: String): String? {
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='$table'").use { rs ->
                return if (rs.next()) rs.getString("sql") else null
            }
        }
    }

    private fun getColumnNames(conn: Connection, table: String): List<String> {
        val cols = mutableListOf<String>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info(\"$table\")").use { rs ->
                while (rs.next()) cols.add(rs.getString("name"))
            }
        }
        return cols
    }

    private fun copyIndexes(src: Connection, tgt: Connection, table: String) {
        src.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name='$table' AND sql IS NOT NULL"
            ).use { rs ->
                while (rs.next()) {
                    val indexSql = rs.getString("sql")
                    try {
                        tgt.createStatement().execute(indexSql)
                    } catch (e: Exception) {
                        logger.debug { "Skipping index: ${e.message}" }
                    }
                }
            }
        }
    }
}

// ── Chunk Import ─────────────────────────────────────────────────────────────

/**
 * Import sub-databases into a target database.
 * Uses INSERT OR IGNORE by default (natural PK dedup).
 */
object DbChunkImporter {

    data class ImportResult(
        val rowsImported: Long,
        val rowsSkipped: Long,
        val tablesProcessed: Int
    )

    fun import(
        chunkDbPath: String,
        targetDbPath: String,
        strategy: GenericDbMerger.MergeStrategy = GenericDbMerger.MergeStrategy.INSERT_OR_IGNORE
    ): ImportResult {
        if (strategy == GenericDbMerger.MergeStrategy.TIMESTAMP_WINS) {
            val result = GenericDbMerger.merge(
                sourceDbPath = chunkDbPath,
                targetDbPath = targetDbPath,
                strategy = strategy,
                excludeTables = listOf("_transfer_metadata")
            )
            return ImportResult(
                rowsImported = result.rowsInserted + result.rowsUpdated,
                rowsSkipped = result.rowsSkipped,
                tablesProcessed = result.tablesProcessed
            )
        }

        val chunkConn = connectReadOnly(chunkDbPath)
        val targetConn = DriverManager.getConnection("jdbc:sqlite:$targetDbPath?journal_mode=WAL&busy_timeout=5000")

        var totalImported = 0L
        var totalSkipped = 0L
        var tablesProcessed = 0

        chunkConn.use { src ->
            targetConn.use { tgt ->
                val tables = mutableListOf<String>()
                src.createStatement().use { stmt ->
                    stmt.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name != '_transfer_metadata'"
                    ).use { rs ->
                        while (rs.next()) tables.add(rs.getString("name"))
                    }
                }

                for (table in tables) {
                    // Ensure table exists in target
                    val ddl = src.createStatement().use { s ->
                        s.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='$table'").use { rs ->
                            if (rs.next()) rs.getString("sql") else null
                        }
                    } ?: continue
                    try { tgt.createStatement().execute(ddl) } catch (_: Exception) { /* already exists */ }

                    val cols = mutableListOf<String>()
                    src.createStatement().use { s ->
                        s.executeQuery("PRAGMA table_info(\"$table\")").use { rs ->
                            while (rs.next()) cols.add(rs.getString("name"))
                        }
                    }

                    val colList = cols.joinToString(",") { "\"$it\"" }
                    val placeholders = cols.joinToString(",") { "?" }
                    val verb = when (strategy) {
                        GenericDbMerger.MergeStrategy.INSERT_OR_IGNORE -> "INSERT OR IGNORE"
                        GenericDbMerger.MergeStrategy.INSERT_OR_REPLACE -> "INSERT OR REPLACE"
                        GenericDbMerger.MergeStrategy.TIMESTAMP_WINS -> error("TIMESTAMP_WINS handled before chunk import")
                    }

                    val countBefore = tgt.createStatement().use { s ->
                        s.executeQuery("SELECT COUNT(*) FROM \"$table\"").use { rs ->
                            if (rs.next()) rs.getLong(1) else 0
                        }
                    }

                    tgt.autoCommit = false
                    val insertStmt = tgt.prepareStatement("$verb INTO \"$table\" ($colList) VALUES ($placeholders)")
                    var batch = 0

                    src.createStatement().use { s ->
                        s.executeQuery("SELECT $colList FROM \"$table\"").use { rs ->
                            while (rs.next()) {
                                for (i in cols.indices) {
                                    insertStmt.setObject(i + 1, rs.getObject(i + 1))
                                }
                                insertStmt.addBatch()
                                batch++
                                if (batch % 5000 == 0) {
                                    insertStmt.executeBatch()
                                    tgt.commit()
                                }
                            }
                        }
                    }
                    insertStmt.executeBatch()
                    tgt.commit()
                    tgt.autoCommit = true
                    insertStmt.close()

                    val countAfter = tgt.createStatement().use { s ->
                        s.executeQuery("SELECT COUNT(*) FROM \"$table\"").use { rs ->
                            if (rs.next()) rs.getLong(1) else 0
                        }
                    }

                    val imported = countAfter - countBefore
                    val skipped = batch.toLong() - imported
                    totalImported += imported
                    totalSkipped += skipped
                    tablesProcessed++

                    logger.info { "Imported $table: $imported new, $skipped skipped (of $batch offered)" }
                }
            }
        }

        return ImportResult(totalImported, totalSkipped, tablesProcessed)
    }
}

// ── Shared Utilities ─────────────────────────────────────────────────────────

private fun connectReadOnly(dbPath: String): Connection {
    return DriverManager.getConnection("jdbc:sqlite:file:$dbPath?mode=ro&journal_mode=WAL")
}

/**
 * Progress tracking for db-transfer operations.
 * Persists to a JSON file so transfers can resume.
 */
@kotlinx.serialization.Serializable
data class DbTransferProgress(
    val sourceDbPath: String,
    val destination: String,
    val chunksCompleted: List<Int>,
    val smallTablesTransferred: Boolean,
    val startedAt: String,
    val lastUpdatedAt: String
) {
    companion object {
        fun load(progressFile: File): DbTransferProgress? {
            if (!progressFile.exists()) return null
            return try {
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                json.decodeFromString(serializer(), progressFile.readText())
            } catch (_: Exception) { null }
        }

        fun save(progress: DbTransferProgress, progressFile: File) {
            progressFile.parentFile?.mkdirs()
            val json = kotlinx.serialization.json.Json { prettyPrint = true }
            progressFile.writeText(json.encodeToString(serializer(), progress))
        }
    }
}

package vision.salient.choam.catalog

import mu.KotlinLogging
import vision.salient.choam.config.Drive
import vision.salient.sietch.core.DEFAULT_EXCLUDE_PATTERNS
import vision.salient.sietch.core.parseCatalog
import vision.salient.sietch.core.parseCidCatalog
import java.io.File
import java.nio.file.FileSystems
import java.sql.Connection
import java.sql.DriverManager

private val logger = KotlinLogging.logger {}

/**
 * Unified searchable index of all Sietch catalogs across all drives and machines.
 * Stores file metadata in SQLite with FTS5 for instant full-text search.
 *
 * Each file entry records which drive/machine it was seen on and when,
 * enabling offline queries like "where are all copies of Aliens.mkv?"
 */
class CatalogIndex(private val dbPath: String) {

    fun open(): Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        val s = conn.createStatement()
        s.executeUpdate("PRAGMA foreign_keys=ON")
        s.close()
        createTables(conn)
        return conn
    }

    private fun createTables(conn: Connection) {
        val stmt = conn.createStatement()
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS catalog_sources (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                drive_label TEXT NOT NULL,
                machine TEXT NOT NULL,
                root_path TEXT NOT NULL,
                catalog_date TEXT NOT NULL,
                hash_algorithm TEXT NOT NULL,
                file_count INTEGER NOT NULL DEFAULT 0,
                total_size INTEGER NOT NULL DEFAULT 0,
                ingested_at TEXT NOT NULL DEFAULT (datetime('now')),
                UNIQUE(drive_label, machine, catalog_date)
            )
        """)

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS files (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_id INTEGER NOT NULL REFERENCES catalog_sources(id),
                path TEXT NOT NULL,
                filename TEXT NOT NULL,
                extension TEXT NOT NULL DEFAULT '',
                hash TEXT NOT NULL DEFAULT '-',
                cid TEXT NOT NULL DEFAULT '',
                size INTEGER NOT NULL,
                UNIQUE(source_id, path)
            )
        """)

        // Migrations: add columns before creating indexes that reference them
        try { stmt.executeUpdate("ALTER TABLE files ADD COLUMN cid TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
        try { stmt.executeUpdate("ALTER TABLE files ADD COLUMN last_synced_at TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}

        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_files_filename ON files(filename)")
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_files_hash ON files(hash) WHERE hash != '-'")
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_files_cid ON files(cid) WHERE cid != ''")
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_files_extension ON files(extension)")
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_files_size ON files(size)")

        // FTS5 virtual table for full-text search on paths
        stmt.executeUpdate("""
            CREATE VIRTUAL TABLE IF NOT EXISTS files_fts USING fts5(
                path, filename,
                content='files',
                content_rowid='id'
            )
        """)
        stmt.close()
    }

    /**
     * Ingest a Sietch catalog file into the index.
     * @param catalogFile The .txt catalog file
     * @param driveLabel Which drive this came from (e.g. "machine-a", "machine-b")
     * @param machine Which machine scanned it (e.g. "desktop", "server")
     */
    data class IngestResult(val fileCount: Int, val totalSize: Long, val totalFiles: Int, val sourceCount: Int)

    fun ingest(catalogFile: File, driveLabel: String, machine: String): IngestResult {
        val catalog = parseCatalog(catalogFile)
        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        val s = conn.createStatement()
        s.executeUpdate("PRAGMA journal_mode=WAL")
        s.executeUpdate("PRAGMA synchronous=NORMAL")
        createTables(conn)

        // Remove old data if re-ingesting
        val checkRs = s.executeQuery(
            "SELECT id FROM catalog_sources WHERE drive_label='${esc(driveLabel)}' AND machine='${esc(machine)}' AND catalog_date='${esc(catalog.date)}'"
        )
        val oldId = if (checkRs.next()) checkRs.getInt("id") else -1
        checkRs.close()
        if (oldId >= 0) {
            s.executeUpdate("DELETE FROM files WHERE source_id=$oldId")
            s.executeUpdate("DELETE FROM catalog_sources WHERE id=$oldId")
        }

        // Insert source record
        val totalSize = catalog.entries.sumOf { it.size }
        s.executeUpdate(
            "INSERT INTO catalog_sources (drive_label, machine, root_path, catalog_date, hash_algorithm, file_count, total_size) " +
            "VALUES ('${esc(driveLabel)}', '${esc(machine)}', '${esc(catalog.rootPath)}', '${esc(catalog.date)}', '${esc(catalog.hashAlgorithm)}', ${catalog.entries.size}, $totalSize)"
        )
        val idRs = s.executeQuery("SELECT last_insert_rowid()")
        idRs.next()
        val sourceId = idRs.getInt(1)
        idRs.close()

        // Bulk insert files in explicit transaction
        s.executeUpdate("BEGIN")
        val insertStmt = conn.prepareStatement(
            "INSERT INTO files (source_id, path, filename, extension, hash, size) VALUES (?, ?, ?, ?, ?, ?)"
        )
        for (entry in catalog.entries) {
            val filename = entry.path.substringAfterLast("/").substringAfterLast("\\")
            val extension = if (filename.contains(".")) filename.substringAfterLast(".").lowercase() else ""
            insertStmt.setInt(1, sourceId)
            insertStmt.setString(2, entry.path)
            insertStmt.setString(3, filename)
            insertStmt.setString(4, extension)
            insertStmt.setString(5, entry.hash)
            insertStmt.setLong(6, entry.size)
            insertStmt.executeUpdate()
        }
        insertStmt.close()
        s.executeUpdate("COMMIT")

        // Rebuild FTS index
        s.executeUpdate("INSERT INTO files_fts(files_fts) VALUES('rebuild')")

        // Gather stats before closing
        val totalFilesRs = s.executeQuery("SELECT COUNT(*) FROM files")
        totalFilesRs.next()
        val totalFiles = totalFilesRs.getInt(1)
        totalFilesRs.close()
        val sourceCountRs = s.executeQuery("SELECT COUNT(*) FROM catalog_sources")
        sourceCountRs.next()
        val sourceCount = sourceCountRs.getInt(1)
        sourceCountRs.close()

        s.close()
        conn.close()
        return IngestResult(catalog.entries.size, totalSize, totalFiles, sourceCount)
    }

    /**
     * Ingest a Sietch CID catalog (path/cid/sha256/size format).
     * CID catalogs come from `sietch index` with IPFS enabled.
     */
    fun ingestCidCatalog(catalogFile: File, driveLabel: String, machine: String): IngestResult {
        val catalog = parseCidCatalog(catalogFile)
        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        val s = conn.createStatement()
        s.executeUpdate("PRAGMA journal_mode=WAL")
        s.executeUpdate("PRAGMA synchronous=NORMAL")
        createTables(conn)

        val checkRs = s.executeQuery(
            "SELECT id FROM catalog_sources WHERE drive_label='${esc(driveLabel)}' AND machine='${esc(machine)}' AND catalog_date='${esc(catalog.date)}'"
        )
        val oldId = if (checkRs.next()) checkRs.getInt("id") else -1
        checkRs.close()
        if (oldId >= 0) {
            s.executeUpdate("DELETE FROM files WHERE source_id=$oldId")
            s.executeUpdate("DELETE FROM catalog_sources WHERE id=$oldId")
        }

        val totalSize = catalog.entries.sumOf { it.size }
        s.executeUpdate(
            "INSERT INTO catalog_sources (drive_label, machine, root_path, catalog_date, hash_algorithm, file_count, total_size) " +
            "VALUES ('${esc(driveLabel)}', '${esc(machine)}', '${esc(catalog.rootPath)}', '${esc(catalog.date)}', 'cid+sha256', ${catalog.entries.size}, $totalSize)"
        )
        val idRs = s.executeQuery("SELECT last_insert_rowid()")
        idRs.next()
        val sourceId = idRs.getInt(1)
        idRs.close()

        s.executeUpdate("BEGIN")
        val insertStmt = conn.prepareStatement(
            "INSERT INTO files (source_id, path, filename, extension, hash, cid, size) VALUES (?, ?, ?, ?, ?, ?, ?)"
        )
        for (entry in catalog.entries) {
            val filename = entry.path.substringAfterLast("/").substringAfterLast("\\")
            val extension = if (filename.contains(".")) filename.substringAfterLast(".").lowercase() else ""
            insertStmt.setInt(1, sourceId)
            insertStmt.setString(2, entry.path)
            insertStmt.setString(3, filename)
            insertStmt.setString(4, extension)
            insertStmt.setString(5, entry.sha256)
            insertStmt.setString(6, entry.cid)
            insertStmt.setLong(7, entry.size)
            insertStmt.executeUpdate()
        }
        insertStmt.close()
        s.executeUpdate("COMMIT")

        s.executeUpdate("INSERT INTO files_fts(files_fts) VALUES('rebuild')")

        val totalFilesRs = s.executeQuery("SELECT COUNT(*) FROM files")
        totalFilesRs.next()
        val totalFiles = totalFilesRs.getInt(1)
        totalFilesRs.close()
        val sourceCountRs = s.executeQuery("SELECT COUNT(*) FROM catalog_sources")
        sourceCountRs.next()
        val sourceCount = sourceCountRs.getInt(1)
        sourceCountRs.close()

        s.close()
        conn.close()
        return IngestResult(catalog.entries.size, totalSize, totalFiles, sourceCount)
    }

    /** Find exact duplicates across drives using CID (content-addressed). */
    fun findCidDuplicates(conn: Connection, minSize: Long = 0): List<DuplicateGroup> {
        val stmt = conn.prepareStatement("""
            SELECT f.filename, f.size, f.cid, GROUP_CONCAT(DISTINCT cs.drive_label) as drives,
                   COUNT(DISTINCT cs.drive_label) as drive_count
            FROM files f
            JOIN catalog_sources cs ON cs.id = f.source_id
            WHERE f.cid != '' AND f.size >= ?
            GROUP BY f.cid
            HAVING drive_count > 1
            ORDER BY f.size DESC
        """)
        stmt.setLong(1, minSize)
        val rs = stmt.executeQuery()
        val results = mutableListOf<DuplicateGroup>()
        while (rs.next()) {
            results.add(DuplicateGroup(
                filename = rs.getString("filename"),
                size = rs.getLong("size"),
                drives = rs.getString("drives"),
                driveCount = rs.getInt("drive_count")
            ))
        }
        return results
    }

    /** Find files with CIDs that exist on only one drive (at risk). */
    fun findCidSingleCopy(conn: Connection, minSize: Long = 0): List<RiskFile> {
        val stmt = conn.prepareStatement("""
            SELECT f.filename, f.size, f.cid, cs.drive_label, cs.machine
            FROM files f
            JOIN catalog_sources cs ON cs.id = f.source_id
            WHERE f.cid != '' AND f.size >= ?
            GROUP BY f.cid
            HAVING COUNT(DISTINCT cs.drive_label) = 1
            ORDER BY f.size DESC
        """)
        stmt.setLong(1, minSize)
        val rs = stmt.executeQuery()
        val results = mutableListOf<RiskFile>()
        while (rs.next()) {
            results.add(RiskFile(
                filename = rs.getString("filename"),
                size = rs.getLong("size"),
                hash = rs.getString("cid"),
                driveLabel = rs.getString("drive_label"),
                machine = rs.getString("machine")
            ))
        }
        return results
    }

    /**
     * Rebuild the CatalogIndex FTS5 tables from a unified registry DB.
     * Reads content_locations from the unified registry and populates
     * catalog_sources and files tables, then rebuilds the FTS index.
     *
     * @param conn An open connection to this CatalogIndex database
     * @param registryDbPath Path to a Sietch registry or CHOAM unified registry
     * @param driveConfig Drive config from ChoamConfig for drive_label derivation
     * @return Number of files indexed
     */
    fun rebuildFromRegistry(conn: Connection, registryDbPath: String, driveConfig: Map<String, Drive>, machineNameMap: Map<String, String> = emptyMap()): Long {
        val registryFile = File(registryDbPath)
        if (!registryFile.exists()) {
            logger.warn { "Registry not found at $registryDbPath" }
            return 0
        }

        data class RegistryEntry(
            val cid: String,
            val machineName: String,
            val filePath: String,
            val fileSize: Long,
            val registeredAt: String,
            val lastSyncedAt: String
        )

        val entries = mutableListOf<RegistryEntry>()
        val regConn = DriverManager.getConnection("jdbc:sqlite:$registryDbPath")
        try {
            regConn.createStatement().use { it.executeUpdate("PRAGMA query_only=ON") }

            // Sietch's public registry schema has registered_at, while CHOAM's
            // unified registry adds last_synced_at. Accept both without requiring
            // a CHOAM-specific migration on a read-only Sietch inventory.
            val columns = mutableSetOf<String>()
            regConn.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA table_info(content_locations)").use { rs ->
                    while (rs.next()) {
                        columns.add(rs.getString("name").lowercase())
                    }
                }
            }
            val requiredColumns = setOf("cid", "machine_name", "file_path", "file_size", "registered_at")
            require(requiredColumns.all { it in columns }) {
                "Registry content_locations table is missing required Sietch columns"
            }
            val lastSyncedAtExpression = if ("last_synced_at" in columns) {
                "last_synced_at"
            } else {
                // A direct Sietch registry has no sync watermark. Its registration
                // time is the freshest available inventory observation.
                "registered_at"
            }

            regConn.createStatement().use { stmt ->
                stmt.executeQuery(
                    "SELECT cid, machine_name, file_path, file_size, registered_at, " +
                        "$lastSyncedAtExpression AS last_synced_at FROM content_locations"
                ).use { rs ->
                    while (rs.next()) {
                        val originalMachine = rs.getString("machine_name")
                        val remappedMachine = machineNameMap[originalMachine] ?: originalMachine
                        entries.add(RegistryEntry(
                            cid = rs.getString("cid"),
                            machineName = remappedMachine,
                            filePath = rs.getString("file_path"),
                            fileSize = rs.getLong("file_size"),
                            registeredAt = rs.getString("registered_at") ?: "",
                            lastSyncedAt = rs.getString("last_synced_at") ?: ""
                        ))
                    }
                }
            }
        } finally {
            regConn.close()
        }

        // Build mount-point lookup from drive config
        val mountPointMap = buildMountPointMap(driveConfig)

        // Build ignore matchers from default exclude patterns + CHOAM extras
        val choamExcludePatterns = listOf("._*") // macOS AppleDouble resource forks on exFAT/NTFS
        val allExcludePatterns = DEFAULT_EXCLUDE_PATTERNS + choamExcludePatterns
        val ignoreMatchers = allExcludePatterns.map { pattern ->
            FileSystems.getDefault().getPathMatcher("glob:$pattern")
        }

        // Filter out ignored paths (macOS metadata, temp files)
        val filtered = entries.filter { entry ->
            val filename = java.nio.file.Path.of(entry.filePath.substringAfterLast("/").substringAfterLast("\\"))
            // Also check parent directory names in the path
            val pathSegments = entry.filePath.split("/", "\\")
            val filenameExcluded = ignoreMatchers.any { it.matches(filename) }
            val dirExcluded = pathSegments.dropLast(1).any { segment ->
                val segPath = java.nio.file.Path.of(segment)
                ignoreMatchers.any { it.matches(segPath) }
            }
            !filenameExcluded && !dirExcluded
        }

        // Deduplicate: for each (machine_name, file_path), keep only the row with latest registered_at.
        // The unified registry PK is (cid, machine_name, file_path), so the same path can appear
        // multiple times with different CIDs (e.g. .DS_Store changing between runs).
        // The files table has UNIQUE(source_id, path), so we must pick one.
        val deduped = filtered
            .groupBy { Pair(it.machineName, it.filePath) }
            .mapValues { (_, dupes) -> dupes.maxByOrNull { it.registeredAt } ?: dupes.first() }
            .values
            .toList()

        val dedupDiff = filtered.size - deduped.size
        if (dedupDiff > 0) {
            logger.info { "Deduplicated $dedupDiff entries (same machine+path, different CIDs)" }
        }
        val ignoreDiff = entries.size - filtered.size
        if (ignoreDiff > 0) {
            logger.info { "Filtered $ignoreDiff ignored paths (macOS metadata, temp files)" }
        }

        // Group by (drive_label, machine_name) for source records
        data class SourceKey(val driveLabel: String, val machine: String)

        val grouped = deduped.groupBy { entry ->
            val driveLabel = deriveDriveLabel(entry.filePath, mountPointMap, entry.machineName)
            SourceKey(driveLabel, entry.machineName)
        }

        val stmt = conn.createStatement()
        var totalFiles = 0L
        var transactionStarted = false
        try {
            stmt.executeUpdate("BEGIN")
            transactionStarted = true

            // Replace the registry projection atomically. A valid empty snapshot
            // must clear prior registry rows rather than leave stale search hits.
            stmt.executeUpdate("DELETE FROM files WHERE source_id IN (SELECT id FROM catalog_sources WHERE hash_algorithm = 'registry')")
            stmt.executeUpdate("DELETE FROM catalog_sources WHERE hash_algorithm = 'registry'")

            conn.prepareStatement(
                "INSERT INTO catalog_sources (drive_label, machine, root_path, catalog_date, hash_algorithm, file_count, total_size) " +
                    "VALUES (?, ?, ?, datetime('now'), 'registry', ?, ?)"
            ).use { sourceInsert ->
                conn.prepareStatement(
                    "INSERT INTO files (source_id, path, filename, extension, hash, cid, size, last_synced_at) VALUES (?, ?, ?, ?, '-', ?, ?, ?)"
                ).use { fileInsert ->
                    for ((sourceKey, sourceEntries) in grouped) {
                        val totalSize = sourceEntries.sumOf { it.fileSize }

                        sourceInsert.setString(1, sourceKey.driveLabel)
                        sourceInsert.setString(2, sourceKey.machine)
                        sourceInsert.setString(3, sourceKey.driveLabel) // root_path = drive label for registry sources
                        sourceInsert.setInt(4, sourceEntries.size)
                        sourceInsert.setLong(5, totalSize)
                        sourceInsert.executeUpdate()

                        val sourceId = conn.createStatement().use { idStmt ->
                            idStmt.executeQuery("SELECT last_insert_rowid()").use { idRs ->
                                idRs.next()
                                idRs.getInt(1)
                            }
                        }

                        for (entry in sourceEntries) {
                            val filename = entry.filePath.substringAfterLast("/").substringAfterLast("\\")
                            val extension = if (filename.contains(".")) filename.substringAfterLast(".").lowercase() else ""

                            fileInsert.setInt(1, sourceId)
                            fileInsert.setString(2, entry.filePath)
                            fileInsert.setString(3, filename)
                            fileInsert.setString(4, extension)
                            fileInsert.setString(5, entry.cid)
                            fileInsert.setLong(6, entry.fileSize)
                            fileInsert.setString(7, entry.lastSyncedAt)
                            fileInsert.executeUpdate()
                            totalFiles++
                        }
                    }
                }
            }

            // Keep the external-content FTS table in the same transaction as its source rows.
            stmt.executeUpdate("INSERT INTO files_fts(files_fts) VALUES('rebuild')")
            stmt.executeUpdate("COMMIT")
            transactionStarted = false
        } catch (e: Exception) {
            if (transactionStarted) {
                try {
                    stmt.executeUpdate("ROLLBACK")
                } catch (_: Exception) {
                    // The original failure is the useful diagnostic.
                }
            }
            throw e
        } finally {
            stmt.close()
        }

        logger.info { "Rebuilt CatalogIndex from registry: $totalFiles files across ${grouped.size} sources" }
        return totalFiles
    }

    /**
     * Build a map of mount-point prefix -> drive label from drive config.
     * Scans /Volumes/ convention for macOS.
     */
    private fun buildMountPointMap(driveConfig: Map<String, Drive>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for ((_, drive) in driveConfig) {
            // Convention: drives mount at /Volumes/<label>/
            map["/Volumes/${drive.label}/"] = drive.label
        }
        return map
    }

    companion object {
        /**
         * Derive a drive_label from a file path.
         * Priority: 1) match config mount points, 2) extract /Volumes/<X>/, 3) use machine name
         */
        fun deriveDriveLabel(filePath: String, mountPointMap: Map<String, String>, machineName: String): String {
            // Check config-derived mount points
            for ((prefix, label) in mountPointMap) {
                if (filePath.startsWith(prefix)) return label
            }
            // Extract /Volumes/<label>/ from path
            val volumesMatch = Regex("^/Volumes/([^/]+)/").find(filePath)
            if (volumesMatch != null) return volumesMatch.groupValues[1]
            // Last resort: use machine name
            return machineName
        }
    }

    private fun esc(v: String) = v.replace("'", "''")

    /** Search filenames using FTS5. Returns matches with drive/machine info. */
    fun search(conn: Connection, query: String, limit: Int = 50): List<SearchResult> {
        val sanitized = "\"" + query.replace("\"", "\"\"") + "\""
        val stmt = conn.prepareStatement("""
            SELECT f.path, f.filename, f.extension, f.hash, f.cid, f.size,
                   cs.drive_label, cs.machine, cs.root_path, cs.catalog_date
            FROM files_fts fts
            JOIN files f ON f.id = fts.rowid
            JOIN catalog_sources cs ON cs.id = f.source_id
            WHERE files_fts MATCH ?
            ORDER BY rank
            LIMIT ?
        """)
        stmt.setString(1, sanitized)
        stmt.setInt(2, limit)
        val rs = stmt.executeQuery()

        val results = mutableListOf<SearchResult>()
        while (rs.next()) {
            results.add(SearchResult(
                path = rs.getString("path"),
                filename = rs.getString("filename"),
                extension = rs.getString("extension"),
                hash = rs.getString("hash"),
                cid = rs.getString("cid") ?: "",
                size = rs.getLong("size"),
                driveLabel = rs.getString("drive_label"),
                machine = rs.getString("machine"),
                rootPath = rs.getString("root_path"),
                catalogDate = rs.getString("catalog_date")
            ))
        }
        return results
    }

    /**
     * Advanced search with filters. Uses FTS5 for the text query (if non-blank),
     * then applies SQL WHERE clauses for size, extension, date, CID, and path glob.
     * When query is blank and filters are set, scans the files table directly.
     */
    fun advancedSearch(conn: Connection, query: String, filters: SearchFilters, limit: Int = 50): List<SearchResult> {
        val params = mutableListOf<Any>()
        val conditions = mutableListOf<String>()

        // CID exact lookup is a special fast path — no FTS needed
        if (filters.cid != null) {
            conditions.add("f.cid = ?")
            params.add(filters.cid)
        }

        if (filters.minSize != null) {
            conditions.add("f.size >= ?")
            params.add(filters.minSize)
        }
        if (filters.maxSize != null) {
            conditions.add("f.size <= ?")
            params.add(filters.maxSize)
        }
        if (filters.extensions != null && filters.extensions.isNotEmpty()) {
            val placeholders = filters.extensions.joinToString(", ") { "?" }
            conditions.add("f.extension IN ($placeholders)")
            params.addAll(filters.extensions)
        }
        if (filters.after != null) {
            conditions.add("cs.catalog_date >= ?")
            params.add(filters.after)
        }
        if (filters.before != null) {
            conditions.add("cs.catalog_date <= ?")
            params.add(filters.before)
        }
        if (filters.pathGlob != null) {
            conditions.add("f.path LIKE ?")
            params.add(filters.pathGlob)
        }

        val usesFts = query.isNotBlank() && filters.cid == null
        val sql: String

        if (usesFts) {
            val whereSuffix = if (conditions.isNotEmpty()) " AND " + conditions.joinToString(" AND ") else ""
            sql = """
                SELECT f.path, f.filename, f.extension, f.hash, f.cid, f.size,
                       cs.drive_label, cs.machine, cs.root_path, cs.catalog_date
                FROM files_fts fts
                JOIN files f ON f.id = fts.rowid
                JOIN catalog_sources cs ON cs.id = f.source_id
                WHERE files_fts MATCH ?$whereSuffix
                ORDER BY rank
                LIMIT ?
            """.trimIndent()
        } else {
            val whereSuffix = if (conditions.isNotEmpty()) "WHERE " + conditions.joinToString(" AND ") else ""
            sql = """
                SELECT f.path, f.filename, f.extension, f.hash, f.cid, f.size,
                       cs.drive_label, cs.machine, cs.root_path, cs.catalog_date
                FROM files f
                JOIN catalog_sources cs ON cs.id = f.source_id
                $whereSuffix
                ORDER BY f.size DESC
                LIMIT ?
            """.trimIndent()
        }

        val stmt = conn.prepareStatement(sql)
        var idx = 1

        if (usesFts) {
            val sanitized = "\"" + query.replace("\"", "\"\"") + "\""
            stmt.setString(idx++, sanitized)
        }

        for (param in params) {
            when (param) {
                is Long -> stmt.setLong(idx++, param)
                is String -> stmt.setString(idx++, param)
                else -> stmt.setObject(idx++, param)
            }
        }
        stmt.setInt(idx, limit)

        val rs = stmt.executeQuery()
        val results = mutableListOf<SearchResult>()
        while (rs.next()) {
            results.add(SearchResult(
                path = rs.getString("path"),
                filename = rs.getString("filename"),
                extension = rs.getString("extension"),
                hash = rs.getString("hash"),
                cid = rs.getString("cid") ?: "",
                size = rs.getLong("size"),
                driveLabel = rs.getString("drive_label"),
                machine = rs.getString("machine"),
                rootPath = rs.getString("root_path"),
                catalogDate = rs.getString("catalog_date")
            ))
        }
        return results
    }

    /** Find files that exist on only one drive (at risk of data loss). */
    fun findSingleCopyFiles(conn: Connection, minSize: Long = 0): List<RiskFile> {
        val stmt = conn.prepareStatement("""
            SELECT f.filename, f.size, f.hash, cs.drive_label, cs.machine
            FROM files f
            JOIN catalog_sources cs ON cs.id = f.source_id
            WHERE f.hash != '-' AND f.hash NOT LIKE 'ERROR:%' AND f.size >= ?
            GROUP BY f.hash
            HAVING COUNT(DISTINCT cs.drive_label) = 1
            ORDER BY f.size DESC
        """)
        stmt.setLong(1, minSize)
        val rs = stmt.executeQuery()

        val results = mutableListOf<RiskFile>()
        while (rs.next()) {
            results.add(RiskFile(
                filename = rs.getString("filename"),
                size = rs.getLong("size"),
                hash = rs.getString("hash"),
                driveLabel = rs.getString("drive_label"),
                machine = rs.getString("machine")
            ))
        }
        return results
    }

    /** Find files that exist on multiple drives (safely backed up). */
    fun findDuplicates(conn: Connection, minSize: Long = 0): List<DuplicateGroup> {
        // For no-hash catalogs, match by filename + size
        val stmt = conn.prepareStatement("""
            SELECT f.filename, f.size, GROUP_CONCAT(DISTINCT cs.drive_label) as drives,
                   COUNT(DISTINCT cs.drive_label) as drive_count
            FROM files f
            JOIN catalog_sources cs ON cs.id = f.source_id
            WHERE f.size >= ? AND f.filename NOT LIKE '.%'
            GROUP BY f.filename, f.size
            HAVING drive_count > 1
            ORDER BY f.size DESC
        """)
        stmt.setLong(1, minSize)
        val rs = stmt.executeQuery()

        val results = mutableListOf<DuplicateGroup>()
        while (rs.next()) {
            results.add(DuplicateGroup(
                filename = rs.getString("filename"),
                size = rs.getLong("size"),
                drives = rs.getString("drives"),
                driveCount = rs.getInt("drive_count")
            ))
        }
        return results
    }

    /** Summary stats for the entire index. */
    fun stats(conn: Connection): IndexStats {
        val sources = mutableListOf<SourceStats>()
        val rs = conn.createStatement().executeQuery("""
            SELECT cs.drive_label, cs.machine, cs.catalog_date, cs.file_count, cs.total_size,
                   cs.root_path, cs.hash_algorithm
            FROM catalog_sources cs ORDER BY cs.drive_label
        """)
        while (rs.next()) {
            sources.add(SourceStats(
                driveLabel = rs.getString("drive_label"),
                machine = rs.getString("machine"),
                catalogDate = rs.getString("catalog_date"),
                fileCount = rs.getInt("file_count"),
                totalSize = rs.getLong("total_size"),
                rootPath = rs.getString("root_path"),
                hashAlgorithm = rs.getString("hash_algorithm")
            ))
        }

        val totalFiles = conn.createStatement().executeQuery("SELECT COUNT(*) FROM files")
        totalFiles.next()
        val totalFileCount = totalFiles.getInt(1)

        return IndexStats(sources = sources, totalFiles = totalFileCount)
    }
}

/**
 * Filters for advanced search. All fields are optional — null means "no filter".
 */
data class SearchFilters(
    val minSize: Long? = null,
    val maxSize: Long? = null,
    val extensions: List<String>? = null,  // lowercase, no dots (e.g. ["mkv", "mp4"])
    val after: String? = null,             // YYYY-MM-DD — catalog_date >=
    val before: String? = null,            // YYYY-MM-DD — catalog_date <=
    val cid: String? = null,               // exact CID match
    val pathGlob: String? = null           // SQL LIKE pattern derived from glob
) {
    val hasAny: Boolean get() = minSize != null || maxSize != null || extensions != null ||
            after != null || before != null || cid != null || pathGlob != null
}

data class SearchResult(
    val path: String, val filename: String, val extension: String,
    val hash: String, val cid: String, val size: Long,
    val driveLabel: String, val machine: String,
    val rootPath: String, val catalogDate: String
)

data class RiskFile(
    val filename: String, val size: Long, val hash: String,
    val driveLabel: String, val machine: String
)

data class DuplicateGroup(
    val filename: String, val size: Long,
    val drives: String, val driveCount: Int
)

data class SourceStats(
    val driveLabel: String, val machine: String, val catalogDate: String,
    val fileCount: Int, val totalSize: Long,
    val rootPath: String, val hashAlgorithm: String
)

data class IndexStats(
    val sources: List<SourceStats>, val totalFiles: Int
)

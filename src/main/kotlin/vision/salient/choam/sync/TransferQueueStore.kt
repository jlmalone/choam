package vision.salient.choam.sync

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import vision.salient.choam.receipt.QueueReceiptStore
import vision.salient.choam.receipt.TransferReceiptState
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * SQLite-backed transfer queue replacing the JSON file-based TransferQueue.
 *
 * Key improvements over the JSON queue:
 * - WAL mode + busy_timeout for safe concurrent access from CLI, daemon, and web
 * - Atomic claim pattern (claimNext) prevents duplicate processing
 * - Deduplication on add — same source/dest/machine can't be queued twice
 * - PID tracking for stale-running detection and cancellation
 */
class TransferQueueStore(
    private val dbPath: Path = Paths.get(
        System.getProperty("user.home"), ".choam", "transfer_queue.db"
    )
) {
    /** The private receipt table shares this SQLite database but not the queue-status schema. */
    val receiptDatabasePath: Path get() = dbPath
    private val receiptStore by lazy { QueueReceiptStore(dbPath) }
    // Progress sidecars live alongside the queue DB. In production dbPath is
    // ~/.choam/transfer_queue.db so this is ~/.choam (unchanged); deriving it from dbPath
    // instead of hardcoding $HOME keeps the watchdog's progress-staleness check correct
    // under a custom dbPath (and makes it unit-testable against a temp dir).
    private val choamDir = dbPath.parent.toFile()
    init {
        Files.createDirectories(dbPath.parent)
        migrateFromJson()
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS transfer_queue (
                        id TEXT PRIMARY KEY,
                        source_path TEXT NOT NULL,
                        destination_machine TEXT NOT NULL,
                        destination_path TEXT NOT NULL,
                        mode TEXT NOT NULL DEFAULT 'COPY',
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        priority INTEGER NOT NULL DEFAULT 1,
                        bandwidth_limit_kbps INTEGER,
                        no_catalog INTEGER NOT NULL DEFAULT 0,
                        created_at TEXT NOT NULL,
                        started_at TEXT,
                        completed_at TEXT,
                        bytes_transferred INTEGER NOT NULL DEFAULT 0,
                        total_bytes INTEGER NOT NULL DEFAULT 0,
                        error TEXT,
                        retry_count INTEGER NOT NULL DEFAULT 0,
                        next_retry_at TEXT,
                        force_overwrite INTEGER NOT NULL DEFAULT 0,
                        backup_before INTEGER NOT NULL DEFAULT 0,
                        pid INTEGER
                    )
                """.trimIndent())
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_queue_status ON transfer_queue(status)")
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_queue_dest ON transfer_queue(destination_machine, destination_path, status)")
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_queue_priority ON transfer_queue(priority, created_at)")
            }
        }
        publishStatusSnapshot()
    }

    private fun getConnection(): Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().use { stmt ->
            stmt.executeUpdate("PRAGMA journal_mode=WAL")
            stmt.executeUpdate("PRAGMA busy_timeout=5000")
            stmt.executeUpdate("PRAGMA synchronous=NORMAL")
        }
        return conn
    }

    /**
     * One-shot migration from the old JSON queue file.
     * Reads transfer_queue.json, inserts all entries into SQLite, and renames the file.
     */
    private fun migrateFromJson() {
        val jsonPath = dbPath.parent.resolve("transfer_queue.json")
        if (!Files.exists(jsonPath)) return

        try {
            val content = Files.readString(jsonPath)
            if (content.isBlank()) {
                Files.move(jsonPath, jsonPath.resolveSibling("transfer_queue.json.migrated"))
                return
            }

            val json = Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
            val entries = json.decodeFromString(ListSerializer(TransferQueueEntry.serializer()), content)

            if (entries.isNotEmpty()) {
                // Ensure table exists before migration
                getConnection().use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.executeUpdate("""
                            CREATE TABLE IF NOT EXISTS transfer_queue (
                                id TEXT PRIMARY KEY,
                                source_path TEXT NOT NULL,
                                destination_machine TEXT NOT NULL,
                                destination_path TEXT NOT NULL,
                                mode TEXT NOT NULL DEFAULT 'COPY',
                                status TEXT NOT NULL DEFAULT 'PENDING',
                                priority INTEGER NOT NULL DEFAULT 1,
                                bandwidth_limit_kbps INTEGER,
                                no_catalog INTEGER NOT NULL DEFAULT 0,
                                created_at TEXT NOT NULL,
                                started_at TEXT,
                                completed_at TEXT,
                                bytes_transferred INTEGER NOT NULL DEFAULT 0,
                                total_bytes INTEGER NOT NULL DEFAULT 0,
                                error TEXT,
                                retry_count INTEGER NOT NULL DEFAULT 0,
                                next_retry_at TEXT,
                                force_overwrite INTEGER NOT NULL DEFAULT 0,
                                backup_before INTEGER NOT NULL DEFAULT 0,
                                pid INTEGER
                            )
                        """.trimIndent())
                    }
                    insertEntries(conn, entries)
                }
                logger.info { "Migrated ${entries.size} queue entries from JSON to SQLite" }
            }

            Files.move(jsonPath, jsonPath.resolveSibling("transfer_queue.json.migrated"))
            logger.info { "Renamed transfer_queue.json to transfer_queue.json.migrated" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to migrate JSON queue: ${e.message}" }
        }
    }

    private fun insertEntries(conn: Connection, entries: List<TransferQueueEntry>) {
        val sql = """
            INSERT OR IGNORE INTO transfer_queue
            (id, source_path, destination_machine, destination_path, mode, status, priority,
             bandwidth_limit_kbps, no_catalog, created_at, started_at, completed_at,
             bytes_transferred, total_bytes, error, retry_count, next_retry_at,
             force_overwrite, backup_before, pid)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        conn.prepareStatement(sql).use { ps ->
            for (entry in entries) {
                ps.setString(1, entry.id)
                ps.setString(2, entry.sourcePath)
                ps.setString(3, entry.destinationMachine)
                ps.setString(4, entry.destinationPath)
                ps.setString(5, entry.mode.name)
                ps.setString(6, entry.status.name)
                ps.setInt(7, priorityToSortOrder(entry.priority))
                if (entry.bandwidthLimitKBps != null) ps.setInt(8, entry.bandwidthLimitKBps) else ps.setNull(8, java.sql.Types.INTEGER)
                ps.setInt(9, if (entry.noCatalog) 1 else 0)
                ps.setString(10, entry.createdAt)
                ps.setString(11, entry.startedAt)
                ps.setString(12, entry.completedAt)
                ps.setLong(13, entry.bytesTransferred)
                ps.setLong(14, entry.totalBytes)
                ps.setString(15, entry.error)
                ps.setInt(16, entry.retryCount)
                ps.setString(17, entry.nextRetryAt)
                ps.setInt(18, if (entry.force) 1 else 0)
                ps.setInt(19, if (entry.backup) 1 else 0)
                ps.setNull(20, java.sql.Types.INTEGER)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    /**
     * Add an entry to the queue. Returns an existing semantically-identical active
     * operation when one is already pending/running.
     *
     * BEGIN IMMEDIATE makes the read-then-insert sequence atomic across processes.
     * Mode and transfer flags are part of the identity: a later MOVE, forced copy,
     * backup request, or bandwidth policy must not be swallowed by an earlier COPY.
     */
    fun add(entry: TransferQueueEntry): TransferQueueEntry {
        val result = getConnection().use { conn ->
            conn.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            try {
                var existing: TransferQueueEntry? = null
                conn.prepareStatement("""
                    SELECT id FROM transfer_queue
                    WHERE source_path = ? AND destination_machine = ? AND destination_path = ?
                      AND mode = ? AND force_overwrite = ? AND backup_before = ?
                      AND no_catalog = ?
                      AND ((bandwidth_limit_kbps IS NULL AND ? IS NULL) OR bandwidth_limit_kbps = ?)
                      AND status IN ('PENDING', 'RUNNING')
                    LIMIT 1
                """.trimIndent()).use { ps ->
                    ps.setString(1, entry.sourcePath)
                    ps.setString(2, entry.destinationMachine)
                    ps.setString(3, entry.destinationPath)
                    ps.setString(4, entry.mode.name)
                    ps.setInt(5, if (entry.force) 1 else 0)
                    ps.setInt(6, if (entry.backup) 1 else 0)
                    ps.setInt(7, if (entry.noCatalog) 1 else 0)
                    if (entry.bandwidthLimitKBps == null) {
                        ps.setNull(8, java.sql.Types.INTEGER)
                        ps.setNull(9, java.sql.Types.INTEGER)
                    } else {
                        ps.setInt(8, entry.bandwidthLimitKBps)
                        ps.setInt(9, entry.bandwidthLimitKBps)
                    }
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            val existingId = rs.getString("id")
                            existing = loadById(conn, existingId)
                            logger.info { "Duplicate queue entry — returning existing $existingId" }
                        }
                    }
                }

                val result = existing ?: entry.also {
                    insertEntries(conn, listOf(it))
                    logger.info { "Queued transfer ${it.id}: ${it.sourcePath} → ${it.destinationMachine}:${it.destinationPath}" }
                }
                conn.createStatement().use { it.execute("COMMIT") }
                return@use result
            } catch (e: Exception) {
                try { conn.createStatement().use { it.execute("ROLLBACK") } } catch (_: Exception) {}
                throw e
            }
        }
        publishStatusSnapshot()
        return result
    }

    fun loadAll(): List<TransferQueueEntry> {
        getConnection().use { conn ->
            // Auto-expire completed/cancelled entries older than 24 hours
            val cutoff = Instant.now().minus(Duration.ofHours(24)).toString()
            val deleted = conn.prepareStatement("""
                DELETE FROM transfer_queue
                WHERE status IN ('COMPLETED', 'CANCELLED')
                  AND COALESCE(completed_at, created_at) < ?
            """.trimIndent()).use { ps ->
                ps.setString(1, cutoff)
                ps.executeUpdate()
            }
            if (deleted > 0) {
                logger.info { "Auto-expired $deleted queue entries older than 24h" }
            }

            return conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT * FROM transfer_queue ORDER BY priority ASC, created_at ASC").use { rs ->
                    val entries = mutableListOf<TransferQueueEntry>()
                    while (rs.next()) {
                        entries.add(rowToEntry(rs))
                    }
                    entries
                }
            }
        }
    }

    /**
     * All active entries: PENDING, RUNNING, and FAILED (including those in backoff).
     * Used for display — shows everything that isn't completed/cancelled.
     */
    fun active(): List<TransferQueueEntry> {
        getConnection().use { conn ->
            return conn.createStatement().use { stmt ->
                stmt.executeQuery("""
                    SELECT * FROM transfer_queue
                    WHERE status IN ('PENDING', 'RUNNING', 'FAILED')
                    ORDER BY priority ASC, created_at ASC
                """.trimIndent()).use { rs ->
                    val entries = mutableListOf<TransferQueueEntry>()
                    while (rs.next()) {
                        entries.add(rowToEntry(rs))
                    }
                    entries
                }
            }
        }
    }

    /**
     * Entries ready to process NOW: pending/failed, within retry limit, past backoff.
     * Used by the queue processor — NOT for display.
     */
    fun pending(): List<TransferQueueEntry> {
        val now = Instant.now().toString()
        getConnection().use { conn ->
            return conn.prepareStatement("""
                SELECT * FROM transfer_queue
                WHERE status IN ('PENDING', 'FAILED')
                  AND retry_count <= ?
                  AND (next_retry_at IS NULL OR next_retry_at <= ?)
                ORDER BY priority ASC, created_at ASC
            """.trimIndent()).use { ps ->
                ps.setInt(1, TransferQueueEntry.MAX_RETRIES)
                ps.setString(2, now)
                ps.executeQuery().use { rs ->
                    val entries = mutableListOf<TransferQueueEntry>()
                    while (rs.next()) {
                        entries.add(rowToEntry(rs))
                    }
                    entries
                }
            }
        }
    }

    /**
     * Atomically claim the next pending entry for processing.
     * Uses UPDATE ... WHERE to prevent two processors from claiming the same entry.
     * Returns null if no entries are available.
     */
    fun claimNext(): TransferQueueEntry? {
        val now = Instant.now().toString()
        val pid = ProcessHandle.current().pid()
        val claimed = getConnection().use { conn ->
            // SQLite doesn't support UPDATE ... RETURNING in all JDBC drivers,
            // so we use a transaction with SELECT + UPDATE
            conn.autoCommit = false
            try {
                val entryId = conn.prepareStatement("""
                    SELECT id FROM transfer_queue
                    WHERE status IN ('PENDING', 'FAILED')
                      AND retry_count <= ?
                      AND (next_retry_at IS NULL OR next_retry_at <= ?)
                    ORDER BY priority ASC, created_at ASC
                    LIMIT 1
                """.trimIndent()).use { ps ->
                    ps.setInt(1, TransferQueueEntry.MAX_RETRIES)
                    ps.setString(2, now)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rs.getString("id") else null
                    }
                }

                if (entryId == null) {
                    conn.rollback()
                    return@use null
                }

                conn.prepareStatement("""
                    UPDATE transfer_queue
                    SET status = 'RUNNING', started_at = ?, pid = ?
                    WHERE id = ? AND status IN ('PENDING', 'FAILED')
                """.trimIndent()).use { ps ->
                    ps.setString(1, now)
                    ps.setLong(2, pid)
                    ps.setString(3, entryId)
                    val updated = ps.executeUpdate()
                    if (updated == 0) {
                        // Another processor claimed it between SELECT and UPDATE
                        conn.rollback()
                        return@use null
                    }
                }

                conn.commit()
                return@use loadById(conn, entryId)
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
        if (claimed != null) publishStatusSnapshot()
        return claimed
    }

    /**
     * Recover stale RUNNING entries — if a transfer has been RUNNING for longer than
     * STALE_RUNNING_HOURS and its PID is no longer alive, transition to FAILED.
     */
    fun recoverStaleRunning(): Int {
        val now = Instant.now()
        getConnection().use { conn ->
            // Query running entries with their PIDs
            data class RunningEntry(val entry: TransferQueueEntry, val pid: Long?)

            val staleEntries = conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT * FROM transfer_queue WHERE status = 'RUNNING'").use { rs ->
                    val entries = mutableListOf<RunningEntry>()
                    while (rs.next()) {
                        val pid = rs.getLong("pid").let { if (rs.wasNull()) null else it }
                        entries.add(RunningEntry(rowToEntry(rs), pid))
                    }
                    entries
                }
            }

            var recovered = 0
            for ((entry, pid) in staleEntries) {
                val startedAt = entry.startedAt?.let {
                    try { Instant.parse(it) } catch (_: Exception) { null }
                } ?: continue

                val hoursRunning = Duration.between(startedAt, now).toHours()

                // Check if PID is still alive (if tracked)
                val pidDead = pid?.let { p ->
                    try { !ProcessHandle.of(p).isPresent } catch (_: Exception) { true }
                }
                // pidDead == true: PID tracked and dead -> recover immediately
                // pidDead == false: PID tracked and alive -> don't recover
                // pidDead == null: no PID tracked -> fall back to time-based check

                // Check progress file staleness — if no update in 15 min, rsync is likely dead
                val progressFile = File(choamDir, "queue-progress-${entry.id}.json")
                val progressStale = if (progressFile.exists()) {
                    val lastModified = progressFile.lastModified()
                    (System.currentTimeMillis() - lastModified) > 15 * 60 * 1000 // 15 minutes
                } else {
                    null // No progress file — can't determine staleness
                }

                // PID is the daemon JVM, not the child rsync. A live daemon PID
                // does NOT mean the transfer is healthy — the transfer may be hung
                // while the daemon loop continues. So: check progress staleness even
                // when PID is alive.
                val shouldRecover = when {
                    pidDead == true -> true                          // owner PID dead — reclaim immediately
                    progressStale == true -> true                    // rsync progress stale >15 min — transfer hung even if the daemon is alive
                    progressStale == false -> false                  // progress sidecar is being written right now — healthy, leave it
                    // No progress sidecar: either a pre-transfer phase (preflight/verify never
                    // write one) or the tracked PID was recycled to an unrelated live process.
                    // The old `pidDead == false -> false` here let a hung-but-alive owner hold
                    // the entry RUNNING forever, freezing the queue. Bound it by wall-clock so a
                    // stuck entry can always be reclaimed even when its PID looks alive.
                    else -> hoursRunning >= TransferQueueEntry.STALE_RUNNING_HOURS
                }

                if (shouldRecover) {
                    val reason = when {
                        pidDead == true -> "PID $pid dead"
                        progressStale == true -> "progress stale >15 min (daemon alive but transfer hung)"
                        pidDead == false -> "stuck ${hoursRunning}h, no progress (owner PID $pid alive but not progressing)"
                        else -> "stuck ${hoursRunning}h"
                    }
                    val newRetryCount = entry.retryCount + 1
                    val exhausted = newRetryCount > TransferQueueEntry.MAX_RETRIES

                    conn.prepareStatement("""
                        UPDATE transfer_queue
                        SET status = 'FAILED',
                            error = ?,
                            retry_count = ?,
                            next_retry_at = ?,
                            pid = NULL
                        WHERE id = ? AND status = 'RUNNING'
                    """.trimIndent()).use { ps ->
                        val errorMsg = "Stale: $reason (watchdog recovery)" +
                            if (exhausted) " — max retries exhausted" else ""
                        ps.setString(1, errorMsg)
                        ps.setInt(2, newRetryCount)
                        ps.setString(3, if (exhausted) null else TransferQueueEntry.nextBackoff(newRetryCount).toString())
                        ps.setString(4, entry.id)
                        ps.executeUpdate()
                    }
                    logger.warn { "Watchdog: recovered stale transfer ${entry.id} ($reason, retry $newRetryCount/${TransferQueueEntry.MAX_RETRIES})" }
                    recovered++
                }
            }

            if (recovered > 0) {
                logger.info { "Watchdog: recovered $recovered stale RUNNING entries" }
                publishStatusSnapshot()
            }
            return recovered
        }
    }

    fun update(id: String, transform: (TransferQueueEntry) -> TransferQueueEntry) {
        getConnection().use { conn ->
            val entry = loadById(conn, id) ?: return
            val updated = transform(entry)
            // pid is DB-only; preserve whatever is already stored
            conn.prepareStatement("""
                UPDATE transfer_queue SET
                    source_path = ?, destination_machine = ?, destination_path = ?,
                    mode = ?, status = ?, priority = ?, bandwidth_limit_kbps = ?,
                    no_catalog = ?, created_at = ?, started_at = ?, completed_at = ?,
                    bytes_transferred = ?, total_bytes = ?, error = ?,
                    retry_count = ?, next_retry_at = ?, force_overwrite = ?, backup_before = ?
                WHERE id = ?
            """.trimIndent()).use { ps ->
                ps.setString(1, updated.sourcePath)
                ps.setString(2, updated.destinationMachine)
                ps.setString(3, updated.destinationPath)
                ps.setString(4, updated.mode.name)
                ps.setString(5, updated.status.name)
                ps.setInt(6, priorityToSortOrder(updated.priority))
                if (updated.bandwidthLimitKBps != null) ps.setInt(7, updated.bandwidthLimitKBps) else ps.setNull(7, java.sql.Types.INTEGER)
                ps.setInt(8, if (updated.noCatalog) 1 else 0)
                ps.setString(9, updated.createdAt)
                ps.setString(10, updated.startedAt)
                ps.setString(11, updated.completedAt)
                ps.setLong(12, updated.bytesTransferred)
                ps.setLong(13, updated.totalBytes)
                ps.setString(14, updated.error)
                ps.setInt(15, updated.retryCount)
                ps.setString(16, updated.nextRetryAt)
                ps.setInt(17, if (updated.force) 1 else 0)
                ps.setInt(18, if (updated.backup) 1 else 0)
                ps.setString(19, id)
                ps.executeUpdate()
            }
        }
        publishStatusSnapshot()
    }

    /**
     * Return a claimed entry to PENDING with bounded exponential backoff.
     *
     * A deferred entry must not remain immediately claimable. Otherwise the same
     * unreachable destination is reclaimed in a tight loop and starves every
     * later queue entry.
     */
    fun defer(id: String, reason: String): Boolean {
        val deferred = getConnection().use { conn ->
            val entry = loadById(conn, id) ?: return@use false
            if (entry.status != TransferStatus.RUNNING) return@use false
            val retryCount = (entry.retryCount + 1).coerceAtMost(TransferQueueEntry.MAX_RETRIES)
            val nextRetryAt = TransferQueueEntry.nextBackoff(retryCount).toString()
            conn.prepareStatement("""
                UPDATE transfer_queue
                SET status = 'PENDING',
                    started_at = NULL,
                    error = ?,
                    retry_count = ?,
                    next_retry_at = ?,
                    pid = NULL
                WHERE id = ? AND status = 'RUNNING'
            """.trimIndent()).use { ps ->
                ps.setString(1, "Deferred: $reason")
                ps.setInt(2, retryCount)
                ps.setString(3, nextRetryAt)
                ps.setString(4, id)
                ps.executeUpdate() == 1
            }
        }
        if (deferred) publishStatusSnapshot()
        return deferred
    }

    /** Publish the lightweight status contract consumed by menu-bar monitors. */
    fun publishStatusSnapshot() {
        try {
            writeQueueStatusSnapshot(loadAll(), dbPath.parent.resolve("queue-status.json"))
        } catch (e: Exception) {
            logger.warn { "Unable to publish queue status snapshot: ${e.message}" }
        }
    }

    fun cancel(id: String): Boolean {
        val cancelled = getConnection().use { conn ->
            conn.prepareStatement("""
                UPDATE transfer_queue SET status = 'CANCELLED'
                WHERE id = ? AND status IN ('PENDING', 'FAILED')
            """.trimIndent()).use { ps ->
                ps.setString(1, id)
                ps.executeUpdate() > 0
            }
        }
        if (cancelled) {
            // Queue data remains the legacy authority. Receipt detail is deliberately generic.
            receiptStore.observe(id, TransferReceiptState.CANCELLED)
            publishStatusSnapshot()
        }
        return cancelled
    }

    /**
     * Cancel a running transfer by killing its process and marking it cancelled.
     */
    fun cancelRunning(id: String): Boolean {
        var deferToNonRunningCancel = false
        val cancelled = getConnection().use { conn ->
            val entry = loadById(conn, id) ?: return@use false
            if (entry.status != TransferStatus.RUNNING) {
                deferToNonRunningCancel = true
                return@use false
            }

            // Kill the process if PID is tracked
            loadPid(conn, id)?.let { pid ->
                try {
                    ProcessHandle.of(pid).ifPresent { it.destroy() }
                    logger.info { "Killed process $pid for transfer $id" }
                } catch (e: Exception) {
                    logger.warn { "Failed to kill process $pid: ${e.message}" }
                }
            }

            conn.prepareStatement("""
                UPDATE transfer_queue SET status = 'CANCELLED', pid = NULL
                WHERE id = ? AND status = 'RUNNING'
            """.trimIndent()).use { ps ->
                ps.setString(1, id)
                ps.executeUpdate() > 0
            }
        }
        if (deferToNonRunningCancel) return cancel(id)
        if (cancelled) {
            receiptStore.observe(id, TransferReceiptState.CANCELLED)
            publishStatusSnapshot()
        }
        return cancelled
    }

    /**
     * Force an entry back to a clean PENDING state so the next processing cycle re-claims it.
     *
     * This is the manual escape hatch for a stuck RUNNING entry whose processor died without
     * releasing it (crash, kill, or a jar swapped out from under a live JVM) — the watchdog in
     * [recoverStaleRunning] handles that automatically on the next `queue --run`, but `--cancel`
     * refuses RUNNING entries and `claimNext()` skips them, so without this an operator has no
     * way to reclaim one immediately.
     *
     * If the entry is still owned by a LIVE process, that process is killed first to avoid two
     * processors racing on the same source. Clears started_at, completed_at, pid, error, retry
     * count, backoff, and the byte counter, and removes any stale progress sidecar.
     *
     * Returns false if the ID isn't found.
     */
    fun resetToPending(id: String): Boolean {
        val updated = getConnection().use { conn ->
            loadById(conn, id) ?: return@use false

            // If a live process still owns it, stop it before reclaiming to prevent a double-run.
            // Never kill the current process (the one invoking --reset).
            loadPid(conn, id)?.let { pid ->
                if (pid == ProcessHandle.current().pid()) return@let
                try {
                    ProcessHandle.of(pid).ifPresent {
                        it.destroy()
                        logger.info { "Killed live process $pid before resetting transfer $id" }
                    }
                } catch (e: Exception) {
                    logger.warn { "Failed to check/kill process $pid for $id: ${e.message}" }
                }
            }

            val changed = conn.prepareStatement("""
                UPDATE transfer_queue
                SET status = 'PENDING',
                    started_at = NULL,
                    completed_at = NULL,
                    pid = NULL,
                    error = NULL,
                    retry_count = 0,
                    next_retry_at = NULL,
                    bytes_transferred = 0
                WHERE id = ?
            """.trimIndent()).use { ps ->
                ps.setString(1, id)
                ps.executeUpdate() > 0
            }

            if (changed) {
                // Drop any stale progress sidecar so --status doesn't report old numbers.
                try { File(choamDir, "queue-progress-$id.json").delete() } catch (_: Exception) {}
                logger.info { "Reset transfer $id to PENDING" }
            }
            changed
        }
        if (updated) publishStatusSnapshot()
        return updated
    }

    fun clear(completedOnly: Boolean = true) {
        getConnection().use { conn ->
            if (completedOnly) {
                conn.createStatement().use { stmt ->
                    stmt.executeUpdate("DELETE FROM transfer_queue WHERE status IN ('COMPLETED', 'CANCELLED')")
                }
            } else {
                conn.createStatement().use { stmt ->
                    stmt.executeUpdate("DELETE FROM transfer_queue")
                }
            }
        }
        publishStatusSnapshot()
    }

    private fun loadById(conn: Connection, id: String): TransferQueueEntry? {
        return conn.prepareStatement("SELECT * FROM transfer_queue WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                if (rs.next()) rowToEntry(rs) else null
            }
        }
    }

    /** Read pid from the DB for a given entry ID. */
    private fun loadPid(conn: Connection, id: String): Long? {
        return conn.prepareStatement("SELECT pid FROM transfer_queue WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getLong("pid").let { if (rs.wasNull()) null else it } else null
            }
        }
    }

    /** Map priority to sort-friendly integer: HIGH=0, NORMAL=1, LOW=2 */
    private fun priorityToSortOrder(p: TransferPriority): Int = when (p) {
        TransferPriority.HIGH -> 0
        TransferPriority.NORMAL -> 1
        TransferPriority.LOW -> 2
    }

    private fun sortOrderToPriority(order: Int): TransferPriority = when (order) {
        0 -> TransferPriority.HIGH
        1 -> TransferPriority.NORMAL
        else -> TransferPriority.LOW
    }

    private fun rowToEntry(rs: ResultSet): TransferQueueEntry {
        return TransferQueueEntry(
            id = rs.getString("id"),
            sourcePath = rs.getString("source_path"),
            destinationMachine = rs.getString("destination_machine"),
            destinationPath = rs.getString("destination_path"),
            mode = TransferMode.valueOf(rs.getString("mode")),
            status = TransferStatus.valueOf(rs.getString("status")),
            priority = sortOrderToPriority(rs.getInt("priority")),
            bandwidthLimitKBps = rs.getInt("bandwidth_limit_kbps").let { if (rs.wasNull()) null else it },
            noCatalog = rs.getInt("no_catalog") == 1,
            createdAt = rs.getString("created_at"),
            startedAt = rs.getString("started_at"),
            completedAt = rs.getString("completed_at"),
            bytesTransferred = rs.getLong("bytes_transferred"),
            totalBytes = rs.getLong("total_bytes"),
            error = rs.getString("error"),
            retryCount = rs.getInt("retry_count"),
            nextRetryAt = rs.getString("next_retry_at"),
            force = rs.getInt("force_overwrite") == 1,
            backup = rs.getInt("backup_before") == 1
        )
    }
}

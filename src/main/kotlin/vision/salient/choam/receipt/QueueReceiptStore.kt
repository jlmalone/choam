package vision.salient.choam.receipt

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/**
 * Private durable storage for receipt observations associated with queue entries.
 *
 * This table deliberately is not part of the queue-status projection.  The receipt JSON,
 * recent observation IDs, and sequence watermark are one value and are replaced by a single
 * optimistic compare-and-swap statement, so a committed observation is durably idempotent
 * after a restart without receipt work blocking legacy queue writers.
 */
class QueueReceiptStore(
    private val dbPath: Path,
    private val freshRouteFingerprint: () -> String = { "route-" + UUID.randomUUID() },
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val initialized: Boolean

    init {
        // Receipt storage is deliberately optional.  It shares a database with the queue but
        // must never make queue construction or processing depend on SQLite/schema health.
        initialized = runCatching {
            Files.createDirectories(requireNotNull(dbPath.parent) { "queue database must have a parent" })
            connection().use { conn ->
                conn.createStatement().use { statement ->
                    statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS queue_transfer_receipts (" +
                            "queue_entry_id TEXT PRIMARY KEY, receipt_json TEXT NOT NULL, " +
                            "receipt_version INTEGER NOT NULL DEFAULT 0)"
                    )
                }
                ensureVersionColumn(conn)
            }
        }.isSuccess
    }

    sealed interface ReadResult {
        data object Missing : ReadResult
        data class Present(val receipt: TransferReceiptV1, internal val version: Long = -1) : ReadResult
        data object Malformed : ReadResult
        /** Sanitized operational state; never expose a JDBC, path, or schema error. */
        data object Unavailable : ReadResult
    }

    sealed interface MutationResult {
        data class Applied(val receipt: TransferReceiptV1) : MutationResult
        data class Ignored(val receipt: TransferReceiptV1, val reason: String) : MutationResult
        data class Rejected(val reason: String) : MutationResult
        /** Receipt-only failure.  Callers must leave the legacy queue outcome unchanged. */
        data object Unavailable : MutationResult
    }

    data class Expectations(val bytes: Long?, val files: Long?) {
        init { require(bytes != null || files != null); require(bytes == null || bytes >= 0); require(files == null || files >= 0) }
    }

    fun load(queueEntryId: String): ReadResult = safelyRead { conn -> read(conn, queueEntryId).public() }

    /** Reuse a valid non-terminal receipt's expectations so a retry does not re-walk its tree. */
    fun reusableExpectations(queueEntryId: String): Expectations? = when (val loaded = load(queueEntryId)) {
        is ReadResult.Present -> if (loaded.receipt.state !in setOf(TransferReceiptState.COMPLETED, TransferReceiptState.CANCELLED)) {
            Expectations(loaded.receipt.expectedBytes, loaded.receipt.expectedFiles)
        } else null
        else -> null
    }

    /**
     * Create the admission receipt once expectations are known.  A retry only reopens a
     * deferred/failed receipt and gets a fresh attempt ID and route identity; malformed or
     * terminal storage is rejected without replacement. Route generation belongs to receipts,
     * not the legacy queue retry counter.
     */
    fun admit(
        queueEntryId: String,
        expectedBytes: Long?,
        expectedFiles: Long?,
        now: Instant = Instant.now(),
    ): MutationResult = safelyMutate {
        when (val existing = readCurrent(queueEntryId)) {
            ReadResult.Missing -> {
                val route = nextRoute(null) ?: return@safelyMutate MutationResult.Rejected("ROUTE_GENERATION_EXHAUSTED")
                val receipt = TransferReceiptV1(
                    transferId = transferId(queueEntryId),
                    attemptId = attemptId(),
                    // The database key provides the private queue association. Do not copy an
                    // unconstrained legacy queue ID into the public receipt payload.
                    queueEntryId = null,
                    sourceAuthority = TransferAuthority("LOCAL"),
                    destinationAuthority = TransferAuthority("DESTINATION"),
                    route = route,
                    expectedBytes = expectedBytes,
                    expectedFiles = expectedFiles,
                    timestamps = TransferTimestamps(commandAcceptedAt = now),
                )
                applyAndWrite(queueEntryId, null, receipt, TransferReceiptObservation(
                    observationId = observationId(), transferId = receipt.transferId, attemptId = receipt.attemptId,
                    sequence = 1, observedAt = now, state = TransferReceiptState.QUEUE_ADMITTED,
                ))
            }
            is ReadResult.Present -> when (existing.receipt.state) {
                TransferReceiptState.DEFERRED, TransferReceiptState.FAILED -> {
                    val route = nextRoute(existing.receipt) ?: return@safelyMutate MutationResult.Rejected("ROUTE_GENERATION_EXHAUSTED")
                    val reopened = try {
                        TransferReceiptReducer.restart(existing.receipt, attemptId(), now, "RETRY_REOPENED", route)
                    } catch (_: IllegalArgumentException) {
                        return@safelyMutate MutationResult.Rejected("REOPEN_REJECTED")
                    }
                    writeIfUnchanged(queueEntryId, existing.version, reopened)
                }
                TransferReceiptState.COMPLETED, TransferReceiptState.CANCELLED -> MutationResult.Rejected("TERMINAL_RECEIPT")
                TransferReceiptState.ACTIVE, TransferReceiptState.VERIFYING_BYTES, TransferReceiptState.VERIFYING_FILES -> {
                    // The legacy queue has claimed this entry again after reset/watchdog recovery.
                    // Terminate the old attempt first; restart is then legal and keeps a durable
                    // summary without changing any legacy retry counter.
                    val deferred = applyAndWrite(queueEntryId, existing.version, existing.receipt, TransferReceiptObservation(
                        observationId = observationId(), transferId = existing.receipt.transferId,
                        attemptId = existing.receipt.attemptId,
                        sequence = existing.receipt.lastAppliedObservationSequence + 1,
                        observedAt = now, state = TransferReceiptState.DEFERRED,
                        failureCode = "STALE_ATTEMPT_RECONCILED",
                    ))
                    if (deferred !is MutationResult.Applied) deferred
                    // The reconciliation observation is durable before reopening.  Re-read and
                    // CAS the fresh attempt separately; neither operation holds a write lock
                    // while decoding or reducing JSON.
                    else admit(queueEntryId, expectedBytes, expectedFiles, now)
                }
                else -> MutationResult.Applied(existing.receipt)
            }
            ReadResult.Malformed -> MutationResult.Rejected("MALFORMED_STORED_RECEIPT")
            ReadResult.Unavailable -> MutationResult.Unavailable
        }
    }

    fun observe(
        queueEntryId: String,
        state: TransferReceiptState,
        failureCode: String? = null,
        processExitCode: Int? = null,
        now: Instant = Instant.now(),
    ): MutationResult = safelyMutate {
        when (val existing = readCurrent(queueEntryId)) {
            ReadResult.Missing -> MutationResult.Rejected("MISSING_RECEIPT")
            ReadResult.Malformed -> MutationResult.Rejected("MALFORMED_STORED_RECEIPT")
            ReadResult.Unavailable -> MutationResult.Unavailable
            is ReadResult.Present -> {
                val observation = try {
                    TransferReceiptObservation(
                        observationId = observationId(), transferId = existing.receipt.transferId, attemptId = existing.receipt.attemptId,
                        sequence = existing.receipt.lastAppliedObservationSequence + 1, observedAt = now, state = state,
                        failureCode = failureCode, processExitCode = processExitCode,
                    )
                } catch (_: IllegalArgumentException) {
                    return@safelyMutate MutationResult.Rejected("INVALID_OBSERVATION")
                }
                applyAndWrite(queueEntryId, existing.version, existing.receipt, observation)
            }
        }
    }

    /** Apply a caller-supplied observation without weakening reducer duplicate/sequence checks. */
    fun apply(queueEntryId: String, observation: TransferReceiptObservation): MutationResult = safelyMutate {
        when (val existing = readCurrent(queueEntryId)) {
            ReadResult.Missing -> MutationResult.Rejected("MISSING_RECEIPT")
            ReadResult.Malformed -> MutationResult.Rejected("MALFORMED_STORED_RECEIPT")
            ReadResult.Unavailable -> MutationResult.Unavailable
            is ReadResult.Present -> applyAndWrite(queueEntryId, existing.version, existing.receipt, observation)
        }
    }

    private fun applyAndWrite(
        queueEntryId: String,
        version: Long?,
        receipt: TransferReceiptV1,
        observation: TransferReceiptObservation,
    ): MutationResult {
        return when (val applied = TransferReceiptReducer.apply(receipt, observation)) {
            is ReceiptApplyResult.Applied -> {
                writeIfUnchanged(queueEntryId, version, applied.receipt)
            }
            is ReceiptApplyResult.Ignored -> MutationResult.Ignored(applied.receipt, applied.reason)
            is ReceiptApplyResult.Rejected -> MutationResult.Rejected(applied.reason)
        }
    }

    private data class StoredReceipt(val receipt: TransferReceiptV1, val version: Long)
    private sealed interface StoredRead {
        data object Missing : StoredRead
        data class Present(val value: StoredReceipt) : StoredRead
        data object Malformed : StoredRead
        fun public(): ReadResult = when (this) {
            Missing -> ReadResult.Missing
            is Present -> ReadResult.Present(value.receipt, value.version)
            Malformed -> ReadResult.Malformed
        }
    }

    private fun readCurrent(queueEntryId: String): ReadResult = connection().use { conn ->
        when (val stored = read(conn, queueEntryId)) {
            StoredRead.Missing -> ReadResult.Missing
            StoredRead.Malformed -> ReadResult.Malformed
            is StoredRead.Present -> ReadResult.Present(stored.value.receipt, stored.value.version)
        }
    }

    private fun read(conn: Connection, queueEntryId: String): StoredRead = conn.prepareStatement(
        "SELECT receipt_json, receipt_version FROM queue_transfer_receipts WHERE queue_entry_id = ?"
    ).use { statement ->
        statement.setString(1, queueEntryId)
        statement.executeQuery().use rowsUse@ { rows ->
            if (!rows.next()) return@rowsUse StoredRead.Missing
            try {
                StoredRead.Present(StoredReceipt(json.decodeFromString(TransferReceiptV1.serializer(), rows.getString(1)), rows.getLong(2)))
            } catch (_: Exception) {
                StoredRead.Malformed
            }
        }
    }

    /** Test-only race seam; production leaves this null. */
    internal var beforeCompareAndSwapForTest: (() -> Unit)? = null

    private fun writeIfUnchanged(queueEntryId: String, version: Long?, receipt: TransferReceiptV1): MutationResult {
        beforeCompareAndSwapForTest?.invoke()
        val changed = connection().use { conn -> conn.prepareStatement(
            if (version == null)
                "INSERT INTO queue_transfer_receipts(queue_entry_id, receipt_json, receipt_version) VALUES (?, ?, 0)"
            else
                "UPDATE queue_transfer_receipts SET receipt_json = ?, receipt_version = receipt_version + 1 " +
                    "WHERE queue_entry_id = ? AND receipt_version = ?"
        ).use { statement ->
            if (version == null) {
                statement.setString(1, queueEntryId)
                statement.setString(2, json.encodeToString(TransferReceiptV1.serializer(), receipt))
            } else {
                statement.setString(1, json.encodeToString(TransferReceiptV1.serializer(), receipt))
                statement.setString(2, queueEntryId)
                statement.setLong(3, version)
            }
            statement.executeUpdate() == 1
        } }
        return if (changed) MutationResult.Applied(receipt) else MutationResult.Unavailable
    }

    private fun ensureVersionColumn(conn: Connection) {
        val hasVersion = conn.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(queue_transfer_receipts)").use { rows ->
                generateSequence { if (rows.next()) rows.getString("name") else null }.any { it == "receipt_version" }
            }
        }
        if (!hasVersion) conn.createStatement().use { it.executeUpdate("ALTER TABLE queue_transfer_receipts ADD COLUMN receipt_version INTEGER NOT NULL DEFAULT 0") }
    }

    private fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath").also { conn ->
        conn.createStatement().use { statement ->
            // Do not wait behind a queue writer: receipt observation is best effort only.
            statement.executeUpdate("PRAGMA busy_timeout=0")
            statement.executeUpdate("PRAGMA synchronous=NORMAL")
        }
    }

    private fun safelyRead(block: (Connection) -> ReadResult): ReadResult {
        if (!initialized) return ReadResult.Unavailable
        return runCatching { connection().use(block) }.getOrElse { ReadResult.Unavailable }
    }

    private fun safelyMutate(block: () -> MutationResult): MutationResult {
        if (!initialized) return MutationResult.Unavailable
        return runCatching(block).getOrElse { MutationResult.Unavailable }
    }

    private fun nextRoute(previous: TransferReceiptV1?): TransferRoute? =
        nextReceiptRoute(previous, freshRouteFingerprint())

    private fun attemptId() = "attempt-" + UUID.randomUUID().toString()
    private fun observationId() = "observation-" + UUID.randomUUID().toString()
    private fun transferId(queueEntryId: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(queueEntryId.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "transfer-${digest.take(48)}"
    }
}

/** Allocates a route from receipt state alone, using already-opaque random fingerprint material. */
internal fun nextReceiptRoute(previous: TransferReceiptV1?, freshFingerprint: String): TransferRoute? {
    val previousGeneration = previous?.let { receipt ->
        (listOf(receipt.route) + receipt.priorAttempts.map { it.route }).maxOf { it.generation }
    } ?: return TransferRoute(0, freshFingerprint)
    val nextGeneration = try {
        Math.addExact(previousGeneration, 1L)
    } catch (_: ArithmeticException) {
        return null
    }
    return TransferRoute(nextGeneration, freshFingerprint)
}

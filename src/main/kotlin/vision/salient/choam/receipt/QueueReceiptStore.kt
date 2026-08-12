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
 * recent observation IDs, and sequence watermark are one value and are replaced in the same
 * SQLite transaction, so a committed observation is durably idempotent after a restart.
 */
class QueueReceiptStore(private val dbPath: Path) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    init {
        Files.createDirectories(requireNotNull(dbPath.parent) { "queue database must have a parent" })
        connection().use { conn ->
            conn.createStatement().use { statement ->
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS queue_transfer_receipts (" +
                        "queue_entry_id TEXT PRIMARY KEY, receipt_json TEXT NOT NULL)"
                )
            }
        }
    }

    sealed interface ReadResult {
        data object Missing : ReadResult
        data class Present(val receipt: TransferReceiptV1) : ReadResult
        data object Malformed : ReadResult
    }

    sealed interface MutationResult {
        data class Applied(val receipt: TransferReceiptV1) : MutationResult
        data class Ignored(val receipt: TransferReceiptV1, val reason: String) : MutationResult
        data class Rejected(val reason: String) : MutationResult
    }

    fun load(queueEntryId: String): ReadResult = connection().use { conn ->
        read(conn, queueEntryId)
    }

    /**
     * Create the admission receipt once expectations are known.  A retry only reopens a
     * deferred/failed receipt and gets a fresh attempt ID; malformed or terminal storage is
     * rejected without replacement.
     */
    fun admit(
        queueEntryId: String,
        expectedBytes: Long?,
        expectedFiles: Long?,
        route: TransferRoute,
        now: Instant = Instant.now(),
    ): MutationResult = transaction { conn ->
        when (val existing = read(conn, queueEntryId)) {
            ReadResult.Missing -> {
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
                applyAndWrite(conn, queueEntryId, receipt, TransferReceiptObservation(
                    observationId = observationId(), transferId = receipt.transferId, attemptId = receipt.attemptId,
                    sequence = 1, observedAt = now, state = TransferReceiptState.QUEUE_ADMITTED,
                ))
            }
            is ReadResult.Present -> when (existing.receipt.state) {
                TransferReceiptState.DEFERRED, TransferReceiptState.FAILED -> {
                    val reopened = try {
                        TransferReceiptReducer.restart(existing.receipt, attemptId(), now, "RETRY_REOPENED", route)
                    } catch (_: IllegalArgumentException) {
                        return@transaction MutationResult.Rejected("REOPEN_REJECTED")
                    }
                    write(conn, queueEntryId, reopened)
                    MutationResult.Applied(reopened)
                }
                TransferReceiptState.COMPLETED, TransferReceiptState.CANCELLED -> MutationResult.Rejected("TERMINAL_RECEIPT")
                else -> MutationResult.Applied(existing.receipt)
            }
            ReadResult.Malformed -> MutationResult.Rejected("MALFORMED_STORED_RECEIPT")
        }
    }

    fun observe(
        queueEntryId: String,
        state: TransferReceiptState,
        failureCode: String? = null,
        processExitCode: Int? = null,
        now: Instant = Instant.now(),
    ): MutationResult = transaction { conn ->
        when (val existing = read(conn, queueEntryId)) {
            ReadResult.Missing -> MutationResult.Rejected("MISSING_RECEIPT")
            ReadResult.Malformed -> MutationResult.Rejected("MALFORMED_STORED_RECEIPT")
            is ReadResult.Present -> {
                val observation = try {
                    TransferReceiptObservation(
                        observationId = observationId(), transferId = existing.receipt.transferId, attemptId = existing.receipt.attemptId,
                        sequence = existing.receipt.lastAppliedObservationSequence + 1, observedAt = now, state = state,
                        failureCode = failureCode, processExitCode = processExitCode,
                    )
                } catch (_: IllegalArgumentException) {
                    return@transaction MutationResult.Rejected("INVALID_OBSERVATION")
                }
                applyAndWrite(conn, queueEntryId, existing.receipt, observation)
            }
        }
    }

    /** Apply a caller-supplied observation without weakening reducer duplicate/sequence checks. */
    fun apply(queueEntryId: String, observation: TransferReceiptObservation): MutationResult = transaction { conn ->
        when (val existing = read(conn, queueEntryId)) {
            ReadResult.Missing -> MutationResult.Rejected("MISSING_RECEIPT")
            ReadResult.Malformed -> MutationResult.Rejected("MALFORMED_STORED_RECEIPT")
            is ReadResult.Present -> applyAndWrite(conn, queueEntryId, existing.receipt, observation)
        }
    }

    private fun applyAndWrite(
        conn: Connection,
        queueEntryId: String,
        receipt: TransferReceiptV1,
        observation: TransferReceiptObservation,
    ): MutationResult {
        return when (val applied = TransferReceiptReducer.apply(receipt, observation)) {
            is ReceiptApplyResult.Applied -> {
                write(conn, queueEntryId, applied.receipt)
                MutationResult.Applied(applied.receipt)
            }
            is ReceiptApplyResult.Ignored -> MutationResult.Ignored(applied.receipt, applied.reason)
            is ReceiptApplyResult.Rejected -> MutationResult.Rejected(applied.reason)
        }
    }

    private fun read(conn: Connection, queueEntryId: String): ReadResult = conn.prepareStatement(
        "SELECT receipt_json FROM queue_transfer_receipts WHERE queue_entry_id = ?"
    ).use { statement ->
        statement.setString(1, queueEntryId)
        statement.executeQuery().use rowsUse@ { rows ->
            if (!rows.next()) return@rowsUse ReadResult.Missing
            try {
                ReadResult.Present(json.decodeFromString(TransferReceiptV1.serializer(), rows.getString(1)))
            } catch (_: Exception) {
                ReadResult.Malformed
            }
        }
    }

    private fun write(conn: Connection, queueEntryId: String, receipt: TransferReceiptV1) {
        conn.prepareStatement(
            "INSERT INTO queue_transfer_receipts(queue_entry_id, receipt_json) VALUES (?, ?) " +
                "ON CONFLICT(queue_entry_id) DO UPDATE SET receipt_json = excluded.receipt_json"
        ).use { statement ->
            statement.setString(1, queueEntryId)
            statement.setString(2, json.encodeToString(TransferReceiptV1.serializer(), receipt))
            check(statement.executeUpdate() == 1) { "receipt write was not applied" }
        }
    }

    private fun <T> transaction(block: (Connection) -> T): T = connection().use { conn ->
        conn.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        try {
            block(conn).also { conn.createStatement().use { statement -> statement.execute("COMMIT") } }
        } catch (error: Exception) {
            runCatching { conn.createStatement().use { statement -> statement.execute("ROLLBACK") } }
            throw error
        }
    }

    private fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath").also { conn ->
        conn.createStatement().use { statement ->
            statement.executeUpdate("PRAGMA busy_timeout=5000")
            statement.executeUpdate("PRAGMA synchronous=NORMAL")
        }
    }

    private fun attemptId() = "attempt-" + UUID.randomUUID().toString()
    private fun observationId() = "observation-" + UUID.randomUUID().toString()
    private fun transferId(queueEntryId: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(queueEntryId.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "transfer-${digest.take(48)}"
    }
}

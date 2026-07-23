package vision.salient.choam.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Serializable
data class TransferQueueEntry(
    val id: String = UUID.randomUUID().toString().substring(0, 8),
    val sourcePath: String,
    val destinationMachine: String,
    val destinationPath: String,
    val mode: TransferMode = TransferMode.COPY,
    val status: TransferStatus = TransferStatus.PENDING,
    val priority: TransferPriority = TransferPriority.NORMAL,
    val bandwidthLimitKBps: Int? = null,
    val noCatalog: Boolean = false,
    val createdAt: String = Instant.now().toString(),
    val startedAt: String? = null,
    val completedAt: String? = null,
    val bytesTransferred: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
    val retryCount: Int = 0,
    val nextRetryAt: String? = null, // ISO instant — don't attempt before this time
    val force: Boolean = false,      // Allow overwriting conflicting files
    val backup: Boolean = false      // Rename remote conflicts to .backup_TIMESTAMP before overwriting
) {
    companion object {
        private const val BASE_BACKOFF_MS = 30_000L     // 30 seconds
        private const val MAX_BACKOFF_MS = 30 * 60_000L // 30 minutes
        const val MAX_RETRIES = 5                        // Give up after 5 failed attempts
        const val STALE_RUNNING_HOURS = 2L               // RUNNING entries older than this are considered stuck

        /** Calculate next retry time using exponential backoff with jitter. */
        fun nextBackoff(retryCount: Int): Instant {
            val delayMs = (BASE_BACKOFF_MS * (1L shl retryCount.coerceAtMost(10)))
                .coerceAtMost(MAX_BACKOFF_MS)
            val jitterMs = (Math.random() * delayMs * 0.2).toLong() // ±20% jitter
            return Instant.now().plusMillis(delayMs + jitterMs)
        }
    }
}

@Serializable
enum class TransferMode { COPY, MOVE }

@Serializable
enum class TransferStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }

@Serializable
enum class TransferPriority { LOW, NORMAL, HIGH }

class TransferQueue(
    private val queuePath: Path = Paths.get(
        System.getProperty("user.home"), ".choam", "transfer_queue.json"
    )
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun add(entry: TransferQueueEntry): TransferQueueEntry {
        val entries = loadAll().toMutableList()
        entries.add(entry)
        save(entries)
        logger.info { "Queued transfer ${entry.id}: ${entry.sourcePath} → ${entry.destinationMachine}:${entry.destinationPath}" }
        return entry
    }

    fun loadAll(): List<TransferQueueEntry> {
        if (!Files.exists(queuePath)) return emptyList()
        return try {
            val content = Files.readString(queuePath)
            if (content.isBlank()) return emptyList()
            val entries = json.decodeFromString(ListSerializer(TransferQueueEntry.serializer()), content)

            // Auto-expire completed/cancelled entries older than 24 hours
            val now = Instant.now()
            val (expired, kept) = entries.partition { entry ->
                (entry.status == TransferStatus.COMPLETED || entry.status == TransferStatus.CANCELLED) &&
                    entry.let {
                        val timestamp = it.completedAt ?: it.createdAt
                        try {
                            val ts = Instant.parse(timestamp)
                            java.time.Duration.between(ts, now).toHours() >= 24
                        } catch (_: Exception) { false }
                    }
            }
            if (expired.isNotEmpty()) {
                logger.info { "Auto-expired ${expired.size} queue entries older than 24h" }
                save(kept)
            }
            kept
        } catch (e: Exception) {
            logger.error(e) { "Failed to load transfer queue: ${e.message}" }
            emptyList()
        }
    }

    fun pending(): List<TransferQueueEntry> {
        val now = Instant.now()
        return loadAll()
            .filter { it.status == TransferStatus.PENDING || it.status == TransferStatus.FAILED }
            .filter { entry ->
                // Enforce max retries — don't return entries that have exhausted retries
                entry.retryCount <= TransferQueueEntry.MAX_RETRIES
            }
            .filter { entry ->
                // Respect exponential backoff — skip entries whose retry time hasn't arrived
                val nextRetry = entry.nextRetryAt?.let {
                    try { Instant.parse(it) } catch (_: Exception) { null }
                }
                nextRetry == null || now.isAfter(nextRetry)
            }
            .sortedBy {
                when (it.priority) {
                    TransferPriority.HIGH -> 0
                    TransferPriority.NORMAL -> 1
                    TransferPriority.LOW -> 2
                }
            }
    }

    /**
     * Recover stale RUNNING entries — if a transfer has been RUNNING for longer than
     * STALE_RUNNING_HOURS, the rsync process is presumed dead (CHOAM process was killed,
     * SSH dropped, etc.). Transition to FAILED so the retry mechanism can handle it.
     *
     * Called on every queue processing cycle to prevent zombie entries.
     */
    fun recoverStaleRunning(): Int {
        val now = Instant.now()
        val entries = loadAll().toMutableList()
        var recovered = 0

        for (i in entries.indices) {
            val entry = entries[i]
            if (entry.status != TransferStatus.RUNNING) continue

            val startedAt = entry.startedAt?.let {
                try { Instant.parse(it) } catch (_: Exception) { null }
            } ?: continue

            val hoursRunning = java.time.Duration.between(startedAt, now).toHours()
            if (hoursRunning >= TransferQueueEntry.STALE_RUNNING_HOURS) {
                val newRetryCount = entry.retryCount + 1
                val exhausted = newRetryCount > TransferQueueEntry.MAX_RETRIES
                entries[i] = entry.copy(
                    status = TransferStatus.FAILED,
                    error = "Stale: stuck RUNNING for ${hoursRunning}h (watchdog recovery)" +
                        if (exhausted) " — max retries exhausted, giving up" else "",
                    retryCount = newRetryCount,
                    nextRetryAt = if (exhausted) null else TransferQueueEntry.nextBackoff(newRetryCount).toString()
                )
                logger.warn { "Watchdog: recovered stale transfer ${entry.id} (RUNNING for ${hoursRunning}h, retry $newRetryCount/${TransferQueueEntry.MAX_RETRIES})" }
                recovered++
            }
        }

        if (recovered > 0) {
            save(entries)
            logger.info { "Watchdog: recovered $recovered stale RUNNING entries" }
        }
        return recovered
    }

    fun update(id: String, transform: (TransferQueueEntry) -> TransferQueueEntry) {
        val entries = loadAll().toMutableList()
        val idx = entries.indexOfFirst { it.id == id }
        if (idx >= 0) {
            entries[idx] = transform(entries[idx])
            save(entries)
        }
    }

    fun cancel(id: String): Boolean {
        var found = false
        val entries = loadAll().map {
            if (it.id == id && (it.status == TransferStatus.PENDING || it.status == TransferStatus.FAILED)) {
                found = true
                it.copy(status = TransferStatus.CANCELLED)
            } else it
        }
        if (found) save(entries)
        return found
    }

    fun clear(completedOnly: Boolean = true) {
        val entries = if (completedOnly) {
            loadAll().filter { it.status != TransferStatus.COMPLETED && it.status != TransferStatus.CANCELLED }
        } else {
            emptyList()
        }
        save(entries)
    }

    private fun save(entries: List<TransferQueueEntry>) {
        Files.createDirectories(queuePath.parent)
        Files.writeString(queuePath, json.encodeToString(ListSerializer(TransferQueueEntry.serializer()), entries))
    }
}

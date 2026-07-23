package vision.salient.choam.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import vision.salient.choam.sync.TransferQueueEntry
import vision.salient.choam.sync.TransferStatus
import java.io.File

/**
 * Machine-readable rendering of the CHOAM transfer queue (`choam queue --status --json`).
 *
 * Emits RAW counters only — consumers compute % and ETA themselves. The human
 * `queue --status` view has a known %/denominator display quirk; this path
 * deliberately does NOT reuse that math, it just reports the underlying bytes.
 * For RUNNING entries the live byte/file/rate figures come from the per-transfer
 * sidecar (`~/.choam/queue-progress-<id>.json`); for every other status they fall
 * back to the persisted entry counters.
 */

/** Live progress merged from the per-transfer sidecar file written during a run. */
data class QueueLiveProgress(
    val bytesTransferred: Long,
    val bytesTotal: Long,
    val filesDone: Int,
    val filesTotal: Int,
    val rateBytesPerSec: Long,
    val currentFile: String,
)

@Serializable
data class QueueJsonItem(
    val id: String,
    val source: String,
    val dest: String,
    val status: String,
    val mode: String,
    val bytesTransferred: Long,
    val bytesTotal: Long,
    val filesDone: Int,
    val filesTotal: Int,
    val rateBytesPerSec: Long,
    val currentFile: String,
)

@Serializable
data class QueueJsonSummary(val running: Int, val pending: Int, val failed: Int)

@Serializable
data class QueueJsonReport(val queue: List<QueueJsonItem>, val summary: QueueJsonSummary)

private val queueJson = Json { prettyPrint = true; encodeDefaults = true }

/**
 * Build the report from queue entries. Pure + dependency-injected ([live]) so it
 * is unit-testable without touching the filesystem.
 */
fun buildQueueReport(
    entries: List<TransferQueueEntry>,
    live: (String) -> QueueLiveProgress?,
): QueueJsonReport {
    val items = entries.map { e ->
        val lp = if (e.status == TransferStatus.RUNNING) live(e.id) else null
        QueueJsonItem(
            id = e.id,
            source = e.sourcePath,
            dest = "${e.destinationMachine}:${e.destinationPath}",
            status = e.status.name.lowercase(),
            mode = e.mode.name.lowercase(),
            bytesTransferred = lp?.bytesTransferred ?: e.bytesTransferred,
            bytesTotal = lp?.bytesTotal ?: e.totalBytes,
            filesDone = lp?.filesDone ?: 0,
            filesTotal = lp?.filesTotal ?: 0,
            rateBytesPerSec = lp?.rateBytesPerSec ?: 0L,
            currentFile = lp?.currentFile ?: "",
        )
    }
    return QueueJsonReport(
        queue = items,
        summary = QueueJsonSummary(
            running = entries.count { it.status == TransferStatus.RUNNING },
            pending = entries.count { it.status == TransferStatus.PENDING },
            failed = entries.count { it.status == TransferStatus.FAILED },
        ),
    )
}

/** Serialize the report to JSON. Defaults to reading live progress from sidecar files. */
fun renderQueueReportJson(
    entries: List<TransferQueueEntry>,
    live: (String) -> QueueLiveProgress? = ::readSidecarProgress,
): String = queueJson.encodeToString(QueueJsonReport.serializer(), buildQueueReport(entries, live))

/** Read live progress from a RUNNING transfer's sidecar file, if present. */
fun readSidecarProgress(id: String): QueueLiveProgress? {
    val f = File(System.getProperty("user.home"), ".choam/queue-progress-$id.json")
    if (!f.exists()) return null
    return try {
        val j = f.readText()
        fun longField(key: String) = Regex("\"$key\":(\\d+)").find(j)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        fun intField(key: String) = Regex("\"$key\":(\\d+)").find(j)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        QueueLiveProgress(
            bytesTransferred = longField("overallBytesTransferred"),
            bytesTotal = longField("overallTotalBytes"),
            filesDone = intField("filesCompleted"),
            filesTotal = intField("totalFiles"),
            rateBytesPerSec = longField("speedBytesPerSec"),
            currentFile = Regex("\"fileName\":\"([^\"]*)\"").find(j)?.groupValues?.get(1) ?: "",
        )
    } catch (_: Exception) {
        null
    }
}

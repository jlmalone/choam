package vision.salient.choam.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Instant

@Serializable
data class QueueStatusItem(
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
data class QueueStatusSummary(val running: Int, val pending: Int, val failed: Int)

@Serializable
data class QueueStatusSnapshot(
    val schema: Int = 1,
    val generatedAt: String = Instant.now().toString(),
    val queue: List<QueueStatusItem>,
    val summary: QueueStatusSummary,
)

private val snapshotJson = Json { prettyPrint = true; encodeDefaults = true }

fun buildQueueStatusSnapshot(
    entries: List<TransferQueueEntry>,
    live: (String) -> QueueLiveProgress? = ::readQueueLiveProgress,
): QueueStatusSnapshot {
    val items = entries.map { entry ->
        val progress = if (entry.status == TransferStatus.RUNNING) live(entry.id) else null
        QueueStatusItem(
            id = entry.id,
            source = entry.sourcePath,
            dest = "${entry.destinationMachine}:${entry.destinationPath}",
            status = entry.status.name.lowercase(),
            mode = entry.mode.name.lowercase(),
            bytesTransferred = progress?.bytesTransferred ?: entry.bytesTransferred,
            bytesTotal = progress?.bytesTotal ?: entry.totalBytes,
            filesDone = progress?.filesDone ?: 0,
            filesTotal = progress?.filesTotal ?: 0,
            rateBytesPerSec = progress?.rateBytesPerSec ?: 0,
            currentFile = progress?.currentFile ?: "",
        )
    }
    return QueueStatusSnapshot(
        queue = items,
        summary = QueueStatusSummary(
            running = entries.count { it.status == TransferStatus.RUNNING },
            pending = entries.count { it.status == TransferStatus.PENDING },
            failed = entries.count { it.status == TransferStatus.FAILED },
        ),
    )
}

fun writeQueueStatusSnapshot(
    entries: List<TransferQueueEntry>,
    destination: Path = Paths.get(System.getProperty("user.home"), ".choam", "queue-status.json"),
) {
    Files.createDirectories(destination.parent)
    val temp = destination.resolveSibling("${destination.fileName}.tmp.${ProcessHandle.current().pid()}.${Thread.currentThread().threadId()}")
    Files.writeString(temp, snapshotJson.encodeToString(QueueStatusSnapshot.serializer(), buildQueueStatusSnapshot(entries)))
    try {
        Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING)
    }
}

data class QueueLiveProgress(
    val bytesTransferred: Long,
    val bytesTotal: Long,
    val filesDone: Int,
    val filesTotal: Int,
    val rateBytesPerSec: Long,
    val currentFile: String,
)

fun readQueueLiveProgress(id: String): QueueLiveProgress? {
    val path = Paths.get(System.getProperty("user.home"), ".choam", "queue-progress-$id.json")
    if (!Files.exists(path)) return null
    return try {
        val json = Files.readString(path)
        fun longField(key: String) = Regex("\"$key\":(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        fun intField(key: String) = Regex("\"$key\":(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        QueueLiveProgress(
            bytesTransferred = longField("overallBytesTransferred"),
            bytesTotal = longField("overallTotalBytes"),
            filesDone = intField("filesCompleted"),
            filesTotal = intField("totalFiles"),
            rateBytesPerSec = longField("speedBytesPerSec"),
            currentFile = Regex("\"fileName\":\"([^\"]*)\"").find(json)?.groupValues?.get(1) ?: "",
        )
    } catch (_: Exception) {
        null
    }
}

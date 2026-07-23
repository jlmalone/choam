package vision.salient.choam.network

import java.time.Duration
import java.time.Instant
import vision.salient.choam.sync.SyncSession
import vision.salient.choam.sync.SyncStatus

class ProgressMonitor {

    private val isTty: Boolean = System.console() != null
    private var lastUpdateTime: Long = 0
    private val updateIntervalMs: Long = 200 // Don't update faster than 5 times/sec

    fun displayProgress(session: SyncSession, progress: TransferProgress) {
        val now = System.currentTimeMillis()
        if (now - lastUpdateTime < updateIntervalMs) return
        lastUpdateTime = now

        val percent = if (progress.totalBytes > 0) {
            (progress.bytesTransferred * 100 / progress.totalBytes).coerceIn(0, 100)
        } else {
            0
        }

        val bar = buildProgressBar(percent.toInt(), 30)
        val speed = formatSpeed(progress.speedBytesPerSec)
        val eta = progress.eta?.let { formatDuration(it) } ?: "---"
        val transferred = formatBytes(progress.bytesTransferred)
        val total = formatBytes(progress.totalBytes)
        val fileName = progress.fileName.substringAfterLast('/')

        val line = "$bar $percent%  $transferred/$total  $speed  ETA $eta  $fileName"

        if (isTty) {
            // Overwrite line in-place
            print("\r\u001b[K$line")
            System.out.flush()
        } else {
            println(line)
        }
    }

    fun displaySummary(session: SyncSession) {
        if (isTty) {
            // Clear the progress line
            print("\r\u001b[K")
        }

        val stats = session.statistics
        val duration = if (session.endTime != null) {
            Duration.between(session.startTime, session.endTime)
        } else {
            Duration.between(session.startTime, Instant.now())
        }

        val statusSymbol = when (session.status) {
            SyncStatus.COMPLETED -> "\u001b[32m✓\u001b[0m" // green
            SyncStatus.FAILED -> "\u001b[31m✗\u001b[0m" // red
            SyncStatus.CANCELLED -> "\u001b[33m⊘\u001b[0m" // yellow
            else -> "•"
        }

        val statusText = when (session.status) {
            SyncStatus.COMPLETED -> "\u001b[32mComplete\u001b[0m"
            SyncStatus.FAILED -> "\u001b[31mFailed\u001b[0m"
            SyncStatus.CANCELLED -> "\u001b[33mCancelled\u001b[0m"
            else -> session.status.name.lowercase()
        }

        println("$statusSymbol $statusText: ${formatBytes(stats.bytesTransferred)} transferred, " +
            "${stats.filesTransferred} files, ${formatDuration(duration)}")

        if (stats.errors > 0) {
            println("  \u001b[31m${stats.errors} error(s)\u001b[0m")
        }
        if (stats.conflicts > 0) {
            println("  \u001b[33m${stats.conflicts} conflict(s)\u001b[0m")
        }
        if (stats.filesSkipped > 0) {
            println("  ${stats.filesSkipped} skipped")
        }
    }

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes >= 1_099_511_627_776L -> "%.1f TB".format(bytes / 1_099_511_627_776.0)
            bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }

        fun formatSpeed(bytesPerSec: Long): String {
            if (bytesPerSec <= 0) return "0 B/s"
            return "${formatBytes(bytesPerSec)}/s"
        }

        fun formatDuration(duration: Duration): String {
            val totalSeconds = duration.seconds
            return when {
                totalSeconds < 60 -> "${totalSeconds}s"
                totalSeconds < 3600 -> "${totalSeconds / 60}m ${totalSeconds % 60}s"
                else -> "${totalSeconds / 3600}h ${(totalSeconds % 3600) / 60}m"
            }
        }

        fun buildProgressBar(percent: Int, width: Int): String {
            val filled = (percent * width / 100).coerceIn(0, width)
            val empty = width - filled
            return "[" + "█".repeat(filled) + "░".repeat(empty) + "]"
        }
    }
}

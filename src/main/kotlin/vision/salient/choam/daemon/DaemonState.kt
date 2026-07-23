package vision.salient.choam.daemon

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}
private val json = Json { prettyPrint = false; encodeDefaults = true }

/**
 * Daemon process state management — PID file, uptime tracking, activity log.
 */
object DaemonState {

    private val choamDir = File(System.getProperty("user.home"), ".choam")
    private val pidFile = File(choamDir, "daemon.pid")
    private val activityFile = File(choamDir, "daemon_activity.jsonl")
    private val healthFile = File(choamDir, "daemon-health.json")
    private val logsDir = File(choamDir, "logs")

    var startTime: Instant = Instant.now()
        private set
    var paused: Boolean = false
    var activeTransferId: String? = null
    var activeTransferName: String? = null
    var lastQueueRun: String? = null
    var lastQueueResult: String? = null
    var lastFailure: String? = null

    fun writePidFile() {
        choamDir.mkdirs()
        val pid = ProcessHandle.current().pid()
        pidFile.writeText("$pid")
        startTime = Instant.now()
        logger.info { "Daemon PID $pid written to ${pidFile.absolutePath}" }
    }

    fun readPidFile(): Long? {
        if (!pidFile.exists()) return null
        return try {
            pidFile.readText().trim().toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }

    fun isRunning(): Boolean {
        val pid = readPidFile() ?: return false
        return try {
            ProcessHandle.of(pid).isPresent
        } catch (_: Exception) {
            false
        }
    }

    fun removePidFile() {
        pidFile.delete()
    }

    fun getUptime(): String {
        val seconds = Instant.now().epochSecond - startTime.epochSecond
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            seconds < 86400 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
            else -> "${seconds / 86400}d ${(seconds % 86400) / 3600}h"
        }
    }

    fun getLogsDir(): File {
        logsDir.mkdirs()
        return logsDir
    }

    // --- Health File (written periodically, read by status/web) ---

    fun writeHealth() {
        val health = DaemonHealth(
            pid = ProcessHandle.current().pid(),
            startedAt = startTime.toString(),
            lastHeartbeat = Instant.now().toString(),
            state = if (activeTransferId != null) "transferring" else if (paused) "paused" else "idle",
            activeTransferId = activeTransferId,
            activeTransferName = activeTransferName,
            lastQueueRun = lastQueueRun,
            lastQueueResult = lastQueueResult,
            lastFailure = lastFailure,
            paused = paused
        )
        try {
            choamDir.mkdirs()
            val tmp = File(choamDir, "daemon-health.json.tmp")
            tmp.writeText(json.encodeToString(health))
            tmp.renameTo(healthFile)
        } catch (e: Exception) {
            logger.warn { "Failed to write health file: ${e.message}" }
        }
    }

    fun readHealth(): DaemonHealth? {
        if (!healthFile.exists()) return null
        return try {
            json.decodeFromString<DaemonHealth>(healthFile.readText())
        } catch (_: Exception) {
            null
        }
    }

    fun removeHealthFile() {
        healthFile.delete()
    }

    fun isHealthStale(): Boolean {
        val health = readHealth() ?: return true
        return try {
            val lastBeat = Instant.parse(health.lastHeartbeat)
            Instant.now().epochSecond - lastBeat.epochSecond > 300 // 5 minutes
        } catch (_: Exception) {
            true
        }
    }

    // --- Activity Log ---

    fun logActivity(action: String, detail: String, success: Boolean = true) {
        choamDir.mkdirs()
        val entry = ActivityEntry(
            timestamp = Instant.now().atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")),
            action = action,
            detail = detail,
            success = success
        )
        try {
            activityFile.appendText(json.encodeToString(entry) + "\n")
        } catch (e: Exception) {
            logger.warn { "Failed to write activity log: ${e.message}" }
        }
    }

    fun getRecentActivity(limit: Int = 20): List<ActivityEntry> {
        if (!activityFile.exists()) return emptyList()
        return try {
            activityFile.readLines()
                .filter { it.isNotBlank() }
                .takeLast(limit)
                .reversed()
                .mapNotNull { line ->
                    try { json.decodeFromString<ActivityEntry>(line) } catch (_: Exception) { null }
                }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

@Serializable
data class DaemonHealth(
    val pid: Long,
    val startedAt: String,
    val lastHeartbeat: String,
    val state: String, // "idle", "transferring", "paused"
    val activeTransferId: String? = null,
    val activeTransferName: String? = null,
    val lastQueueRun: String? = null,
    val lastQueueResult: String? = null,
    val lastFailure: String? = null,
    val paused: Boolean = false
)

@Serializable
data class ActivityEntry(
    val timestamp: String,
    val action: String,
    val detail: String,
    val success: Boolean = true
)

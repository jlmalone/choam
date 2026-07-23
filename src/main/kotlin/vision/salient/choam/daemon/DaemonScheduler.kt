package vision.salient.choam.daemon

import kotlinx.coroutines.*
import mu.KotlinLogging
import vision.salient.choam.cli.TargetResolver
import vision.salient.choam.config.ChoamConfig
import vision.salient.choam.dag.DagStore
import vision.salient.choam.dag.DagSync
import vision.salient.choam.drive.DriveDetector
import vision.salient.choam.network.*
import vision.salient.choam.sync.*
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}

/**
 * Background task scheduler for the CHOAM daemon.
 *
 * Runs periodic monitoring tasks: catalog freshness checks, peer reachability,
 * drive health monitoring. Each task runs in its own coroutine with independent
 * error handling and intervals.
 */
class DaemonScheduler(
    private val config: ChoamConfig,
    private val scope: CoroutineScope
) {
    private val jobs = mutableListOf<Job>()
    private val taskStatus = mutableMapOf<String, TaskState>()

    data class TaskState(
        val name: String,
        var lastRun: String? = null,
        var lastResult: String? = null,
        var running: Boolean = false
    )

    fun start() {
        logger.info { "Daemon scheduler starting with ${config.machines.size} machines" }
        DaemonState.logActivity("scheduler_start", "Started with ${config.machines.size} machines")

        // Peer reachability — every 15 minutes
        scheduleTask("peer_reachability", 15 * 60 * 1000L) {
            checkPeerReachability()
        }

        // Catalog freshness — every 6 hours
        scheduleTask("catalog_freshness", 6 * 60 * 60 * 1000L) {
            checkCatalogFreshness()
        }

        // Drive health — every hour
        scheduleTask("drive_health", 60 * 60 * 1000L) {
            checkDriveHealth()
        }

        // DAG sync — every 15 minutes
        scheduleTask("dag_sync", 15 * 60 * 1000L) {
            syncDag()
        }

        // Health heartbeat — every 60 seconds
        scheduleTask("health_heartbeat", 60 * 1000L) {
            DaemonState.writeHealth()
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        logger.info { "Daemon scheduler stopped" }
        DaemonState.logActivity("scheduler_stop", "All tasks stopped")
        DaemonState.removeHealthFile()
    }

    fun status(): Map<String, TaskState> = taskStatus.toMap()

    /**
     * Manually trigger a specific task by name.
     */
    fun triggerTask(taskName: String): Boolean {
        return when (taskName) {
            "peer_reachability" -> { scope.launch { checkPeerReachability() }; true }
            "catalog_freshness" -> { scope.launch { checkCatalogFreshness() }; true }
            "drive_health" -> { scope.launch { checkDriveHealth() }; true }
            "dag_sync" -> { scope.launch { syncDag() }; true }
            else -> false
        }
    }

    // --- Scheduled task launcher ---

    private fun scheduleTask(name: String, intervalMs: Long, task: suspend () -> Unit) {
        taskStatus[name] = TaskState(name)
        val job = scope.launch {
            // Initial delay to stagger tasks (avoid all running at once on startup)
            delay((Math.random() * 10_000).toLong())

            while (isActive) {
                if (!DaemonState.paused) {
                    taskStatus[name]?.running = true
                    try {
                        task()
                        taskStatus[name]?.lastResult = "success"
                    } catch (e: CancellationException) {
                        throw e // Don't catch coroutine cancellation
                    } catch (e: Exception) {
                        logger.warn { "Task $name failed: ${e.message}" }
                        taskStatus[name]?.lastResult = "failed: ${e.message}"
                        DaemonState.logActivity(name, "Failed: ${e.message}", success = false)
                    } finally {
                        taskStatus[name]?.running = false
                        taskStatus[name]?.lastRun = LocalDateTime.now().toString()
                    }
                }
                delay(intervalMs)
            }
        }
        jobs.add(job)
    }

    // --- Task implementations ---

    private suspend fun checkPeerReachability() {
        val reachable = mutableListOf<String>()
        val unreachable = mutableListOf<String>()
        val hostname = try { InetAddress.getLocalHost().hostName } catch (_: Exception) { "unknown" }

        for ((name, machine) in config.machines) {
            val isLocal = machine.hostname == hostname || machine.hostname.startsWith(hostname)
            if (isLocal) {
                reachable.add(name)
                continue
            }
            val ip = machine.tailscaleIp ?: machine.hostname
            val isReachable = withContext(Dispatchers.IO) {
                try {
                    InetAddress.getByName(ip).isReachable(3000)
                } catch (_: Exception) { false }
            }
            if (isReachable) reachable.add(name) else unreachable.add(name)
        }

        val detail = "Reachable: ${reachable.joinToString()}" +
            if (unreachable.isNotEmpty()) " | Unreachable: ${unreachable.joinToString()}" else ""
        DaemonState.logActivity("peer_reachability", detail)
        logger.debug { "Peer check: $detail" }
    }

    private suspend fun checkCatalogFreshness() {
        val unifiedDbPath = "${System.getProperty("user.home")}/.choam/unified_registry.db"
        if (!File(unifiedDbPath).exists()) {
            DaemonState.logActivity("catalog_freshness", "No unified registry found")
            return
        }

        val stale = mutableListOf<String>()
        withContext(Dispatchers.IO) {
            val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedDbPath")
            val rs = conn.createStatement().executeQuery(
                "SELECT machine_name, MAX(last_synced_at) as last_sync FROM content_locations GROUP BY machine_name"
            )
            while (rs.next()) {
                val machine = rs.getString("machine_name")
                val lastSync = rs.getString("last_sync") ?: continue
                try {
                    val syncTime = LocalDateTime.parse(lastSync.replace(" ", "T").substringBefore("."))
                    val daysSince = ChronoUnit.DAYS.between(syncTime, LocalDateTime.now())
                    if (daysSince > 7) stale.add("$machine (${daysSince}d)")
                } catch (_: Exception) {}
            }
            rs.close(); conn.close()
        }

        val detail = if (stale.isEmpty()) "All catalogs fresh" else "Stale: ${stale.joinToString()}"
        DaemonState.logActivity("catalog_freshness", detail, success = stale.isEmpty())
        logger.debug { "Catalog freshness: $detail" }
    }

    private suspend fun syncDag() {
        val dagDbPath = "${System.getProperty("user.home")}/.choam/dag.db"
        if (!File(dagDbPath).exists()) {
            DaemonState.logActivity("dag_sync", "No DAG initialized — skipping")
            return
        }

        withContext(Dispatchers.IO) {
            val dagStore = DagStore(dagDbPath)
            val dagSync = DagSync(dagStore, config)
            val results = dagSync.syncAll()

            val successes = results.count { it.value.success }
            val totalPulled = results.values.filter { it.success }.sumOf { it.pulled }
            val totalPushed = results.values.filter { it.success }.sumOf { it.pushed }

            val detail = if (results.isEmpty()) {
                "No remote machines to sync with"
            } else {
                "$successes/${results.size} machines synced" +
                    if (totalPulled > 0 || totalPushed > 0) ", pulled $totalPulled, pushed $totalPushed events" else ""
            }
            // Don't mark as failure if remotes just don't have choam installed yet
            DaemonState.logActivity("dag_sync", detail, success = true)
            logger.debug { "DAG sync: $detail" }
        }
    }

    private suspend fun checkDriveHealth() {
        if (config.drives.isEmpty()) {
            DaemonState.logActivity("drive_health", "No drives configured")
            return
        }

        val warnings = mutableListOf<String>()
        val detector = DriveDetector()
        val mounted = withContext(Dispatchers.IO) {
            detector.detectConfiguredDrives(config.drives)
        }

        for ((key, drive) in config.drives) {
            val mountInfo = mounted[key]
            if (mountInfo == null) {
                // Drive not mounted — not necessarily an error
                continue
            }
            val usedPct = if (mountInfo.totalSpace > 0) {
                ((mountInfo.totalSpace - mountInfo.freeSpace) * 100.0 / mountInfo.totalSpace)
            } else 0.0

            if (usedPct >= 99) {
                warnings.add("${drive.label}: CRITICAL ${String.format("%.1f", usedPct)}% full")
            } else if (usedPct >= 95) {
                warnings.add("${drive.label}: WARNING ${String.format("%.1f", usedPct)}% full")
            } else if (usedPct >= 90) {
                warnings.add("${drive.label}: ${String.format("%.1f", usedPct)}% full")
            }
        }

        val detail = if (warnings.isEmpty()) "All drives healthy" else warnings.joinToString("; ")
        DaemonState.logActivity("drive_health", detail, success = warnings.isEmpty())
        logger.debug { "Drive health: $detail" }
    }
}

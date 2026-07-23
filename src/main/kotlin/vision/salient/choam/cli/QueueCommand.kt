package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import mu.KotlinLogging
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.network.ProgressMonitor
import vision.salient.choam.sync.*
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

class QueueCommand : CliktCommand(
    name = "queue",
    help = """
        Manage the CHOAM transfer queue.

        View, process, or cancel queued transfers created by 'choam send --queue'.

        Examples:
          choam queue                   Show pending transfers
          choam queue --run             Process all pending transfers
          choam queue --cancel abc123   Cancel a transfer (pending, failed, OR running)
          choam queue --reset abc123    Reset a stuck/running transfer back to pending
          choam queue --clear           Remove completed/cancelled entries
          choam queue --status          Show full queue with all statuses
          choam queue --status --json   Machine-readable JSON (raw bytes, for tooling)
    """.trimIndent()
) {
    private val run by option("--run", help = "Process all pending and failed transfers now").flag()
    private val cancel by option("--cancel", help = "Cancel a queued transfer by ID (pending, failed, or running)")
    private val reset by option("--reset", help = "Reset a stuck/running transfer back to pending so the next --run reclaims it")
    private val clear by option("--clear", help = "Remove completed/cancelled entries from queue").flag()
    private val status by option("--status", help = "Show all queue entries including completed").flag()
    private val asJson by option("--json", help = "Emit queue state as JSON (raw bytes; for tooling)").flag()
    private val verbose by option("--verbose", "-v", help = "Show full errors, retry schedule, defer reasons").flag()
    private val bwlimit by option("--bwlimit", help = "Override bandwidth limit for all transfers (KB/s)").int()

    override fun run() {
        val transferQueue = TransferQueueStore()

        when {
            cancel != null -> runCancel(transferQueue, cancel!!)
            reset != null -> runReset(transferQueue, reset!!)
            clear -> runClear(transferQueue)
            run -> runQueue(transferQueue)
            asJson -> echo(renderQueueReportJson(transferQueue.loadAll()))
            status -> showQueue(transferQueue, showAll = true, verbose = verbose)
            else -> showQueue(transferQueue, showAll = false, verbose = verbose)
        }
        transferQueue.publishStatusSnapshot()
    }

    private fun showQueue(queue: TransferQueueStore, showAll: Boolean, verbose: Boolean = false) {
        val entries = if (showAll) queue.loadAll() else queue.active()

        if (entries.isEmpty()) {
            echo(if (showAll) "Transfer queue is empty." else "No pending transfers.")
            return
        }

        echo("Transfer Queue (${entries.size} entries):\n")
        echo("  %-8s  %-10s  %-8s  %-6s  %-40s  →  %s".format("ID", "STATUS", "MODE", "PRIO", "SOURCE", "DEST"))
        echo("  " + "─".repeat(120))

        for (entry in entries) {
            val statusColor = when (entry.status) {
                TransferStatus.PENDING -> "\u001b[33m" // yellow
                TransferStatus.RUNNING -> "\u001b[36m" // cyan
                TransferStatus.COMPLETED -> "\u001b[32m" // green
                TransferStatus.FAILED -> "\u001b[31m" // red
                TransferStatus.CANCELLED -> "\u001b[90m" // gray
            }
            val reset = "\u001b[0m"
            val srcName = File(entry.sourcePath).name.take(40)
            val size = File(entry.sourcePath).let { f ->
                if (!f.exists()) "?"
                else if (f.isDirectory) ProgressMonitor.formatBytes(f.walkTopDown().filter { it.isFile }.sumOf { it.length() })
                else ProgressMonitor.formatBytes(f.length())
            }
            val dest = "${entry.destinationMachine}:${entry.destinationPath}"
            val modeStr = entry.mode.name.lowercase()
            val prioStr = entry.priority.name.lowercase().take(6)

            echo("  %-8s  ${statusColor}%-10s${reset}  %-8s  %-6s  %-40s  →  %s  (%s)".format(
                entry.id, entry.status.name.lowercase(), modeStr, prioStr, srcName, dest, size
            ))

            if (entry.status == TransferStatus.RUNNING) {
                // Show live progress from sidecar file
                val progressFile = File(System.getProperty("user.home"), ".choam/queue-progress-${entry.id}.json")
                if (progressFile.exists()) {
                    try {
                        val json = progressFile.readText()
                        val totalFiles = Regex("\"totalFiles\":(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val filesCompleted = Regex("\"filesCompleted\":(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val overallBytes = Regex("\"overallBytesTransferred\":(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                        val overallTotal = Regex("\"overallTotalBytes\":(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                        val speed = Regex("\"speedBytesPerSec\":(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                        val fileName = Regex("\"fileName\":\"([^\"]*)\"").find(json)?.groupValues?.get(1) ?: ""

                        if (totalFiles > 1) {
                            val pct = if (overallTotal > 0) overallBytes * 100 / overallTotal else 0
                            echo("           \u001b[36m└─ $filesCompleted/$totalFiles files  ${ProgressMonitor.formatBytes(overallBytes)} / ${ProgressMonitor.formatBytes(overallTotal)}  ${pct}%  ${if (speed > 0) ProgressMonitor.formatBytes(speed) + "/s" else ""}  $fileName\u001b[0m")
                        } else if (overallTotal > 0) {
                            val pct = overallBytes * 100 / overallTotal
                            echo("           \u001b[36m└─ ${ProgressMonitor.formatBytes(overallBytes)} / ${ProgressMonitor.formatBytes(overallTotal)}  ${pct}%  ${if (speed > 0) ProgressMonitor.formatBytes(speed) + "/s" else ""}\u001b[0m")
                        }
                    } catch (_: Exception) { }
                }
            }

            // Show errors for FAILED entries and defer reasons for PENDING entries
            val hasError = entry.error != null
            val isFailed = entry.status == TransferStatus.FAILED
            val isDeferred = entry.status == TransferStatus.PENDING && entry.error?.startsWith("Deferred:") == true

            if ((isFailed || isDeferred) && hasError) {
                val retryInfo = if (isFailed && entry.nextRetryAt != null && entry.retryCount <= TransferQueueEntry.MAX_RETRIES) {
                    val nextAt = entry.nextRetryAt!!.substringAfter("T").substringBefore(".")
                    " (retry ${entry.retryCount}/${TransferQueueEntry.MAX_RETRIES}, next at $nextAt)"
                } else if (isFailed && entry.retryCount > TransferQueueEntry.MAX_RETRIES) {
                    " (retries exhausted)"
                } else ""

                if (verbose && isFailed) {
                    // Verbose: full multi-line error with classification and timestamps
                    val errorLines = entry.error!!.split("\n")
                    val badge = when {
                        entry.error!!.startsWith("[SG_ACQUIRE]") -> "\u001b[35m[SG_ACQUIRE]\u001b[0m "
                        entry.error!!.startsWith("[SG_WAL]") -> "\u001b[35m[SG_WAL]\u001b[0m "
                        entry.error!!.startsWith("[SG_VERIFY]") -> "\u001b[35m[SG_VERIFY]\u001b[0m "
                        entry.error!!.contains("[connectivity") -> "\u001b[33m[TRANSIENT]\u001b[0m "
                        else -> ""
                    }
                    echo("           \u001b[31m└─ ${badge}Error:\u001b[0m")
                    for (line in errorLines) {
                        echo("              \u001b[31m$line\u001b[0m")
                    }
                    echo("           \u001b[31m└─ Retry: ${entry.retryCount}/${TransferQueueEntry.MAX_RETRIES}$retryInfo\u001b[0m")
                    if (entry.createdAt.isNotEmpty()) echo("           \u001b[31m└─ Created: ${entry.createdAt.substringBefore("T")} ${entry.createdAt.substringAfter("T").substringBefore(".")}\u001b[0m")
                    if (entry.startedAt != null) echo("           \u001b[31m└─ Last attempt: ${entry.startedAt!!.substringBefore("T")} ${entry.startedAt!!.substringAfter("T").substringBefore(".")}\u001b[0m")
                } else {
                    // Standard: single-line with badge
                    val errorDisplay = when {
                        entry.error!!.startsWith("[SG_ACQUIRE]") -> "\u001b[35m[SG_ACQUIRE]\u001b[31m ${entry.error!!.removePrefix("[SG_ACQUIRE] ")}"
                        entry.error!!.startsWith("[SG_WAL]") -> "\u001b[35m[SG_WAL]\u001b[31m ${entry.error!!.removePrefix("[SG_WAL] ")}"
                        entry.error!!.startsWith("[SG_VERIFY]") -> "\u001b[35m[SG_VERIFY]\u001b[31m ${entry.error!!.removePrefix("[SG_VERIFY] ")}"
                        isDeferred -> "\u001b[33m${entry.error}\u001b[0m"
                        else -> entry.error!!
                    }
                    val color = if (isDeferred) "\u001b[33m" else "\u001b[31m"
                    echo("           ${color}└─ $errorDisplay$retryInfo\u001b[0m")
                }
            }
        }
    }

    private fun runCancel(queue: TransferQueueStore, id: String) {
        // cancelRunning() dispatches by status: a RUNNING entry's process is killed and the
        // entry cancelled; pending/failed are cancelled directly; completed/cancelled are left as-is.
        if (queue.cancelRunning(id)) {
            echo("Cancelled transfer $id")
        } else {
            echo("Transfer $id not found, or already completed/cancelled")
        }
    }

    private fun runReset(queue: TransferQueueStore, id: String) {
        if (queue.resetToPending(id)) {
            echo("Reset transfer $id → pending (will reprocess on the next 'queue --run')")
        } else {
            echo("Transfer $id not found")
        }
    }

    private fun runClear(queue: TransferQueueStore) {
        queue.clear(completedOnly = true)
        echo("Cleared completed and cancelled entries from queue.")
    }

    private fun runQueue(queue: TransferQueueStore) {
        val lock = QueueRunLock()
        if (!lock.tryAcquire()) {
            val pid = lock.holderPid()
            echo("Another queue processor is already running (PID ${pid ?: "unknown"}).")
            echo("Use 'queue --status' to check progress, or wait for it to finish.")
            exitProcess(1)
        }

        lock.use {
            val config = try {
                ChoamConfigLoader.load()
            } catch (e: Exception) {
                echo("Failed to load CHOAM config: ${e.message}")
                exitProcess(1)
            }

            val processor = QueueProcessor(
                config = config,
                queue = queue,
                mode = ProcessingMode.INTERACTIVE,
                bandwidthOverride = bwlimit,
                echo = ::echo
            )
            processor.processAll()
        }
    }
}

package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.dag.ConfigResolver
import vision.salient.choam.network.NetworkDetector
import vision.salient.choam.network.ProgressMonitor
import vision.salient.choam.network.TransferManager
import vision.salient.choam.sync.ConflictResolver
import vision.salient.choam.sync.SyncEngine
import vision.salient.choam.sync.SyncHistoryStore
import java.io.File
import java.net.InetAddress
import java.sql.DriverManager

private val logger = KotlinLogging.logger {}

/**
 * Execute pending copy requests. For each pending request, checks if the target
 * machine is reachable and runs the equivalent of `choam push <repo> --to <machine>`.
 */
class FulfillCommand : CliktCommand(
    name = "fulfill",
    help = """
        Execute pending copy requests created by 'choam request-copy'.

        Scans the copy_requests table for pending requests, checks target machine
        reachability, and runs push operations for reachable targets. Marks requests
        as completed after successful transfer.

        Key behaviors:
          - Only processes pending requests
          - Skips requests where target is unreachable
          - Runs push (local → remote) for each fulfilled request
          - Use --dry-run to see what would be transferred
          - Use --list to just show pending requests without executing

        Safety: Transfers files to remote machines. Use --dry-run first.

        Examples:
          choam fulfill
          choam fulfill --dry-run
          choam fulfill --list
    """.trimIndent()
) {
    private val dryRun by option("--dry-run", "-n", help = "Show what would be transferred without copying").flag()
    private val listOnly by option("--list", "-l", help = "List pending requests without executing").flag()

    override fun run() {
        val config = try {
            ConfigResolver.resolve()
        } catch (e: Exception) {
            echo("Failed to load CHOAM config: ${e.message}")
            return
        }

        val unifiedDbPath = "${System.getProperty("user.home")}/.choam/unified_registry.db"
        if (!File(unifiedDbPath).exists()) {
            echo("No unified registry found. No pending requests.")
            return
        }

        try {
            val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedDbPath")
            RequestCopyCommand.ensureCopyRequestsTable(conn)

            val rs = conn.createStatement().executeQuery(
                "SELECT id, repository, target_machine, requested_at FROM copy_requests WHERE status = 'pending' ORDER BY requested_at"
            )

            data class PendingRequest(val id: Int, val repository: String, val targetMachine: String, val requestedAt: String)

            val pending = mutableListOf<PendingRequest>()
            while (rs.next()) {
                pending.add(PendingRequest(
                    id = rs.getInt("id"),
                    repository = rs.getString("repository"),
                    targetMachine = rs.getString("target_machine"),
                    requestedAt = rs.getString("requested_at")
                ))
            }
            rs.close()

            if (pending.isEmpty()) {
                echo("No pending copy requests.")
                conn.close()
                return
            }

            echo("${pending.size} pending copy request(s):")
            echo()

            for (req in pending) {
                echo("  #${req.id}  ${req.repository} → ${req.targetMachine}  (requested: ${req.requestedAt})")
            }
            echo()

            if (listOnly) {
                conn.close()
                return
            }

            // Find local machine
            val resolver = TargetResolver(config)
            val localMachine = resolver.findLocalMachine()
            if (localMachine == null) {
                echo("Cannot determine local machine. Check config.")
                conn.close()
                return
            }

            var fulfilled = 0
            var skipped = 0

            for (req in pending) {
                val targetProfile = config.machines[req.targetMachine]
                if (targetProfile == null) {
                    echo("  #${req.id}: Machine '${req.targetMachine}' not in config — skipping")
                    skipped++
                    continue
                }

                // Check reachability
                val reachable = try {
                    val ip = targetProfile.tailscaleIp ?: targetProfile.hostname
                    InetAddress.getByName(ip).isReachable(5000)
                } catch (e: Exception) {
                    false
                }

                if (!reachable) {
                    echo("  #${req.id}: ${req.targetMachine} unreachable — skipping")
                    skipped++
                    continue
                }

                // Check local machine has the repo
                if (!localMachine.repositories.containsKey(req.repository)) {
                    echo("  #${req.id}: Local machine doesn't have '${req.repository}' — skipping")
                    skipped++
                    continue
                }

                echo("  #${req.id}: Pushing ${req.repository} to ${req.targetMachine}...")

                try {
                    // Mark as in_progress
                    val updateStmt = conn.prepareStatement(
                        "UPDATE copy_requests SET status = 'in_progress' WHERE id = ?"
                    )
                    updateStmt.setInt(1, req.id)
                    updateStmt.executeUpdate()
                    updateStmt.close()

                    val networkDetector = NetworkDetector()
                    val route = networkDetector.detectBestRoute(localMachine, targetProfile)
                    val rules = config.defaultSyncRules
                    val transferManager = TransferManager(config)
                    val conflictResolver = ConflictResolver()
                    val syncEngine = SyncEngine(config, transferManager, conflictResolver)
                    val progressMonitor = ProgressMonitor()
                    val historyStore = SyncHistoryStore()

                    runBlocking {
                        val session = syncEngine.sync(
                            source = localMachine,
                            target = targetProfile,
                            repositories = listOf(req.repository),
                            rules = rules,
                            route = route,
                            dryRun = dryRun
                        ) { syncSession, progress ->
                            progressMonitor.displayProgress(syncSession, progress)
                        }

                        if (!dryRun) {
                            historyStore.record(session)

                            if (session.status == vision.salient.choam.sync.SyncStatus.COMPLETED) {
                                // Mark as completed only if sync actually succeeded
                                val completeStmt = conn.prepareStatement(
                                    "UPDATE copy_requests SET status = 'completed', fulfilled_at = datetime('now') WHERE id = ?"
                                )
                                completeStmt.setInt(1, req.id)
                                completeStmt.executeUpdate()
                                completeStmt.close()
                            } else {
                                echo("    Sync had errors (status=${session.status}) — request stays pending")
                                val resetStmt = conn.prepareStatement(
                                    "UPDATE copy_requests SET status = 'pending' WHERE id = ?"
                                )
                                resetStmt.setInt(1, req.id)
                                resetStmt.executeUpdate()
                                resetStmt.close()
                            }
                        }

                        echo("    ${req.repository}: ${session.statistics.filesTransferred} files, " +
                            "${ProgressMonitor.formatBytes(session.statistics.bytesTransferred)}")
                    }

                    fulfilled++
                } catch (e: Exception) {
                    echo("    Error: ${e.message}")
                    logger.error(e) { "Failed to fulfill request #${req.id}" }

                    // Reset to pending on failure
                    val resetStmt = conn.prepareStatement(
                        "UPDATE copy_requests SET status = 'pending' WHERE id = ?"
                    )
                    resetStmt.setInt(1, req.id)
                    resetStmt.executeUpdate()
                    resetStmt.close()
                    skipped++
                }
            }

            echo()
            echo("Fulfilled: $fulfilled, Skipped: $skipped")
            conn.close()
        } catch (e: Exception) {
            echo("Error processing copy requests: ${e.message}")
        }
    }
}

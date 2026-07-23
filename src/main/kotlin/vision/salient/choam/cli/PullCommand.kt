package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlin.system.exitProcess
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

private val logger = KotlinLogging.logger {}

class PullCommand : CliktCommand(
    name = "pull",
    help = """
        Download files from a remote machine's repository to the local machine.

        Resolves the best network route (LAN, Tailscale, or WAN), builds file catalogs on both sides, computes the diff, and transfers missing/updated files from the remote to local. Records the sync in history.

        Key behaviors:
          - Auto-detects the local machine from hostname
          - Auto-selects remote if only one machine has the repository; use --from if ambiguous
          - Applies conflict resolution and exclude patterns from config
          - Supports 'all' to pull every configured repository at once
          - Use --dry-run to preview what would be transferred without copying

        Safety: Modifies LOCAL files only — downloads from remote. Use --dry-run first to preview changes.

        Examples:
          choam pull media --from server
          choam pull all --from desktop --dry-run
          choam pull archive
    """.trimIndent()
) {
    private val target by argument(help = "Repository name to pull, or 'all' for every configured repository")
    private val from by option("--from", help = "Source machine name to pull from (required if multiple remotes have the repo)")
    private val dryRun by option("--dry-run", "-n", help = "Show what would be transferred without actually copying files").flag()

    override fun run() {
        val config = try {
            ConfigResolver.resolve()
        } catch (e: Exception) {
            echo("Failed to load CHOAM config: ${e.message}")
            exitProcess(1)
        }

        val resolver = TargetResolver(config)
        val (resolved, error) = resolver.resolve(target, from, Direction.PULL)

        if (resolved == null) {
            echo(error ?: "Failed to resolve pull target")
            exitProcess(1)
        }

        val local = resolved.localMachine
        val remote = resolved.remoteMachine

        echo("Pull: ${resolved.repos.joinToString()} ${remote.name} → ${local.name}")

        val networkDetector = NetworkDetector()
        val route = networkDetector.detectBestRoute(remote, local)
        val connectivity = networkDetector.testConnectivity(route)

        echo("Route: ${route.mode} (${route.sourceAddress} → ${route.targetAddress})")
        logger.info { "Pull using route $route (reachable=${connectivity.reachable})" }

        val rules = config.defaultSyncRules
        val transferManager = TransferManager(config)
        val conflictResolver = ConflictResolver()
        val syncEngine = SyncEngine(config, transferManager, conflictResolver)
        val progressMonitor = ProgressMonitor()
        val historyStore = SyncHistoryStore()

        runBlocking {
            // Pull: remote is source, local is target
            val session = syncEngine.sync(
                source = remote,
                target = local,
                repositories = resolved.repos,
                rules = rules,
                route = route,
                dryRun = dryRun
            ) { syncSession, progress ->
                progressMonitor.displayProgress(syncSession, progress)
            }

            if (dryRun) {
                echo("Dry-run: would transfer ${session.statistics.filesTransferred} files " +
                    "(${ProgressMonitor.formatBytes(session.statistics.bytesTransferred)})")
            }

            progressMonitor.displaySummary(session)

            if (!dryRun) {
                historyStore.record(session)
            }
        }
    }
}

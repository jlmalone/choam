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

class PushCommand : CliktCommand(
    name = "push",
    help = """
        Upload files from the local machine's repository to a remote machine.

        Resolves the best network route (LAN, Tailscale, or WAN), builds file catalogs on both sides, computes the diff, and transfers missing/updated files from local to the remote. Records the sync in history.

        Key behaviors:
          - Auto-detects the local machine from hostname
          - Auto-selects remote if only one machine has the repository; use --to if ambiguous
          - Applies conflict resolution and exclude patterns from config
          - Supports 'all' to push every configured repository at once
          - Use --dry-run to preview what would be transferred without copying

        Safety: Modifies REMOTE files — uploads from local. Use --dry-run first to preview changes.

        Examples:
          choam push media --to laptop
          choam push all --to server --dry-run
          choam push archive
    """.trimIndent()
) {
    private val target by argument(help = "Repository name to push, or 'all' for every configured repository")
    private val to by option("--to", help = "Target machine name to push to (required if multiple remotes have the repo)")
    private val dryRun by option("--dry-run", "-n", help = "Show what would be transferred without actually copying files").flag()

    override fun run() {
        val config = try {
            ConfigResolver.resolve()
        } catch (e: Exception) {
            echo("Failed to load CHOAM config: ${e.message}")
            exitProcess(1)
        }

        val resolver = TargetResolver(config)
        val (resolved, error) = resolver.resolve(target, to, Direction.PUSH)

        if (resolved == null) {
            echo(error ?: "Failed to resolve push target")
            exitProcess(1)
        }

        val source = resolved.localMachine
        val remote = resolved.remoteMachine

        echo("Push: ${resolved.repos.joinToString()} ${source.name} → ${remote.name}")

        val networkDetector = NetworkDetector()
        val route = networkDetector.detectBestRoute(source, remote)
        val connectivity = networkDetector.testConnectivity(route)

        echo("Route: ${route.mode} (${route.sourceAddress} → ${route.targetAddress})")
        logger.info { "Push using route $route (reachable=${connectivity.reachable})" }

        val rules = config.defaultSyncRules
        val transferManager = TransferManager(config)
        val conflictResolver = ConflictResolver()
        val syncEngine = SyncEngine(config, transferManager, conflictResolver)
        val progressMonitor = ProgressMonitor()
        val historyStore = SyncHistoryStore()

        runBlocking {
            val session = syncEngine.sync(
                source = source,
                target = remote,
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

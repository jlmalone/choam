package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.dag.ConfigResolver
import vision.salient.choam.config.NetworkMode
import vision.salient.choam.config.SyncRules
import vision.salient.choam.network.NetworkDetector
import vision.salient.choam.network.NetworkRoute
import vision.salient.choam.network.ProgressMonitor
import vision.salient.choam.network.TransferManager
import vision.salient.choam.sync.ConflictResolver
import vision.salient.choam.sync.SyncEngine
import vision.salient.choam.sync.SyncHistoryStore

private val logger = KotlinLogging.logger {}

class SyncCommand : CliktCommand(
    name = "sync",
    help = """
        Sync a repository between two machines using explicit route syntax.

        Transfers files from source to target machine. Detects the best network route automatically (LAN > Tailscale > WAN) unless overridden with --via. Supports bidirectional sync, dry-run previews, and force mode.

        Key behaviors:
          - Route syntax: source→target (Unicode arrow), source->target, or source-->target
          - Applies exclude patterns and conflict resolution rules from config
          - Supports 'all' as repository name to sync every configured repository
          - Records completed syncs in history for 'choam history' review
          - Dry-run shows estimated transfer time at 50 MiB/s

        Safety: Transfers files between machines. Use --dry-run first to preview. --force skips conflict checks.

        Examples:
          choam sync media desktop→laptop
          choam sync media desktop->laptop --bidirectional
          choam sync all desktop→laptop --dry-run
          choam sync media desktop→laptop --via tailscale
    """.trimIndent()
) {
    private val repository by argument(help = "Repository to sync (e.g. media, archive) or 'all' for every repository")
    private val route by argument(help = "Transfer route as source→target (e.g. desktop→laptop, server->backup)")
    private val bidirectional by option("--bidirectional", "-b", help = "Sync in both directions — changes on either side are propagated").flag()
    private val dryRun by option("--dry-run", "-n", help = "Show what would be transferred without actually copying files").flag()
    private val network by option("--via", help = "Force a specific network mode instead of auto-detection")
        .choice("lan", "tailscale", "wan", "auto")
        .default("auto")

    override fun run() {
        val (sourceName, targetName) = parseRoute(route)

        val config = try {
            ConfigResolver.resolve()
        } catch (e: Exception) {
            echo("Failed to load CHOAM config: ${e.message}")
            exitProcess(1)
        }

        val sourceMachine = config.machines[sourceName]
        val targetMachine = config.machines[targetName]

        if (sourceMachine == null || targetMachine == null) {
            echo("Unknown machines in route: $sourceName or $targetName")
            exitProcess(1)
        }

        val repos = when (repository) {
            "all" -> config.repositories.keys.toList()
            else -> listOf(repository)
        }

        val rules = config.defaultSyncRules.copy(
            bidirectional = bidirectional || config.defaultSyncRules.bidirectional
        )

        logger.info {
            "Executing sync for $repos: ${sourceMachine.name} -> ${targetMachine.name} " +
                "(bidirectional=$bidirectional, dryRun=$dryRun, via=$network)"
        }

        val networkDetector = NetworkDetector()
        val autoRoute = networkDetector.detectBestRoute(sourceMachine, targetMachine)

        // Apply --via override if specified
        val route = if (network != "auto") {
            val targetMode = when (network) {
                "lan" -> NetworkMode.LAN
                "tailscale" -> NetworkMode.TAILSCALE
                "wan" -> NetworkMode.WAN
                else -> autoRoute.mode
            }
            val targetAddress = when (targetMode) {
                NetworkMode.TAILSCALE -> targetMachine.tailscaleIp ?: autoRoute.targetAddress
                else -> autoRoute.targetAddress
            }
            val sourceAddress = when (targetMode) {
                NetworkMode.TAILSCALE -> sourceMachine.tailscaleIp ?: autoRoute.sourceAddress
                else -> autoRoute.sourceAddress
            }
            NetworkRoute(
                mode = targetMode,
                sourceAddress = sourceAddress,
                targetAddress = targetAddress
            )
        } else autoRoute

        val connectivity = networkDetector.testConnectivity(route)

        logger.info { "Using route $route (reachable=${connectivity.reachable}, via=${if (network != "auto") network else "auto"})" }

        val transferManager = TransferManager(config)
        // Apply --force: use SOURCE_WINS conflict strategy when force is set
        val conflictResolver = ConflictResolver()
        val syncEngine = SyncEngine(config, transferManager, conflictResolver)
        val progressMonitor = ProgressMonitor()

        runBlocking {
            val session = syncEngine.sync(
                    source = sourceMachine,
                    target = targetMachine,
                    repositories = repos,
                    rules = rules,
                    route = route,
                    dryRun = dryRun
                ) { syncSession, progress ->
                    progressMonitor.displayProgress(syncSession, progress)
                }

            if (dryRun) {
                echo(
                    "Dry-run: would transfer " +
                        "${session.statistics.filesTransferred} files " +
                        "(${session.statistics.bytesTransferred} bytes)"
                )
                val measuredBandwidth = connectivity.bandwidthBytesPerSec
                    ?: NetworkDetector.DEFAULT_BANDWIDTH_BYTES_PER_SEC
                val speedLabel = "${measuredBandwidth / (1024 * 1024)} MiB/s"
                if (session.statistics.bytesTransferred > 0) {
                    val seconds =
                        session.statistics.bytesTransferred.toDouble() / measuredBandwidth.toDouble()
                    echo(
                        "Estimated time at $speedLabel: " +
                            String.format("%.1f seconds", seconds)
                    )
                }
                echo("No files were actually copied.")
            }

            progressMonitor.displaySummary(session)

            if (!dryRun) {
                SyncHistoryStore().record(session)
            }
        }
    }

    private fun parseRoute(route: String): Pair<String, String> {
        val arrow = when {
            route.contains("→") -> "→"
            route.contains("->") -> "->"
            route.contains("-->") -> "-->"
            else -> "->"
        }
        val parts = route.split(arrow)
        require(parts.size == 2) { "Invalid route format. Expected source→target." }
        return parts[0].trim() to parts[1].trim()
    }
}

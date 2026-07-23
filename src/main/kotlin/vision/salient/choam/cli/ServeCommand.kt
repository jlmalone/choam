package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import mu.KotlinLogging
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.dag.ConfigResolver
import vision.salient.choam.daemon.DaemonScheduler
import vision.salient.choam.daemon.DaemonState
import vision.salient.choam.web.configureRouting

private val logger = KotlinLogging.logger {}

class ServeCommand : CliktCommand(
    name = "serve",
    help = """
        Start the CHOAM web dashboard and content proxy.

        Launches a Ktor+HTMX web server for browsing catalog data, monitoring
        machine status, searching files, streaming content, and managing federation.

        With --daemon: writes PID file, starts background scheduler for periodic
        monitoring (peer reachability, catalog freshness, drive health).

        Key behaviors:
          - Serves on localhost (configurable port)
          - Content proxy: streams from local files or IPFS gateway
          - CORS enabled for cross-project browser access
          - HTMX for dynamic updates without a JS build step

        Examples:
          choam serve
          choam serve --port 8742
          choam serve --daemon
    """.trimIndent()
) {
    private val port by option("--port", "-p", help = "Port to serve on (default: 8742)").default("8742")
    private val daemon by option("--daemon", "-d", help = "Run as daemon (PID file, scheduler, no Ctrl+C message)").flag()

    override fun run() {
        val config = try {
            ConfigResolver.resolve()
        } catch (e: Exception) {
            echo("Failed to load CHOAM config: ${e.message}")
            return
        }

        val portNum = port.toIntOrNull() ?: run {
            echo("Invalid port: $port")
            return
        }

        // Daemon mode: PID file + scheduler
        var scheduler: DaemonScheduler? = null
        if (daemon) {
            DaemonState.writePidFile()
            DaemonState.logActivity("daemon_start", "Starting on port $portNum")

            val scope = CoroutineScope(SupervisorJob())
            scheduler = DaemonScheduler(config, scope)
            scheduler.start()

            // Shutdown hook
            Runtime.getRuntime().addShutdownHook(Thread {
                logger.info { "Daemon shutting down..." }
                scheduler.stop()
                DaemonState.logActivity("daemon_stop", "Shutdown")
                DaemonState.removePidFile()
            })

            logger.info { "Daemon mode: PID ${ProcessHandle.current().pid()}, scheduler active" }
        } else {
            echo("Starting CHOAM dashboard at http://localhost:$portNum")
            echo("Press Ctrl+C to stop.")
        }

        embeddedServer(CIO, port = portNum) {
            configureRouting(config, scheduler)
        }.start(wait = true)
    }
}

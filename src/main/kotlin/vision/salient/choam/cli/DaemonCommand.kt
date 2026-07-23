package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import vision.salient.choam.daemon.DaemonState
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Daemon management — start/stop/status/install/uninstall the CHOAM background service.
 */
class DaemonParentCommand : CliktCommand(
    name = "daemon",
    help = """
        Manage the CHOAM background daemon.

        The daemon runs the web dashboard + content proxy + background scheduler
        as a persistent service. It monitors peer reachability, catalog freshness,
        and drive health automatically.

        Subcommands:
          start     — Start daemon in background
          stop      — Stop running daemon
          status    — Show daemon state
          install   — Install macOS launchd auto-start
          uninstall — Remove launchd auto-start
          log       — Show recent daemon activity

        Examples:
          choam daemon start
          choam daemon status
          choam daemon install
    """.trimIndent(),
    invokeWithoutSubcommand = true
) {
    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            DaemonStatusSubcommand().parse(emptyList())
        }
    }
}

class DaemonStartSubcommand : CliktCommand(
    name = "start",
    help = "Start the CHOAM daemon in the background."
) {
    private val port by option("--port", "-p", help = "Port to serve on").default("8742")

    override fun run() {
        if (DaemonState.isRunning()) {
            val pid = DaemonState.readPidFile()
            echo("Daemon already running (PID $pid)")
            return
        }

        // Find the choam binary
        val choamBin = findChoamBinary()
        if (choamBin == null) {
            echo("Cannot find choam binary. Use 'choam serve --daemon' directly.")
            return
        }

        val logsDir = DaemonState.getLogsDir()
        val logFile = File(logsDir, "daemon.log")
        val errFile = File(logsDir, "daemon-error.log")

        echo("Starting daemon on port $port...")

        val process = ProcessBuilder(choamBin, "serve", "--daemon", "--port", port)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .redirectError(ProcessBuilder.Redirect.appendTo(errFile))
            .start()

        // Wait briefly and check if it's alive
        Thread.sleep(2000)
        if (process.isAlive) {
            echo("Daemon started (PID ${process.pid()})")
            echo("  Dashboard: http://localhost:$port")
            echo("  Logs:      ${logFile.absolutePath}")
        } else {
            echo("Daemon failed to start. Check ${errFile.absolutePath}")
        }
    }

    private fun findChoamBinary(): String? {
        // Check if running from Gradle installDist
        val installDist = File(System.getProperty("user.dir"), "build/install/choam/bin/choam")
        if (installDist.exists()) return installDist.absolutePath

        // Check PATH
        val whichResult = try {
            val p = ProcessBuilder("which", "choam").start()
            p.waitFor(5, TimeUnit.SECONDS)
            p.inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) { "" }

        return whichResult.ifEmpty { null }
    }
}

class DaemonStopSubcommand : CliktCommand(
    name = "stop",
    help = "Stop the running CHOAM daemon."
) {
    override fun run() {
        val pid = DaemonState.readPidFile()
        if (pid == null) {
            echo("No daemon PID file found.")
            return
        }

        if (!DaemonState.isRunning()) {
            echo("Daemon not running (stale PID file). Cleaning up.")
            DaemonState.removePidFile()
            return
        }

        echo("Stopping daemon (PID $pid)...")
        try {
            ProcessBuilder("kill", "$pid").start().waitFor(5, TimeUnit.SECONDS)
            // Wait for process to exit
            var waited = 0
            while (DaemonState.isRunning() && waited < 5000) {
                Thread.sleep(500)
                waited += 500
            }

            if (!DaemonState.isRunning()) {
                DaemonState.removePidFile()
                echo("Daemon stopped.")
            } else {
                echo("Daemon still running. Use 'kill -9 $pid' to force stop.")
            }
        } catch (e: Exception) {
            echo("Failed to stop daemon: ${e.message}")
        }
    }
}

class DaemonStatusSubcommand : CliktCommand(
    name = "status",
    help = "Show daemon state — running/stopped, PID, uptime, recent activity."
) {
    override fun run() {
        val pid = DaemonState.readPidFile()
        val running = DaemonState.isRunning()

        echo("CHOAM Daemon:")
        if (running) {
            echo("  Status:  \u001b[32mrunning\u001b[0m")
            echo("  PID:     $pid")
            echo("  Uptime:  ${DaemonState.getUptime()}")
            echo("  Paused:  ${DaemonState.paused}")

            // Show health details from health file
            val health = DaemonState.readHealth()
            if (health != null) {
                val stale = DaemonState.isHealthStale()
                if (stale) {
                    echo("  Health:  \u001b[33mUNRESPONSIVE\u001b[0m (heartbeat >5 min old)")
                } else {
                    echo("  State:   ${health.state}")
                }
                if (health.activeTransferId != null) {
                    echo("  Active:  ${health.activeTransferName ?: health.activeTransferId}")
                }
                if (health.lastQueueRun != null) {
                    echo("  Last run: ${health.lastQueueRun!!.substringAfter("T").substringBefore(".")} (${health.lastQueueResult ?: "?"})")
                }
                if (health.lastFailure != null) {
                    echo("  Last err: ${health.lastFailure}")
                }
            }
        } else {
            echo("  Status:  \u001b[31mstopped\u001b[0m")
            if (pid != null) echo("  (Stale PID file: $pid)")
        }

        // Queue summary
        echo()
        try {
            val queue = vision.salient.choam.sync.TransferQueueStore()
            val all = queue.loadAll()
            val pending = all.count { it.status == vision.salient.choam.sync.TransferStatus.PENDING }
            val queueRunning = all.count { it.status == vision.salient.choam.sync.TransferStatus.RUNNING }
            val failed = all.count { it.status == vision.salient.choam.sync.TransferStatus.FAILED }
            echo("Queue: $pending pending, $queueRunning running, $failed failed")
        } catch (_: Exception) {
            echo("Queue: unavailable")
        }

        echo()
        echo("Recent Activity:")
        val activity = DaemonState.getRecentActivity(5)
        if (activity.isEmpty()) {
            echo("  No activity recorded.")
        } else {
            for (entry in activity) {
                val icon = if (entry.success) "\u001b[32m✓\u001b[0m" else "\u001b[31m✗\u001b[0m"
                echo("  $icon ${entry.timestamp.substringAfter("T").substringBefore(".")} ${entry.action}: ${entry.detail}")
            }
        }
    }
}

class DaemonInstallSubcommand : CliktCommand(
    name = "install",
    help = "Install macOS launchd plist for auto-start on login."
) {
    private val port by option("--port", "-p", help = "Port for the daemon").default("8742")

    override fun run() {
        val launchAgentsDir = File(System.getProperty("user.home"), "Library/LaunchAgents")
        val plistFile = File(launchAgentsDir, "com.choam.daemon.plist")

        val choamBin = findChoamBinary()
        if (choamBin == null) {
            echo("Cannot find choam binary. Install via 'brew install jlmalone/tap/choam' or './gradlew installDist'.")
            return
        }

        val logsDir = DaemonState.getLogsDir()

        val plist = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.choam.daemon</string>
    <key>ProgramArguments</key>
    <array>
        <string>$choamBin</string>
        <string>serve</string>
        <string>--daemon</string>
        <string>--port</string>
        <string>$port</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>${logsDir.absolutePath}/daemon.log</string>
    <key>StandardErrorPath</key>
    <string>${logsDir.absolutePath}/daemon-error.log</string>
    <key>EnvironmentVariables</key>
    <dict>
        <key>JAVA_HOME</key>
        <string>${System.getProperty("java.home")}</string>
    </dict>
</dict>
</plist>"""

        launchAgentsDir.mkdirs()
        plistFile.writeText(plist)
        echo("Plist written: ${plistFile.absolutePath}")

        // Load the agent
        try {
            val result = ProcessBuilder("launchctl", "load", plistFile.absolutePath)
                .redirectErrorStream(true).start()
            result.waitFor(10, TimeUnit.SECONDS)
            val output = result.inputStream.bufferedReader().readText().trim()
            if (output.isNotEmpty()) echo("  launchctl: $output")
            echo("Daemon installed and started. Will auto-start on login.")
            echo("  Dashboard: http://localhost:$port")
            echo("  Uninstall: choam daemon uninstall")
        } catch (e: Exception) {
            echo("Failed to load launchd agent: ${e.message}")
            echo("Plist was written — you can load manually: launchctl load ${plistFile.absolutePath}")
        }
    }

    private fun findChoamBinary(): String? {
        val installDist = File(System.getProperty("user.dir"), "build/install/choam/bin/choam")
        if (installDist.exists()) return installDist.absolutePath
        val whichResult = try {
            val p = ProcessBuilder("which", "choam").start()
            p.waitFor(5, TimeUnit.SECONDS)
            p.inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) { "" }
        return whichResult.ifEmpty { null }
    }
}

class DaemonUninstallSubcommand : CliktCommand(
    name = "uninstall",
    help = "Remove macOS launchd plist and stop auto-start."
) {
    override fun run() {
        val plistFile = File(System.getProperty("user.home"), "Library/LaunchAgents/com.choam.daemon.plist")
        if (!plistFile.exists()) {
            echo("No launchd plist found. Nothing to uninstall.")
            return
        }

        try {
            ProcessBuilder("launchctl", "unload", plistFile.absolutePath)
                .redirectErrorStream(true).start()
                .waitFor(10, TimeUnit.SECONDS)
        } catch (_: Exception) {}

        plistFile.delete()
        echo("Daemon uninstalled. Will no longer auto-start on login.")
    }
}

class DaemonLogSubcommand : CliktCommand(
    name = "log",
    help = "Show recent daemon activity log."
) {
    private val limit by option("--limit", "-n", help = "Number of entries to show").default("20")

    override fun run() {
        val entries = DaemonState.getRecentActivity(limit.toInt())
        if (entries.isEmpty()) {
            echo("No daemon activity recorded.")
            return
        }

        echo("Daemon Activity (last ${entries.size}):")
        echo()
        for (entry in entries) {
            val icon = if (entry.success) "\u001b[32m✓\u001b[0m" else "\u001b[31m✗\u001b[0m"
            echo("$icon ${entry.timestamp}  ${entry.action.padEnd(22)} ${entry.detail}")
        }
    }
}

fun daemonCommand(): DaemonParentCommand = DaemonParentCommand().subcommands(
    DaemonStartSubcommand(),
    DaemonStopSubcommand(),
    DaemonStatusSubcommand(),
    DaemonInstallSubcommand(),
    DaemonUninstallSubcommand(),
    DaemonLogSubcommand()
)

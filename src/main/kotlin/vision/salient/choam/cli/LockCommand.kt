package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.sync.SourceGuard
import java.io.File

class LockCommand : CliktCommand(
    name = "lock",
    help = """
        Inspect and manage SourceGuard lock files (.choam_lock).

        Lock files protect source files during transfers. This command lists active
        locks, inspects individual locks, and cleans up stale locks from dead processes.

        Examples:
          choam lock                          List all active locks
          choam lock ~/data/file.db           Inspect lock for a specific file
          choam lock --clean                  Remove all stale locks (dead PIDs)
          choam lock --clean ~/data/file.db   Remove stale lock for specific file
          choam lock --force-clean ~/data/f   Force-remove lock even if PID is alive
    """.trimIndent()
) {
    private val path by argument(help = "File path to inspect/clean lock for").optional()
    private val clean by option("--clean", "-c", help = "Remove stale locks from dead processes").flag()
    private val forceClean by option("--force-clean", help = "Force-remove lock even if owning process is alive").flag()

    override fun run() {
        when {
            forceClean && path != null -> forceCleanLock(path!!)
            clean && path != null -> cleanLock(path!!)
            clean -> cleanAllLocks()
            path != null -> inspectLock(path!!)
            else -> listLocks()
        }
    }

    private fun inspectLock(filePath: String) {
        val lockFile = File("$filePath.choam_lock")
        if (!lockFile.exists()) {
            echo("No lock file for: $filePath")
            return
        }
        displayLock(lockFile, filePath)
    }

    private fun displayLock(lockFile: File, sourcePath: String) {
        val content = lockFile.readText()
        val pid = SourceGuard.parseLockPid(lockFile)
        val alive = pid?.let { SourceGuard.isProcessAlive(it) } ?: false
        val transferId = Regex(""""transfer_id"\s*:\s*"([^"]+)"""").find(content)?.groupValues?.get(1) ?: "unknown"
        val started = Regex(""""started"\s*:\s*"([^"]+)"""").find(content)?.groupValues?.get(1) ?: "unknown"
        val mode = Regex(""""mode"\s*:\s*"([^"]+)"""").find(content)?.groupValues?.get(1) ?: "unknown"

        val status = if (alive) "\u001b[32mALIVE\u001b[0m" else "\u001b[31mDEAD\u001b[0m"
        val sourceFile = File(sourcePath)
        val name = sourceFile.name

        echo("  $name")
        echo("    PID:      $pid ($status)")
        echo("    Transfer: $transferId")
        echo("    Mode:     $mode")
        echo("    Started:  $started")
        echo("    Lock:     ${lockFile.absolutePath}")
    }

    private fun findLockFiles(): List<Pair<File, String>> {
        val home = File(System.getProperty("user.home"))
        val config = runCatching { ChoamConfigLoader.load() }.getOrNull()
        val configuredPaths = buildList {
            addAll(config?.lockSearchPaths.orEmpty())
            addAll(config?.repositories?.values?.map { it.localPath }.orEmpty())
            addAll(config?.machines?.values?.flatMap { it.repositories.values }.orEmpty())
        }
        val defaultPaths = listOf("Desktop", "Documents", "Downloads")
        val searchDirs = (defaultPaths.map { File(home, it) } + configuredPaths.map { configured ->
            val expanded = if (configured == "~") {
                home.path
            } else if (configured.startsWith("~/")) {
                File(home, configured.removePrefix("~/")).path
            } else {
                configured
            }
            File(expanded)
        }).filter { it.isDirectory }.distinctBy { it.absoluteFile.normalize().path }.toMutableList()

        // External drives at /Volumes
        val volumes = File("/Volumes")
        if (volumes.exists()) {
            volumes.listFiles()?.filter { it.isDirectory }?.let { searchDirs.addAll(it) }
        }

        val locks = mutableListOf<Pair<File, String>>()
        for (dir in searchDirs) {
            try {
                dir.walkTopDown()
                    .maxDepth(6)
                    .filter { it.name.endsWith(".choam_lock") }
                    .forEach { lockFile ->
                        val sourcePath = lockFile.absolutePath.removeSuffix(".choam_lock")
                        locks.add(lockFile to sourcePath)
                    }
            } catch (_: Exception) { /* permission denied, etc. */ }
        }
        return locks
    }

    private fun listLocks() {
        val locks = findLockFiles()

        if (locks.isEmpty()) {
            echo("No active lock files found.")
            return
        }

        var aliveCount = 0
        var staleCount = 0

        echo("SourceGuard locks:\n")
        for ((lockFile, sourcePath) in locks) {
            val pid = SourceGuard.parseLockPid(lockFile)
            val alive = pid?.let { SourceGuard.isProcessAlive(it) } ?: false
            if (alive) aliveCount++ else staleCount++
            displayLock(lockFile, sourcePath)
            echo("")
        }

        echo("${locks.size} lock(s): $aliveCount active, $staleCount stale")
        if (staleCount > 0) {
            echo("Run \u001b[33mchoam lock --clean\u001b[0m to remove stale locks.")
        }
    }

    private fun cleanLock(filePath: String) {
        val lockFile = File("$filePath.choam_lock")
        if (!lockFile.exists()) {
            echo("No lock file for: $filePath")
            return
        }

        val pid = SourceGuard.parseLockPid(lockFile)
        val alive = pid?.let { SourceGuard.isProcessAlive(it) } ?: false

        if (alive) {
            echo("Lock is held by live process (PID $pid) — not removing.")
            echo("Use --force-clean to remove locks from live processes.")
            return
        }

        lockFile.delete()
        echo("Removed stale lock: ${lockFile.name} (PID $pid was dead)")
    }

    private fun forceCleanLock(filePath: String) {
        val lockFile = File("$filePath.choam_lock")
        if (!lockFile.exists()) {
            echo("No lock file for: $filePath")
            return
        }

        val pid = SourceGuard.parseLockPid(lockFile)
        val alive = pid?.let { SourceGuard.isProcessAlive(it) } ?: false

        lockFile.delete()
        if (alive) {
            echo("\u001b[33m⚠\u001b[0m Force-removed lock from LIVE process (PID $pid): ${lockFile.name}")
            echo("  The owning transfer may now produce corrupt results.")
        } else {
            echo("Removed stale lock: ${lockFile.name} (PID $pid was dead)")
        }
    }

    private fun cleanAllLocks() {
        val locks = findLockFiles()
        var removed = 0
        var skipped = 0

        for ((lockFile, _) in locks) {
            val pid = SourceGuard.parseLockPid(lockFile)
            val alive = pid?.let { SourceGuard.isProcessAlive(it) } ?: false

            if (!alive) {
                lockFile.delete()
                echo("  Removed: ${lockFile.absolutePath} (PID $pid was dead)")
                removed++
            } else {
                echo("  Skipped: ${lockFile.absolutePath} (PID $pid is alive)")
                skipped++
            }
        }

        if (removed == 0 && skipped == 0) {
            echo("No lock files found.")
        } else {
            echo("\nCleaned $removed stale lock(s), skipped $skipped active lock(s).")
        }
    }
}

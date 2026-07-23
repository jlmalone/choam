package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import java.io.File
import java.net.InetAddress
import java.sql.DriverManager
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.dag.ConfigResolver
import vision.salient.choam.config.MachineProfile
import vision.salient.choam.drive.DriveDetector
import vision.salient.choam.network.ProgressMonitor
import vision.salient.choam.sync.SyncHistoryStore

class StatusCommand : CliktCommand(
    name = "status",
    help = """
        Show a comprehensive dashboard of drives, repositories, machines, and catalog state.

        Displays four sections: (1) Drives — mount status and free/total space, (2) Repositories — local size, remote machines, and last sync timestamp, (3) Machines — reachability via ping (Tailscale IP or hostname), (4) Catalog — per-machine file counts from the unified registry with staleness warnings.

        Key behaviors:
          - Pings remote machines with a 2-second timeout
          - Calculates local repository sizes by walking the directory tree
          - Flags catalog data as [stale] if last synced over 30 days ago
          - Auto-detects the local machine from hostname

        Safety: Read-only. No files or remotes are modified.

        Examples:
          choam status
    """.trimIndent()
) {
    override fun run() {
        val config = try {
            ConfigResolver.resolve()
        } catch (e: Exception) {
            echo("Failed to load CHOAM config: ${e.message}")
            return
        }

        // Determine local machine
        val hostname = try {
            InetAddress.getLocalHost().hostName
        } catch (e: Exception) {
            "unknown"
        }
        val localMachineEntry = config.machines.entries
            .find { it.value.hostname == hostname || it.value.hostname.startsWith(hostname) }
        val localName = localMachineEntry?.key ?: hostname

        echo("CHOAM Status — $localName (local)")
        echo()

        // === DRIVES ===
        if (config.drives.isNotEmpty()) {
            echo("Drives:")
            val driveDetector = DriveDetector()
            val mountedDrives = driveDetector.detectConfiguredDrives(config.drives)

            // Cache remote drive checks per machine to avoid multiple SSH calls.
            // Uses containsKey() instead of getOrPut() because getOrPut treats cached null as missing.
            val remoteDriveCache = mutableMapOf<String, List<String>?>() // machine name -> drive labels (null = unreachable)

            for ((key, drive) in config.drives) {
                val mounted = mountedDrives[key]
                val classTag = "\u001b[35m[${drive.storageClass}]\u001b[0m"
                if (mounted != null) {
                    val free = ProgressMonitor.formatBytes(mounted.freeSpace)
                    val total = ProgressMonitor.formatBytes(mounted.totalSpace)
                    echo("  ${drive.label.padEnd(20)} $classTag \u001b[32mMOUNTED\u001b[0m at ${mounted.mountPoint} ($free free / $total)")
                } else {
                    // Drive not local — check which remote machine owns it
                    val owner = deriveDriveOwner(drive.label, config.machines)
                    if (owner != null && owner != localName) {
                        val machine = config.machines[owner]
                        if (machine != null) {
                            val remoteDrives = if (remoteDriveCache.containsKey(owner)) {
                                remoteDriveCache[owner]
                            } else {
                                val result = if (checkReachability(machine)) checkRemoteDrives(machine) else null
                                remoteDriveCache[owner] = result
                                result
                            }
                            when {
                                remoteDrives == null ->
                                    echo("  ${drive.label.padEnd(20)} $classTag \u001b[33m$owner unreachable\u001b[0m")
                                drive.label in remoteDrives -> {
                                    val freeSpace = checkRemoteDriveFreeSpace(machine, drive.label)
                                    val freeStr = if (freeSpace != null) " (${ProgressMonitor.formatBytes(freeSpace)} free)" else ""
                                    echo("  ${drive.label.padEnd(20)} $classTag \u001b[32mMOUNTED on $owner\u001b[0m$freeStr")
                                }
                                else ->
                                    echo("  ${drive.label.padEnd(20)} $classTag \u001b[31mNOT MOUNTED\u001b[0m on $owner")
                            }
                        } else {
                            echo("  ${drive.label.padEnd(20)} $classTag \u001b[31mNOT MOUNTED\u001b[0m")
                        }
                    } else {
                        echo("  ${drive.label.padEnd(20)} $classTag \u001b[31mNOT MOUNTED\u001b[0m")
                    }
                }
            }
            echo()
        }

        // === REPOSITORIES ===
        echo("Repositories:")
        val historyStore = SyncHistoryStore()

        for ((repoName, repoConfig) in config.repositories) {
            val localPath = localMachineEntry?.value?.repositories?.get(repoName)
            val localSize = if (localPath != null) {
                val dir = File(localPath)
                if (dir.exists()) {
                    val size = calculateDirSize(dir)
                    ProgressMonitor.formatBytes(size)
                } else {
                    "missing"
                }
            } else {
                "not configured"
            }

            // Find remote machines for this repo
            val remotes = config.machines.values
                .filter { it.name != localName && it.repositories.containsKey(repoName) }

            val lastSync = historyStore.lastSyncForRepo(repoName)
            val lastSyncStr = if (lastSync != null) {
                val ts = lastSync.startTime.replace("T", " ").substringBefore(".")
                "\u001b[36m$ts\u001b[0m"
            } else {
                "\u001b[33mnever synced\u001b[0m"
            }

            val remoteSummary = if (remotes.isNotEmpty()) {
                remotes.joinToString(", ") { it.name }
            } else {
                "local only"
            }

            echo("  ${repoName.padEnd(16)} local: $localSize  →  $remoteSummary  last sync: $lastSyncStr")
        }
        echo()

        // === MACHINES ===
        echo("Machines:")
        for ((name, machine) in config.machines) {
            val isLocal = name == localName
            if (isLocal) {
                echo("  ${name.padEnd(20)} \u001b[32m✓ local\u001b[0m")
                continue
            }

            val reachable = checkReachability(machine)
            if (reachable) {
                val remoteDrives = checkRemoteDrives(machine)
                val driveInfo = if (remoteDrives != null && remoteDrives.isNotEmpty()) "  drives: ${remoteDrives.joinToString(", ")}" else ""
                echo("  ${name.padEnd(20)} \u001b[32m✓ reachable\u001b[0m" +
                    (if (machine.tailscaleIp != null) " (${machine.tailscaleIp})" else "") + driveInfo)
            } else {
                echo("  ${name.padEnd(20)} \u001b[31m✗ unreachable\u001b[0m")
            }
        }

        // === CATALOG ===
        // Build alias map for display remapping
        val machineNameMap = mutableMapOf<String, String>()
        for ((configKey, profile) in config.machines) {
            for (alias in profile.aliases) {
                machineNameMap[alias] = configKey
            }
        }
        showCatalogSection(machineNameMap)
    }

    private fun showCatalogSection(machineNameMap: Map<String, String>) {
        val unifiedDbPath = "${System.getProperty("user.home")}/.choam/unified_registry.db"
        val unifiedFile = File(unifiedDbPath)

        echo()
        echo("Catalog:")
        if (!unifiedFile.exists()) {
            echo("  No catalog synced yet. Run 'choam catalog-sync'")
            return
        }

        try {
            val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedDbPath")
            val rs = conn.createStatement().executeQuery(
                "SELECT machine_name, COUNT(*) as cnt, MAX(last_synced_at) as last_sync FROM content_locations GROUP BY machine_name"
            )
            var total = 0L
            var machineCount = 0
            while (rs.next()) {
                val rawName = rs.getString("machine_name")
                val displayName = machineNameMap[rawName] ?: rawName
                val count = rs.getLong("cnt")
                val lastSync = rs.getString("last_sync")?.replace("T", " ")?.substringBefore(".") ?: "unknown"
                total += count
                machineCount++
                val staleMarker = if (lastSync != "unknown" && GlobalSearchCommand.isStale(lastSync)) " \u001b[33m[stale]\u001b[0m" else ""
                echo("  ${displayName.padEnd(24)} ${"%,d".format(count)} files    last synced: \u001b[36m$lastSync\u001b[0m$staleMarker")
            }
            echo("  Total: ${"%,d".format(total)} files across $machineCount machines")
            rs.close()
            conn.close()
        } catch (e: Exception) {
            echo("  \u001b[31mError reading catalog: ${e.message}\u001b[0m")
        }
    }

    private fun calculateDirSize(dir: File): Long {
        return dir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    private fun checkReachability(machine: MachineProfile): Boolean {
        return try {
            val ip = machine.tailscaleIp ?: machine.hostname
            InetAddress.getByName(ip).isReachable(2000)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Derive which machine owns a drive by scanning repo paths for /Volumes/<label>/.
     */
    private fun deriveDriveOwner(driveLabel: String, machines: Map<String, MachineProfile>): String? {
        for ((name, machine) in machines) {
            if (machine.repositories.values.any { it.contains("/Volumes/$driveLabel/") }) {
                return name
            }
        }
        return null
    }

    /**
     * SSH to a remote machine and get free space for a specific drive via df.
     */
    private fun checkRemoteDriveFreeSpace(machine: MachineProfile, driveLabel: String): Long? {
        return try {
            val ip = machine.tailscaleIp ?: machine.hostname
            val user = machine.sshUser ?: return null
            val port = machine.sshPort ?: 22
            val cmd = listOf(
                "ssh", "-o", "ConnectTimeout=3", "-o", "StrictHostKeyChecking=no",
                "-p", port.toString(), "$user@$ip",
                "df -k '/Volumes/$driveLabel' 2>/dev/null | tail -1"
            )
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) { process.destroyForcibly(); return null }
            if (process.exitValue() != 0) return null
            val line = process.inputStream.bufferedReader().readText().trim()
            // df -k output: filesystem blocks used available capacity mount
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 4) parts[3].toLongOrNull()?.times(1024) else null
        } catch (_: Exception) { null }
    }

    /**
     * SSH to a remote machine and list mounted /Volumes/ drives.
     * Returns drive names on success, null on SSH failure/timeout/missing sshUser.
     * Callers MUST treat null as "unreachable", not "no drives mounted".
     */
    private fun checkRemoteDrives(machine: MachineProfile): List<String>? {
        return try {
            val ip = machine.tailscaleIp ?: machine.hostname
            val user = machine.sshUser ?: return null // No SSH user = can't check
            val port = machine.sshPort ?: 22
            val cmd = listOf(
                "ssh", "-o", "ConnectTimeout=3", "-o", "StrictHostKeyChecking=no",
                "-p", port.toString(), "$user@$ip",
                "ls /Volumes/ 2>/dev/null"
            )
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) { process.destroyForcibly(); return null } // Timeout = unreachable
            if (process.exitValue() != 0) return null // SSH error = unreachable
            process.inputStream.bufferedReader().readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "Macintosh HD" && !it.startsWith(".") }
        } catch (_: Exception) { null }
    }
}

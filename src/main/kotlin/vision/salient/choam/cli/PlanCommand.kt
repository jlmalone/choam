package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.dag.ConfigResolver
import vision.salient.choam.config.ReplicationPolicy
import vision.salient.choam.drive.DriveDetector
import vision.salient.choam.network.ProgressMonitor
import java.io.File
import java.sql.DriverManager

/**
 * Replication gap analysis — compares actual copy counts against policy.
 *
 * Queries unified_registry.db to count distinct machines holding files
 * for each repository, then compares against the configured ReplicationPolicy.
 * Shows under-replicated repos and recommends target machines with free space.
 */
class PlanCommand : CliktCommand(
    name = "plan",
    help = """
        Analyze replication gaps — shows which repositories need more copies.

        For each repository with a replication policy, counts distinct machines holding
        its files in the unified registry and compares against minCopies/preferredCopies.
        Recommends target machines with available free space for under-replicated repos.

        Key behaviors:
          - Reads unified_registry.db for actual copy counts per repo
          - Compares against ReplicationPolicy in config
          - Shows drive free space on reachable machines for recommendations
          - Use --verbose for per-CID breakdown

        Safety: Read-only. No files or remotes are modified.

        Examples:
          choam plan
          choam plan --verbose
    """.trimIndent()
) {
    private val verbose by option("--verbose", "-v", help = "Show per-CID replication details").flag()

    override fun run() {
        val config = try {
            ConfigResolver.resolve()
        } catch (e: Exception) {
            echo("Failed to load CHOAM config: ${e.message}")
            return
        }

        val unifiedDbPath = "${System.getProperty("user.home")}/.choam/unified_registry.db"
        if (!File(unifiedDbPath).exists()) {
            echo("No unified registry found. Run 'choam catalog-sync' first.")
            return
        }

        // Build alias map
        val machineNameMap = mutableMapOf<String, String>()
        for ((configKey, profile) in config.machines) {
            for (alias in profile.aliases) {
                machineNameMap[alias] = configKey
            }
        }

        // Build reverse map: which config repos map to which path prefixes per machine
        // We derive repo membership from file_path matching machine repo paths
        data class RepoLocationCount(
            val repoName: String,
            val machines: MutableSet<String> = mutableSetOf(),
            val totalFiles: Long = 0,
            val totalSize: Long = 0
        )

        val repoStats = mutableMapOf<String, RepoLocationCount>()

        try {
            val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedDbPath")

            // Get distinct (machine_name, file_path prefix) counts
            val rs = conn.createStatement().executeQuery(
                "SELECT machine_name, file_path, file_size FROM content_locations"
            )

            // For each file, determine which repo it belongs to based on path matching
            while (rs.next()) {
                val rawMachine = rs.getString("machine_name")
                val machine = machineNameMap[rawMachine] ?: rawMachine
                val filePath = rs.getString("file_path")
                val fileSize = rs.getLong("file_size")

                // Match file to repo via machine's repository paths
                val machineProfile = config.machines[machine]
                if (machineProfile != null) {
                    for ((repoName, repoPath) in machineProfile.repositories) {
                        if (filePath.startsWith(repoPath) || filePath.startsWith("/Volumes/")) {
                            // For /Volumes/ paths, match by drive repo mapping
                            val driveMatch = config.drives.values.any { drive ->
                                val drivePath = drive.repositories[repoName]
                                drivePath != null && filePath.contains("/${drive.label}/") &&
                                    filePath.contains(drivePath)
                            }
                            if (filePath.startsWith(repoPath) || driveMatch) {
                                val stats = repoStats.getOrPut(repoName) {
                                    RepoLocationCount(repoName)
                                }
                                stats.machines.add(machine)
                                break
                            }
                        }
                    }
                }
            }
            rs.close()

            // Also count by simpler heuristic: group by machine for repos that have ANY files
            // Fall back to counting distinct machines per repo name from drive config
            val repoCopyCounts = countRepoMachines(conn, config, machineNameMap)

            conn.close()

            // Display results
            echo("Replication Plan:")
            echo()

            if (config.repositories.isEmpty()) {
                echo("  No repositories configured.")
                return
            }

            // Get drive free space for recommendations
            val driveDetector = DriveDetector()
            val mountedDrives = driveDetector.detectConfiguredDrives(config.drives)
            val machineFreeSpace = mutableMapOf<String, Long>()

            // Local free space from mounted drives
            for ((_, mounted) in mountedDrives) {
                // Determine which machine owns this drive
                for ((machineName, machineProfile) in config.machines) {
                    for ((_, drive) in config.drives) {
                        if (drive.uuid == mounted.uuid) {
                            machineFreeSpace[machineName] = (machineFreeSpace[machineName] ?: 0) + mounted.freeSpace
                        }
                    }
                }
            }

            for ((repoName, repoConfig) in config.repositories) {
                val policy = repoConfig.replication
                val copies = repoCopyCounts[repoName] ?: emptySet()
                val copyCount = copies.size

                val status = when {
                    copyCount >= policy.preferredCopies -> "\u001b[32m$copyCount copies — meets preferred (${policy.preferredCopies})\u001b[0m"
                    copyCount >= policy.minCopies -> "\u001b[33m$copyCount copies — meets minimum (${policy.minCopies}) but below preferred (${policy.preferredCopies})\u001b[0m"
                    else -> "\u001b[31m$copyCount ${if (copyCount == 1) "copy" else "copies"} — needs ${policy.minCopies - copyCount} more (min: ${policy.minCopies})\u001b[0m"
                }

                val locationSummary = if (copies.isNotEmpty()) {
                    copies.joinToString(", ") { machine ->
                        val driveInfo = config.drives.entries
                            .filter { it.value.repositories.containsKey(repoName) }
                            .joinToString(", ") { it.value.label }
                        if (driveInfo.isNotEmpty()) "$machine/$driveInfo" else machine
                    }
                } else {
                    "no copies found"
                }

                echo("  ${repoName.padEnd(16)} $status")
                echo("    Locations: $locationSummary")

                // Show recommendations for under-replicated repos
                if (copyCount < policy.preferredCopies) {
                    val candidateMachines = config.machines.keys
                        .filter { it !in copies }
                        .filter { config.machines[it]?.repositories?.containsKey(repoName) == true }

                    if (candidateMachines.isNotEmpty()) {
                        for (candidate in candidateMachines) {
                            val freeSpace = machineFreeSpace[candidate]
                            val freeStr = if (freeSpace != null) "${ProgressMonitor.formatBytes(freeSpace)} free" else "space unknown"
                            echo("    \u001b[36mRecommendation: sync to $candidate ($freeStr)\u001b[0m")
                        }
                    }
                }
                echo()
            }

            if (verbose) {
                showVerboseCidBreakdown(unifiedDbPath, machineNameMap)
            }

        } catch (e: Exception) {
            echo("Error analyzing replication: ${e.message}")
        }
    }

    private fun showVerboseCidBreakdown(
        unifiedDbPath: String,
        machineNameMap: Map<String, String>
    ) {
        echo("Per-CID Replication Detail:")
        echo()

        try {
            val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedDbPath")
            val rs = conn.createStatement().executeQuery("""
                SELECT cid, COUNT(DISTINCT machine_name) as machine_count,
                       GROUP_CONCAT(DISTINCT machine_name) as machines,
                       MIN(file_path) as sample_path,
                       file_size
                FROM content_locations
                GROUP BY cid
                HAVING machine_count = 1
                ORDER BY file_size DESC
                LIMIT 20
            """)

            echo("  Single-copy CIDs (top 20 by size):")
            while (rs.next()) {
                val cid = rs.getString("cid")
                val rawMachines = rs.getString("machines")
                val machines = rawMachines.split(",").map { machineNameMap[it.trim()] ?: it.trim() }
                val samplePath = rs.getString("sample_path")
                val size = rs.getLong("file_size")
                val filename = samplePath.substringAfterLast("/")
                echo("    ${cid.take(16)}...  ${ProgressMonitor.formatBytes(size).padStart(10)}  ${machines.joinToString(",")}  $filename")
            }
            rs.close()
            conn.close()
        } catch (e: Exception) {
            echo("  Error loading CID details: ${e.message}")
        }
    }

    companion object {
        /**
         * Count distinct machines per repository by examining which machines have
         * the repo configured in their repositories map.
         * Then cross-reference with unified registry to see which actually have data.
         */
        fun countRepoMachines(
            conn: java.sql.Connection,
            config: vision.salient.choam.config.ChoamConfig,
            machineNameMap: Map<String, String>
        ): Map<String, Set<String>> {
            val result = mutableMapOf<String, MutableSet<String>>()

            // Get all distinct machine names from the registry
            val registryMachines = mutableSetOf<String>()
            val rs = conn.createStatement().executeQuery(
                "SELECT DISTINCT machine_name FROM content_locations"
            )
            while (rs.next()) {
                val raw = rs.getString("machine_name")
                registryMachines.add(machineNameMap[raw] ?: raw)
            }
            rs.close()

            // For each repo, a machine counts as having a copy if:
            // 1) The machine is configured for that repo, AND
            // 2) The machine has entries in the unified registry
            for ((repoName, _) in config.repositories) {
                val machinesWithRepo = config.machines.entries
                    .filter { it.value.repositories.containsKey(repoName) }
                    .map { it.key }
                    .filter { it in registryMachines }
                    .toMutableSet()

                result[repoName] = machinesWithRepo
            }

            return result
        }
    }
}

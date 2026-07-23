package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import mu.KotlinLogging
import vision.salient.choam.catalog.CatalogIndex
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.dag.ConfigResolver
import vision.salient.choam.network.NetworkDetector
import vision.salient.choam.network.ProgressMonitor
import vision.salient.choam.web.guessContentType
import java.io.File
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

/**
 * Deep drill-down on a single file or CID.
 *
 * Shows all copies, machines, paths, replication status, transfer estimates,
 * and IPFS gateway URLs. Supports both exact CID lookup and filename FTS search.
 */
class InspectCommand : CliktCommand(
    name = "inspect",
    help = """
        Deep drill-down on a file by CID or filename.

        Shows everything known about a piece of content: filename, size, content type,
        full CID, IPFS gateway URL, all copies across machines with paths and verification
        dates, replication status vs policy, and transfer speed estimates.

        Supports both exact CID lookup and filename search (FTS). When searching by
        filename, shows details for the top match.

        Key behaviors:
          - Queries unified_registry.db for all locations of the content
          - Shows estimated transfer time to each machine via NetworkDetector
          - Compares copy count against replication policy if applicable
          - Shows IPFS gateway URL for public access

        Safety: Read-only. No files or remotes are modified.

        Examples:
          choam inspect bafkreihdwdce...
          choam inspect "Aliens"
          choam inspect QmABC123
    """.trimIndent()
) {
    private val target by argument(help = "CID (exact) or filename (FTS search) to inspect")
    private val verbose by option("--verbose", "-v", help = "Show extra detail").flag()

    override fun run() {
        val config = try {
            ConfigResolver.resolve()
        } catch (e: Exception) {
            echo("Failed to load config: ${e.message}")
            return
        }

        val unifiedDbPath = "${System.getProperty("user.home")}/.choam/unified_registry.db"
        if (!File(unifiedDbPath).exists()) {
            echo("No unified registry found. Run 'choam catalog-sync' first.")
            return
        }

        val aliasMap = ReportCommand.buildAliasMap(config)

        // Determine if target is a CID (starts with bafy/Qm/bafk or is hex-like) or a filename search
        val isCid = target.startsWith("bafy") || target.startsWith("Qm") || target.startsWith("bafk") ||
            (target.length >= 32 && target.all { it.isLetterOrDigit() })

        if (isCid) {
            inspectByCid(unifiedDbPath, target, aliasMap, config)
        } else {
            inspectBySearch(unifiedDbPath, target, aliasMap, config)
        }
    }

    private fun inspectByCid(dbPath: String, cid: String, aliasMap: Map<String, String>, config: vision.salient.choam.config.ChoamConfig) {
        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")

        val stmt = conn.prepareStatement(
            "SELECT machine_name, file_path, file_size, last_synced_at FROM content_locations WHERE cid = ? ORDER BY machine_name"
        )
        stmt.setString(1, cid)
        val rs = stmt.executeQuery()

        data class CopyInfo(val machine: String, val path: String, val size: Long, val lastSynced: String)
        val copies = mutableListOf<CopyInfo>()

        while (rs.next()) {
            copies.add(CopyInfo(
                machine = aliasMap[rs.getString("machine_name")] ?: rs.getString("machine_name"),
                path = rs.getString("file_path"),
                size = rs.getLong("file_size"),
                lastSynced = rs.getString("last_synced_at") ?: "unknown"
            ))
        }
        rs.close()
        stmt.close()
        conn.close()

        if (copies.isEmpty()) {
            echo("CID not found: $cid")
            echo("Try searching by filename: choam inspect \"<filename>\"")
            return
        }

        val firstCopy = copies.first()
        val filename = firstCopy.path.substringAfterLast("/")
        val ext = filename.substringAfterLast(".", "")
        val contentType = guessContentType(filename)

        echo("$filename — ${ProgressMonitor.formatBytes(firstCopy.size)} — $contentType")
        echo("CID:  $cid")
        echo("IPFS: https://ipfs.io/ipfs/$cid")
        echo()

        // Group copies by machine to dedup paths on same machine
        val byMachine = copies.groupBy { it.machine }
        val distinctMachines = byMachine.keys

        echo("Copies (${distinctMachines.size}):")
        var idx = 1
        for ((machine, machineCopies) in byMachine) {
            for (copy in machineCopies) {
                val driveLabel = extractDriveLabel(copy.path)
                val driveStr = if (driveLabel != null) "$machine/$driveLabel" else machine
                val verified = formatVerifiedDate(copy.lastSynced)
                echo("  $idx. ${driveStr.padEnd(25)} ${copy.path}")
                echo("     ${" ".repeat(25)} verified: $verified")
                idx++
            }
        }
        echo()

        // Replication policy check
        showReplicationStatus(config, aliasMap, distinctMachines, filename)

        // Transfer estimates
        showTransferEstimates(config, distinctMachines, firstCopy.size)
    }

    private fun inspectBySearch(dbPath: String, query: String, aliasMap: Map<String, String>, config: vision.salient.choam.config.ChoamConfig) {
        val indexDbPath = "${System.getProperty("user.home")}/.choam/catalog-index.db"
        if (!File(indexDbPath).exists()) {
            echo("No search index. Run 'choam rebuild-index' first.")
            return
        }

        val catalogIndex = CatalogIndex(indexDbPath)
        val conn = catalogIndex.open()
        val results = catalogIndex.search(conn, query, 1)
        conn.close()

        if (results.isEmpty()) {
            echo("No results for \"$query\"")
            return
        }

        val topResult = results.first()
        echo("Top match for \"$query\":")
        echo()

        if (topResult.cid.isNotEmpty()) {
            inspectByCid(dbPath, topResult.cid, aliasMap, config)
        } else {
            // No CID — show what we have
            echo("${topResult.filename} — ${ProgressMonitor.formatBytes(topResult.size)}")
            echo("  Machine: ${topResult.machine}")
            echo("  Drive:   ${topResult.driveLabel}")
            echo("  Path:    ${topResult.path}")
            echo("  CID:     (not indexed)")
            echo()
            echo("Run 'choam catalog-all' to compute CIDs for this content.")
        }
    }

    private fun showReplicationStatus(
        config: vision.salient.choam.config.ChoamConfig,
        aliasMap: Map<String, String>,
        currentMachines: Set<String>,
        filename: String
    ) {
        if (config.repositories.isEmpty()) return

        // Try to determine which repo this file belongs to
        for ((repoName, repoConfig) in config.repositories) {
            val policy = repoConfig.replication
            val repoMachines = config.machines.entries
                .filter { it.value.repositories.containsKey(repoName) }
                .map { it.key }
                .toSet()

            val copiesInRepo = currentMachines.intersect(repoMachines)
            if (copiesInRepo.isEmpty()) continue

            val icon = when {
                copiesInRepo.size >= policy.preferredCopies -> "\u001b[32m✓\u001b[0m"
                copiesInRepo.size >= policy.minCopies -> "\u001b[33m~\u001b[0m"
                else -> "\u001b[31m✗\u001b[0m"
            }
            echo("Replication ($repoName): $icon ${copiesInRepo.size}/${policy.preferredCopies} copies (min: ${policy.minCopies})")

            // Show machines missing this file
            val missingMachines = repoMachines - currentMachines
            if (missingMachines.isNotEmpty()) {
                echo("  Missing from: ${missingMachines.joinToString(", ")}")
            }
            echo()
            break // Only show first matching repo
        }
    }

    private fun showTransferEstimates(
        config: vision.salient.choam.config.ChoamConfig,
        currentMachines: Set<String>,
        fileSize: Long
    ) {
        val allMachines = config.machines.keys
        val missingMachines = allMachines - currentMachines
        if (missingMachines.isEmpty()) return

        echo("Transfer Estimates:")
        val detector = NetworkDetector()

        for (targetName in missingMachines) {
            val targetProfile = config.machines[targetName] ?: continue
            val ip = targetProfile.tailscaleIp ?: targetProfile.hostname

            // Estimate bandwidth without actually pinging (use latency heuristic)
            val bandwidth = NetworkDetector.DEFAULT_BANDWIDTH_BYTES_PER_SEC
            val seconds = fileSize / bandwidth
            val timeStr = formatDuration(seconds)

            echo("  → $targetName ($ip): ~$timeStr at ~${ProgressMonitor.formatBytes(bandwidth)}/s")
            echo("    Run: choam push --to $targetName")
        }
        echo()
    }

    companion object {
        fun extractDriveLabel(path: String): String? {
            if (!path.startsWith("/Volumes/")) return null
            val parts = path.removePrefix("/Volumes/").split("/")
            return if (parts.isNotEmpty()) parts[0] else null
        }

        fun formatVerifiedDate(lastSynced: String): String {
            return try {
                val syncTime = LocalDateTime.parse(
                    lastSynced.replace(" ", "T").substringBefore("."),
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME
                )
                syncTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            } catch (_: Exception) {
                lastSynced.substringBefore(" ").substringBefore("T")
            }
        }

        fun formatDuration(seconds: Long): String {
            return when {
                seconds < 60 -> "${seconds}s"
                seconds < 3600 -> "${seconds / 60} min"
                else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
            }
        }
    }
}

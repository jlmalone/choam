package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import mu.KotlinLogging
import vision.salient.choam.config.ChoamConfig
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.dag.ConfigResolver
import vision.salient.choam.config.StorageClass
import vision.salient.choam.drive.DriveDetector
import vision.salient.choam.network.NetworkDetector
import vision.salient.choam.network.ProgressMonitor
import java.io.File
import java.net.InetAddress
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}

/**
 * Health dashboard — comprehensive report on data coverage, risk, staleness,
 * replication, geographic spread, and content-class distribution.
 */
class ReportCommand : CliktCommand(
    name = "report",
    help = """
        Generate a comprehensive health report for all CHOAM-managed data.

        Analyzes the unified registry and config to produce a dashboard covering:
        coverage (total files/machines), replication status vs policy, at-risk files
        (single-copy, large), staleness per machine, geographic diversification scoring,
        content-class distribution, dedup-aware copy counting, and recommendations.

        Key behaviors:
          - Reads unified_registry.db for all metrics (no network required)
          - Cross-references replication policies from config
          - Identifies single-copy files over 100MB as at-risk
          - Scores geographic spread per CID
          - Classifies content by extension into media/document/archive/code/other
          - Dedup-aware: same CID on multiple machines = multiple copies

        Safety: Read-only. No files or remotes are modified.

        Examples:
          choam report
          choam report --verbose
    """.trimIndent()
) {
    private val verbose by option("--verbose", "-v", help = "Show detailed breakdowns").flag()

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

        val machineNameMap = buildAliasMap(config)

        try {
            val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedDbPath")

            val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            echo("CHOAM Health Report — $now")
            echo("${"=".repeat(50)}")
            echo()

            showCoverageSection(conn, machineNameMap)
            showReplicationSection(conn, config, machineNameMap)
            showCopyDistributionSection(conn)
            showRiskSection(conn, machineNameMap)
            showStalenessSection(conn, config, machineNameMap)
            showTransferSpeedSection(config)
            showGeoDiversitySection(conn, machineNameMap)
            showContentClassSection(conn)
            showDedupSection(conn, machineNameMap)
            showRecommendations(conn, config, machineNameMap)

            conn.close()
        } catch (e: Exception) {
            echo("Error generating report: ${e.message}")
            logger.error(e) { "Report generation failed" }
        }
    }

    private fun showCoverageSection(conn: Connection, aliasMap: Map<String, String>) {
        echo("Coverage:")

        val rs = conn.createStatement().executeQuery("""
            SELECT COUNT(*) as total_files,
                   COUNT(DISTINCT cid) as unique_cids,
                   SUM(file_size) as total_size,
                   COUNT(DISTINCT machine_name) as machine_count
            FROM content_locations
        """)
        rs.next()
        val totalFiles = rs.getLong("total_files")
        val uniqueCids = rs.getLong("unique_cids")
        val totalSize = rs.getLong("total_size")
        val machineCount = rs.getInt("machine_count")
        rs.close()

        echo("  ${"%,d".format(totalFiles)} file entries across $machineCount machines")
        echo("  ${"%,d".format(uniqueCids)} unique CIDs (${ProgressMonitor.formatBytes(totalSize)} total)")

        // CIDs with 2+ copies
        val multiRs = conn.createStatement().executeQuery("""
            SELECT COUNT(*) as multi_copy FROM (
                SELECT cid FROM content_locations GROUP BY cid HAVING COUNT(DISTINCT machine_name) >= 2
            )
        """)
        multiRs.next()
        val multiCopy = multiRs.getLong("multi_copy")
        multiRs.close()

        val pct = if (uniqueCids > 0) "%.1f%%".format(multiCopy * 100.0 / uniqueCids) else "0%"
        echo("  ${"%,d".format(multiCopy)} CIDs have 2+ copies ($pct safely backed up)")
        echo()
    }

    private fun showReplicationSection(conn: Connection, config: ChoamConfig, aliasMap: Map<String, String>) {
        if (config.repositories.isEmpty()) return

        echo("Replication:")
        val repoCopies = PlanCommand.countRepoMachines(conn, config, aliasMap)

        for ((repoName, repoConfig) in config.repositories) {
            val policy = repoConfig.replication
            val copies = repoCopies[repoName] ?: emptySet()
            val copyCount = copies.size
            val icon = when {
                copyCount >= policy.preferredCopies -> "\u001b[32m✓\u001b[0m"
                copyCount >= policy.minCopies -> "\u001b[33m~\u001b[0m"
                else -> "\u001b[31m✗\u001b[0m"
            }
            echo("  $icon ${repoName.padEnd(16)} $copyCount/${policy.preferredCopies} copies (min: ${policy.minCopies})")
        }
        echo()
    }

    private fun showRiskSection(conn: Connection, aliasMap: Map<String, String>) {
        echo("Risk (single-copy files >100MB):")

        val rs = conn.createStatement().executeQuery("""
            SELECT COUNT(*) as at_risk_count, COALESCE(SUM(file_size), 0) as at_risk_size
            FROM (
                SELECT cid, MAX(file_size) as file_size
                FROM content_locations
                WHERE file_size > 104857600
                GROUP BY cid
                HAVING COUNT(DISTINCT machine_name) = 1
            )
        """)
        rs.next()
        val atRiskCount = rs.getLong("at_risk_count")
        val atRiskSize = rs.getLong("at_risk_size")
        rs.close()

        if (atRiskCount == 0L) {
            echo("  \u001b[32mNo single-copy files over 100MB — looking good!\u001b[0m")
        } else {
            echo("  \u001b[31m${"%,d".format(atRiskCount)} files (${ProgressMonitor.formatBytes(atRiskSize)}) at risk\u001b[0m")

            if (verbose) {
                // Show top 10 at-risk files
                val detailRs = conn.createStatement().executeQuery("""
                    SELECT cid, machine_name, file_path, file_size
                    FROM content_locations
                    WHERE cid IN (
                        SELECT cid FROM content_locations
                        WHERE file_size > 104857600
                        GROUP BY cid HAVING COUNT(DISTINCT machine_name) = 1
                    )
                    ORDER BY file_size DESC
                    LIMIT 10
                """)
                echo("  Top at-risk:")
                while (detailRs.next()) {
                    val machine = aliasMap[detailRs.getString("machine_name")] ?: detailRs.getString("machine_name")
                    val path = detailRs.getString("file_path")
                    val size = detailRs.getLong("file_size")
                    val filename = path.substringAfterLast("/")
                    echo("    ${ProgressMonitor.formatBytes(size).padStart(10)}  $machine  $filename")
                }
                detailRs.close()
            }
        }
        echo()
    }

    private fun showStalenessSection(conn: Connection, config: ChoamConfig, aliasMap: Map<String, String>) {
        echo("Staleness:")

        val rs = conn.createStatement().executeQuery(
            "SELECT machine_name, MAX(last_synced_at) as last_sync, COUNT(*) as cnt FROM content_locations GROUP BY machine_name"
        )

        while (rs.next()) {
            val rawName = rs.getString("machine_name")
            val displayName = aliasMap[rawName] ?: rawName
            val lastSync = rs.getString("last_sync") ?: "unknown"
            val count = rs.getLong("cnt")

            val (agoStr, staleColor) = if (lastSync != "unknown") {
                try {
                    val syncTime = LocalDateTime.parse(lastSync.replace(" ", "T").substringBefore("."))
                    val daysSince = ChronoUnit.DAYS.between(syncTime, LocalDateTime.now())
                    val hoursSince = ChronoUnit.HOURS.between(syncTime, LocalDateTime.now())
                    val ago = when {
                        hoursSince < 1 -> "just now"
                        hoursSince < 24 -> "${hoursSince}h ago"
                        daysSince < 7 -> "${daysSince}d ago"
                        else -> "${daysSince}d ago"
                    }
                    val color = when {
                        daysSince > 30 -> "\u001b[31m" // red
                        daysSince > 7 -> "\u001b[33m"  // yellow
                        else -> "\u001b[32m"            // green
                    }
                    ago to color
                } catch (_: Exception) {
                    "unknown" to "\u001b[33m"
                }
            } else {
                "unknown" to "\u001b[33m"
            }

            echo("  ${displayName.padEnd(20)} ${"%,d".format(count)} files    last synced: $staleColor$agoStr\u001b[0m")
        }
        rs.close()
        echo()
    }

    private fun showGeoDiversitySection(conn: Connection, aliasMap: Map<String, String>) {
        echo("Geographic Diversity:")

        // Score each CID by number of distinct machines
        val rs = conn.createStatement().executeQuery("""
            SELECT
                COUNT(CASE WHEN mc = 1 THEN 1 END) as single_machine,
                COUNT(CASE WHEN mc = 2 THEN 1 END) as two_machines,
                COUNT(CASE WHEN mc >= 3 THEN 1 END) as three_plus
            FROM (
                SELECT cid, COUNT(DISTINCT machine_name) as mc
                FROM content_locations
                GROUP BY cid
            )
        """)
        rs.next()
        val single = rs.getLong("single_machine")
        val two = rs.getLong("two_machines")
        val threePlus = rs.getLong("three_plus")
        rs.close()

        val total = single + two + threePlus
        fun pct(n: Long) = if (total > 0) "%.1f%%".format(n * 100.0 / total) else "0%"

        echo("  1 machine:   ${"%,d".format(single)} CIDs (${pct(single)})")
        echo("  2 machines:  ${"%,d".format(two)} CIDs (${pct(two)})")
        echo("  3+ machines: ${"%,d".format(threePlus)} CIDs (${pct(threePlus)})")

        val avgSpread = if (total > 0) {
            val spreadRs = conn.createStatement().executeQuery(
                "SELECT AVG(mc) as avg_mc FROM (SELECT COUNT(DISTINCT machine_name) as mc FROM content_locations GROUP BY cid)"
            )
            spreadRs.next()
            val avg = spreadRs.getDouble("avg_mc")
            spreadRs.close()
            "%.2f".format(avg)
        } else "0"
        echo("  Average spread: $avgSpread machines per CID")
        echo()
    }

    private fun showContentClassSection(conn: Connection) {
        echo("Content Classes:")

        // Classify by extension
        val rs = conn.createStatement().executeQuery("""
            SELECT
                LOWER(
                    CASE
                        WHEN file_path LIKE '%.%'
                        THEN REPLACE(file_path, RTRIM(file_path, REPLACE(file_path, '.', '')), '')
                        ELSE ''
                    END
                ) as ext,
                COUNT(DISTINCT cid) as cid_count,
                SUM(file_size) as total_size
            FROM content_locations
            WHERE file_path LIKE '%.%'
            GROUP BY ext
            ORDER BY total_size DESC
        """)

        var mediaSize = 0L; var mediaCount = 0L
        var docSize = 0L; var docCount = 0L
        var archiveSize = 0L; var archiveCount = 0L
        var codeSize = 0L; var codeCount = 0L
        var otherSize = 0L; var otherCount = 0L

        val mediaExts = setOf("mkv", "mp4", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts",
            "mp3", "flac", "aac", "ogg", "wav", "m4a", "wma",
            "jpg", "jpeg", "png", "gif", "bmp", "tiff", "webp", "heic", "svg", "raw", "cr2", "nef")
        val docExts = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "csv", "epub")
        val archiveExts = setOf("zip", "tar", "gz", "bz2", "xz", "7z", "rar", "dmg", "iso", "img")
        val codeExts = setOf("kt", "java", "py", "js", "ts", "swift", "rs", "go", "c", "cpp", "h", "sh", "sql", "json", "xml", "yaml", "yml", "toml", "gradle", "md", "html", "css")

        while (rs.next()) {
            val ext = rs.getString("ext").removePrefix(".")
            val count = rs.getLong("cid_count")
            val size = rs.getLong("total_size")

            when (ext) {
                in mediaExts -> { mediaSize += size; mediaCount += count }
                in docExts -> { docSize += size; docCount += count }
                in archiveExts -> { archiveSize += size; archiveCount += count }
                in codeExts -> { codeSize += size; codeCount += count }
                else -> { otherSize += size; otherCount += count }
            }
        }
        rs.close()

        echo("  Media:    ${"%,d".format(mediaCount)} CIDs  ${ProgressMonitor.formatBytes(mediaSize).padStart(10)}")
        echo("  Document: ${"%,d".format(docCount)} CIDs  ${ProgressMonitor.formatBytes(docSize).padStart(10)}")
        echo("  Archive:  ${"%,d".format(archiveCount)} CIDs  ${ProgressMonitor.formatBytes(archiveSize).padStart(10)}")
        echo("  Code:     ${"%,d".format(codeCount)} CIDs  ${ProgressMonitor.formatBytes(codeSize).padStart(10)}")
        echo("  Other:    ${"%,d".format(otherCount)} CIDs  ${ProgressMonitor.formatBytes(otherSize).padStart(10)}")
        echo()
    }

    private fun showDedupSection(conn: Connection, aliasMap: Map<String, String>) {
        echo("Deduplication:")

        // CIDs that appear on multiple paths (same machine, different paths = renamed/copied)
        val rs = conn.createStatement().executeQuery("""
            SELECT COUNT(*) as dup_cids, SUM(extra_copies) as wasted_entries
            FROM (
                SELECT cid, COUNT(*) - 1 as extra_copies
                FROM content_locations
                GROUP BY cid
                HAVING COUNT(*) > COUNT(DISTINCT machine_name)
            )
        """)
        rs.next()
        val dupCids = rs.getLong("dup_cids")
        val wastedEntries = rs.getLong("wasted_entries")
        rs.close()

        if (dupCids == 0L) {
            echo("  No cross-path duplicates detected on same machine")
        } else {
            echo("  ${"%,d".format(dupCids)} CIDs appear at multiple paths on same machine (${"%,d".format(wastedEntries)} extra entries)")
        }

        // True dedup: same CID across machines (good — means replication is working)
        val crossRs = conn.createStatement().executeQuery("""
            SELECT COUNT(*) as cross_dup FROM (
                SELECT cid FROM content_locations
                GROUP BY cid HAVING COUNT(DISTINCT machine_name) > 1
            )
        """)
        crossRs.next()
        val crossDup = crossRs.getLong("cross_dup")
        crossRs.close()

        echo("  ${"%,d".format(crossDup)} CIDs replicated across machines (healthy dedup)")
        echo()
    }

    private fun showCopyDistributionSection(conn: Connection) {
        echo("Copy Distribution:")

        val rs = conn.createStatement().executeQuery("""
            SELECT mc, COUNT(*) as cid_count FROM (
                SELECT cid, COUNT(DISTINCT machine_name) as mc
                FROM content_locations GROUP BY cid
            ) GROUP BY mc ORDER BY mc
        """)

        while (rs.next()) {
            val copies = rs.getInt("mc")
            val count = rs.getLong("cid_count")
            val label = if (copies == 1) "1 copy " else "$copies copies"
            echo("  $label: ${"%,d".format(count)} CIDs")
        }
        rs.close()

        // Top 5 largest single-copy files
        val topRs = conn.createStatement().executeQuery("""
            SELECT cl.cid, cl.machine_name, cl.file_path, cl.file_size
            FROM content_locations cl
            INNER JOIN (
                SELECT cid FROM content_locations
                GROUP BY cid HAVING COUNT(DISTINCT machine_name) = 1
            ) single ON cl.cid = single.cid
            ORDER BY cl.file_size DESC
            LIMIT 5
        """)

        echo("  Top 5 largest single-copy files:")
        while (topRs.next()) {
            val path = topRs.getString("file_path")
            val size = topRs.getLong("file_size")
            val machine = topRs.getString("machine_name")
            val filename = path.substringAfterLast("/")
            echo("    ${ProgressMonitor.formatBytes(size).padStart(10)}  $machine  $filename")
            echo("      CID: ${topRs.getString("cid")}")
        }
        topRs.close()
        echo()
    }

    private fun showTransferSpeedSection(config: ChoamConfig) {
        echo("Transfer Speeds (estimated):")

        for ((name, machine) in config.machines) {
            val ip = machine.tailscaleIp ?: machine.hostname
            val bandwidth = NetworkDetector.DEFAULT_BANDWIDTH_BYTES_PER_SEC
            echo("  ${name.padEnd(20)} ${ip.padEnd(18)} ~${ProgressMonitor.formatBytes(bandwidth)}/s (${machine.networkPreference})")
        }
        echo()
    }

    private fun showRecommendations(conn: Connection, config: ChoamConfig, aliasMap: Map<String, String>) {
        echo("Recommendations:")

        var recCount = 0

        // 1. Under-replicated repos
        val repoCopies = PlanCommand.countRepoMachines(conn, config, aliasMap)
        for ((repoName, repoConfig) in config.repositories) {
            val copies = repoCopies[repoName] ?: emptySet()
            if (copies.size < repoConfig.replication.minCopies) {
                recCount++
                val need = repoConfig.replication.minCopies - copies.size
                echo("  $recCount. \u001b[31mURGENT:\u001b[0m Sync '$repoName' — needs $need more cop${if (need == 1) "y" else "ies"} (has ${copies.size}/${repoConfig.replication.minCopies})")
            }
        }

        // 2. Stale machines
        val staleRs = conn.createStatement().executeQuery(
            "SELECT machine_name, MAX(last_synced_at) as last_sync FROM content_locations GROUP BY machine_name"
        )
        while (staleRs.next()) {
            val rawName = staleRs.getString("machine_name")
            val displayName = aliasMap[rawName] ?: rawName
            val lastSync = staleRs.getString("last_sync") ?: continue
            try {
                val syncTime = LocalDateTime.parse(lastSync.replace(" ", "T").substringBefore("."))
                val daysSince = ChronoUnit.DAYS.between(syncTime, LocalDateTime.now())
                if (daysSince > 30) {
                    recCount++
                    echo("  $recCount. Run 'choam catalog-sync --from $displayName' (${daysSince}d stale)")
                }
            } catch (_: Exception) {}
        }
        staleRs.close()

        // 3. Large at-risk content
        val riskRs = conn.createStatement().executeQuery("""
            SELECT COALESCE(SUM(file_size), 0) as risk_size FROM (
                SELECT cid, MAX(file_size) as file_size
                FROM content_locations WHERE file_size > 104857600
                GROUP BY cid HAVING COUNT(DISTINCT machine_name) = 1
            )
        """)
        riskRs.next()
        val riskSize = riskRs.getLong("risk_size")
        riskRs.close()

        if (riskSize > 0) {
            recCount++
            echo("  $recCount. ${ProgressMonitor.formatBytes(riskSize)} of large files have only 1 copy — consider replicating")
        }

        // 4. Pending copy requests
        RequestCopyCommand.ensureCopyRequestsTable(conn)
        val pendingRs = conn.createStatement().executeQuery(
            "SELECT COUNT(*) as cnt FROM copy_requests WHERE status = 'pending'"
        )
        pendingRs.next()
        val pending = pendingRs.getInt("cnt")
        pendingRs.close()

        if (pending > 0) {
            recCount++
            echo("  $recCount. $pending pending copy request(s) — run 'choam fulfill' to execute")
        }

        // 5. Junk awaiting purge
        ensureJunkTable(conn)
        val junkRs = conn.createStatement().executeQuery("SELECT COUNT(*) as cnt FROM junk_list")
        junkRs.next()
        val junkCount = junkRs.getInt("cnt")
        junkRs.close()

        if (junkCount > 0) {
            recCount++
            echo("  $recCount. $junkCount item(s) marked as junk — run 'choam junk purge' to clean up")
        }

        if (recCount == 0) {
            echo("  \u001b[32mAll clear — no issues detected.\u001b[0m")
        }
        echo()
    }

    companion object {
        fun buildAliasMap(config: ChoamConfig): Map<String, String> {
            val map = mutableMapOf<String, String>()
            for ((configKey, profile) in config.machines) {
                for (alias in profile.aliases) {
                    map[alias] = configKey
                }
            }
            return map
        }
    }
}

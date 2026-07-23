package vision.salient.choam.web

import kotlinx.html.*
import vision.salient.choam.cli.PlanCommand
import vision.salient.choam.cli.ReportCommand
import vision.salient.choam.cli.RequestCopyCommand
import vision.salient.choam.cli.ensureJunkTable
import vision.salient.choam.config.ChoamConfig
import vision.salient.choam.network.NetworkDetector
import vision.salient.choam.network.ProgressMonitor
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Web report page — 8-section health dashboard.
 * Coverage, replication, copy distribution, risk, staleness, transfer speeds,
 * geo diversity, content classes, dedup, recommendations.
 */
fun HTML.reportPage(config: ChoamConfig) = layout("Report", "report") {
    h1 { +"Health Report" }

    val unifiedDbPath = "${System.getProperty("user.home")}/.choam/unified_registry.db"
    if (!File(unifiedDbPath).exists()) {
        p("status-warn") { +"No unified registry found. Run 'choam catalog-sync' first." }
        return@layout
    }

    val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    p("detail") { +"Generated: $now" }

    // Each section loads independently via HTMX
    for ((endpoint, label) in listOf(
        "/htmx/report/coverage" to "Coverage",
        "/htmx/report/replication" to "Replication",
        "/htmx/report/copy-distribution" to "Copy Distribution",
        "/htmx/report/risk" to "Risk",
        "/htmx/report/staleness" to "Staleness",
        "/htmx/report/transfer-speeds" to "Transfer Speeds",
        "/htmx/report/geo-diversity" to "Geographic Diversity",
        "/htmx/report/content-classes" to "Content Classes",
        "/htmx/report/dedup" to "Deduplication",
        "/htmx/report/recommendations" to "Recommendations"
    )) {
        div {
            attributes["hx-get"] = endpoint
            attributes["hx-trigger"] = "load"
            attributes["hx-swap"] = "innerHTML"
            h2 { +label }
            p("detail") { +"Loading..." }
        }
    }
}

/**
 * HTMX fragment renderers — each opens its own DB connection, renders, closes.
 */
fun HTML.reportCoverageFragment() {
    body {
        val conn = openUnifiedDb() ?: return@body
        reportCoverage(conn)
        conn.close()
    }
}

fun HTML.reportReplicationFragment(config: ChoamConfig) {
    body {
        val conn = openUnifiedDb() ?: return@body
        val aliasMap = ReportCommand.buildAliasMap(config)
        reportReplication(conn, config, aliasMap)
        conn.close()
    }
}

fun HTML.reportCopyDistributionFragment() {
    body {
        val conn = openUnifiedDb() ?: return@body
        reportCopyDistribution(conn)
        conn.close()
    }
}

fun HTML.reportRiskFragment() {
    body {
        val conn = openUnifiedDb() ?: return@body
        reportRisk(conn)
        conn.close()
    }
}

fun HTML.reportStalenessFragment() {
    body {
        val conn = openUnifiedDb() ?: return@body
        val aliasMap = ReportCommand.buildAliasMap(
            try { vision.salient.choam.dag.ConfigResolver.resolve() } catch (_: Exception) {
                conn.close(); return@body
            }
        )
        reportStaleness(conn, aliasMap)
        conn.close()
    }
}

fun HTML.reportTransferSpeedsFragment(config: ChoamConfig) {
    body { reportTransferSpeeds(config) }
}

fun HTML.reportGeoDiversityFragment() {
    body {
        val conn = openUnifiedDb() ?: return@body
        reportGeoDiversity(conn)
        conn.close()
    }
}

fun HTML.reportContentClassesFragment() {
    body {
        val conn = openUnifiedDb() ?: return@body
        reportContentClasses(conn)
        conn.close()
    }
}

fun HTML.reportDedupFragment() {
    body {
        val conn = openUnifiedDb() ?: return@body
        reportDedup(conn)
        conn.close()
    }
}

fun HTML.reportRecommendationsFragment(config: ChoamConfig) {
    body {
        val conn = openUnifiedDb() ?: return@body
        val aliasMap = ReportCommand.buildAliasMap(config)
        reportRecommendations(conn, config, aliasMap)
        conn.close()
    }
}

private fun openUnifiedDb(): Connection? {
    val dbPath = "${System.getProperty("user.home")}/.choam/unified_registry.db"
    if (!File(dbPath).exists()) return null
    return try {
        DriverManager.getConnection("jdbc:sqlite:$dbPath")
    } catch (_: Exception) { null }
}

private fun FlowContent.reportCoverage(conn: Connection) {
    h2 { +"Coverage" }
    val rs = conn.createStatement().executeQuery("""
        SELECT COUNT(*) as total_files, COUNT(DISTINCT cid) as unique_cids,
               COALESCE(SUM(file_size), 0) as total_size, COUNT(DISTINCT machine_name) as machine_count
        FROM content_locations
    """)
    rs.next()
    val totalFiles = rs.getLong("total_files")
    val uniqueCids = rs.getLong("unique_cids")
    val totalSize = rs.getLong("total_size")
    val machineCount = rs.getInt("machine_count")
    rs.close()

    val multiRs = conn.createStatement().executeQuery("""
        SELECT COUNT(*) as mc FROM (
            SELECT cid FROM content_locations GROUP BY cid HAVING COUNT(DISTINCT machine_name) >= 2
        )
    """)
    multiRs.next()
    val multiCopy = multiRs.getLong("mc")
    multiRs.close()

    val pct = if (uniqueCids > 0) "%.1f%%".format(multiCopy * 100.0 / uniqueCids) else "0%"

    div("grid") {
        statCardWeb("Total Files", "%,d".format(totalFiles), "$machineCount machines")
        statCardWeb("Unique CIDs", "%,d".format(uniqueCids), ProgressMonitor.formatBytes(totalSize))
        statCardWeb("Backed Up", pct, "${"%,d".format(multiCopy)} CIDs with 2+ copies")
    }
}

private fun FlowContent.reportReplication(conn: Connection, config: ChoamConfig, aliasMap: Map<String, String>) {
    if (config.repositories.isEmpty()) return

    h2 { +"Replication" }
    val repoCopies = PlanCommand.countRepoMachines(conn, config, aliasMap)

    table {
        thead { tr { th { +"Repository" }; th { +"Copies" }; th { +"Policy" }; th { +"Status" } } }
        tbody {
            for ((repoName, repoConfig) in config.repositories) {
                val policy = repoConfig.replication
                val copies = repoCopies[repoName] ?: emptySet()
                val copyCount = copies.size
                val statusClass = when {
                    copyCount >= policy.preferredCopies -> "status-ok"
                    copyCount >= policy.minCopies -> "status-warn"
                    else -> "status-err"
                }
                tr {
                    td { +repoName }
                    td { +"$copyCount" }
                    td { +"min ${policy.minCopies} / preferred ${policy.preferredCopies}" }
                    td {
                        span(statusClass) {
                            +when {
                                copyCount >= policy.preferredCopies -> "Meets preferred"
                                copyCount >= policy.minCopies -> "Below preferred"
                                else -> "UNDER-REPLICATED"
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun FlowContent.reportCopyDistribution(conn: Connection) {
    h2 { +"Copy Distribution" }

    val rs = conn.createStatement().executeQuery("""
        SELECT mc, COUNT(*) as cid_count FROM (
            SELECT cid, COUNT(DISTINCT machine_name) as mc
            FROM content_locations GROUP BY cid
        ) GROUP BY mc ORDER BY mc
    """)

    table {
        thead { tr { th { +"Copies" }; th { +"CIDs" } } }
        tbody {
            while (rs.next()) {
                val copies = rs.getInt("mc")
                val count = rs.getLong("cid_count")
                tr {
                    td { +"$copies" }
                    td { +"%,d".format(count) }
                }
            }
        }
    }
    rs.close()
}

private fun FlowContent.reportRisk(conn: Connection) {
    h2 { +"Risk (single-copy files >100MB)" }

    val rs = conn.createStatement().executeQuery("""
        SELECT COUNT(*) as at_risk_count, COALESCE(SUM(file_size), 0) as at_risk_size FROM (
            SELECT cid, MAX(file_size) as file_size FROM content_locations
            WHERE file_size > 104857600
            GROUP BY cid HAVING COUNT(DISTINCT machine_name) = 1
        )
    """)
    rs.next()
    val atRiskCount = rs.getLong("at_risk_count")
    val atRiskSize = rs.getLong("at_risk_size")
    rs.close()

    if (atRiskCount == 0L) {
        p("status-ok") { +"No single-copy files over 100MB." }
    } else {
        p("status-err") { +"${"%,d".format(atRiskCount)} files (${ProgressMonitor.formatBytes(atRiskSize)}) at risk" }

        val detailRs = conn.createStatement().executeQuery("""
            SELECT cid, machine_name, file_path, file_size FROM content_locations
            WHERE cid IN (
                SELECT cid FROM content_locations WHERE file_size > 104857600
                GROUP BY cid HAVING COUNT(DISTINCT machine_name) = 1
            )
            ORDER BY file_size DESC LIMIT 10
        """)

        table {
            thead { tr { th { +"Size" }; th { +"Machine" }; th { +"File" }; th { +"CID" } } }
            tbody {
                while (detailRs.next()) {
                    val path = detailRs.getString("file_path")
                    val cid = detailRs.getString("cid")
                    tr {
                        td { +ProgressMonitor.formatBytes(detailRs.getLong("file_size")) }
                        td { +detailRs.getString("machine_name") }
                        td { +path.substringAfterLast("/") }
                        td {
                            a(href = "/inspect/$cid") {
                                style = "color: var(--blue); font-size: 11px"
                                +"${cid.take(16)}..."
                            }
                        }
                    }
                }
            }
        }
        detailRs.close()
    }
}

private fun FlowContent.reportStaleness(conn: Connection, aliasMap: Map<String, String>) {
    h2 { +"Staleness" }

    val rs = conn.createStatement().executeQuery(
        "SELECT machine_name, MAX(last_synced_at) as last_sync, COUNT(*) as cnt FROM content_locations GROUP BY machine_name"
    )

    table {
        thead { tr { th { +"Machine" }; th { +"Files" }; th { +"Last Synced" }; th { +"Status" } } }
        tbody {
            while (rs.next()) {
                val rawName = rs.getString("machine_name")
                val displayName = aliasMap[rawName] ?: rawName
                val count = rs.getLong("cnt")
                val lastSync = rs.getString("last_sync") ?: "unknown"

                val (agoStr, statusClass) = if (lastSync != "unknown") {
                    try {
                        val syncTime = LocalDateTime.parse(lastSync.replace(" ", "T").substringBefore("."))
                        val daysSince = ChronoUnit.DAYS.between(syncTime, LocalDateTime.now())
                        val ago = when {
                            daysSince < 1 -> "today"
                            daysSince < 7 -> "${daysSince}d ago"
                            else -> "${daysSince}d ago"
                        }
                        val cls = when {
                            daysSince > 30 -> "status-err"
                            daysSince > 7 -> "status-warn"
                            else -> "status-ok"
                        }
                        ago to cls
                    } catch (_: Exception) { "unknown" to "status-warn" }
                } else { "unknown" to "status-warn" }

                tr {
                    td { +displayName }
                    td { +"%,d".format(count) }
                    td { +lastSync.substringBefore(".").replace("T", " ") }
                    td { span(statusClass) { +agoStr } }
                }
            }
        }
    }
    rs.close()
}

private fun FlowContent.reportTransferSpeeds(config: ChoamConfig) {
    h2 { +"Transfer Speeds (estimated)" }
    table {
        thead { tr { th { +"Machine" }; th { +"IP" }; th { +"Bandwidth" }; th { +"Mode" } } }
        tbody {
            for ((name, machine) in config.machines) {
                val ip = machine.tailscaleIp ?: machine.hostname
                val bandwidth = NetworkDetector.DEFAULT_BANDWIDTH_BYTES_PER_SEC
                tr {
                    td { +name }
                    td { +ip }
                    td { +"~${ProgressMonitor.formatBytes(bandwidth)}/s" }
                    td { +machine.networkPreference.name }
                }
            }
        }
    }
}

private fun FlowContent.reportGeoDiversity(conn: Connection) {
    h2 { +"Geographic Diversity" }

    val rs = conn.createStatement().executeQuery("""
        SELECT
            COUNT(CASE WHEN mc = 1 THEN 1 END) as single_machine,
            COUNT(CASE WHEN mc = 2 THEN 1 END) as two_machines,
            COUNT(CASE WHEN mc >= 3 THEN 1 END) as three_plus
        FROM (SELECT cid, COUNT(DISTINCT machine_name) as mc FROM content_locations GROUP BY cid)
    """)
    rs.next()
    val single = rs.getLong("single_machine")
    val two = rs.getLong("two_machines")
    val threePlus = rs.getLong("three_plus")
    rs.close()

    val total = single + two + threePlus
    fun pct(n: Long) = if (total > 0) "%.1f%%".format(n * 100.0 / total) else "0%"

    table {
        thead { tr { th { +"Spread" }; th { +"CIDs" }; th { +"%" } } }
        tbody {
            tr { td { +"1 machine" }; td { +"%,d".format(single) }; td { +pct(single) } }
            tr { td { +"2 machines" }; td { +"%,d".format(two) }; td { +pct(two) } }
            tr { td { +"3+ machines" }; td { +"%,d".format(threePlus) }; td { +pct(threePlus) } }
        }
    }
}

private fun FlowContent.reportContentClasses(conn: Connection) {
    h2 { +"Content Classes" }

    val mediaExts = setOf("mkv", "mp4", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts",
        "mp3", "flac", "aac", "ogg", "wav", "m4a", "wma",
        "jpg", "jpeg", "png", "gif", "bmp", "tiff", "webp", "heic", "svg", "raw", "cr2", "nef")
    val docExts = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "csv", "epub")
    val archiveExts = setOf("zip", "tar", "gz", "bz2", "xz", "7z", "rar", "dmg", "iso", "img")
    val codeExts = setOf("kt", "java", "py", "js", "ts", "swift", "rs", "go", "c", "cpp", "h", "sh", "sql", "json", "xml", "yaml", "yml", "toml", "gradle", "md", "html", "css")

    val rs = conn.createStatement().executeQuery("""
        SELECT LOWER(CASE WHEN file_path LIKE '%.%'
            THEN REPLACE(file_path, RTRIM(file_path, REPLACE(file_path, '.', '')), '')
            ELSE '' END) as ext,
            COUNT(DISTINCT cid) as cid_count, SUM(file_size) as total_size
        FROM content_locations WHERE file_path LIKE '%.%'
        GROUP BY ext ORDER BY total_size DESC
    """)

    var mediaSize = 0L; var mediaCount = 0L
    var docSize = 0L; var docCount = 0L
    var archiveSize = 0L; var archiveCount = 0L
    var codeSize = 0L; var codeCount = 0L
    var otherSize = 0L; var otherCount = 0L

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

    table {
        thead { tr { th { +"Class" }; th { +"CIDs" }; th { +"Size" } } }
        tbody {
            tr { td { +"Media" }; td { +"%,d".format(mediaCount) }; td { +ProgressMonitor.formatBytes(mediaSize) } }
            tr { td { +"Document" }; td { +"%,d".format(docCount) }; td { +ProgressMonitor.formatBytes(docSize) } }
            tr { td { +"Archive" }; td { +"%,d".format(archiveCount) }; td { +ProgressMonitor.formatBytes(archiveSize) } }
            tr { td { +"Code" }; td { +"%,d".format(codeCount) }; td { +ProgressMonitor.formatBytes(codeSize) } }
            tr { td { +"Other" }; td { +"%,d".format(otherCount) }; td { +ProgressMonitor.formatBytes(otherSize) } }
        }
    }
}

private fun FlowContent.reportDedup(conn: Connection) {
    h2 { +"Deduplication" }

    val rs = conn.createStatement().executeQuery("""
        SELECT COUNT(*) as dup_cids, COALESCE(SUM(extra_copies), 0) as wasted_entries FROM (
            SELECT cid, COUNT(*) - 1 as extra_copies FROM content_locations
            GROUP BY cid HAVING COUNT(*) > COUNT(DISTINCT machine_name)
        )
    """)
    rs.next()
    val dupCids = rs.getLong("dup_cids")
    val wastedEntries = rs.getLong("wasted_entries")
    rs.close()

    val crossRs = conn.createStatement().executeQuery("""
        SELECT COUNT(*) as cross_dup FROM (
            SELECT cid FROM content_locations GROUP BY cid HAVING COUNT(DISTINCT machine_name) > 1
        )
    """)
    crossRs.next()
    val crossDup = crossRs.getLong("cross_dup")
    crossRs.close()

    div("grid") {
        statCardWeb("Same-Machine Dupes", "%,d".format(dupCids), "${"%,d".format(wastedEntries)} extra entries")
        statCardWeb("Cross-Machine Copies", "%,d".format(crossDup), "Healthy replication")
    }
}

private fun FlowContent.reportRecommendations(conn: Connection, config: ChoamConfig, aliasMap: Map<String, String>) {
    h2 { +"Recommendations" }

    val recs = mutableListOf<String>()

    // Under-replicated repos
    val repoCopies = PlanCommand.countRepoMachines(conn, config, aliasMap)
    for ((repoName, repoConfig) in config.repositories) {
        val copies = repoCopies[repoName] ?: emptySet()
        if (copies.size < repoConfig.replication.minCopies) {
            val need = repoConfig.replication.minCopies - copies.size
            recs.add("URGENT: Sync '$repoName' — needs $need more cop${if (need == 1) "y" else "ies"}")
        }
    }

    // Stale machines
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
            if (daysSince > 30) recs.add("Run 'choam catalog-sync --from $displayName' (${daysSince}d stale)")
        } catch (_: Exception) {}
    }
    staleRs.close()

    // Large at-risk content
    val riskRs = conn.createStatement().executeQuery("""
        SELECT COALESCE(SUM(file_size), 0) as risk_size FROM (
            SELECT cid, MAX(file_size) as file_size FROM content_locations WHERE file_size > 104857600
            GROUP BY cid HAVING COUNT(DISTINCT machine_name) = 1
        )
    """)
    riskRs.next()
    val riskSize = riskRs.getLong("risk_size")
    riskRs.close()
    if (riskSize > 0) recs.add("${ProgressMonitor.formatBytes(riskSize)} of large files have only 1 copy — replicate")

    if (recs.isEmpty()) {
        p("status-ok") { +"All clear — no issues detected." }
    } else {
        ul {
            for (rec in recs) {
                li { +rec }
            }
        }
    }
}

private fun FlowContent.statCardWeb(title: String, value: String, detail: String) {
    div("card") {
        h3 { +title }
        div("value") { +value }
        div("detail") { +detail }
    }
}

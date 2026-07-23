package vision.salient.choam.web

import kotlinx.html.*
import vision.salient.choam.catalog.CatalogIndex
import vision.salient.choam.catalog.SearchFilters
import vision.salient.choam.cli.GlobalSearchCommand
import vision.salient.choam.cli.PlanCommand
import vision.salient.choam.cli.ReportCommand
import vision.salient.choam.cli.ensureJunkTable
import vision.salient.choam.cli.RequestCopyCommand
import vision.salient.choam.config.ChoamConfig
import vision.salient.choam.config.BackupStatus
import vision.salient.choam.config.StorageClass
import vision.salient.choam.drive.DriveDetector
import vision.salient.choam.network.ProgressMonitor
import vision.salient.choam.sync.SyncHistoryEntry
import vision.salient.choam.sync.SyncHistoryStore
import java.io.File
import java.net.InetAddress
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// ==========================================
// Full pages
// ==========================================

fun HTML.dashboardPage(config: ChoamConfig) = layout("Dashboard", "dashboard") {
    h1 { +"Dashboard" }

    // Stats cards — loaded async
    div {
        attributes["hx-get"] = "/htmx/stats-cards"
        attributes["hx-trigger"] = "load"
        attributes["hx-swap"] = "innerHTML"
        div("grid") {
            statCard("Total Files", "...", "loading")
            statCard("Unique CIDs", "...", "loading")
            statCard("Backed Up", "...", "loading")
            statCard("At Risk", "...", "loading")
        }
    }

    // Machine status (HTMX auto-refresh)
    h2 { +"Machines" }
    div {
        attributes["hx-get"] = "/htmx/machines"
        attributes["hx-trigger"] = "load, every 30s"
        attributes["hx-swap"] = "innerHTML"
        p("detail") { +"Checking reachability..." }
    }

    // Catalog stats
    h2 { +"Catalog" }
    div {
        attributes["hx-get"] = "/htmx/catalog-stats"
        attributes["hx-trigger"] = "load"
        attributes["hx-swap"] = "innerHTML"
        p("detail") { +"Loading catalog..." }
    }

    // Replication — loaded async
    h2 { +"Replication" }
    div {
        attributes["hx-get"] = "/htmx/replication"
        attributes["hx-trigger"] = "load"
        attributes["hx-swap"] = "innerHTML"
        p("detail") { +"Loading replication status..." }
    }

    // Federation summary — loaded async
    if (config.house != null && config.house.houseId.isNotEmpty()) {
        h2 { +"Federation" }
        div {
            attributes["hx-get"] = "/htmx/federation-summary"
            attributes["hx-trigger"] = "load"
            attributes["hx-swap"] = "innerHTML"
            p("detail") { +"Loading federation..." }
        }
    }

    // Quick Actions — functional buttons + navigation links
    h2 { +"Quick Actions" }
    div("grid") {
        div("card") {
            h3 { +"Trigger" }
            div("detail") {
                button {
                    attributes["hx-post"] = "/api/catalog-sync"
                    attributes["hx-swap"] = "none"
                    style = "font-size: 12px; padding: 4px 10px; margin: 2px 0"
                    +"Sync Catalogs"
                }
            }
            div("detail") {
                button {
                    attributes["hx-post"] = "/api/peer-check"
                    attributes["hx-swap"] = "none"
                    style = "font-size: 12px; padding: 4px 10px; margin: 2px 0"
                    +"Check Peers"
                }
            }
            div("detail") {
                button {
                    attributes["hx-post"] = "/api/fulfill"
                    attributes["hx-swap"] = "none"
                    style = "font-size: 12px; padding: 4px 10px; margin: 2px 0"
                    +"Fulfill Requests"
                }
            }
        }
        div("card") {
            h3 { +"Navigate" }
            div("detail") { a(href = "/report") { style = "color: var(--green)"; +"Health report" } }
            div("detail") { a(href = "/federation") { style = "color: var(--green)"; +"Federation" } }
            div("detail") { a(href = "/network") { style = "color: var(--green)"; +"Network" } }
            div("detail") { a(href = "/media") { style = "color: var(--green)"; +"Media browser" } }
        }
    }

    // Daemon Activity — auto-refreshing feed
    h2 { +"Daemon Activity" }
    div {
        attributes["hx-get"] = "/htmx/daemon-activity"
        attributes["hx-trigger"] = "load, every 10s"
        attributes["hx-swap"] = "innerHTML"
        p("detail") { +"Loading activity..." }
    }
}

fun HTML.searchPage(config: ChoamConfig, query: String, ext: String, machine: String, limit: Int) = layout("Search", "search") {
    h1 { +"Catalog Search" }

    // Search form
    form(classes = "search-form") {
        attributes["hx-get"] = "/htmx/search-results"
        attributes["hx-target"] = "#results"
        attributes["hx-trigger"] = "submit"
        input {
            type = InputType.text; name = "q"; placeholder = "Search files..."; value = query
        }
        input {
            type = InputType.text; name = "ext"; placeholder = "ext (mkv,mp4)"; value = ext
            style = "max-width: 150px"
        }
        select {
            name = "machine"
            option { value = ""; if (machine.isEmpty()) selected = true; +"All machines" }
            for ((key, _) in config.machines) {
                option { value = key; if (machine == key) selected = true; +key }
            }
        }
        button { type = ButtonType.submit; +"Search" }
    }

    // Results area
    div {
        id = "results"
        if (query.isNotEmpty() || ext.isNotEmpty()) {
            // Show initial results
            searchResultsContent(config, query, ext, machine, limit)
        } else {
            p("detail") { +"Enter a search term or extension filter to search across all machines." }
        }
    }
}

fun HTML.drivesPage(config: ChoamConfig) = layout("Drives", "drives") {
    h1 { +"Drives" }
    div {
        attributes["hx-get"] = "/htmx/drive-status"
        attributes["hx-trigger"] = "load"
        attributes["hx-swap"] = "innerHTML"
        span("spinner htmx-indicator") { +"Loading..." }
    }
}

fun HTML.historyPage(config: ChoamConfig) = layout("Sync History", "history") {
    h1 { +"Sync History" }
    val store = SyncHistoryStore()
    val entries = store.loadAll()

    val sorted = entries.sortedByDescending { it.startTime }
    if (sorted.isEmpty()) {
        p { +"No sync history recorded yet." }
    } else {
        table {
            thead { tr { th { +"Time" }; th { +"Source" }; th { +"Target" }; th { +"Repos" }; th { +"Files" }; th { +"Size" }; th { +"Status" } } }
            tbody {
                for (entry in sorted) {
                    val repos = entry.repositories.joinToString()
                    tr {
                        td { +entry.startTime.substringBefore("T") }
                        td { +entry.sourceMachine }
                        td { +entry.targetMachine }
                        td { +repos }
                        td { +"${entry.filesTransferred}" }
                        td { +ProgressMonitor.formatBytes(entry.bytesTransferred) }
                        td {
                            val cls = when (entry.status) {
                                "COMPLETED" -> "status-ok"
                                "FAILED" -> "status-err"
                                else -> "status-warn"
                            }
                            span(cls) { +entry.status }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// HTMX partial fragments
// ==========================================

fun HTML.machineStatusFragment(config: ChoamConfig) {
    body {
        table {
            thead { tr { th { +"Machine" }; th { +"Type" }; th { +"IP" }; th { +"Status" } } }
            tbody {
                val hostname = try { InetAddress.getLocalHost().hostName } catch (_: Exception) { "unknown" }
                for ((name, machine) in config.machines) {
                    val isLocal = machine.hostname == hostname || machine.hostname.startsWith(hostname)
                    val reachable = if (isLocal) true else try {
                        val ip = machine.tailscaleIp ?: machine.hostname
                        InetAddress.getByName(ip).isReachable(2000)
                    } catch (_: Exception) { false }

                    tr {
                        td { +name }
                        td { +machine.type.name.lowercase() }
                        td { +(machine.tailscaleIp ?: machine.hostname) }
                        td {
                            if (isLocal) { span("status-ok") { +"local" } }
                            else if (reachable) { span("status-ok") { +"reachable" } }
                            else { span("status-err") { +"unreachable" } }
                        }
                    }
                }
            }
        }
    }
}

fun HTML.catalogStatsFragment(config: ChoamConfig) {
    body {
        val aliasMap = ReportCommand.buildAliasMap(config)
        val unifiedDbPath = "${System.getProperty("user.home")}/.choam/unified_registry.db"
        if (!File(unifiedDbPath).exists()) {
            p { +"No catalog synced yet." }
            return@body
        }

        val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedDbPath")
        val rs = conn.createStatement().executeQuery(
            "SELECT machine_name, COUNT(*) as cnt, MAX(last_synced_at) as last_sync FROM content_locations GROUP BY machine_name"
        )
        table {
            thead { tr { th { +"Machine" }; th { +"Files" }; th { +"Last Synced" }; th { +"Status" } } }
            tbody {
                while (rs.next()) {
                    val rawName = rs.getString("machine_name")
                    val displayName = aliasMap[rawName] ?: rawName
                    val count = rs.getLong("cnt")
                    val lastSync = rs.getString("last_sync") ?: "unknown"
                    val stale = GlobalSearchCommand.isStale(lastSync.replace("T", " ").substringBefore("."))

                    tr {
                        td { +displayName }
                        td { +"%,d".format(count) }
                        td { +lastSync.replace("T", " ").substringBefore(".") }
                        td {
                            if (stale) span("badge badge-stale") { +"stale" }
                            else span("badge badge-ok") { +"fresh" }
                        }
                    }
                }
            }
        }
        rs.close(); conn.close()
    }
}

fun HTML.statsCardsFragment() {
    body {
        val stats = loadCatalogStats()
        div("grid") {
            statCard("Total Files", "%,d".format(stats.totalFiles), "${stats.machineCount} machines")
            statCard("Unique CIDs", "%,d".format(stats.uniqueCids), ProgressMonitor.formatBytes(stats.totalSize))
            statCard("Backed Up", stats.backupPct, "${"%,d".format(stats.multiCopyCids)} CIDs with 2+ copies")
            statCard("At Risk", "%,d".format(stats.atRiskCount), "${ProgressMonitor.formatBytes(stats.atRiskSize)} single-copy >100MB")
        }
    }
}

fun HTML.replicationFragment(config: ChoamConfig) {
    body {
        val aliasMap = ReportCommand.buildAliasMap(config)
        val unifiedDbPath = "${System.getProperty("user.home")}/.choam/unified_registry.db"
        if (!File(unifiedDbPath).exists()) {
            p { +"No catalog synced yet. Run 'choam catalog-sync'." }
            return@body
        }
        val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedDbPath")
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
                        td { span(statusClass) { +when {
                            copyCount >= policy.preferredCopies -> "Meets preferred"
                            copyCount >= policy.minCopies -> "Below preferred"
                            else -> "UNDER-REPLICATED"
                        } } }
                    }
                }
            }
        }
        conn.close()
    }
}

fun HTML.federationSummaryFragment(config: ChoamConfig) {
    body {
        val house = config.house
        if (house == null || house.houseId.isEmpty()) {
            div("grid") {
                statCard("House", "Not initialized", "Run: choam dag init")
            }
            return@body
        }
        div("grid") {
            statCard("House", house.name, "ID: ${house.houseId.take(12)}...")
            statCard("Peers", "${house.peers.size}", if (house.peers.isEmpty()) "Add peers to enable sharing" else "trusted peers")
            try {
                val fedStore = vision.salient.choam.federation.FederationStore()
                val fedConn = fedStore.open()
                val shares = fedStore.listActiveShares(fedConn)
                val backups = fedStore.listBackupAgreements(fedConn)
                fedConn.close()
                if (shares.isNotEmpty() || backups.isNotEmpty()) {
                    statCard("Shares", "${shares.size}", "${backups.size} backup agreements")
                }
            } catch (_: Exception) {}
        }
    }
}

fun HTML.searchResultsFragment(config: ChoamConfig, query: String, ext: String, machine: String, limit: Int) {
    body { searchResultsContent(config, query, ext, machine, limit) }
}

fun HTML.driveStatusFragment(config: ChoamConfig) {
    body {
        if (config.drives.isEmpty()) {
            p { +"No drives configured." }
            return@body
        }

        val detector = DriveDetector()
        val mounted = detector.detectConfiguredDrives(config.drives)

        table {
            thead { tr { th { +"Drive" }; th { +"Class" }; th { +"Status" }; th { +"Free" }; th { +"Total" }; th { +"Repositories" } } }
            tbody {
                for ((key, drive) in config.drives) {
                    val mountInfo = mounted[key]
                    tr {
                        td { +drive.label }
                        td {
                            val badgeClass = when (drive.storageClass) {
                                StorageClass.HOT -> "badge-hot"
                                StorageClass.WARM -> "badge-warm"
                                StorageClass.COLD -> "badge-cold"
                            }
                            span("badge $badgeClass") { +drive.storageClass.name }
                        }
                        td {
                            if (mountInfo != null) span("status-ok") { +"MOUNTED" }
                            else span("status-err") { +"NOT MOUNTED" }
                        }
                        td { +(if (mountInfo != null) ProgressMonitor.formatBytes(mountInfo.freeSpace) else "-") }
                        td { +(if (mountInfo != null) ProgressMonitor.formatBytes(mountInfo.totalSpace) else "-") }
                        td { +drive.repositories.keys.joinToString(", ") }
                    }
                }
            }
        }
    }
}

// ==========================================
// Shared content builders
// ==========================================

private fun FlowContent.searchResultsContent(config: ChoamConfig, query: String, ext: String, machine: String, limit: Int) {
    val indexDbPath = "${System.getProperty("user.home")}/.choam/catalog-index.db"
    if (!File(indexDbPath).exists()) {
        p { +"No search index. Run 'choam catalog-sync' then 'choam rebuild-index'." }
        return
    }

    val catalogIndex = CatalogIndex(indexDbPath)
    val conn = catalogIndex.open()

    val extensions = ext.split(",").map { it.trim().lowercase().removePrefix(".") }.filter { it.isNotEmpty() }.ifEmpty { null }
    val filters = SearchFilters(extensions = extensions)

    val results = if (filters.hasAny || query.isNotBlank()) {
        catalogIndex.advancedSearch(conn, query, filters, limit)
    } else {
        emptyList()
    }

    val filtered = if (machine.isNotEmpty()) {
        results.filter { it.machine.equals(machine, ignoreCase = true) }
    } else results

    if (filtered.isEmpty()) {
        p { +"No results${if (query.isNotEmpty()) " for \"$query\"" else ""}." }
        conn.close()
        return
    }

    p { +"${filtered.size} results" }

    val grouped = filtered.groupBy { it.machine }
    for ((machineName, machineResults) in grouped) {
        div("result-group") {
            h3 { +machineName }
            val byDrive = machineResults.groupBy { it.driveLabel }
            for ((driveLabel, driveResults) in byDrive) {
                p { style = "color: #888; font-size: 12px; margin: 4px 0"; +"$driveLabel:" }
                for (r in driveResults) {
                    div("result-item") {
                        span { +r.filename }
                        span("size") { +" ${ProgressMonitor.formatBytes(r.size)}" }
                        div("path") { +r.path }
                        if (r.cid.isNotEmpty()) {
                            div {
                                style = "margin: 2px 0; font-size: 12px"
                                a(href = "/inspect/${r.cid}") {
                                    style = "color: var(--green); font-family: var(--mono)"
                                    +"CID: ${r.cid}"
                                }
                            }
                            div {
                                style = "font-size: 11px"
                                a(href = "https://ipfs.io/ipfs/${r.cid}") {
                                    target = "_blank"
                                    style = "color: var(--blue)"
                                    +"IPFS Gateway"
                                }
                                val ext = r.filename.substringAfterLast(".", "").lowercase()
                                val streamable = ext in setOf("mkv", "mp4", "avi", "mov", "webm", "m4v", "mp3", "flac", "aac", "ogg", "wav")
                                if (streamable) {
                                    a(href = "/stream/${r.cid}") {
                                        style = "color: var(--green); margin-left: 12px"
                                        +"Stream"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    conn.close()
}

internal fun FlowContent.statCard(title: String, value: String, detail: String) {
    div("card") {
        h3 { +title }
        div("value") { +value }
        div("detail") { +detail }
    }
}

// ==========================================
// Data helpers
// ==========================================

data class CatalogStats(
    val totalFiles: Long = 0,
    val uniqueCids: Long = 0,
    val totalSize: Long = 0,
    val machineCount: Int = 0,
    val multiCopyCids: Long = 0,
    val backupPct: String = "0%",
    val atRiskCount: Long = 0,
    val atRiskSize: Long = 0
)

private fun loadCatalogStats(): CatalogStats {
    val dbPath = "${System.getProperty("user.home")}/.choam/unified_registry.db"
    if (!File(dbPath).exists()) return CatalogStats()

    return try {
        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")

        val rs = conn.createStatement().executeQuery("""
            SELECT COUNT(*) as tf, COUNT(DISTINCT cid) as uc,
                   COALESCE(SUM(file_size), 0) as ts, COUNT(DISTINCT machine_name) as mc
            FROM content_locations
        """)
        rs.next()
        val totalFiles = rs.getLong("tf")
        val uniqueCids = rs.getLong("uc")
        val totalSize = rs.getLong("ts")
        val machineCount = rs.getInt("mc")
        rs.close()

        val multiRs = conn.createStatement().executeQuery("""
            SELECT COUNT(*) FROM (
                SELECT cid FROM content_locations GROUP BY cid HAVING COUNT(DISTINCT machine_name) >= 2
            )
        """)
        multiRs.next()
        val multiCopy = multiRs.getLong(1)
        multiRs.close()

        val pct = if (uniqueCids > 0) "%.1f%%".format(multiCopy * 100.0 / uniqueCids) else "0%"

        val riskRs = conn.createStatement().executeQuery("""
            SELECT COUNT(*) as c, COALESCE(SUM(file_size), 0) as s FROM (
                SELECT cid, MAX(file_size) as file_size FROM content_locations
                WHERE file_size > 104857600
                GROUP BY cid HAVING COUNT(DISTINCT machine_name) = 1
            )
        """)
        riskRs.next()
        val riskCount = riskRs.getLong("c")
        val riskSize = riskRs.getLong("s")
        riskRs.close()

        conn.close()
        CatalogStats(totalFiles, uniqueCids, totalSize, machineCount, multiCopy, pct, riskCount, riskSize)
    } catch (_: Exception) {
        CatalogStats()
    }
}

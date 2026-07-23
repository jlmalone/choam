package vision.salient.choam.web

import kotlinx.html.*
import vision.salient.choam.cli.InspectCommand
import vision.salient.choam.cli.ReportCommand
import vision.salient.choam.config.ChoamConfig
import vision.salient.choam.network.NetworkDetector
import vision.salient.choam.network.ProgressMonitor
import java.io.File
import java.sql.DriverManager

/**
 * Web inspect page — deep drill-down on a CID.
 * Shows file info, all copies, replication status, transfer actions.
 */
fun HTML.inspectPage(config: ChoamConfig, cid: String) = layout("Inspect", "inspect") {
    h1 { +"Inspect" }

    if (cid.isBlank()) {
        p { +"No CID specified. Use "; a(href = "/search") { +"Search" }; +" to find content, then click a CID." }
        return@layout
    }

    val unifiedDbPath = "${System.getProperty("user.home")}/.choam/unified_registry.db"
    if (!File(unifiedDbPath).exists()) {
        p("status-warn") { +"No unified registry found. Run 'choam catalog-sync' first." }
        return@layout
    }

    val aliasMap = ReportCommand.buildAliasMap(config)

    val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedDbPath")
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
        p("status-err") { +"CID not found: $cid" }
        return@layout
    }

    val firstCopy = copies.first()
    val filename = firstCopy.path.substringAfterLast("/")
    val ext = filename.substringAfterLast(".", "")
    val contentType = guessContentType(filename)
    val isStreamable = contentType.contentType in listOf("video", "audio")

    // File info card
    div("card") {
        style = "margin-bottom: 16px"
        h3 { +"File Info" }
        div("value") { +filename }
        div("detail") {
            +"${ProgressMonitor.formatBytes(firstCopy.size)} — $contentType"
        }
    }

    // CID + IPFS
    div("card") {
        style = "margin-bottom: 16px"
        h3 { +"Content Address" }
        div {
            style = "word-break: break-all; font-size: 13px; margin: 8px 0"
            strong { +"CID: " }
            span { style = "color: var(--green)"; +cid }
        }
        div {
            style = "font-size: 13px; margin: 4px 0"
            strong { +"IPFS: " }
            a(href = "https://ipfs.io/ipfs/$cid") {
                target = "_blank"
                style = "color: var(--blue)"
                +"https://ipfs.io/ipfs/$cid"
            }
        }
        if (isStreamable) {
            div {
                style = "margin-top: 12px"
                a(href = "/stream/$cid") {
                    classes = setOf("stream-btn")
                    +"Stream"
                }
            }
        }
    }

    // Copies table
    val byMachine = copies.groupBy { it.machine }
    h2 { +"Copies (${byMachine.keys.size} machines)" }
    table {
        thead {
            tr { th { +"#" }; th { +"Machine/Drive" }; th { +"Path" }; th { +"Verified" } }
        }
        tbody {
            var idx = 1
            for ((machine, machineCopies) in byMachine) {
                for (copy in machineCopies) {
                    val driveLabel = InspectCommand.extractDriveLabel(copy.path)
                    val driveStr = if (driveLabel != null) "$machine/$driveLabel" else machine
                    val verified = InspectCommand.formatVerifiedDate(copy.lastSynced)
                    tr {
                        td { +"$idx" }
                        td { span("status-ok") { +driveStr } }
                        td { span { style = "font-size: 12px"; +copy.path } }
                        td { +verified }
                    }
                    idx++
                }
            }
        }
    }

    // Replication status
    if (config.repositories.isNotEmpty()) {
        val currentMachines = byMachine.keys
        for ((repoName, repoConfig) in config.repositories) {
            val policy = repoConfig.replication
            val repoMachines = config.machines.entries
                .filter { it.value.repositories.containsKey(repoName) }
                .map { it.key }
                .toSet()
            val copiesInRepo = currentMachines.intersect(repoMachines)
            if (copiesInRepo.isEmpty()) continue

            h2 { +"Replication ($repoName)" }
            val statusClass = when {
                copiesInRepo.size >= policy.preferredCopies -> "status-ok"
                copiesInRepo.size >= policy.minCopies -> "status-warn"
                else -> "status-err"
            }
            p {
                span(statusClass) {
                    +"${copiesInRepo.size}/${policy.preferredCopies} copies (min: ${policy.minCopies})"
                }
            }

            val missingMachines = repoMachines - currentMachines
            if (missingMachines.isNotEmpty()) {
                p { style = "color: var(--yellow)"; +"Missing from: ${missingMachines.joinToString(", ")}" }
            }
            break
        }
    }

    // Transfer estimates
    val allMachines = config.machines.keys
    val missingMachines = allMachines - byMachine.keys
    if (missingMachines.isNotEmpty()) {
        h2 { +"Transfer Estimates" }
        table {
            thead { tr { th { +"Target" }; th { +"IP" }; th { +"Est. Time" }; th { +"Bandwidth" } } }
            tbody {
                for (targetName in missingMachines) {
                    val targetProfile = config.machines[targetName] ?: continue
                    val ip = targetProfile.tailscaleIp ?: targetProfile.hostname
                    val bandwidth = NetworkDetector.DEFAULT_BANDWIDTH_BYTES_PER_SEC
                    val seconds = firstCopy.size / bandwidth
                    val timeStr = InspectCommand.formatDuration(seconds)
                    tr {
                        td { +targetName }
                        td { +ip }
                        td { +"~$timeStr" }
                        td { +"~${ProgressMonitor.formatBytes(bandwidth)}/s" }
                    }
                }
            }
        }
    }
}

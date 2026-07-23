package vision.salient.choam.web

import kotlinx.html.*
import vision.salient.choam.config.BackupStatus
import vision.salient.choam.config.ChoamConfig
import vision.salient.choam.federation.FederationStore
import vision.salient.choam.network.ProgressMonitor

/**
 * Web federation page — house identity, peers, shares, backups.
 */
fun HTML.federationPage(config: ChoamConfig) = layout("Federation", "federation") {
    h1 { +"Federation" }

    val house = config.house
    if (house == null || house.houseId.isEmpty()) {
        div("card") {
            h3 { +"No House Initialized" }
            p { +"Run "; code { +"choam house init --name <name>" }; +" to create your House identity." }
        }
        return@layout
    }

    // House identity card
    div("card") {
        style = "margin-bottom: 16px"
        h3 { +"House Identity" }
        div("value") { +house.name }
        div("detail") {
            +"ID: ${house.houseId}"
        }
        div("detail") {
            +"Created: ${house.createdAt.substringBefore("T")}  Peers: ${house.peers.size}"
        }
        if (house.description.isNotEmpty()) {
            div("detail") { +house.description }
        }
    }

    // Peers table
    h2 { +"Peers" }
    if (house.peers.isEmpty()) {
        p("detail") { +"No peers configured. Add with: "; code { +"choam house add-peer <name> --id <id> --ip <ip>" } }
    } else {
        table {
            thead { tr { th { +"Name" }; th { +"House ID" }; th { +"IP" }; th { +"SSH User" }; th { +"Added" } } }
            tbody {
                for ((id, peer) in house.peers) {
                    tr {
                        td { +peer.name }
                        td { span { style = "font-size: 12px; color: var(--text-dim)"; +id } }
                        td { +(peer.tailscaleIp ?: "-") }
                        td { +(peer.sshUser ?: "-") }
                        td { +peer.addedAt.substringBefore("T") }
                    }
                }
            }
        }
    }

    // Shares and backups from federation DB
    try {
        val store = FederationStore()
        val conn = store.open()
        val shares = store.listActiveShares(conn)
        val backups = store.listBackupAgreements(conn)

        // Shares
        h2 { +"Active Shares" }
        if (shares.isEmpty()) {
            p("detail") { +"No shares. Grant with: "; code { +"choam share grant <repo> --with <peer-id> --access read" } }
        } else {
            table {
                thead { tr { th { +"Repository" }; th { +"Peer" }; th { +"Access" }; th { +"Granted" } } }
                tbody {
                    for (share in shares) {
                        val peerName = house.peers[share.peerHouseId]?.name ?: share.peerHouseId.take(12)
                        tr {
                            td { +share.repository }
                            td { +peerName }
                            td {
                                val badgeClass = when (share.access.name) {
                                    "WRITE" -> "badge-hot"
                                    "READ" -> "badge-ok"
                                    else -> "badge-cold"
                                }
                                span("badge $badgeClass") { +share.access.name }
                            }
                            td { +share.grantedAt.substringBefore("T").substringBefore(" ") }
                        }
                    }
                }
            }
        }

        // Backup agreements
        h2 { +"Backup Agreements" }
        if (backups.isEmpty()) {
            p("detail") { +"No backups. Offer with: "; code { +"choam backup offer --to <peer-id> --size 2TB" } }
        } else {
            table {
                thead {
                    tr {
                        th { +"Peer" }; th { +"Status" }; th { +"We Offer" }; th { +"They Used" }
                        th { +"They Offer" }; th { +"We Used" }
                    }
                }
                tbody {
                    for (agreement in backups) {
                        val peerName = house.peers[agreement.peerHouseId]?.name ?: agreement.peerHouseId.take(12)
                        tr {
                            td { +peerName }
                            td {
                                val badgeClass = when (agreement.status) {
                                    BackupStatus.ACCEPTED, BackupStatus.ACTIVE -> "badge-ok"
                                    BackupStatus.PROPOSED -> "badge-warm"
                                    BackupStatus.SUSPENDED -> "badge-stale"
                                    BackupStatus.TERMINATED -> "badge-hot"
                                }
                                span("badge $badgeClass") { +agreement.status.name }
                            }
                            td { +ProgressMonitor.formatBytes(agreement.offeredBytes) }
                            td { +ProgressMonitor.formatBytes(agreement.receivedBytes) }
                            td { +ProgressMonitor.formatBytes(agreement.theirOfferedBytes) }
                            td { +ProgressMonitor.formatBytes(agreement.ourUsedBytes) }
                        }
                    }
                }
            }
        }

        conn.close()
    } catch (e: Exception) {
        p("status-warn") { +"Federation database not available: ${e.message}" }
    }
}

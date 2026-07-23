package vision.salient.choam.web

import kotlinx.html.*
import vision.salient.choam.config.ChoamConfig
import vision.salient.choam.federation.BandwidthEconomy
import vision.salient.choam.federation.GossipProtocol
import vision.salient.choam.federation.TransferPriority
import vision.salient.choam.network.NetworkDetector
import vision.salient.choam.network.ProgressMonitor

/**
 * Web network page — gossip announcements + bandwidth economy.
 */
fun HTML.networkPage(config: ChoamConfig) = layout("Network", "network") {
    h1 { +"Network" }

    // Gossip peer announcements
    h2 { +"Peer Announcements" }
    try {
        val gossip = GossipProtocol()
        val gossipConn = gossip.open()
        val announcements = gossip.getLatestAnnouncements(gossipConn)
        gossipConn.close()

        if (announcements.isEmpty()) {
            p("detail") { +"No peer announcements. Run "; code { +"choam gossip announce" }; +" to create yours." }
        } else {
            table {
                thead {
                    tr {
                        th { +"House" }; th { +"CIDs" }; th { +"Size" }
                        th { +"Needs Replication" }; th { +"Repos" }; th { +"Last Seen" }
                    }
                }
                tbody {
                    for (a in announcements) {
                        tr {
                            td { +a.houseName }
                            td { +"%,d".format(a.cidCount) }
                            td { +ProgressMonitor.formatBytes(a.totalSizeBytes) }
                            td {
                                if (a.needsReplication > 0) {
                                    span("status-warn") { +"%,d".format(a.needsReplication) }
                                } else {
                                    span("status-ok") { +"0" }
                                }
                            }
                            td { +a.sharedRepos.joinToString(", ") }
                            td { +a.timestamp }
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        p("status-warn") { +"Gossip DB not available: ${e.message}" }
    }

    // Bandwidth economy
    h2 { +"Bandwidth Economy" }
    try {
        val economy = BandwidthEconomy()
        val econConn = economy.open()
        val balances = economy.getAllBalances(econConn)

        if (balances.isEmpty()) {
            p("detail") { +"No transfer history yet." }
        } else {
            table {
                thead {
                    tr {
                        th { +"Peer" }; th { +"Uploaded" }; th { +"Downloaded" }
                        th { +"Balance" }; th { +"Priority" }; th { +"Bandwidth" }; th { +"Transfers" }
                    }
                }
                tbody {
                    for (balance in balances) {
                        val peerName = config.house?.peers?.get(balance.peerHouseId)?.name
                            ?: balance.peerHouseId.take(12)
                        val priority = economy.getPriority(econConn, balance.peerHouseId)

                        tr {
                            td { +peerName }
                            td { +ProgressMonitor.formatBytes(balance.bytesUploaded) }
                            td { +ProgressMonitor.formatBytes(balance.bytesDownloaded) }
                            td {
                                val balanceClass = if (balance.balance >= 0) "status-ok" else "status-err"
                                val prefix = if (balance.balance >= 0) "+" else "-"
                                span(balanceClass) {
                                    +"$prefix${ProgressMonitor.formatBytes(kotlin.math.abs(balance.balance))}"
                                }
                            }
                            td {
                                val badgeClass = when (priority) {
                                    TransferPriority.HIGH -> "badge-ok"
                                    TransferPriority.NORMAL -> "badge-warm"
                                    TransferPriority.LOW -> "badge-stale"
                                    TransferPriority.THROTTLED -> "badge-hot"
                                }
                                span("badge $badgeClass") { +priority.name }
                            }
                            td { +"~${ProgressMonitor.formatBytes(NetworkDetector.DEFAULT_BANDWIDTH_BYTES_PER_SEC)}/s" }
                            td { +"${balance.transferCount}" }
                        }
                    }
                }
            }
        }

        econConn.close()
    } catch (e: Exception) {
        p("status-warn") { +"Economy DB not available: ${e.message}" }
    }
}

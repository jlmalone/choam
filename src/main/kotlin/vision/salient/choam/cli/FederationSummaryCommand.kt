package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.dag.ConfigResolver
import vision.salient.choam.federation.FederationStore
import vision.salient.choam.network.ProgressMonitor

/**
 * Combined federation summary — house + peers + shares + backups in one view.
 *
 * Single command showing the complete federation state without needing to run
 * house status + share list + backup list + gossip peers separately.
 */
class FederationSummaryCommand : CliktCommand(
    name = "federation",
    help = """
        Show complete federation status in a single view.

        Combines house identity, peers, active shares, and backup agreements
        into one unified display. Equivalent to running house status + share list +
        backup list in sequence.

        Key behaviors:
          - Shows House identity (name, ID, creation date)
          - Lists all peers with IPs and SSH users
          - Lists all active share grants with access levels
          - Lists backup agreements with status and capacity usage

        Safety: Read-only. No files or remotes are modified.

        Examples:
          choam federation
    """.trimIndent()
) {
    override fun run() {
        val config = try {
            ConfigResolver.resolve()
        } catch (e: Exception) {
            echo("Failed to load config: ${e.message}")
            return
        }

        val house = config.house
        if (house == null || house.houseId.isEmpty()) {
            echo("No House initialized. Run 'choam house init --name <name>' first.")
            return
        }

        // House identity
        echo("House: ${house.name} (${house.houseId})")
        echo("  Created: ${house.createdAt.substringBefore("T")}  Peers: ${house.peers.size}")
        if (house.description.isNotEmpty()) echo("  Description: ${house.description}")
        echo()

        // Peers
        if (house.peers.isNotEmpty()) {
            echo("Peers:")
            for ((id, peer) in house.peers) {
                val ip = peer.tailscaleIp ?: "no IP"
                val user = peer.sshUser ?: "no user"
                val added = peer.addedAt.substringBefore("T")
                echo("  ${peer.name.padEnd(22)} $ip  $user  added: $added")
            }
            echo()
        } else {
            echo("Peers: none")
            echo("  Add peers with: choam house add-peer <name> --id <house-id> --ip <ip>")
            echo()
        }

        // Shares and backups from federation DB
        val store = FederationStore()
        try {
            val conn = store.open()
            val shares = store.listActiveShares(conn)
            val backups = store.listBackupAgreements(conn)

            // Shares
            if (shares.isNotEmpty()) {
                echo("Shares:")
                for (share in shares) {
                    val peerName = house.peers[share.peerHouseId]?.name ?: share.peerHouseId.take(12)
                    val granted = share.grantedAt.substringBefore("T").substringBefore(" ")
                    echo("  ${share.repository.padEnd(16)} → ${peerName.padEnd(20)} ${share.access.name.padEnd(6)} granted: $granted")
                }
                echo()
            } else {
                echo("Shares: none")
                echo("  Grant access with: choam share grant <repo> --with <peer-id> --access read")
                echo()
            }

            // Backups
            if (backups.isNotEmpty()) {
                echo("Backups:")
                for (agreement in backups) {
                    val peerName = house.peers[agreement.peerHouseId]?.name ?: agreement.peerHouseId.take(12)
                    val statusColor = when (agreement.status.name) {
                        "ACCEPTED", "ACTIVE" -> "\u001b[32m"
                        "PROPOSED" -> "\u001b[33m"
                        else -> "\u001b[31m"
                    }
                    echo("  ${peerName.padEnd(22)} ${statusColor}${agreement.status}\u001b[0m" +
                        "   we offer: ${ProgressMonitor.formatBytes(agreement.offeredBytes)}" +
                        "   they used: ${ProgressMonitor.formatBytes(agreement.receivedBytes)}" +
                        "   they offer: ${ProgressMonitor.formatBytes(agreement.theirOfferedBytes)}" +
                        "   we used: ${ProgressMonitor.formatBytes(agreement.ourUsedBytes)}")
                }
                echo()
            } else {
                echo("Backups: none")
                echo("  Offer storage with: choam backup offer --to <peer-id> --size 2TB")
                echo()
            }

            conn.close()
        } catch (e: Exception) {
            echo("Federation DB not available: ${e.message}")
        }
    }
}

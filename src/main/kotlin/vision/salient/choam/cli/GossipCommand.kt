package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.dag.ConfigResolver
import vision.salient.choam.config.NodeCapability
import vision.salient.choam.federation.BandwidthEconomy
import vision.salient.choam.federation.GossipProtocol
import vision.salient.choam.federation.TransferPriority
import vision.salient.choam.network.NetworkDetector
import vision.salient.choam.network.ProgressMonitor
import java.io.File
import java.sql.DriverManager

/**
 * Gossip protocol — peer inventory announcements and bandwidth economy.
 */
class GossipParentCommand : CliktCommand(
    name = "gossip",
    help = """
        Peer coordination via gossip protocol.

        Nodes announce their inventory, capabilities, and needs. Trusted peers
        use this information to coordinate replication automatically.

        Subcommands:
          announce  — Broadcast your current state to peers
          peers     — Show latest announcements from all peers
          economy   — Show bandwidth reciprocity balances
          prune     — Clean old announcements

        Safety: announce sends data to peers. Other commands are read-only.

        Examples:
          choam gossip announce
          choam gossip peers
          choam gossip economy
          choam gossip prune
    """.trimIndent(),
    invokeWithoutSubcommand = true
) {
    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            GossipPeersSubcommand().parse(emptyList())
        }
    }
}

class GossipAnnounceSubcommand : CliktCommand(
    name = "announce",
    help = """
        Create a gossip announcement with your current inventory and capabilities.

        Reads the unified registry to count CIDs and total size, then creates
        an announcement record. In a full implementation, this would be broadcast
        to peers via SSH or HTTP.

        Safety: Creates a local record. Future: sends to peers.

        Examples:
          choam gossip announce
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
            echo("No House initialized. Run 'choam house init' first.")
            return
        }

        // Gather stats from unified registry
        val unifiedDbPath = "${System.getProperty("user.home")}/.choam/unified_registry.db"
        var cidCount = 0L
        var totalSize = 0L
        var needsReplication = 0L

        if (File(unifiedDbPath).exists()) {
            try {
                val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedDbPath")
                val rs = conn.createStatement().executeQuery(
                    "SELECT COUNT(DISTINCT cid) as cids, COALESCE(SUM(file_size), 0) as size FROM content_locations"
                )
                rs.next()
                cidCount = rs.getLong("cids")
                totalSize = rs.getLong("size")
                rs.close()

                // Count single-copy CIDs (need replication)
                val needsRs = conn.createStatement().executeQuery("""
                    SELECT COUNT(*) FROM (
                        SELECT cid FROM content_locations GROUP BY cid HAVING COUNT(DISTINCT machine_name) = 1
                    )
                """)
                needsRs.next()
                needsReplication = needsRs.getLong(1)
                needsRs.close()
                conn.close()
            } catch (_: Exception) {}
        }

        val sharedRepos = config.repositories.keys.toList()

        val gossip = GossipProtocol()
        val conn = gossip.open()
        val announcement = gossip.createAnnouncement(
            conn, house.houseId, house.name,
            NodeCapability(),
            cidCount, totalSize, needsReplication, sharedRepos
        )
        conn.close()

        echo("Announcement created:")
        echo("  House: ${house.name}")
        echo("  CIDs: ${"%,d".format(cidCount)}")
        echo("  Size: ${ProgressMonitor.formatBytes(totalSize)}")
        echo("  Needs replication: ${"%,d".format(needsReplication)} CIDs")
        echo("  Shared repos: ${sharedRepos.joinToString()}")
    }
}

class GossipPeersSubcommand : CliktCommand(
    name = "peers",
    help = "Show latest gossip announcements from all peers."
) {
    override fun run() {
        val gossip = GossipProtocol()
        val conn = gossip.open()
        val announcements = gossip.getLatestAnnouncements(conn)
        conn.close()

        if (announcements.isEmpty()) {
            echo("No peer announcements. Run 'choam gossip announce' to create yours.")
            return
        }

        echo("Peer Announcements:")
        echo()
        for (a in announcements) {
            echo("  ${a.houseName} (${a.houseId.take(12)}...)")
            echo("    CIDs: ${"%,d".format(a.cidCount)}  Size: ${ProgressMonitor.formatBytes(a.totalSizeBytes)}")
            echo("    Needs replication: ${"%,d".format(a.needsReplication)}")
            echo("    Repos: ${a.sharedRepos.joinToString()}")
            echo("    Last seen: ${a.timestamp}")
            echo()
        }
    }
}

class GossipEconomySubcommand : CliktCommand(
    name = "economy",
    help = """
        Show bandwidth reciprocity balances with all peers.

        Tracks who contributes and who consumes. Peers with positive balance
        (net contributors) get priority. No cryptocurrency — just reciprocity.

        Safety: Read-only.

        Examples:
          choam gossip economy
    """.trimIndent()
) {
    override fun run() {
        val config = try {
            ConfigResolver.resolve()
        } catch (e: Exception) {
            echo("Failed to load config: ${e.message}")
            return
        }

        val economy = BandwidthEconomy()
        val conn = economy.open()
        val balances = economy.getAllBalances(conn)
        conn.close()

        if (balances.isEmpty()) {
            echo("No transfer history yet.")
            return
        }

        echo("Bandwidth Economy:")
        echo()
        for (balance in balances) {
            val peerName = config.house?.peers?.get(balance.peerHouseId)?.name ?: balance.peerHouseId.take(12)
            val balanceStr = if (balance.balance >= 0) {
                "\u001b[32m+${ProgressMonitor.formatBytes(balance.balance)}\u001b[0m"
            } else {
                "\u001b[31m-${ProgressMonitor.formatBytes(-balance.balance)}\u001b[0m"
            }
            val priority = economy.open().use { c -> economy.getPriority(c, balance.peerHouseId) }
            val priorityColor = when (priority) {
                TransferPriority.HIGH -> "\u001b[32m"
                TransferPriority.NORMAL -> ""
                TransferPriority.LOW -> "\u001b[33m"
                TransferPriority.THROTTLED -> "\u001b[31m"
            }

            // Estimate bandwidth to this peer
            val peerIp = config.house?.peers?.values?.find { it.name == peerName }?.tailscaleIp
            val bandwidthStr = if (peerIp != null) {
                "~${ProgressMonitor.formatBytes(NetworkDetector.DEFAULT_BANDWIDTH_BYTES_PER_SEC)}/s"
            } else {
                "unknown"
            }

            echo("  ${peerName.padEnd(20)} balance: $balanceStr  priority: $priorityColor${priority}\u001b[0m  bandwidth: $bandwidthStr")
            echo("    Uploaded: ${ProgressMonitor.formatBytes(balance.bytesUploaded)}  Downloaded: ${ProgressMonitor.formatBytes(balance.bytesDownloaded)}  Transfers: ${balance.transferCount}")
        }
    }
}

class GossipPruneSubcommand : CliktCommand(
    name = "prune",
    help = "Remove old gossip announcements (keep latest 10 per peer)."
) {
    override fun run() {
        val gossip = GossipProtocol()
        val conn = gossip.open()
        val pruned = gossip.prune(conn)
        conn.close()

        echo("Pruned $pruned old announcement(s).")
    }
}

fun gossipCommand(): GossipParentCommand = GossipParentCommand().subcommands(
    GossipAnnounceSubcommand(),
    GossipPeersSubcommand(),
    GossipEconomySubcommand(),
    GossipPruneSubcommand()
)

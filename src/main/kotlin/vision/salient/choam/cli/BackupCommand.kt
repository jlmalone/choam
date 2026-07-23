package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import vision.salient.choam.config.BackupStatus
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.dag.ConfigResolver
import vision.salient.choam.dag.DagEventType
import vision.salient.choam.dag.writeDagEvent
import vision.salient.choam.federation.FederationStore
import vision.salient.choam.network.ProgressMonitor

/**
 * Mutual backup management — offer/accept storage with peers.
 */
class BackupParentCommand : CliktCommand(
    name = "backup",
    help = """
        Manage mutual backup agreements with peer Houses.

        Peers agree to store each other's encrypted data. Content is encrypted
        at rest on peer storage (STORE access level) — only the owner has the view key.

        Subcommands:
          offer   — Offer storage capacity to a peer
          accept  — Accept a peer's backup offer
          list    — Show all backup agreements
          suspend — Temporarily pause an agreement
          terminate — End an agreement

        Safety: offer/accept modify the federation database. Content encryption
        ensures peers cannot read your backed-up data.

        Examples:
          choam backup offer --to abc123 --size 2TB
          choam backup accept --from abc123 --their-size 1TB
          choam backup list
    """.trimIndent(),
    invokeWithoutSubcommand = true
) {
    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            BackupListSubcommand().parse(emptyList())
        }
    }
}

class BackupOfferSubcommand : CliktCommand(
    name = "offer",
    help = """
        Offer storage capacity to a peer House for mutual backup.

        Creates a backup proposal. The peer must accept before data flows.
        Storage is STORE-level only — encrypted blobs without view keys.

        Key behaviors:
          - Size is in human-readable format (e.g. 2TB, 500GB)
          - Overwrites any existing proposal to the same peer
          - Logged to federation audit trail

        Safety: Modifies federation DB. No data is transferred until the peer accepts.

        Examples:
          choam backup offer --to abc123 --size 2TB
          choam backup offer --to abc123 --size 500GB
    """.trimIndent()
) {
    private val to by option("--to", help = "Peer House ID to offer backup to").required()
    private val size by option("--size", help = "Storage to offer (e.g. 2TB, 500GB)").required()

    override fun run() {
        val config = try {
            ConfigResolver.resolve()
        } catch (e: Exception) {
            echo("Failed to load config: ${e.message}")
            return
        }

        val house = config.house
        if (house == null || house.houseId.isEmpty()) {
            echo("No House initialized.")
            return
        }

        if (!house.peers.containsKey(to)) {
            echo("Unknown peer '$to'. Add them first with 'choam house add-peer'.")
            return
        }

        val bytes = parseSize(size)
        if (bytes == null) {
            echo("Invalid size '$size'. Use format: 500GB, 2TB, 100MB")
            return
        }

        val store = FederationStore()
        val conn = store.open()
        store.proposeBackup(conn, to, bytes)
        conn.close()

        writeDagEvent(DagEventType.BACKUP_OFFERED, mapOf(
            "peerHouseId" to to, "offeredBytes" to bytes.toString()
        ))

        val peerName = house.peers[to]?.name ?: to.take(12)
        echo("Offered ${ProgressMonitor.formatBytes(bytes)} of backup storage to $peerName")
        echo("Waiting for them to accept. They should run:")
        echo("  choam backup accept --from ${house.houseId} --their-size <their-offer>")
    }
}

class BackupAcceptSubcommand : CliktCommand(
    name = "accept",
    help = """
        Accept a peer's backup offer and specify what you offer in return.

        Establishes a mutual backup agreement. Both sides must agree before
        data flows.

        Safety: Modifies federation DB. Encrypted data will begin flowing after acceptance.

        Examples:
          choam backup accept --from abc123 --their-size 1TB
    """.trimIndent()
) {
    private val from by option("--from", help = "Peer House ID to accept from").required()
    private val theirSize by option("--their-size", help = "What they're offering you (e.g. 1TB)")

    override fun run() {
        val theirBytes = if (theirSize != null) parseSize(theirSize!!) ?: 0L else 0L

        val store = FederationStore()
        val conn = store.open()
        val accepted = store.acceptBackup(conn, from, theirBytes)
        conn.close()

        if (accepted) {
            writeDagEvent(DagEventType.BACKUP_ACCEPTED, mapOf(
                "peerHouseId" to from, "theirOfferedBytes" to theirBytes.toString()
            ))
            echo("Backup agreement accepted with $from")
            if (theirBytes > 0) echo("  They offer you: ${ProgressMonitor.formatBytes(theirBytes)}")
        } else {
            echo("No pending backup proposal from '$from'")
        }
    }
}

class BackupListSubcommand : CliktCommand(
    name = "list",
    help = "List all backup agreements and their status."
) {
    override fun run() {
        val config = try {
            ConfigResolver.resolve()
        } catch (e: Exception) {
            echo("Failed to load config: ${e.message}")
            return
        }

        val store = FederationStore()
        val conn = store.open()
        val agreements = store.listBackupAgreements(conn)
        conn.close()

        if (agreements.isEmpty()) {
            echo("No backup agreements. Use 'choam backup offer' to propose one.")
            return
        }

        echo("Backup Agreements:")
        echo()
        for (agreement in agreements) {
            val peerName = config.house?.peers?.get(agreement.peerHouseId)?.name ?: agreement.peerHouseId.take(12)
            val statusColor = when (agreement.status) {
                BackupStatus.ACCEPTED, BackupStatus.ACTIVE -> "\u001b[32m"
                BackupStatus.PROPOSED -> "\u001b[33m"
                BackupStatus.SUSPENDED -> "\u001b[33m"
                BackupStatus.TERMINATED -> "\u001b[31m"
            }
            echo("  $peerName")
            echo("    Status:    $statusColor${agreement.status}\u001b[0m")
            echo("    We offer:  ${ProgressMonitor.formatBytes(agreement.offeredBytes)}")
            echo("    They used: ${ProgressMonitor.formatBytes(agreement.receivedBytes)}")
            echo("    They offer: ${ProgressMonitor.formatBytes(agreement.theirOfferedBytes)}")
            echo("    We used:   ${ProgressMonitor.formatBytes(agreement.ourUsedBytes)}")
            echo("    Created:   ${agreement.createdAt}")
            echo()
        }
    }
}

class BackupSuspendSubcommand : CliktCommand(
    name = "suspend",
    help = "Temporarily suspend a backup agreement."
) {
    private val peer by argument(help = "Peer House ID to suspend")

    override fun run() {
        val store = FederationStore()
        val conn = store.open()
        val updated = store.updateBackupStatus(conn, peer, BackupStatus.SUSPENDED)
        conn.close()

        if (updated) echo("Suspended backup agreement with $peer")
        else echo("No active backup agreement with '$peer'")
    }
}

class BackupTerminateSubcommand : CliktCommand(
    name = "terminate",
    help = "Permanently end a backup agreement. Requires typing TERMINATE to confirm."
) {
    private val peer by argument(help = "Peer House ID to terminate")

    override fun run() {
        echo("This will permanently end the backup agreement with $peer.")
        echo("Data already stored is NOT deleted — it just stops syncing.")
        echo("Type TERMINATE to confirm: ", trailingNewline = false)
        val confirm = readlnOrNull()?.trim()
        if (confirm != "TERMINATE") {
            echo("Cancelled.")
            return
        }

        val store = FederationStore()
        val conn = store.open()
        val updated = store.updateBackupStatus(conn, peer, BackupStatus.TERMINATED)
        conn.close()

        if (updated) {
            writeDagEvent(DagEventType.BACKUP_TERMINATED, mapOf("peerHouseId" to peer))
            echo("Terminated backup agreement with $peer")
        }
        else { echo("No backup agreement with '$peer'") }
    }
}

fun backupCommand(): BackupParentCommand = BackupParentCommand().subcommands(
    BackupOfferSubcommand(),
    BackupAcceptSubcommand(),
    BackupListSubcommand(),
    BackupSuspendSubcommand(),
    BackupTerminateSubcommand()
)

/**
 * Parse human-readable size strings like "2TB", "500GB", "100MB" to bytes.
 */
fun parseSize(size: String): Long? {
    val normalized = size.trim().uppercase()
    val regex = Regex("""(\d+(?:\.\d+)?)\s*(TB|GB|MB|KB|B)""")
    val match = regex.matchEntire(normalized) ?: return null
    val value = match.groupValues[1].toDoubleOrNull() ?: return null
    val unit = match.groupValues[2]
    return when (unit) {
        "TB" -> (value * 1_099_511_627_776).toLong()
        "GB" -> (value * 1_073_741_824).toLong()
        "MB" -> (value * 1_048_576).toLong()
        "KB" -> (value * 1_024).toLong()
        "B" -> value.toLong()
        else -> null
    }
}

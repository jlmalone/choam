package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import vision.salient.choam.config.AccessLevel
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.dag.ConfigResolver
import vision.salient.choam.dag.DagEventType
import vision.salient.choam.dag.writeDagEvent
import vision.salient.choam.config.ShareGrant
import vision.salient.choam.federation.FederationStore

/**
 * Repository sharing — grant/revoke access for peer Houses.
 */
class ShareParentCommand : CliktCommand(
    name = "share",
    help = """
        Share repositories with peer Houses in the federation.

        Controls who can access your repositories and at what level. Requires
        an initialized House and at least one peer.

        Access levels (from VISION_DOC trust tiers):
          STORE — Peer holds encrypted blob. Cannot read content.
          READ  — Peer can pull (download) content.
          WRITE — Peer can push (upload) changes.

        Subcommands:
          grant   — Grant a peer access to a repository
          revoke  — Revoke access from a peer
          list    — Show all active share grants

        Safety: grant/revoke modify the federation database. list is read-only.

        Examples:
          choam share grant film --with abc123 --access read
          choam share revoke film --from abc123
          choam share list
    """.trimIndent(),
    invokeWithoutSubcommand = true
) {
    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            ShareListSubcommand().parse(emptyList())
        }
    }
}

class ShareGrantSubcommand : CliktCommand(
    name = "grant",
    help = """
        Grant a peer House access to a repository.

        Creates or updates a share grant. If the peer already has access to this
        repository, the access level is updated. Access levels: STORE, READ, WRITE.

        Key behaviors:
          - Validates repository exists in config
          - Validates peer House ID exists in your peers list
          - Upserts: re-granting updates the access level
          - Logged to federation audit trail

        Safety: Modifies federation database. The peer can access the repo at the granted level.

        Examples:
          choam share grant film --with abc123 --access read
          choam share grant backup --with abc123 --access store --note "encrypted backup"
    """.trimIndent()
) {
    private val repository by argument(help = "Repository to share")
    private val with by option("--with", help = "Peer House ID to share with")
    private val access by option("--access", "-a", help = "Access level: store, read, write (default: read)").default("read")
    private val note by option("--note", help = "Reason or note for the grant")

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

        if (!config.repositories.containsKey(repository)) {
            echo("Unknown repository '$repository'. Available: ${config.repositories.keys.joinToString()}")
            return
        }

        val peerId = with ?: run {
            echo("--with is required (peer House ID)")
            return
        }

        if (!house.peers.containsKey(peerId)) {
            echo("Unknown peer '$peerId'. Add them first: choam house add-peer <name> --id $peerId")
            return
        }

        val accessLevel = try {
            AccessLevel.valueOf(access.uppercase())
        } catch (_: Exception) {
            echo("Invalid access level '$access'. Use: store, read, write")
            return
        }

        val store = FederationStore()
        val conn = store.open()
        val peerName = house.peers[peerId]?.name ?: peerId.take(12)

        store.grantShare(conn, ShareGrant(
            repository = repository,
            peerHouseId = peerId,
            access = accessLevel,
            note = note ?: ""
        ))

        conn.close()

        writeDagEvent(DagEventType.SHARE_GRANTED, mapOf(
            "repository" to repository,
            "peerHouseId" to peerId,
            "accessLevel" to accessLevel.name
        ))

        echo("Shared: $repository → $peerName (${accessLevel.name})")
    }
}

class ShareRevokeSubcommand : CliktCommand(
    name = "revoke",
    help = """
        Revoke a peer House's access to a repository.

        Marks the share grant as revoked. The peer will no longer be able to
        pull or push to this repository. Does not affect data already transferred.

        Safety: Modifies federation database. Data already on the peer is NOT deleted.

        Examples:
          choam share revoke film --from abc123
    """.trimIndent()
) {
    private val repository by argument(help = "Repository to unshare")
    private val from by option("--from", help = "Peer House ID to revoke from")

    override fun run() {
        val config = try {
            ConfigResolver.resolve()
        } catch (e: Exception) {
            echo("Failed to load config: ${e.message}")
            return
        }

        val peerId = from ?: run {
            echo("--from is required (peer House ID)")
            return
        }

        val store = FederationStore()
        val conn = store.open()

        val revoked = store.revokeShare(conn, repository, peerId)
        conn.close()

        if (revoked) {
            writeDagEvent(DagEventType.SHARE_REVOKED, mapOf(
                "repository" to repository, "peerHouseId" to peerId
            ))
            val peerName = config.house?.peers?.get(peerId)?.name ?: peerId.take(12)
            echo("Revoked: $repository access from $peerName")
        } else {
            echo("No active share found for '$repository' with peer '$peerId'")
        }
    }
}

class ShareListSubcommand : CliktCommand(
    name = "list",
    help = """
        List all active share grants.

        Shows which repositories are shared with which peers and at what access level.

        Safety: Read-only.

        Examples:
          choam share list
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
            echo("No House initialized.")
            return
        }

        val store = FederationStore()
        val conn = store.open()
        val shares = store.listActiveShares(conn)
        conn.close()

        if (shares.isEmpty()) {
            echo("No active shares. Use 'choam share grant <repo> --with <peer>' to share.")
            return
        }

        echo("Active Shares:")
        echo()
        for (share in shares) {
            val peerName = house.peers[share.peerHouseId]?.name ?: share.peerHouseId.take(12)
            echo("  ${share.repository.padEnd(16)} → ${peerName.padEnd(20)} ${share.access.name.padEnd(6)} (granted: ${share.grantedAt})")
            if (share.note.isNotEmpty()) echo("    Note: ${share.note}")
        }
    }
}

fun shareCommand(): ShareParentCommand = ShareParentCommand().subcommands(
    ShareGrantSubcommand(),
    ShareRevokeSubcommand(),
    ShareListSubcommand()
)

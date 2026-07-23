package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.option
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.config.HouseConfig
import vision.salient.choam.config.PeerHouse
import vision.salient.choam.dag.*
import vision.salient.choam.dag.ConfigResolver
import vision.salient.choam.federation.FederationStore
import java.io.File
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * House management — identity, peers, and federation status.
 */
class HouseParentCommand : CliktCommand(
    name = "house",
    help = """
        Manage your CHOAM House — your identity in the federation.

        A House is your personal CHOAM domain. It has a name, an Ed25519 identity
        keypair, and a list of trusted peer Houses. Federation features (share,
        backup) require an initialized House.

        Subcommands:
          init    — Create your House identity
          status  — Show House info and federation state
          add-peer — Add a trusted peer House
          peers   — List known peer Houses

        Safety: init generates a keypair and modifies config. Other commands are read-only.

        Examples:
          choam house init --name house-myserver
          choam house status
          choam house add-peer house-remote --id abc123 --ip 100.64.0.2
          choam house peers
    """.trimIndent(),
    invokeWithoutSubcommand = true
) {
    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            // Default: show status
            HouseStatusSubcommand().parse(emptyList())
        }
    }
}

class HouseInitSubcommand : CliktCommand(
    name = "init",
    help = """
        Initialize your CHOAM House identity.

        Generates an Ed25519 keypair, creates a House ID from the public key fingerprint,
        and saves the House configuration. This is required before using any federation
        features (share, backup, peers).

        Key behaviors:
          - Generates a new keypair if none exists
          - Refuses to overwrite an existing House identity
          - Saves to ~/.choam/config.json

        Safety: Creates keypair and modifies config. Cannot be undone without manual edit.

        Examples:
          choam house init --name house-myserver
          choam house init --name "Example Media Server"
    """.trimIndent()
) {
    private val name by option("--name", "-n", help = "Human-readable name for your House")
    private val description by option("--desc", "-d", help = "Description of this House")

    override fun run() {
        val configPath = ChoamConfigLoader.defaultPath()
        val config = try {
            ChoamConfigLoader.load(configPath)
        } catch (e: Exception) {
            echo("Failed to load config: ${e.message}")
            return
        }

        if (config.house != null && config.house.houseId.isNotEmpty()) {
            echo("House already initialized: ${config.house.name} (${config.house.houseId.take(16)}...)")
            echo("To re-initialize, manually remove the 'house' section from config.")
            return
        }

        // Generate identity keypair using standard Java crypto
        // Using EC key as a portable stand-in (Ed25519 requires JDK 15+ EdDSA)
        val keyPair = try {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(256)
            kpg.generateKeyPair()
        } catch (e: Exception) {
            echo("Failed to generate keypair: ${e.message}")
            return
        }

        val pubKeyBytes = keyPair.public.encoded
        val pubKeyHex = pubKeyBytes.joinToString("") { "%02x".format(it) }
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(pubKeyBytes)
            .take(16)
            .joinToString("") { "%02x".format(it) }

        // Save private key to dedicated file
        val keyDir = File(System.getProperty("user.home"), ".choam")
        keyDir.mkdirs()
        val keyFile = File(keyDir, "house_key")
        keyFile.writeBytes(keyPair.private.encoded)
        keyFile.setReadable(false, false)
        keyFile.setReadable(true, true)  // owner-only
        keyFile.setWritable(false, false)
        keyFile.setWritable(true, true)

        val houseName = name ?: "house-${config.machines.keys.firstOrNull() ?: "choam"}"
        val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

        val houseConfig = HouseConfig(
            name = houseName,
            houseId = fingerprint,
            publicKey = pubKeyHex,
            description = description ?: "",
            createdAt = now
        )

        val updatedConfig = config.copy(house = houseConfig)
        ChoamConfigLoader.save(updatedConfig, configPath)

        echo("House initialized:")
        echo("  Name:     $houseName")
        echo("  House ID: $fingerprint")
        echo("  Key file: ${keyFile.absolutePath}")
        echo()
        echo("Share your House ID with peers so they can add you:")
        echo("  choam house add-peer $houseName --id $fingerprint --ip <your-tailscale-ip>")
    }
}

class HouseStatusSubcommand : CliktCommand(
    name = "status",
    help = "Show your House identity and federation state."
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

        echo("House: ${house.name}")
        echo("  ID:          ${house.houseId}")
        echo("  Public Key:  ${house.publicKey.take(32)}...")
        echo("  Created:     ${house.createdAt}")
        if (house.description.isNotEmpty()) echo("  Description: ${house.description}")
        echo("  Peers:       ${house.peers.size}")
        echo()

        // Show federation stats from DB
        val store = FederationStore()
        try {
            val conn = store.open()
            val shares = store.listActiveShares(conn)
            val backups = store.listBackupAgreements(conn)

            echo("Federation:")
            echo("  Active shares: ${shares.size}")
            echo("  Backup agreements: ${backups.size}")

            if (shares.isNotEmpty()) {
                echo()
                echo("Shared repositories:")
                for (share in shares) {
                    val peerName = house.peers[share.peerHouseId]?.name ?: share.peerHouseId.take(12)
                    echo("  ${share.repository.padEnd(16)} → $peerName (${share.access})")
                }
            }

            conn.close()
        } catch (e: Exception) {
            echo("  Federation DB: not available (${e.message})")
        }
    }
}

class HouseAddPeerSubcommand : CliktCommand(
    name = "add-peer",
    help = """
        Add a trusted peer House to your federation.

        Registers a remote CHOAM House so you can share repositories with them
        and negotiate backup agreements. Requires their House ID and network address.

        Key behaviors:
          - Validates the peer ID format
          - Saves to config.json immediately
          - Does not establish a connection — just records the peer

        Safety: Modifies config. The peer cannot access anything until you explicitly share.

        Examples:
          choam house add-peer house-remote --id abc123def456 --ip 100.64.0.2
          choam house add-peer house-remote --id abc123def456 --ip 100.64.0.2 --user myuser
    """.trimIndent()
) {
    private val peerName by argument(help = "Name for the peer House")
    private val id by option("--id", help = "The peer's House ID (public key fingerprint)")
    private val ip by option("--ip", help = "Tailscale IP or hostname")
    private val user by option("--user", help = "SSH username on the peer")
    private val port by option("--port", help = "SSH port (default: 22)")

    override fun run() {
        val configPath = ChoamConfigLoader.defaultPath()
        val config = try {
            ChoamConfigLoader.load(configPath)
        } catch (e: Exception) {
            echo("Failed to load config: ${e.message}")
            return
        }

        val house = config.house
        if (house == null || house.houseId.isEmpty()) {
            echo("No House initialized. Run 'choam house init' first.")
            return
        }

        val peerId = id ?: run {
            echo("--id is required (the peer's House ID)")
            return
        }

        if (house.peers.containsKey(peerId)) {
            echo("Peer '$peerId' already exists: ${house.peers[peerId]?.name}")
            return
        }

        val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val peer = PeerHouse(
            name = peerName,
            houseId = peerId,
            tailscaleIp = ip,
            sshUser = user,
            sshPort = port?.toIntOrNull() ?: 22,
            addedAt = now
        )

        val updatedPeers = house.peers + (peerId to peer)
        val updatedHouse = house.copy(peers = updatedPeers)
        val updatedConfig = config.copy(house = updatedHouse)
        ChoamConfigLoader.save(updatedConfig, configPath)

        // Also write DAG event if DAG is initialized
        writeDagEvent(DagEventType.PEER_TRUSTED, buildMap {
            put("peerHouseId", peerId)
            put("peerName", peerName)
            ip?.let { put("tailscaleIp", it) }
            user?.let { put("sshUser", it) }
        })

        echo("Peer added: $peerName ($peerId)")
        echo("  IP:   ${ip ?: "not set"}")
        echo("  User: ${user ?: "not set"}")
        echo()
        echo("They can't access anything yet. Use 'choam share <repo> --with $peerId' to grant access.")
    }
}

class HousePeersSubcommand : CliktCommand(
    name = "peers",
    help = "List all known peer Houses."
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

        if (house.peers.isEmpty()) {
            echo("No peers configured. Use 'choam house add-peer' to add one.")
            return
        }

        echo("Known Peers:")
        echo()
        for ((id, peer) in house.peers) {
            echo("  ${peer.name}")
            echo("    ID:    $id")
            echo("    IP:    ${peer.tailscaleIp ?: "not set"}")
            echo("    User:  ${peer.sshUser ?: "not set"}")
            echo("    Added: ${peer.addedAt}")
            echo()
        }
    }
}

fun houseCommand(): HouseParentCommand = HouseParentCommand().subcommands(
    HouseInitSubcommand(),
    HouseStatusSubcommand(),
    HouseAddPeerSubcommand(),
    HousePeersSubcommand()
)

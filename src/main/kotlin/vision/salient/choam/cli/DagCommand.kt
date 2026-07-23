package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.dag.*
import vision.salient.choam.daemon.DaemonState
import vision.salient.choam.federation.FederationStore
import vision.salient.choam.network.ProgressMonitor
import java.io.File
import java.nio.file.Path

private val prettyJson = Json { prettyPrint = true; encodeDefaults = true }

/**
 * DAG protocol commands — manage the event-sourced configuration DAG.
 */
class DagParentCommand : CliktCommand(
    name = "dag",
    help = """
        CHOAM DAG protocol — decentralized config and access control.

        Every config change becomes an immutable, signed event in a directed acyclic
        graph. Events propagate across all your nodes automatically.

        Subcommands:
          init    — Create Ed25519 identity and genesis event
          status  — Show DAG statistics
          log     — Show event history
          migrate — Import existing config.json + federation.db as DAG events
          verify  — Verify all signatures and hashes
          export  — Export DAG as JSON
          import  — Import DAG events from JSON file

        Examples:
          choam dag init
          choam dag status
          choam dag log --limit 20
          choam dag migrate
    """.trimIndent(),
    invokeWithoutSubcommand = true
) {
    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            DagStatusSubcommand().parse(emptyList())
        }
    }
}

class DagInitSubcommand : CliktCommand(
    name = "init",
    help = "Generate Ed25519 keypair and create genesis HOUSE_CREATED event."
) {
    private val name by option("--name", "-n", help = "House name")

    override fun run() {
        val dagStore = DagStore()
        val conn = dagStore.open()

        // Check if already initialized
        val existingCount = dagStore.getEventCount(conn)
        if (existingCount > 0) {
            echo("DAG already initialized (${existingCount} events). Use 'choam dag status' to view.")
            conn.close()
            return
        }

        // Generate Ed25519 keypair
        val (publicKeyHex, privateKeyHex) = DagCrypto.generateKeyPair()
        val houseId = DagCrypto.deriveHouseId(publicKeyHex)

        // Save private key
        val keyPath = Path.of(System.getProperty("user.home"), ".choam", "house_key_ed25519")
        DagCrypto.savePrivateKey(privateKeyHex, keyPath)

        // Determine house name
        val houseName = name ?: "house-choam"

        // Create engine and genesis event
        val engine = DagEngine(dagStore, houseId, "local", publicKeyHex, privateKeyHex)
        val event = engine.createEvent(conn, DagEventType.HOUSE_CREATED, mapOf(
            "name" to houseName,
            "publicKey" to publicKeyHex,
            "description" to "CHOAM DAG identity"
        ))

        conn.close()

        echo("DAG initialized:")
        echo("  House:      $houseName")
        echo("  House ID:   $houseId")
        echo("  Public Key: ${publicKeyHex.take(16)}...")
        echo("  Key file:   $keyPath")
        echo("  Genesis:    ${event.id}")
        echo()
        echo("Run 'choam dag migrate' to import your existing config.json.")
    }
}

class DagStatusSubcommand : CliktCommand(
    name = "status",
    help = "Show DAG statistics — event count, heads, Lamport clock."
) {
    override fun run() {
        val dagStore = DagStore()
        val conn = dagStore.open()

        val eventCount = dagStore.getEventCount(conn)
        if (eventCount == 0L) {
            echo("DAG not initialized. Run 'choam dag init' first.")
            conn.close()
            return
        }

        val maxLamport = dagStore.getMaxLamport(conn)

        // Count by type
        val rs = conn.createStatement().executeQuery(
            "SELECT type, COUNT(*) as cnt FROM events GROUP BY type ORDER BY cnt DESC"
        )
        val typeCounts = mutableMapOf<String, Long>()
        while (rs.next()) typeCounts[rs.getString("type")] = rs.getLong("cnt")
        rs.close()

        // Heads
        val headsRs = conn.createStatement().executeQuery("SELECT house_id, head_id FROM heads")
        val heads = mutableMapOf<String, String>()
        while (headsRs.next()) heads[headsRs.getString("house_id")] = headsRs.getString("head_id")
        headsRs.close()

        // Materialize to show summary
        val materializer = StateMaterializer(dagStore)
        val state = materializer.materialize(conn)
        conn.close()

        echo("CHOAM DAG Status:")
        echo("  Events:       ${"%,d".format(eventCount)}")
        echo("  Lamport:      $maxLamport")
        echo("  Heads:        ${heads.size}")
        echo()

        echo("Event Types:")
        for ((type, count) in typeCounts) {
            echo("  ${type.padEnd(24)} $count")
        }
        echo()

        echo("Materialized State:")
        echo("  House:        ${state.house?.name ?: "none"}")
        echo("  Machines:     ${state.machines.size}")
        echo("  Drives:       ${state.drives.size}")
        echo("  Repositories: ${state.repositories.size}")
        echo("  Peers:        ${state.house?.peers?.size ?: 0}")
        echo("  Shares:       ${state.shares.size}")
        echo("  Backups:      ${state.backups.size}")
    }
}

class DagLogSubcommand : CliktCommand(
    name = "log",
    help = "Show recent DAG events (like git log)."
) {
    private val limit by option("--limit", "-n", help = "Number of events to show").default("20")

    override fun run() {
        val dagStore = DagStore()
        val conn = dagStore.open()
        val events = dagStore.getAllEvents(conn)
        conn.close()

        if (events.isEmpty()) {
            echo("No events. Run 'choam dag init' first.")
            return
        }

        val shown = events.takeLast(limit.toInt()).reversed()
        echo("${events.size} events total (showing last ${shown.size}):")
        echo()

        for (event in shown) {
            val sig = if (event.signature != null) "\u001b[32m✓\u001b[0m" else "\u001b[33m?\u001b[0m"
            echo("$sig ${event.id.take(20)}...  ${event.type.padEnd(22)} lamport=${event.timestamp.lamport}")
            echo("  ${event.timestamp.wall}  author=${event.author.houseId.take(12)}...  machine=${event.author.machineId}")

            // Show key payload fields
            val payloadSummary = event.payload.entries.take(3).joinToString(", ") { "${it.key}=${it.value.take(30)}" }
            if (payloadSummary.isNotEmpty()) echo("  $payloadSummary")
            echo()
        }
    }
}

class DagMigrateSubcommand : CliktCommand(
    name = "migrate",
    help = "Import existing config.json + federation.db as DAG events."
) {
    override fun run() {
        val dagStore = DagStore()
        val conn = dagStore.open()

        // Need an initialized DAG first
        val eventCount = dagStore.getEventCount(conn)
        if (eventCount == 0L) {
            echo("DAG not initialized. Run 'choam dag init' first.")
            conn.close()
            return
        }

        // Load private key
        val keyPath = Path.of(System.getProperty("user.home"), ".choam", "house_key_ed25519")
        if (!keyPath.toFile().exists()) {
            echo("No Ed25519 key found at $keyPath. Run 'choam dag init' first.")
            conn.close()
            return
        }
        val privateKeyHex = DagCrypto.loadPrivateKey(keyPath)

        // Get house ID from genesis event
        val genesis = dagStore.getEventsByType(conn, DagEventType.HOUSE_CREATED).firstOrNull()
        if (genesis == null) {
            echo("No genesis event found.")
            conn.close()
            return
        }
        val houseId = genesis.author.houseId
        val publicKeyHex = genesis.author.publicKey ?: genesis.payload["publicKey"] ?: ""

        val engine = DagEngine(dagStore, houseId, "local", publicKeyHex, privateKeyHex)
        var migrated = 0

        // Load existing config
        val config = try {
            ChoamConfigLoader.load()
        } catch (e: Exception) {
            echo("Failed to load config.json: ${e.message}")
            conn.close()
            return
        }

        // Migrate machines
        for ((name, machine) in config.machines) {
            engine.createEvent(conn, DagEventType.MACHINE_JOINED, buildMap {
                put("name", name)
                put("hostname", machine.hostname)
                put("type", machine.type.name)
                machine.tailscaleIp?.let { put("tailscaleIp", it) }
                machine.sshUser?.let { put("sshUser", it) }
                put("sshPort", machine.sshPort.toString())
                put("networkPreference", machine.networkPreference.name)
                if (machine.repositories.isNotEmpty()) {
                    put("repositories", prettyJson.encodeToString(machine.repositories))
                }
                if (machine.aliases.isNotEmpty()) {
                    put("aliases", machine.aliases.joinToString(","))
                }
            })
            migrated++
        }

        // Migrate drives
        for ((key, drive) in config.drives) {
            engine.createEvent(conn, DagEventType.DRIVE_ADDED, buildMap {
                put("key", key)
                put("label", drive.label)
                put("uuid", drive.uuid)
                put("storageClass", drive.storageClass.name)
                if (drive.repositories.isNotEmpty()) {
                    put("repos", prettyJson.encodeToString(drive.repositories))
                }
            })
            migrated++
        }

        // Migrate repositories
        for ((name, repo) in config.repositories) {
            engine.createEvent(conn, DagEventType.REPO_CREATED, buildMap {
                put("name", name)
                put("type", repo.type.name)
                put("localPath", repo.localPath)
                put("minCopies", repo.replication.minCopies.toString())
                put("preferredCopies", repo.replication.preferredCopies.toString())
            })
            migrated++
        }

        // Migrate federation (peers, shares, backups)
        config.house?.let { house ->
            for ((peerId, peer) in house.peers) {
                engine.createEvent(conn, DagEventType.PEER_TRUSTED, buildMap {
                    put("peerHouseId", peerId)
                    put("peerName", peer.name)
                    peer.publicKey.takeIf { it.isNotEmpty() }?.let { put("peerPublicKey", it) }
                    peer.tailscaleIp?.let { put("tailscaleIp", it) }
                    peer.sshUser?.let { put("sshUser", it) }
                })
                migrated++
            }
        }

        // Migrate shares from federation.db
        try {
            val fedStore = FederationStore()
            val fedConn = fedStore.open()
            val shares = fedStore.listActiveShares(fedConn)
            for (share in shares) {
                engine.createEvent(conn, DagEventType.SHARE_GRANTED, mapOf(
                    "repository" to share.repository,
                    "peerHouseId" to share.peerHouseId,
                    "accessLevel" to share.access.name
                ))
                migrated++
            }

            val backups = fedStore.listBackupAgreements(fedConn)
            for (backup in backups) {
                engine.createEvent(conn, DagEventType.BACKUP_OFFERED, mapOf(
                    "peerHouseId" to backup.peerHouseId,
                    "offeredBytes" to backup.offeredBytes.toString()
                ))
                migrated++
                if (backup.status.name in listOf("ACCEPTED", "ACTIVE")) {
                    engine.createEvent(conn, DagEventType.BACKUP_ACCEPTED, mapOf(
                        "peerHouseId" to backup.peerHouseId,
                        "theirOfferedBytes" to backup.theirOfferedBytes.toString()
                    ))
                    migrated++
                }
            }
            fedConn.close()
        } catch (e: Exception) {
            echo("  Federation DB: ${e.message} (skipped)")
        }

        conn.close()
        echo("Migrated $migrated config items to DAG events.")
        echo("Run 'choam dag status' to verify.")
    }
}

class DagVerifySubcommand : CliktCommand(
    name = "verify",
    help = "Verify all DAG event signatures and hash integrity."
) {
    override fun run() {
        val dagStore = DagStore()
        val conn = dagStore.open()
        val events = dagStore.getAllEvents(conn)

        if (events.isEmpty()) {
            echo("No events to verify.")
            conn.close()
            return
        }

        var valid = 0
        var invalid = 0
        var unsigned = 0

        for (event in events) {
            // Verify hash
            val expectedId = CanonicalJson.hashEvent(event)
            if (event.id != expectedId) {
                echo("\u001b[31m✗\u001b[0m ${event.id.take(20)}... HASH MISMATCH (${event.type})")
                invalid++
                continue
            }

            // Verify signature
            if (event.signature == null) {
                unsigned++
                continue
            }

            val pubKey = event.author.publicKey
                ?: dagStore.getEventsByType(conn, DagEventType.HOUSE_CREATED)
                    .firstOrNull { it.author.houseId == event.author.houseId }?.author?.publicKey

            if (pubKey != null) {
                val canonicalJson = CanonicalJson.stringify(CanonicalJson.buildHashableMap(event))
                if (DagCrypto.verify(canonicalJson, event.signature, pubKey)) {
                    valid++
                } else {
                    echo("\u001b[31m✗\u001b[0m ${event.id.take(20)}... SIG INVALID (${event.type})")
                    invalid++
                }
            } else {
                unsigned++
            }
        }

        conn.close()

        echo("DAG Verification:")
        echo("  Total events: ${events.size}")
        echo("  \u001b[32mValid:       $valid\u001b[0m")
        if (invalid > 0) echo("  \u001b[31mInvalid:     $invalid\u001b[0m")
        if (unsigned > 0) echo("  \u001b[33mUnsigned:    $unsigned\u001b[0m")

        if (invalid == 0) {
            echo("  \u001b[32mAll hashes and signatures verified.\u001b[0m")
        }
    }
}

class DagExportSubcommand : CliktCommand(
    name = "export",
    help = "Export all DAG events as JSON."
) {
    private val output by argument(help = "Output file path").optional()

    override fun run() {
        val dagStore = DagStore()
        val conn = dagStore.open()
        val events = dagStore.getAllEvents(conn)
        conn.close()

        val jsonStr = prettyJson.encodeToString(events)

        if (output != null) {
            File(output!!).writeText(jsonStr)
            echo("Exported ${events.size} events to $output")
        } else {
            echo(jsonStr)
        }
    }
}

class DagImportSubcommand : CliktCommand(
    name = "import",
    help = "Import DAG events from a JSON file (with validation)."
) {
    private val input by argument(help = "JSON file to import")

    override fun run() {
        val file = File(input)
        if (!file.exists()) {
            echo("File not found: $input")
            return
        }

        val events: List<DagEvent> = try {
            prettyJson.decodeFromString(file.readText())
        } catch (e: Exception) {
            echo("Failed to parse JSON: ${e.message}")
            return
        }

        val dagStore = DagStore()
        val conn = dagStore.open()

        // Need an initialized DAG
        val keyPath = Path.of(System.getProperty("user.home"), ".choam", "house_key_ed25519")
        val privateKeyHex = if (keyPath.toFile().exists()) DagCrypto.loadPrivateKey(keyPath) else ""
        val genesis = dagStore.getEventsByType(conn, DagEventType.HOUSE_CREATED).firstOrNull()
        val houseId = genesis?.author?.houseId ?: ""
        val publicKeyHex = genesis?.author?.publicKey ?: ""

        val engine = DagEngine(dagStore, houseId, "local", publicKeyHex, privateKeyHex)

        var imported = 0
        var skipped = 0
        var rejected = 0

        for (event in events) {
            val result = engine.validate(conn, event)
            when (result) {
                is ValidationResult.Valid -> {
                    if (dagStore.append(conn, event)) imported++ else skipped++
                }
                is ValidationResult.Invalid -> {
                    echo("  Rejected: ${event.id.take(20)}... — ${result.reason}")
                    rejected++
                }
            }
        }

        conn.close()
        echo("Import: $imported new, $skipped existing, $rejected rejected")
    }
}

class DagSyncSubcommand : CliktCommand(
    name = "sync",
    help = """
        Synchronize DAG events with remote machines over SSH.

        Pulls remote events we don't have, pushes local events they don't have.
        Both machines converge to the same state. Idempotent and order-independent.

        Requires choam to be installed on the remote machine (in PATH).

        Examples:
          choam dag sync --to server-a
          choam dag sync --all
    """.trimIndent()
) {
    private val to by option("--to", help = "Machine name to sync with")
    private val all by option("--all", "-a", help = "Sync with all reachable machines").flag()

    override fun run() {
        val config = try { ConfigResolver.resolve() } catch (e: Exception) {
            echo("Failed to load config: ${e.message}"); return
        }
        val dagStore = DagStore()
        val dagSync = DagSync(dagStore, config)

        if (all) {
            echo("Syncing DAG with all reachable machines...")
            val results = dagSync.syncAll()
            for ((name, result) in results) {
                if (result.success) {
                    echo("  \u001b[32m✓\u001b[0m $name: pulled ${result.pulled}, pushed ${result.pushed}")
                } else {
                    echo("  \u001b[31m✗\u001b[0m $name: ${result.error}")
                }
            }
            DaemonState.logActivity("dag_sync", "Synced with ${results.count { it.value.success }}/${results.size} machines")
        } else if (to != null) {
            echo("Syncing DAG with $to...")
            val result = dagSync.syncWith(to!!)
            if (result.success) {
                echo("  Pulled: ${result.pulled} events")
                echo("  Pushed: ${result.pushed} events")
                DaemonState.logActivity("dag_sync", "Synced with $to: pulled ${result.pulled}, pushed ${result.pushed}")
            } else {
                echo("  Failed: ${result.error}")
                DaemonState.logActivity("dag_sync", "Failed to sync with $to: ${result.error}", success = false)
            }
        } else {
            echo("Specify --to <machine> or --all")
        }
    }
}

fun dagCommand(): DagParentCommand = DagParentCommand().subcommands(
    DagInitSubcommand(),
    DagStatusSubcommand(),
    DagLogSubcommand(),
    DagMigrateSubcommand(),
    DagVerifySubcommand(),
    DagExportSubcommand(),
    DagImportSubcommand(),
    DagSyncSubcommand()
)

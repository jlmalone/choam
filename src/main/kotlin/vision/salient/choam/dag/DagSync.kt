package vision.salient.choam.dag

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import vision.salient.choam.config.ChoamConfig
import vision.salient.choam.config.MachineProfile
import vision.salient.choam.lowPriority
import java.io.File
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

private val pushJsonFormat = Json { prettyPrint = true; encodeDefaults = true }
private val parseJsonFormat = Json { ignoreUnknownKeys = true }

/**
 * DAG synchronization between machines over SSH.
 *
 * Protocol:
 * 1. SSH to remote → run `choam dag export` → capture JSON
 * 2. Parse remote events, validate, import new ones locally
 * 3. Export local events → SSH to remote → run `choam dag import`
 * 4. Both machines now have the union of all events
 *
 * Idempotent: duplicate events are ignored (content-addressed by ID).
 * Order-independent: events sort by Lamport clock during materialization.
 */
class DagSync(
    private val localStore: DagStore,
    private val config: ChoamConfig
) {
    /**
     * Sync DAG events with a remote machine.
     * @return SyncResult with counts of events exchanged
     */
    fun syncWith(machineName: String): SyncResult {
        val machine = config.machines[machineName]
            ?: return SyncResult(error = "Unknown machine: $machineName")

        val ip = machine.tailscaleIp ?: machine.hostname
        val user = machine.sshUser ?: return SyncResult(error = "No SSH user for $machineName")

        logger.info { "DAG sync starting with $machineName ($user@$ip)" }

        // Step 1: Pull remote events
        val remoteJson = sshExec(user, ip, machine.sshPort, "choam dag export")
            ?: return SyncResult(error = "Failed to SSH to $machineName — is choam installed there?")

        val remoteEvents = parseEvents(remoteJson)
        if (remoteEvents == null) {
            // Remote has no DAG — push our events there
            logger.info { "Remote $machineName has no DAG. Pushing local events." }
        }

        val conn = localStore.open()
        val localIds = localStore.getAllEventIds(conn)
        var pulled = 0
        var pushNeeded = 0

        // Step 2: Import remote events we don't have
        if (remoteEvents != null) {
            val keyPath = java.nio.file.Path.of(System.getProperty("user.home"), ".choam", "house_key_ed25519")
            val privateKeyHex = if (keyPath.toFile().exists()) DagCrypto.loadPrivateKey(keyPath) else ""
            val genesis = localStore.getEventsByType(conn, DagEventType.HOUSE_CREATED).firstOrNull()
            val houseId = genesis?.author?.houseId ?: ""
            val publicKeyHex = genesis?.author?.publicKey ?: ""
            val engine = DagEngine(localStore, houseId, "local", publicKeyHex, privateKeyHex)

            for (event in remoteEvents) {
                if (event.id in localIds) continue

                // Validate before importing
                // Skip parent check for events from remote — they may reference parents we haven't seen yet
                // We trust events from our own house (same house ID)
                val hashValid = CanonicalJson.hashEvent(event) == event.id
                if (!hashValid) {
                    logger.warn { "Rejecting event ${event.id.take(20)}: hash mismatch" }
                    continue
                }

                localStore.append(conn, event)
                pulled++
            }
        }

        // Step 3: Push local events they don't have
        val localEvents = localStore.getAllEvents(conn)
        conn.close()

        val remoteIds = remoteEvents?.map { it.id }?.toSet() ?: emptySet()
        val toPush = localEvents.filter { it.id !in remoteIds }
        pushNeeded = toPush.size

        if (toPush.isNotEmpty()) {
            val pushJson = pushJsonFormat
                .encodeToString(ListSerializer(DagEvent.serializer()), toPush)

            // Write to temp file, rsync to remote, import
            val tempFile = File.createTempFile("choam-dag-push-", ".json")
            tempFile.writeText(pushJson)

            val remoteTempPath = "/tmp/choam-dag-import-${System.currentTimeMillis()}.json"
            val rsyncOk = rsyncTo(user, ip, machine.sshPort, tempFile.absolutePath, remoteTempPath)
            if (rsyncOk) {
                sshExec(user, ip, machine.sshPort, "choam dag import $remoteTempPath && rm $remoteTempPath")
            }
            tempFile.delete()
        }

        val result = SyncResult(pulled = pulled, pushed = pushNeeded)
        logger.info { "DAG sync with $machineName: pulled $pulled, pushed $pushNeeded" }
        return result
    }

    /**
     * Sync with all reachable machines.
     */
    fun syncAll(): Map<String, SyncResult> {
        val results = mutableMapOf<String, SyncResult>()
        for ((name, machine) in config.machines) {
            if (machine.tailscaleIp == null && machine.sshUser == null) continue
            // Skip local machine
            val isLocal = try {
                machine.hostname == java.net.InetAddress.getLocalHost().hostName
            } catch (_: Exception) { false }
            if (isLocal) continue

            results[name] = try {
                syncWith(name)
            } catch (e: Exception) {
                SyncResult(error = e.message ?: "Unknown error")
            }
        }
        return results
    }

    // --- SSH helpers ---

    private fun sshExec(user: String, host: String, port: Int, command: String): String? {
        return try {
            val cmd = lowPriority(listOf("ssh", "-o", "StrictHostKeyChecking=no",
                "-o", "ConnectTimeout=10", "-p", "$port", "$user@$host", command))
            logger.debug { "SSH: ${cmd.joinToString(" ")}" }

            val process = ProcessBuilder(cmd)
                .redirectErrorStream(false)
                .start()

            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                logger.warn { "SSH timed out: $user@$host" }
                return null
            }

            val output = process.inputStream.bufferedReader().readText()
            val errors = process.errorStream.bufferedReader().readText()

            if (process.exitValue() != 0) {
                logger.debug { "SSH failed (exit ${process.exitValue()}): $errors" }
                return null
            }

            output
        } catch (e: Exception) {
            logger.debug { "SSH error: ${e.message}" }
            null
        }
    }

    private fun rsyncTo(user: String, host: String, port: Int, localPath: String, remotePath: String): Boolean {
        return try {
            val cmd = lowPriority(listOf("rsync", "--partial", "--compress",
                "-e", "ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 -p $port",
                localPath, "$user@$host:$remotePath"))
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            process.waitFor(30, TimeUnit.SECONDS)
            process.exitValue() == 0
        } catch (e: Exception) {
            logger.debug { "rsync error: ${e.message}" }
            false
        }
    }

    private fun parseEvents(json: String): List<DagEvent>? {
        if (json.isBlank() || json.startsWith("No events") || json.startsWith("{") || json.startsWith("choam")) {
            return null
        }
        return try {
            parseJsonFormat.decodeFromString<List<DagEvent>>(json)
        } catch (e: Exception) {
            logger.debug { "Failed to parse remote DAG: ${e.message}" }
            null
        }
    }
}

data class SyncResult(
    val pulled: Int = 0,
    val pushed: Int = 0,
    val error: String? = null
) {
    val success get() = error == null
}

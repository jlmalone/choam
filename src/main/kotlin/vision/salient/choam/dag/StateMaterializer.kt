package vision.salient.choam.dag

import mu.KotlinLogging
import vision.salient.choam.config.*

private val logger = KotlinLogging.logger {}

/**
 * Replays DAG events to compute the current materialized state.
 *
 * This replaces config.json + federation.db for DAG-managed state.
 * Events are sorted deterministically (Lamport → wall → id) and applied
 * sequentially to build up the current configuration.
 */
class StateMaterializer(private val store: DagStore) {

    /**
     * Replay all events → current materialized state.
     */
    fun materialize(conn: java.sql.Connection): MaterializedState {
        val events = store.getAllEvents(conn) // Already sorted by lamport ASC, wall ASC, id ASC

        var house: HouseConfig? = null
        val machines = mutableMapOf<String, MachineProfile>()
        val drives = mutableMapOf<String, Drive>()
        val repositories = mutableMapOf<String, RepositoryConfig>()
        val peers = mutableMapOf<String, PeerHouse>()
        val shares = mutableListOf<ShareGrant>()
        val backups = mutableMapOf<String, BackupAgreement>()

        for (event in events) {
            val p = event.payload
            when (event.type) {
                DagEventType.HOUSE_CREATED -> {
                    house = HouseConfig(
                        name = p["name"] ?: "",
                        houseId = event.author.houseId,
                        publicKey = event.author.publicKey ?: p["publicKey"] ?: "",
                        description = p["description"] ?: "",
                        createdAt = event.timestamp.wall
                    )
                }
                DagEventType.MACHINE_JOINED -> {
                    val name = p["name"] ?: continue
                    machines[name] = MachineProfile(
                        name = name,
                        hostname = p["hostname"] ?: "",
                        type = try { MachineType.valueOf(p["type"] ?: "DESKTOP") } catch (_: Exception) { MachineType.DESKTOP },
                        repositories = parseStringMap(p["repositories"]),
                        sshUser = p["sshUser"],
                        sshPort = p["sshPort"]?.toIntOrNull() ?: 22,
                        tailscaleIp = p["tailscaleIp"],
                        networkPreference = try { NetworkMode.valueOf(p["networkPreference"] ?: "AUTO") } catch (_: Exception) { NetworkMode.AUTO },
                        aliases = p["aliases"]?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
                    )
                }
                DagEventType.MACHINE_UPDATED -> {
                    val name = p["name"] ?: continue
                    val existing = machines[name] ?: continue
                    machines[name] = existing.copy(
                        hostname = p["hostname"] ?: existing.hostname,
                        tailscaleIp = p["tailscaleIp"] ?: existing.tailscaleIp,
                        sshUser = p["sshUser"] ?: existing.sshUser,
                        networkPreference = p["networkPreference"]?.let {
                            try { NetworkMode.valueOf(it) } catch (_: Exception) { null }
                        } ?: existing.networkPreference
                    )
                }
                DagEventType.MACHINE_LEFT -> {
                    val name = p["name"] ?: continue
                    machines.remove(name)
                }
                DagEventType.DRIVE_ADDED -> {
                    val key = p["key"] ?: continue
                    drives[key] = Drive(
                        uuid = p["uuid"] ?: "",
                        label = p["label"] ?: key,
                        repositories = parseStringMap(p["repos"]),
                        storageClass = try { StorageClass.valueOf(p["storageClass"] ?: "WARM") } catch (_: Exception) { StorageClass.WARM }
                    )
                }
                DagEventType.DRIVE_REMOVED -> {
                    val key = p["key"] ?: continue
                    drives.remove(key)
                }
                DagEventType.REPO_CREATED -> {
                    val name = p["name"] ?: continue
                    repositories[name] = RepositoryConfig(
                        name = name,
                        localPath = p["localPath"] ?: "",
                        type = try { RepositoryType.valueOf(p["type"] ?: "GENERIC") } catch (_: Exception) { RepositoryType.GENERIC },
                        replication = ReplicationPolicy(
                            minCopies = p["minCopies"]?.toIntOrNull() ?: 1,
                            preferredCopies = p["preferredCopies"]?.toIntOrNull() ?: 2
                        )
                    )
                }
                DagEventType.REPO_POLICY_CHANGED -> {
                    val name = p["name"] ?: continue
                    val existing = repositories[name] ?: continue
                    repositories[name] = existing.copy(
                        replication = existing.replication.copy(
                            minCopies = p["minCopies"]?.toIntOrNull() ?: existing.replication.minCopies,
                            preferredCopies = p["preferredCopies"]?.toIntOrNull() ?: existing.replication.preferredCopies
                        )
                    )
                }
                DagEventType.PEER_TRUSTED -> {
                    val peerId = p["peerHouseId"] ?: continue
                    peers[peerId] = PeerHouse(
                        name = p["peerName"] ?: "",
                        houseId = peerId,
                        publicKey = p["peerPublicKey"] ?: "",
                        tailscaleIp = p["tailscaleIp"],
                        sshUser = p["sshUser"],
                        addedAt = event.timestamp.wall
                    )
                }
                DagEventType.PEER_REVOKED -> {
                    val peerId = p["peerHouseId"] ?: continue
                    peers.remove(peerId)
                }
                DagEventType.SHARE_GRANTED -> {
                    val repo = p["repository"] ?: continue
                    val peerId = p["peerHouseId"] ?: continue
                    val level = try { AccessLevel.valueOf(p["accessLevel"] ?: "READ") } catch (_: Exception) { AccessLevel.READ }
                    // Remove any existing share for this repo+peer, add new one
                    shares.removeAll { it.repository == repo && it.peerHouseId == peerId }
                    shares.add(ShareGrant(
                        repository = repo,
                        peerHouseId = peerId,
                        access = level,
                        grantedAt = event.timestamp.wall,
                        expiresAt = p["expiresAt"]
                    ))
                }
                DagEventType.SHARE_REVOKED -> {
                    val repo = p["repository"] ?: continue
                    val peerId = p["peerHouseId"] ?: continue
                    shares.removeAll { it.repository == repo && it.peerHouseId == peerId }
                }
                DagEventType.BACKUP_OFFERED -> {
                    val peerId = p["peerHouseId"] ?: continue
                    backups[peerId] = BackupAgreement(
                        peerHouseId = peerId,
                        offeredBytes = p["offeredBytes"]?.toLongOrNull() ?: 0,
                        status = BackupStatus.PROPOSED,
                        createdAt = event.timestamp.wall
                    )
                }
                DagEventType.BACKUP_ACCEPTED -> {
                    val peerId = p["peerHouseId"] ?: continue
                    val existing = backups[peerId] ?: continue
                    backups[peerId] = existing.copy(
                        theirOfferedBytes = p["theirOfferedBytes"]?.toLongOrNull() ?: 0,
                        status = BackupStatus.ACCEPTED,
                        acceptedAt = event.timestamp.wall
                    )
                }
                DagEventType.BACKUP_TERMINATED -> {
                    val peerId = p["peerHouseId"] ?: continue
                    val existing = backups[peerId] ?: continue
                    backups[peerId] = existing.copy(status = BackupStatus.TERMINATED)
                }
            }
        }

        // Attach peers to house config
        val finalHouse = house?.copy(peers = peers)

        return MaterializedState(
            house = finalHouse,
            machines = machines,
            drives = drives,
            repositories = repositories,
            shares = shares,
            backups = backups.values.toList()
        )
    }

    private fun parseStringMap(jsonStr: String?): Map<String, String> {
        if (jsonStr.isNullOrBlank()) return emptyMap()
        return try {
            kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(jsonStr)
        } catch (_: Exception) {
            emptyMap()
        }
    }
}

data class MaterializedState(
    val house: HouseConfig?,
    val machines: Map<String, MachineProfile>,
    val drives: Map<String, Drive>,
    val repositories: Map<String, RepositoryConfig>,
    val shares: List<ShareGrant>,
    val backups: List<BackupAgreement>
) {
    /**
     * Convert to a ChoamConfig for backward compatibility.
     */
    fun toChoamConfig(localSettings: Map<String, String> = emptyMap()): ChoamConfig {
        return ChoamConfig(
            machines = machines,
            drives = drives,
            repositories = repositories,
            house = house,
            ipfsGatewayPort = localSettings["ipfsGatewayPort"]?.toIntOrNull() ?: 8080
        )
    }
}

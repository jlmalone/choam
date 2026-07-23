package vision.salient.choam.config

import kotlinx.serialization.Serializable

@Serializable
data class ChoamConfig(
    val version: String = "1.0.0",
    val machines: Map<String, MachineProfile> = emptyMap(),
    val drives: Map<String, Drive> = emptyMap(),
    val repositories: Map<String, RepositoryConfig> = emptyMap(),
    val lockSearchPaths: List<String> = emptyList(),
    val defaultSyncRules: SyncRules = SyncRules(),
    val house: HouseConfig? = null, // Federation identity — null until init-house
    val ipfsGatewayPort: Int = 8080 // Kubo IPFS gateway port (default 8080)
)

@Serializable
data class ReplicationPolicy(
    val minCopies: Int = 1,
    val preferredCopies: Int = 2,
    val geoDistribute: Boolean = false,
    val preferredClass: List<StorageClass> = emptyList()
)

@Serializable
data class RepositoryConfig(
    val name: String,
    val localPath: String = "",
    val type: RepositoryType,
    val databases: List<String> = emptyList(),
    val excludePatterns: List<String> = emptyList(),
    val replication: ReplicationPolicy = ReplicationPolicy()
)

enum class RepositoryType {
    MEDIA,
    ARCHIVE,
    GENERIC
}

@Serializable
data class ResourceLimits(
    val maxHeapMb: Int? = null,
    val maxCpuCores: Int? = null,
    val ioNice: Boolean = false
)

@Serializable
data class MachineProfile(
    val name: String,
    val hostname: String,
    val type: MachineType,
    val repositories: Map<String, String>, // repo name -> local path
    val sshUser: String? = null,
    val sshPort: Int = 22,
    val tailscaleIp: String? = null,
    val networkPreference: NetworkMode = NetworkMode.AUTO,
    val resourceLimits: ResourceLimits = ResourceLimits(),
    val aliases: List<String> = emptyList() // old hostnames this machine has used
)

enum class MachineType {
    DESKTOP,
    LAPTOP,
    SERVER
}

enum class NetworkMode {
    LAN,
    TAILSCALE,
    WAN,
    AUTO
}

@Serializable
data class SyncRules(
    val bidirectional: Boolean = false,
    val deleteRemoved: Boolean = false,
    val conflictResolution: ConflictStrategy = ConflictStrategy.NEWER_WINS,
    val bandwidthLimit: Int? = null, // KB/s
    val excludePatterns: List<String> = listOf("*.tmp", "*.part", ".DS_Store")
)

enum class ConflictStrategy {
    NEWER_WINS,
    LARGER_WINS,
    MANUAL,
    KEEP_BOTH
}

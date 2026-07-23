package vision.salient.choam.config

import kotlinx.serialization.Serializable

/**
 * Node capability profile — describes what a node can do.
 * Used by gossip protocol for intelligent replication coordination.
 */
@Serializable
data class NodeCapability(
    val nodeType: NodeType = NodeType.DESKTOP,
    val storageCapacityBytes: Long = 0,
    val storageUsedBytes: Long = 0,
    val bandwidthBytesPerSec: Long = 0,          // Measured or estimated
    val canStream: Boolean = false,               // Supports HTTP range requests
    val canTranscode: Boolean = false,            // Has ffmpeg/handbrake
    val alwaysOn: Boolean = false,                // Server-class uptime
    val batteryPowered: Boolean = false,          // Phone/laptop
    val syncSchedule: SyncSchedule = SyncSchedule.ON_DEMAND
)

@Serializable
enum class NodeType {
    PHONE,      // Limited storage, battery, intermittent connectivity
    TABLET,     // Like phone but more storage
    LAPTOP,     // Medium storage, intermittent (lid closed)
    DESKTOP,    // Medium-large storage, on when user is active
    SERVER,     // Large storage, 24/7, high bandwidth
    NAS,        // Bulk storage, always-on, limited CPU
    CLOUD       // Unlimited storage, expensive, encrypted blobs only
}

@Serializable
enum class SyncSchedule {
    ON_DEMAND,   // Only sync when explicitly requested
    ON_WIFI,     // Sync when on Wi-Fi (mobile nodes)
    ON_POWER,    // Sync when charging (battery nodes)
    CONTINUOUS,  // Always syncing when reachable (servers, NAS)
    SCHEDULED    // Cron-like schedule (e.g. nightly)
}

/**
 * Mobile node profile — additional constraints for phones/tablets.
 */
@Serializable
data class MobileProfile(
    val maxCacheBytes: Long = 1_073_741_824,     // 1GB default cache
    val uploadOnly: List<String> = emptyList(),   // Repos that only upload (e.g. "photos")
    val downloadOnly: List<String> = emptyList(), // Repos that only download (e.g. "music")
    val autoPurgeWhenFull: Boolean = true,         // LRU eviction when cache full
    val requireWifi: Boolean = true,               // No sync on cellular
    val requireCharging: Boolean = false            // Only sync when plugged in
)

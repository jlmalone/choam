package vision.salient.choam.dag

import kotlinx.serialization.Serializable

/**
 * A single event in the CHOAM DAG.
 *
 * Every config change (add machine, grant share, add drive) becomes an immutable,
 * signed event. Events form a directed acyclic graph via parent references.
 *
 * Follows REDO protocol patterns: content-addressed ID, Lamport clock,
 * Ed25519 signature, canonical JSON hashing.
 */
@Serializable
data class DagEvent(
    val id: String,                    // "sha256:<64 hex chars>" — derived from content hash
    val version: Int = 1,              // Protocol version
    val parents: List<String>,         // Parent event IDs (DAG edges)
    val timestamp: DagTimestamp,       // Lamport clock + wall time
    val author: DagAuthor,             // Who created this event
    val type: String,                  // Event type (HOUSE_CREATED, MACHINE_JOINED, etc.)
    val payload: Map<String, String>,  // Type-specific data (all strings for canonical JSON)
    val signature: String? = null      // Ed25519 signature (128 hex chars)
)

@Serializable
data class DagTimestamp(
    val lamport: Long,                 // 1 for genesis, max(parent_lamports)+1 for others
    val wall: String                   // ISO 8601 UTC: "2026-03-06T12:00:00.000Z"
)

@Serializable
data class DagAuthor(
    val houseId: String,               // First 32 chars of public key hex
    val machineId: String,             // Machine config key
    val publicKey: String? = null      // Full 64-char hex (on first event or when needed)
)

/**
 * Event type constants.
 */
object DagEventType {
    const val HOUSE_CREATED = "HOUSE_CREATED"
    const val MACHINE_JOINED = "MACHINE_JOINED"
    const val MACHINE_UPDATED = "MACHINE_UPDATED"
    const val MACHINE_LEFT = "MACHINE_LEFT"
    const val DRIVE_ADDED = "DRIVE_ADDED"
    const val DRIVE_REMOVED = "DRIVE_REMOVED"
    const val REPO_CREATED = "REPO_CREATED"
    const val REPO_POLICY_CHANGED = "REPO_POLICY_CHANGED"
    const val PEER_TRUSTED = "PEER_TRUSTED"
    const val PEER_REVOKED = "PEER_REVOKED"
    const val SHARE_GRANTED = "SHARE_GRANTED"
    const val SHARE_REVOKED = "SHARE_REVOKED"
    const val BACKUP_OFFERED = "BACKUP_OFFERED"
    const val BACKUP_ACCEPTED = "BACKUP_ACCEPTED"
    const val BACKUP_TERMINATED = "BACKUP_TERMINATED"
}

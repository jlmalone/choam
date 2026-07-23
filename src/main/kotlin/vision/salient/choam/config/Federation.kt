package vision.salient.choam.config

import kotlinx.serialization.Serializable

/**
 * Access levels for shared repositories, following the VISION_DOC trust tiers:
 *
 * STORE — Peer holds encrypted blob without view key. Cannot read content.
 * READ  — Peer can pull (download) content. Has view key.
 * WRITE — Peer can push (upload) changes. Full collaboration.
 */
@Serializable
enum class AccessLevel {
    STORE,  // Encrypted blob storage — no view key
    READ,   // Can pull content — has view key
    WRITE   // Can push changes — full access
}

/**
 * A CHOAM House — the user's personal domain in the federation.
 * Each user runs exactly one House. Houses form alliances with peer Houses.
 *
 * Identity is derived from an Ed25519 keypair stored at ~/.choam/house_key.
 * The public key fingerprint serves as the House ID.
 */
@Serializable
data class HouseConfig(
    val name: String,                              // Human-readable house name (e.g. "house-myserver")
    val houseId: String = "",                      // Ed25519 public key fingerprint (hex)
    val publicKey: String = "",                    // Ed25519 public key (hex)
    val description: String = "",                  // "Off-site media server"
    val createdAt: String = "",                    // ISO-8601
    val peers: Map<String, PeerHouse> = emptyMap() // peerHouseId -> PeerHouse
)

/**
 * A known peer House — a remote CHOAM domain we have a trust relationship with.
 */
@Serializable
data class PeerHouse(
    val name: String,                              // "house-remote"
    val houseId: String,                           // Their Ed25519 public key fingerprint
    val publicKey: String = "",                    // Their Ed25519 public key (hex)
    val tailscaleIp: String? = null,               // How to reach them
    val sshUser: String? = null,
    val sshPort: Int = 22,
    val addedAt: String = "",                      // When we added this peer
    val lastSeen: String = ""                      // Last successful contact
)

/**
 * A share grant — permission for a peer House to access a repository.
 */
@Serializable
data class ShareGrant(
    val repository: String,                        // Which repo is shared
    val peerHouseId: String,                       // Who it's shared with
    val access: AccessLevel,                       // What level of access
    val grantedAt: String = "",                    // When granted
    val expiresAt: String? = null,                 // Optional expiry
    val note: String = ""                          // "Shared for off-site backup"
)

/**
 * A mutual backup agreement — two Houses agree to store each other's data.
 */
@Serializable
data class BackupAgreement(
    val peerHouseId: String,                       // Who we're backing up with
    val offeredBytes: Long,                        // How much storage we offer them
    val receivedBytes: Long = 0,                   // How much they've actually stored
    val theirOfferedBytes: Long = 0,               // How much they offer us
    val ourUsedBytes: Long = 0,                    // How much we've stored on them
    val status: BackupStatus = BackupStatus.PROPOSED,
    val createdAt: String = "",
    val acceptedAt: String? = null
)

@Serializable
enum class BackupStatus {
    PROPOSED,   // We offered, waiting for acceptance
    ACCEPTED,   // Both sides agreed
    ACTIVE,     // Data is flowing
    SUSPENDED,  // Temporarily paused
    TERMINATED  // Agreement ended
}

package vision.salient.choam.dag

import mu.KotlinLogging
import java.sql.Connection
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

/**
 * Core DAG engine — creates events, validates them, maintains the DAG.
 *
 * Follows REDO protocol patterns: content-addressed IDs, Lamport clocks,
 * Ed25519 signatures, deterministic canonical JSON hashing.
 */
class DagEngine(
    private val store: DagStore,
    private val houseId: String,
    private val machineId: String,
    private val publicKeyHex: String,
    private val privateKeyHex: String
) {
    /**
     * Create a new event, compute its content-addressed ID, sign it, append to store.
     */
    fun createEvent(conn: Connection, type: String, payload: Map<String, String>): DagEvent {
        val lamport = store.getMaxLamport(conn) + 1
        val wall = Instant.now().atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))

        // Parent is the current head (or empty for genesis)
        val parents = if (type == DagEventType.HOUSE_CREATED) {
            emptyList()
        } else {
            val head = store.getHead(conn, houseId)
            if (head != null) listOf(head) else emptyList()
        }

        // Include public key on genesis event
        val includePublicKey = type == DagEventType.HOUSE_CREATED

        val event = DagEvent(
            id = "", // Placeholder — computed below
            version = 1,
            parents = parents,
            timestamp = DagTimestamp(lamport = lamport, wall = wall),
            author = DagAuthor(
                houseId = houseId,
                machineId = machineId,
                publicKey = if (includePublicKey) publicKeyHex else null
            ),
            type = type,
            payload = payload,
            signature = null
        )

        // Compute content-addressed ID
        val id = CanonicalJson.hashEvent(event)

        // Sign the canonical JSON
        val canonicalJson = CanonicalJson.stringify(CanonicalJson.buildHashableMap(event))
        val signature = DagCrypto.sign(canonicalJson, privateKeyHex)

        val signedEvent = event.copy(id = id, signature = signature)

        // Append to store
        store.append(conn, signedEvent)
        logger.debug { "Created DAG event: $type (${id.take(20)}...) lamport=$lamport" }

        return signedEvent
    }

    /**
     * Validate an incoming event (from peer sync or import).
     */
    fun validate(conn: Connection, event: DagEvent): ValidationResult {
        // Version check
        if (event.version != 1) {
            return ValidationResult.Invalid("Unsupported version: ${event.version}")
        }

        // ID format
        if (!event.id.startsWith("sha256:") || event.id.length != 71) {
            return ValidationResult.Invalid("Invalid ID format: ${event.id.take(20)}")
        }

        // Recompute hash and verify
        val expectedId = CanonicalJson.hashEvent(event)
        if (event.id != expectedId) {
            return ValidationResult.Invalid("ID mismatch: expected $expectedId, got ${event.id}")
        }

        // Lamport clock
        if (event.timestamp.lamport < 1) {
            return ValidationResult.Invalid("Invalid Lamport clock: ${event.timestamp.lamport}")
        }

        // Genesis event rules
        if (event.type == DagEventType.HOUSE_CREATED) {
            if (event.parents.isNotEmpty()) {
                return ValidationResult.Invalid("Genesis event must have empty parents")
            }
        } else {
            if (event.parents.isEmpty()) {
                return ValidationResult.Invalid("Non-genesis event must have at least 1 parent")
            }
            // Check parents exist
            val existingIds = store.getAllEventIds(conn)
            for (parentId in event.parents) {
                if (parentId !in existingIds) {
                    return ValidationResult.Invalid("Missing parent: $parentId")
                }
            }
        }

        // Verify signature if present
        if (event.signature != null) {
            val authorPubKey = resolvePublicKey(conn, event.author.houseId, event.author.publicKey)
            if (authorPubKey != null) {
                val canonicalJson = CanonicalJson.stringify(CanonicalJson.buildHashableMap(event))
                if (!DagCrypto.verify(canonicalJson, event.signature, authorPubKey)) {
                    return ValidationResult.Invalid("Invalid signature for house ${event.author.houseId}")
                }
            }
        }

        // Verify houseId matches public key
        if (event.author.publicKey != null) {
            val expectedHouseId = DagCrypto.deriveHouseId(event.author.publicKey)
            if (event.author.houseId != expectedHouseId) {
                return ValidationResult.Invalid("House ID doesn't match public key")
            }
        }

        return ValidationResult.Valid
    }

    /**
     * Find the public key for a house ID — check the event itself, then search DAG.
     */
    private fun resolvePublicKey(conn: Connection, houseId: String, eventPubKey: String?): String? {
        if (eventPubKey != null) return eventPubKey

        // Search for HOUSE_CREATED event with this house ID
        val events = store.getEventsByType(conn, DagEventType.HOUSE_CREATED)
        return events.firstOrNull { it.author.houseId == houseId }?.author?.publicKey
    }
}

sealed class ValidationResult {
    data object Valid : ValidationResult()
    data class Invalid(val reason: String) : ValidationResult()
}

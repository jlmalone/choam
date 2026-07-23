package vision.salient.choam.dag

import mu.KotlinLogging
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Convenience function for CLI commands to write DAG events.
 *
 * If the DAG is initialized (dag.db exists with events + Ed25519 key exists),
 * creates and appends a signed event. If not, silently skips (backward compat).
 *
 * This allows mutation commands (house add-peer, share grant, etc.) to dual-write
 * to both config.json/federation.db AND the DAG during the transition period.
 */
fun writeDagEvent(type: String, payload: Map<String, String>) {
    val dagDbPath = "${System.getProperty("user.home")}/.choam/dag.db"
    val keyPath = Path.of(System.getProperty("user.home"), ".choam", "house_key_ed25519")

    if (!java.io.File(dagDbPath).exists() || !keyPath.toFile().exists()) {
        logger.debug { "DAG not initialized — skipping event write for $type" }
        return
    }

    try {
        val store = DagStore(dagDbPath)
        val conn = store.open()

        val eventCount = store.getEventCount(conn)
        if (eventCount == 0L) {
            conn.close()
            return
        }

        val privateKeyHex = DagCrypto.loadPrivateKey(keyPath)

        // Find house ID and public key from genesis event
        val genesis = store.getEventsByType(conn, DagEventType.HOUSE_CREATED).firstOrNull()
        if (genesis == null) {
            conn.close()
            return
        }

        val houseId = genesis.author.houseId
        val publicKeyHex = genesis.author.publicKey ?: genesis.payload["publicKey"] ?: ""

        val engine = DagEngine(store, houseId, "local", publicKeyHex, privateKeyHex)
        engine.createEvent(conn, type, payload)
        conn.close()

        logger.debug { "DAG event written: $type" }
    } catch (e: Exception) {
        logger.debug { "Failed to write DAG event $type: ${e.message}" }
    }
}

package vision.salient.choam.dag

import mu.KotlinLogging
import vision.salient.choam.config.ChoamConfig
import vision.salient.choam.config.ChoamConfigLoader
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Unified config resolution: DAG-first, config.json fallback.
 *
 * If a DAG exists and has events, materializes config from the event log.
 * Otherwise falls back to the legacy config.json loader.
 *
 * This is the single entry point for all config reads across CHOAM.
 * Replaces direct calls to ChoamConfigLoader.load().
 */
object ConfigResolver {

    /**
     * Resolve the current CHOAM configuration.
     *
     * Resolution order:
     * 1. DAG materialized state (if dag.db exists and has events)
     * 2. Legacy config.json (fallback)
     *
     * Local-only settings (ipfsGatewayPort) come from DAG local_state table
     * or from config.json.
     */
    fun resolve(): ChoamConfig {
        // Try DAG first
        val dagConfig = tryDag()
        if (dagConfig != null) {
            logger.debug { "Config resolved from DAG (${dagConfig.machines.size} machines, ${dagConfig.repositories.size} repos)" }
            return dagConfig
        }

        // Fall back to config.json
        return try {
            val config = ChoamConfigLoader.load()
            logger.debug { "Config resolved from config.json (DAG not available)" }
            config
        } catch (e: Exception) {
            logger.debug { "Config resolution failed: ${e.message}" }
            throw e
        }
    }

    /**
     * Try to resolve config from DAG. Returns null if DAG doesn't exist or is empty.
     */
    private fun tryDag(): ChoamConfig? {
        val dagDbPath = "${System.getProperty("user.home")}/.choam/dag.db"
        if (!File(dagDbPath).exists()) return null

        return try {
            val store = DagStore(dagDbPath)
            val conn = store.open()

            val eventCount = store.getEventCount(conn)
            if (eventCount == 0L) {
                conn.close()
                return null
            }

            val materializer = StateMaterializer(store)
            val state = materializer.materialize(conn)

            // Read local-only settings
            val localSettings = mutableMapOf<String, String>()
            store.getLocalState(conn, "ipfsGatewayPort")?.let { localSettings["ipfsGatewayPort"] = it }

            conn.close()

            // Merge DAG state with legacy config for fields the DAG doesn't manage yet
            val dagConfig = state.toChoamConfig(localSettings)

            // Inherit defaultSyncRules from config.json if it exists (DAG doesn't manage these yet)
            val legacySyncRules = try {
                ChoamConfigLoader.load().defaultSyncRules
            } catch (_: Exception) {
                null
            }

            if (legacySyncRules != null) {
                dagConfig.copy(defaultSyncRules = legacySyncRules)
            } else {
                dagConfig
            }
        } catch (e: Exception) {
            logger.debug { "DAG resolution failed: ${e.message}" }
            null
        }
    }

    /**
     * Check if DAG is initialized (has events).
     */
    fun isDagInitialized(): Boolean {
        val dagDbPath = "${System.getProperty("user.home")}/.choam/dag.db"
        if (!File(dagDbPath).exists()) return false
        return try {
            val store = DagStore(dagDbPath)
            val conn = store.open()
            val count = store.getEventCount(conn)
            conn.close()
            count > 0
        } catch (_: Exception) {
            false
        }
    }
}

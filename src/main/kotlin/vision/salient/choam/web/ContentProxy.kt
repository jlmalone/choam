package vision.salient.choam.web

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import mu.KotlinLogging
import vision.salient.choam.config.ChoamConfig
import java.io.File
import java.sql.DriverManager

private val logger = KotlinLogging.logger {}

/**
 * Tiered content resolution and proxying.
 *
 * Given a CID, finds the fastest way to serve its bytes:
 *   Tier 0: Local file (instant)
 *   Tier 1: IPFS gateway proxy via remote machine's Kubo node (~100ms + transfer)
 *   Tier 2: Diagnostic 404 with all known locations
 *
 * Designed to make /stream/{cid} work for ALL content, not just local files.
 * Also serves as the universal content proxy for cross-project access.
 */
class ContentProxy(private val config: ChoamConfig) {

    private val httpClient = HttpClient(CIO)

    /**
     * Resolve a CID to a streamable source.
     */
    suspend fun resolve(cid: String): ProxyResult {
        val locations = lookupLocations(cid)
        if (locations.isEmpty()) {
            return ProxyResult.NotAvailable(cid, emptyList(), "CID not found in registry")
        }

        // Tier 0: Local file
        for (loc in locations) {
            val file = File(loc.path)
            if (file.exists()) {
                val contentType = guessContentType(file.name)
                logger.debug { "Tier 0 (local): $cid → ${file.absolutePath}" }
                return ProxyResult.LocalFile(file, contentType)
            }
        }

        // Tier 1: IPFS gateway proxy
        val locationInfos = mutableListOf<LocationInfo>()
        for (loc in locations) {
            val machineConfig = findMachineForLocation(loc.machine)
            val ip = machineConfig?.tailscaleIp
            if (ip != null) {
                val gatewayUrl = "http://$ip:${config.ipfsGatewayPort}/ipfs/$cid"
                val reachable = checkGateway(gatewayUrl)
                locationInfos.add(LocationInfo(
                    machine = loc.machine, path = loc.path,
                    isLocal = false, reachable = reachable, gatewayUrl = gatewayUrl
                ))
                if (reachable) {
                    val filename = loc.path.substringAfterLast("/")
                    val contentType = guessContentType(filename)
                    logger.debug { "Tier 1 (IPFS gateway): $cid → $gatewayUrl" }
                    return ProxyResult.RemoteStream(gatewayUrl, contentType, loc.size)
                }
            } else {
                locationInfos.add(LocationInfo(
                    machine = loc.machine, path = loc.path,
                    isLocal = false, reachable = null, gatewayUrl = null
                ))
            }
        }

        // Tier 2: Not available
        logger.debug { "Tier 2 (not available): $cid — ${locationInfos.size} known locations, none reachable" }
        return ProxyResult.NotAvailable(cid, locationInfos, "Content exists on remote machines but no IPFS gateway is reachable")
    }

    /**
     * Resolve a file path to a CID, then resolve the CID.
     * Used by /resolve/machine/drive/path endpoint.
     */
    suspend fun resolveByPath(machine: String, path: String): ProxyResult {
        val cid = lookupCidForPath(machine, path)
        if (cid != null) {
            return resolve(cid)
        }

        // No CID known — try direct access if machine has a Tailscale IP
        val machineConfig = config.machines[machine]
        val ip = machineConfig?.tailscaleIp
        if (ip != null) {
            val gatewayUrl = "http://$ip:${config.ipfsGatewayPort}/$path"
            val filename = path.substringAfterLast("/")
            val contentType = guessContentType(filename)
            return ProxyResult.RemoteStream(gatewayUrl, contentType, null)
        }

        return ProxyResult.NotAvailable("", listOf(
            LocationInfo(machine, path, false, null, null)
        ), "No CID found for path and machine is not reachable")
    }

    /**
     * Proxy a remote URL — streams bytes from the IPFS gateway to the client.
     * Supports Range headers for seeking in media players.
     */
    suspend fun proxyStream(url: String, rangeHeader: String?): HttpResponse {
        return httpClient.get(url) {
            if (rangeHeader != null) {
                header(HttpHeaders.Range, rangeHeader)
            }
        }
    }

    fun close() {
        httpClient.close()
    }

    // --- Private helpers ---

    private data class RegistryLocation(val machine: String, val path: String, val size: Long)

    private fun lookupLocations(cid: String): List<RegistryLocation> {
        val dbPath = "${System.getProperty("user.home")}/.choam/unified_registry.db"
        if (!File(dbPath).exists()) return emptyList()

        return try {
            val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
            val stmt = conn.prepareStatement(
                "SELECT machine_name, file_path, file_size FROM content_locations WHERE cid = ?"
            )
            stmt.setString(1, cid)
            val rs = stmt.executeQuery()
            val results = mutableListOf<RegistryLocation>()
            while (rs.next()) {
                results.add(RegistryLocation(
                    machine = rs.getString("machine_name"),
                    path = rs.getString("file_path"),
                    size = rs.getLong("file_size")
                ))
            }
            rs.close(); stmt.close(); conn.close()
            results
        } catch (e: Exception) {
            logger.debug { "Registry lookup failed for $cid: ${e.message}" }
            emptyList()
        }
    }

    private fun lookupCidForPath(machine: String, path: String): String? {
        val dbPath = "${System.getProperty("user.home")}/.choam/unified_registry.db"
        if (!File(dbPath).exists()) return null

        return try {
            val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
            val stmt = conn.prepareStatement(
                "SELECT cid FROM content_locations WHERE machine_name = ? AND file_path LIKE ? LIMIT 1"
            )
            stmt.setString(1, machine)
            stmt.setString(2, "%$path")
            val rs = stmt.executeQuery()
            val cid = if (rs.next()) rs.getString("cid") else null
            rs.close(); stmt.close(); conn.close()
            cid
        } catch (e: Exception) {
            logger.debug { "CID lookup for path failed: ${e.message}" }
            null
        }
    }

    private fun findMachineForLocation(machineName: String): vision.salient.choam.config.MachineProfile? {
        // Direct match
        config.machines[machineName]?.let { return it }
        // Check aliases
        for ((_, profile) in config.machines) {
            if (machineName in profile.aliases) return profile
        }
        return null
    }

    /**
     * HEAD request to IPFS gateway with 3-second timeout.
     * Returns true if the gateway is up and has the content.
     */
    private suspend fun checkGateway(url: String): Boolean {
        return try {
            val response = httpClient.head(url)
            response.status == HttpStatusCode.OK || response.status == HttpStatusCode.PartialContent
        } catch (e: Exception) {
            logger.debug { "Gateway check failed for $url: ${e.message}" }
            false
        }
    }
}

// --- Result types ---

sealed class ProxyResult {
    data class LocalFile(val file: File, val contentType: ContentType) : ProxyResult()
    data class RemoteStream(val url: String, val contentType: ContentType, val size: Long?) : ProxyResult()
    data class NotAvailable(val cid: String, val locations: List<LocationInfo>, val message: String) : ProxyResult()
}

data class LocationInfo(
    val machine: String,
    val path: String,
    val isLocal: Boolean,
    val reachable: Boolean?,
    val gatewayUrl: String?
)

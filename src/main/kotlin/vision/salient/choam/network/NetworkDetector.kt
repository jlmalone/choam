package vision.salient.choam.network

import mu.KotlinLogging
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import vision.salient.choam.config.MachineProfile
import vision.salient.choam.config.NetworkMode

private val logger = KotlinLogging.logger {}

/**
 * Network quality assessment for transfer decisions.
 * SSID-based: mobile hotspots are HOTSPOT, unknown WiFi is METERED, known home/office is UNMETERED.
 */
enum class NetworkQuality {
    UNMETERED,  // Home/office WiFi or Ethernet — safe for large transfers
    METERED,    // Unknown WiFi — proceed with caution
    HOTSPOT,    // Phone hotspot (Pixel, iPhone) — skip transfers
    OFFLINE     // No connectivity
}

data class NetworkStatus(
    val quality: NetworkQuality,
    val ssid: String?,
    val reason: String
)

class NetworkDetector {

    // Cache connectivity results for 5 minutes to avoid re-pinging on every command
    private val cache = mutableMapOf<String, CachedResult>()
    private val cacheTtlMs = 5 * 60 * 1000L // 5 minutes

    private data class CachedResult(val result: ConnectivityTest, val timestamp: Long)

    /**
     * Get the current WiFi SSID on macOS.
     * Returns null if not connected to WiFi or on Ethernet.
     */
    fun getWifiSsid(): String? {
        return try {
            val process = ProcessBuilder("networksetup", "-getairportnetwork", "en0")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return null
            }
            val output = process.inputStream.bufferedReader().readText().trim()
            // Format: "Current Wi-Fi Network: <SSID>" or "You are not associated with an AirPort network."
            if (output.startsWith("Current Wi-Fi Network:")) {
                output.substringAfter("Current Wi-Fi Network:").trim()
            } else null
        } catch (e: Exception) {
            logger.debug { "WiFi SSID detection failed: ${e.message}" }
            null
        }
    }

    /**
     * Apple's authoritative metered signal (Network framework NWPathMonitor.isExpensive):
     * true for a Personal Hotspot / cellular tether, false for cafe/home/office WiFi or
     * Ethernet. This is the correct signal. The SSID heuristic below is unreliable and, on
     * recent macOS, broken outright because Apple no longer exposes the SSID to networksetup
     * (so a real iPhone hotspot reads as "SSID unknown" and slips through as METERED). Returns
     * null when the Swift probe is unavailable, in which case we fall back to the SSID heuristic.
     */
    fun isExpensiveNetwork(): Boolean? {
        return try {
            val swiftSrc = """
                import Network
                import Foundation
                let m = NWPathMonitor()
                let sem = DispatchSemaphore(value: 0)
                var out = "unknown"
                m.pathUpdateHandler = { p in
                    if p.status == .satisfied { out = p.isExpensive ? "expensive" : "ok" }
                    sem.signal()
                }
                m.start(queue: DispatchQueue.global())
                _ = sem.wait(timeout: .now() + 3)
                m.cancel()
                print(out)
            """.trimIndent()
            val tmp = java.io.File.createTempFile("choam-netcheck", ".swift")
            tmp.writeText(swiftSrc)
            val process = ProcessBuilder("swift", tmp.absolutePath)
                .redirectErrorStream(true).start()
            val finished = process.waitFor(8, TimeUnit.SECONDS)
            val output = if (finished) {
                process.inputStream.bufferedReader().readText().trim().lines().lastOrNull()?.trim()
            } else { process.destroyForcibly(); null }
            tmp.delete()
            when (output) {
                "expensive" -> true
                "ok" -> false
                else -> null
            }
        } catch (e: Exception) {
            logger.debug { "isExpensive probe unavailable: ${e.message}" }
            null
        }
    }

    /**
     * Assess current network quality for transfer decisions.
     * Checks WiFi SSID against hotspot patterns to avoid transferring over phone tethering.
     */
    fun assessNetwork(hotspotPatterns: List<String> = DEFAULT_HOTSPOT_PATTERNS): NetworkStatus {
        // Authoritative metered check first (Apple isExpensive). This catches a real
        // Personal Hotspot / cellular tether even when the SSID is hidden, and never
        // misclassifies cafe/home WiFi. SSID matching below is only a fallback.
        when (isExpensiveNetwork()) {
            true -> return NetworkStatus(NetworkQuality.HOTSPOT, getWifiSsid(), "Apple isExpensive=true (hotspot/cellular)")
            false -> {
                val s = getWifiSsid()
                return NetworkStatus(NetworkQuality.UNMETERED, s,
                    if (s != null) "WiFi: $s (isExpensive=false)" else "Unmetered (isExpensive=false)")
            }
            null -> { /* Swift probe unavailable — fall through to the SSID heuristic */ }
        }

        val ssid = getWifiSsid()

        if (ssid == null) {
            // Could be Ethernet (good) or offline (bad) — check basic connectivity.
            // On macOS, WiFi is typically en0 and Ethernet is en1+ or bridge*.
            // If WiFi is powered on, en0 is a WiFi interface even if SSID detection failed,
            // so we exclude en0 from the "wired" check to avoid false positives.
            val wifiPoweredOn = isWifiPoweredOn()
            val hasEthernet = try {
                java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                    .any { iface ->
                        iface.isUp && !iface.isLoopback && !iface.isVirtual &&
                            (iface.name.startsWith("en") || iface.name.startsWith("eth")) &&
                            // Skip en0 if WiFi is powered on — it's the WiFi adapter, not Ethernet
                            !(wifiPoweredOn && iface.name == "en0") &&
                            iface.inetAddresses.asSequence().any { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    }
            } catch (_: Exception) { false }

            return if (hasEthernet) {
                NetworkStatus(NetworkQuality.UNMETERED, null, "Wired connection")
            } else if (wifiPoweredOn) {
                // WiFi is on but SSID unknown — treat as metered (cautious)
                NetworkStatus(NetworkQuality.METERED, null, "WiFi (SSID unknown)")
            } else {
                NetworkStatus(NetworkQuality.OFFLINE, null, "No network detected")
            }
        }

        // Check SSID against hotspot patterns (case-insensitive)
        val ssidLower = ssid.lowercase()
        val matchedPattern = hotspotPatterns.firstOrNull { pattern ->
            ssidLower.contains(pattern.lowercase())
        }

        return if (matchedPattern != null) {
            NetworkStatus(NetworkQuality.HOTSPOT, ssid, "Matches hotspot pattern: $matchedPattern")
        } else {
            NetworkStatus(NetworkQuality.UNMETERED, ssid, "WiFi: $ssid")
        }
    }

    /**
     * Check if WiFi hardware is powered on (macOS).
     * Used to distinguish "Ethernet-only" from "WiFi on but SSID unknown."
     */
    private fun isWifiPoweredOn(): Boolean {
        return try {
            val process = ProcessBuilder("networksetup", "-getairportpower", "en0")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) { process.destroyForcibly(); return false }
            process.inputStream.bufferedReader().readText().trim().endsWith("On")
        } catch (_: Exception) { false }
    }

    fun detectBestRoute(
        source: MachineProfile,
        target: MachineProfile
    ): NetworkRoute {
        val mode = when {
            source.tailscaleIp != null && target.tailscaleIp != null -> NetworkMode.TAILSCALE
            else -> NetworkMode.LAN
        }

        val sourceAddress =
            if (mode == NetworkMode.TAILSCALE) source.tailscaleIp!! else source.hostname
        val targetAddress =
            if (mode == NetworkMode.TAILSCALE) target.tailscaleIp!! else target.hostname

        return NetworkRoute(
            mode = mode,
            sourceAddress = sourceAddress,
            targetAddress = targetAddress,
            estimatedBandwidth = null,
            latency = null
        )
    }

    /**
     * Test connectivity to a target by pinging it and parsing RTT.
     * Results are cached for 5 minutes per target address.
     */
    fun testConnectivity(route: NetworkRoute): ConnectivityTest {
        val cacheKey = route.targetAddress
        val now = System.currentTimeMillis()

        // Check cache
        cache[cacheKey]?.let { cached ->
            if (now - cached.timestamp < cacheTtlMs) {
                logger.debug { "Using cached connectivity for ${route.targetAddress} (${(now - cached.timestamp) / 1000}s old)" }
                return cached.result
            }
        }

        val result = pingHost(route.targetAddress)

        // Cache the result
        cache[cacheKey] = CachedResult(result, now)
        return result
    }

    /** Clear the connectivity cache. */
    fun clearCache() {
        cache.clear()
    }

    companion object {
        /** Default assumed bandwidth when no measurement is available. */
        const val DEFAULT_BANDWIDTH_BYTES_PER_SEC = 50L * 1024 * 1024 // 50 MiB/s

        // SSIDs matching these patterns are considered phone hotspots — skip large transfers.
        // Case-insensitive substring matching.
        val DEFAULT_HOTSPOT_PATTERNS = listOf(
            "iphone",
            "pixel",
            "android",
            "galaxy",
            "hotspot",
            "mobile hotspot",
            "matthew"  // Matthew's iPhone
        )

        /**
         * Estimate bandwidth based on network mode and measured latency.
         * Returns bytes/sec. Falls back to DEFAULT_BANDWIDTH_BYTES_PER_SEC if no latency available.
         */
        fun estimateBandwidth(mode: NetworkMode, latencyMs: Long?): Long {
            if (latencyMs == null) return DEFAULT_BANDWIDTH_BYTES_PER_SEC
            return when {
                // LAN: low latency typically means gigabit
                latencyMs < 5 -> 100L * 1024 * 1024   // <5ms → ~100 MiB/s (gigabit LAN)
                latencyMs < 20 -> 50L * 1024 * 1024    // <20ms → ~50 MiB/s (fast LAN/local Tailscale)
                latencyMs < 100 -> 10L * 1024 * 1024   // <100ms → ~10 MiB/s (Tailscale relay)
                latencyMs < 500 -> 2L * 1024 * 1024    // <500ms → ~2 MiB/s (high-latency WAN)
                else -> 512L * 1024                     // >500ms → ~512 KiB/s (very slow link)
            }
        }

        /**
         * Ping a host and parse RTT from output.
         * Runs `ping -c 3 -W 3 <host>` and parses the avg RTT from the summary line.
         * @return ConnectivityTest with reachable, latency, and estimated bandwidth
         */
        fun pingHost(host: String): ConnectivityTest {
            return try {
                val cmd = listOf("ping", "-c", "3", "-W", "3", host)
                logger.debug { "Ping: ${cmd.joinToString(" ")}" }
                val process = ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start()

                val finished = process.waitFor(15, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    logger.debug { "Ping timed out for $host" }
                    return ConnectivityTest(reachable = false, latency = null, bandwidthBytesPerSec = null)
                }

                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.exitValue()

                if (exitCode != 0) {
                    logger.debug { "Ping failed for $host (exit $exitCode)" }
                    return ConnectivityTest(reachable = false, latency = null, bandwidthBytesPerSec = null)
                }

                // Parse avg RTT from: "round-trip min/avg/max/stddev = 0.5/1.2/2.3/0.4 ms"
                val rttMs = parseAvgRtt(output)
                val latency = if (rttMs != null) Duration.ofMillis(rttMs) else Duration.ZERO
                val bandwidth = estimateBandwidth(NetworkMode.AUTO, rttMs)

                logger.debug { "Ping $host: avg RTT=${rttMs}ms, estimated bandwidth=${bandwidth / (1024 * 1024)} MiB/s" }

                ConnectivityTest(
                    reachable = true,
                    latency = latency,
                    bandwidthBytesPerSec = bandwidth
                )
            } catch (e: Exception) {
                logger.warn(e) { "Ping failed for $host" }
                ConnectivityTest(reachable = false, latency = null, bandwidthBytesPerSec = null)
            }
        }

        /**
         * Parse the average RTT from ping output.
         * macOS format: "round-trip min/avg/max/stddev = 0.5/1.2/2.3/0.4 ms"
         * Linux format: "rtt min/avg/max/mdev = 0.5/1.2/2.3/0.4 ms"
         * @return average RTT in milliseconds, or null if unparseable
         */
        fun parseAvgRtt(pingOutput: String): Long? {
            // Match: "= min/avg/max/stddev ms" or "= min/avg/max/mdev ms"
            // stddev can be "nan" when only 1 packet received
            val pattern = Regex("""=\s*([\d.]+)/([\d.]+)/([\d.]+)/([\d.]+|nan)\s*ms""")
            val match = pattern.find(pingOutput) ?: return null
            return try {
                match.groupValues[2].toDouble().toLong()
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class NetworkRoute(
    val mode: NetworkMode,
    val sourceAddress: String,
    val targetAddress: String,
    val estimatedBandwidth: Long? = null, // bytes/sec
    val latency: Duration? = null
)

data class ConnectivityTest(
    val reachable: Boolean,
    val latency: Duration?,
    val bandwidthBytesPerSec: Long?
)

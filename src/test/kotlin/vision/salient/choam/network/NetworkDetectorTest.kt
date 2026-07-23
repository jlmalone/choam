package vision.salient.choam.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vision.salient.choam.config.MachineProfile
import vision.salient.choam.config.MachineType
import vision.salient.choam.config.NetworkMode

class NetworkDetectorTest {
    private fun createMachine(
        name: String,
        hostname: String,
        tailscaleIp: String? = null
    ): MachineProfile =
        MachineProfile(
            name = name,
            hostname = hostname,
            type = MachineType.DESKTOP,
            repositories = emptyMap(),
            sshUser = null,
            sshPort = 22,
            tailscaleIp = tailscaleIp,
            networkPreference = NetworkMode.AUTO
        )

    @Test
    fun `detects LAN route when no Tailscale IPs configured`() {
        val detector = NetworkDetector()
        val source = createMachine("source", "192.168.1.10")
        val target = createMachine("target", "192.168.1.20")

        val route = detector.detectBestRoute(source, target)

        assertEquals(NetworkMode.LAN, route.mode)
        assertEquals("192.168.1.10", route.sourceAddress)
        assertEquals("192.168.1.20", route.targetAddress)
    }

    @Test
    fun `detects Tailscale route when both machines have Tailscale IPs`() {
        val detector = NetworkDetector()
        val source = createMachine("source", "192.168.1.10", tailscaleIp = "100.64.1.10")
        val target = createMachine("target", "192.168.1.20", tailscaleIp = "100.64.1.20")

        val route = detector.detectBestRoute(source, target)

        assertEquals(NetworkMode.TAILSCALE, route.mode)
        assertEquals("100.64.1.10", route.sourceAddress)
        assertEquals("100.64.1.20", route.targetAddress)
    }

    @Test
    fun `detects LAN route when only source has Tailscale IP`() {
        val detector = NetworkDetector()
        val source = createMachine("source", "192.168.1.10", tailscaleIp = "100.64.1.10")
        val target = createMachine("target", "192.168.1.20")

        val route = detector.detectBestRoute(source, target)

        assertEquals(NetworkMode.LAN, route.mode)
        assertEquals("192.168.1.10", route.sourceAddress)
        assertEquals("192.168.1.20", route.targetAddress)
    }

    @Test
    fun `detects LAN route when only target has Tailscale IP`() {
        val detector = NetworkDetector()
        val source = createMachine("source", "192.168.1.10")
        val target = createMachine("target", "192.168.1.20", tailscaleIp = "100.64.1.20")

        val route = detector.detectBestRoute(source, target)

        assertEquals(NetworkMode.LAN, route.mode)
        assertEquals("192.168.1.10", route.sourceAddress)
        assertEquals("192.168.1.20", route.targetAddress)
    }

    @Test
    fun `handles hostnames instead of IPs`() {
        val detector = NetworkDetector()
        val source = createMachine("source", "desktop.local")
        val target = createMachine("target", "laptop.local")

        val route = detector.detectBestRoute(source, target)

        assertEquals(NetworkMode.LAN, route.mode)
        assertEquals("desktop.local", route.sourceAddress)
        assertEquals("laptop.local", route.targetAddress)
    }

    // --- testConnectivity with real ping ---

    @Test
    fun `testConnectivity pings localhost successfully`() {
        val detector = NetworkDetector()
        val route = NetworkRoute(
            mode = NetworkMode.LAN,
            sourceAddress = "localhost",
            targetAddress = "127.0.0.1"
        )

        val result = detector.testConnectivity(route)

        assertTrue(result.reachable, "localhost should be reachable")
        assertNotNull(result.latency, "latency should be measured")
        assertNotNull(result.bandwidthBytesPerSec, "bandwidth should be estimated")
        assertTrue(result.latency!!.toMillis() < 100, "localhost latency should be < 100ms")
    }

    @Test
    fun `testConnectivity returns unreachable for bad address`() {
        val detector = NetworkDetector()
        val route = NetworkRoute(
            mode = NetworkMode.LAN,
            sourceAddress = "localhost",
            targetAddress = "192.0.2.1" // TEST-NET-1, guaranteed unreachable
        )

        val result = detector.testConnectivity(route)

        assertFalse(result.reachable)
    }

    @Test
    fun `testConnectivity caches results for 5 minutes`() {
        val detector = NetworkDetector()
        val route = NetworkRoute(
            mode = NetworkMode.LAN,
            sourceAddress = "localhost",
            targetAddress = "127.0.0.1"
        )

        // First call — real ping
        val result1 = detector.testConnectivity(route)
        assertTrue(result1.reachable)

        // Second call — should hit cache (no network delay)
        val start = System.currentTimeMillis()
        val result2 = detector.testConnectivity(route)
        val elapsed = System.currentTimeMillis() - start

        assertTrue(result2.reachable)
        assertTrue(elapsed < 100, "Cached result should return in <100ms, took ${elapsed}ms")
    }

    @Test
    fun `clearCache forces fresh ping`() {
        val detector = NetworkDetector()
        val route = NetworkRoute(
            mode = NetworkMode.LAN,
            sourceAddress = "localhost",
            targetAddress = "127.0.0.1"
        )

        detector.testConnectivity(route) // populate cache
        detector.clearCache()

        // After clear, next call should do a real ping (still succeeds)
        val result = detector.testConnectivity(route)
        assertTrue(result.reachable)
    }

    // --- parseAvgRtt tests ---

    @Test
    fun `parseAvgRtt parses macOS ping output`() {
        val output = """
            PING 127.0.0.1 (127.0.0.1): 56 data bytes
            64 bytes from 127.0.0.1: icmp_seq=0 ttl=64 time=0.045 ms
            64 bytes from 127.0.0.1: icmp_seq=1 ttl=64 time=0.082 ms
            64 bytes from 127.0.0.1: icmp_seq=2 ttl=64 time=0.076 ms

            --- 127.0.0.1 ping statistics ---
            3 packets transmitted, 3 packets received, 0.0% packet loss
            round-trip min/avg/max/stddev = 0.045/0.067/0.082/0.016 ms
        """.trimIndent()

        val rtt = NetworkDetector.parseAvgRtt(output)
        assertEquals(0, rtt) // 0.067 rounds to 0
    }

    @Test
    fun `parseAvgRtt parses Tailscale high-latency ping`() {
        val output = """
            PING 100.64.0.2 (100.64.0.2): 56 data bytes
            64 bytes from 100.64.0.2: icmp_seq=0 ttl=64 time=1924.922 ms

            --- 100.64.0.2 ping statistics ---
            3 packets transmitted, 1 packets received, 66.7% packet loss
            round-trip min/avg/max/stddev = 1924.922/1924.922/1924.922/nan ms
        """.trimIndent()

        val rtt = NetworkDetector.parseAvgRtt(output)
        assertEquals(1924, rtt)
    }

    @Test
    fun `parseAvgRtt parses Linux ping output`() {
        val output = """
            PING 10.0.0.1 (10.0.0.1) 56(84) bytes of data.
            64 bytes from 10.0.0.1: icmp_seq=1 ttl=64 time=1.23 ms
            64 bytes from 10.0.0.1: icmp_seq=2 ttl=64 time=1.45 ms
            64 bytes from 10.0.0.1: icmp_seq=3 ttl=64 time=1.10 ms

            --- 10.0.0.1 ping statistics ---
            3 packets transmitted, 3 received, 0% packet loss, time 2003ms
            rtt min/avg/max/mdev = 1.100/1.260/1.450/0.143 ms
        """.trimIndent()

        val rtt = NetworkDetector.parseAvgRtt(output)
        assertEquals(1, rtt) // 1.260 rounds to 1
    }

    @Test
    fun `parseAvgRtt returns null for no summary line`() {
        val output = "Request timeout for icmp_seq 0\nRequest timeout for icmp_seq 1\n"
        assertNull(NetworkDetector.parseAvgRtt(output))
    }

    @Test
    fun `parseAvgRtt returns null for empty output`() {
        assertNull(NetworkDetector.parseAvgRtt(""))
    }

    // --- estimateBandwidth tests ---

    @Test
    fun `estimateBandwidth returns default for null latency`() {
        val bw = NetworkDetector.estimateBandwidth(NetworkMode.AUTO, null)
        assertEquals(NetworkDetector.DEFAULT_BANDWIDTH_BYTES_PER_SEC, bw)
    }

    @Test
    fun `estimateBandwidth returns high for sub-5ms LAN`() {
        val bw = NetworkDetector.estimateBandwidth(NetworkMode.LAN, 1)
        assertEquals(100L * 1024 * 1024, bw) // 100 MiB/s
    }

    @Test
    fun `estimateBandwidth returns medium for 20ms Tailscale`() {
        val bw = NetworkDetector.estimateBandwidth(NetworkMode.TAILSCALE, 15)
        assertEquals(50L * 1024 * 1024, bw) // 50 MiB/s
    }

    @Test
    fun `estimateBandwidth returns low for high latency`() {
        val bw = NetworkDetector.estimateBandwidth(NetworkMode.WAN, 300)
        assertEquals(2L * 1024 * 1024, bw) // 2 MiB/s
    }

    @Test
    fun `estimateBandwidth returns very low for extreme latency`() {
        val bw = NetworkDetector.estimateBandwidth(NetworkMode.WAN, 2000)
        assertEquals(512L * 1024, bw) // 512 KiB/s
    }
}

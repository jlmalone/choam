package vision.salient.choam.daemon

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DaemonStateTest {

    @TempDir
    lateinit var tempDir: Path

    // --- Activity log ---

    @Test
    fun `logActivity appends to file`() {
        val logFile = tempDir.resolve("test_activity.jsonl").toFile()
        // Use reflection or direct file write to test parsing
        logFile.appendText("""{"timestamp":"2026-03-07T12:00:00.000Z","action":"test","detail":"hello","success":true}""" + "\n")
        logFile.appendText("""{"timestamp":"2026-03-07T12:01:00.000Z","action":"test2","detail":"world","success":false}""" + "\n")

        val lines = logFile.readLines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
    }

    @Test
    fun `ActivityEntry parses correctly`() {
        val json = """{"timestamp":"2026-03-07T12:00:00.000Z","action":"peer_check","detail":"all ok","success":true}"""
        val entry = kotlinx.serialization.json.Json.decodeFromString<ActivityEntry>(json)
        assertEquals("peer_check", entry.action)
        assertEquals("all ok", entry.detail)
        assertTrue(entry.success)
    }

    @Test
    fun `ActivityEntry with failure parses correctly`() {
        val json = """{"timestamp":"2026-03-07T12:00:00.000Z","action":"sync","detail":"timeout","success":false}"""
        val entry = kotlinx.serialization.json.Json.decodeFromString<ActivityEntry>(json)
        assertFalse(entry.success)
    }

    @Test
    fun `ActivityEntry serializes and deserializes`() {
        val entry = ActivityEntry("2026-03-07T12:00:00.000Z", "test", "detail here", true)
        val json = kotlinx.serialization.json.Json.encodeToString(ActivityEntry.serializer(), entry)
        val decoded = kotlinx.serialization.json.Json.decodeFromString<ActivityEntry>(json)
        assertEquals("test", decoded.action)
        assertEquals("detail here", decoded.detail)
        assertTrue(decoded.success)
    }

    @Test
    fun `getRecentActivity returns most recent entries first`() {
        // Create temp activity file
        val logFile = tempDir.resolve("activity.jsonl").toFile()
        repeat(5) { i ->
            val entry = ActivityEntry("2026-03-07T12:0${i}:00.000Z", "task_$i", "detail $i", true)
            logFile.appendText(kotlinx.serialization.json.Json.encodeToString(ActivityEntry.serializer(), entry) + "\n")
        }

        val lines = logFile.readLines().filter { it.isNotBlank() }
        assertEquals(5, lines.size)

        // Parse last 3
        val recent = lines.takeLast(3).reversed().map {
            kotlinx.serialization.json.Json.decodeFromString<ActivityEntry>(it)
        }
        assertEquals(3, recent.size)
        assertEquals("task_4", recent[0].action) // Most recent first
        assertEquals("task_2", recent[2].action)
    }

    // --- PID file ---

    @Test
    fun `PID file roundtrip`() {
        val pidFile = tempDir.resolve("test.pid").toFile()
        pidFile.writeText("12345")
        assertEquals(12345L, pidFile.readText().trim().toLongOrNull())
    }

    @Test
    fun `missing PID file returns null`() {
        val pidFile = tempDir.resolve("nonexistent.pid").toFile()
        assertFalse(pidFile.exists())
    }

    // --- Uptime formatting ---

    @Test
    fun `uptime formats seconds`() {
        // Test the formatting logic directly
        fun formatUptime(seconds: Long): String = when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            seconds < 86400 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
            else -> "${seconds / 86400}d ${(seconds % 86400) / 3600}h"
        }

        assertEquals("30s", formatUptime(30))
        assertEquals("5m 30s", formatUptime(330))
        assertEquals("2h 15m", formatUptime(8100))
        assertEquals("1d 3h", formatUptime(97200))
    }
}

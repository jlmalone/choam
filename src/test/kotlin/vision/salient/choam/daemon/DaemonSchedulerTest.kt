package vision.salient.choam.daemon

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import vision.salient.choam.config.*
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DaemonSchedulerTest {

    @TempDir
    lateinit var tempDir: Path

    private fun minimalConfig() = ChoamConfig(
        machines = mapOf(
            "local" to MachineProfile(
                name = "local", hostname = "localhost", type = MachineType.DESKTOP,
                repositories = emptyMap()
            )
        )
    )

    @Test
    fun `scheduler starts and has tasks`() = runTest {
        val scheduler = DaemonScheduler(minimalConfig(), this)
        scheduler.start()

        val status = scheduler.status()
        assertTrue(status.containsKey("peer_reachability"))
        assertTrue(status.containsKey("catalog_freshness"))
        assertTrue(status.containsKey("drive_health"))
        assertTrue(status.containsKey("dag_sync"))
        // queue_processor is intentionally absent: queue draining is owned by the
        // green-gated choam-autodrain agent, not the daemon's internal scheduler.
        assertFalse(status.containsKey("queue_processor"))

        scheduler.stop()
    }

    @Test
    fun `scheduler stop cancels all tasks`() = runTest {
        val scheduler = DaemonScheduler(minimalConfig(), this)
        scheduler.start()
        scheduler.stop()
        // After stop, status should still have entries but they're not running
        val status = scheduler.status()
        assertTrue(status.isNotEmpty())
    }

    @Test
    fun `triggerTask returns true for known tasks`() = runTest {
        val scheduler = DaemonScheduler(minimalConfig(), this)
        scheduler.start()

        assertTrue(scheduler.triggerTask("peer_reachability"))
        assertTrue(scheduler.triggerTask("catalog_freshness"))
        assertTrue(scheduler.triggerTask("drive_health"))
        assertTrue(scheduler.triggerTask("dag_sync"))
        // queue_processor is no longer a daemon task (draining moved to autodrain),
        // so triggering it is a no-op like any unknown task.
        assertFalse(scheduler.triggerTask("queue_processor"))
        assertFalse(scheduler.triggerTask("nonexistent_task"))

        scheduler.stop()
    }

    @Test
    fun `scheduler with empty config does not crash`() = runTest {
        val scheduler = DaemonScheduler(ChoamConfig(), this)
        scheduler.start()
        // Let it run briefly
        delay(100)
        scheduler.stop()
    }

    @Test
    fun `scheduler respects paused state`() = runTest {
        DaemonState.paused = true
        val scheduler = DaemonScheduler(minimalConfig(), this)
        scheduler.start()
        // Tasks should not execute while paused
        delay(100)
        scheduler.stop()
        DaemonState.paused = false // Reset
    }

    @Test
    fun `status tracks task names`() = runTest {
        val scheduler = DaemonScheduler(minimalConfig(), this)
        scheduler.start()

        val status = scheduler.status()
        assertEquals(5, status.size) // 4 periodic checks + health_heartbeat
        assertTrue(status.all { it.value.name.isNotEmpty() })

        scheduler.stop()
    }
}

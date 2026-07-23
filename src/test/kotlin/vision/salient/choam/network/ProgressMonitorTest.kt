package vision.salient.choam.network

import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ProgressMonitorTest {

    // === formatBytes ===

    @Test
    fun `formatBytes handles zero`() {
        assertEquals("0 B", ProgressMonitor.formatBytes(0))
    }

    @Test
    fun `formatBytes handles bytes`() {
        assertEquals("512 B", ProgressMonitor.formatBytes(512))
    }

    @Test
    fun `formatBytes handles kilobytes`() {
        assertEquals("1.0 KB", ProgressMonitor.formatBytes(1024))
        assertEquals("1.5 KB", ProgressMonitor.formatBytes(1536))
    }

    @Test
    fun `formatBytes handles megabytes`() {
        assertEquals("1.0 MB", ProgressMonitor.formatBytes(1_048_576))
        assertEquals("2.5 MB", ProgressMonitor.formatBytes(2_621_440))
    }

    @Test
    fun `formatBytes handles gigabytes`() {
        assertEquals("1.0 GB", ProgressMonitor.formatBytes(1_073_741_824))
        assertEquals("3.7 GB", ProgressMonitor.formatBytes(3_972_844_748))
    }

    @Test
    fun `formatBytes handles terabytes`() {
        assertEquals("1.0 TB", ProgressMonitor.formatBytes(1_099_511_627_776))
        assertEquals("2.3 TB", ProgressMonitor.formatBytes(2_528_876_543_283))
    }

    @Test
    fun `formatBytes handles exact boundaries`() {
        assertEquals("1.0 KB", ProgressMonitor.formatBytes(1024))
        assertEquals("1.0 MB", ProgressMonitor.formatBytes(1_048_576))
        assertEquals("1.0 GB", ProgressMonitor.formatBytes(1_073_741_824))
        assertEquals("1.0 TB", ProgressMonitor.formatBytes(1_099_511_627_776))
    }

    @Test
    fun `formatBytes handles small negative values as bytes`() {
        // Negative bytes shouldn't happen but should not crash
        val result = ProgressMonitor.formatBytes(-1)
        assertEquals("-1 B", result)
    }

    // === formatSpeed ===

    @Test
    fun `formatSpeed handles zero`() {
        assertEquals("0 B/s", ProgressMonitor.formatSpeed(0))
    }

    @Test
    fun `formatSpeed handles negative`() {
        assertEquals("0 B/s", ProgressMonitor.formatSpeed(-100))
    }

    @Test
    fun `formatSpeed formats bytes per second`() {
        assertEquals("512 B/s", ProgressMonitor.formatSpeed(512))
    }

    @Test
    fun `formatSpeed formats kilobytes per second`() {
        assertEquals("1.0 KB/s", ProgressMonitor.formatSpeed(1024))
    }

    @Test
    fun `formatSpeed formats megabytes per second`() {
        assertEquals("50.0 MB/s", ProgressMonitor.formatSpeed(52_428_800))
    }

    @Test
    fun `formatSpeed formats gigabytes per second`() {
        assertEquals("1.0 GB/s", ProgressMonitor.formatSpeed(1_073_741_824))
    }

    // === formatDuration ===

    @Test
    fun `formatDuration handles zero`() {
        assertEquals("0s", ProgressMonitor.formatDuration(Duration.ZERO))
    }

    @Test
    fun `formatDuration handles seconds only`() {
        assertEquals("30s", ProgressMonitor.formatDuration(Duration.ofSeconds(30)))
        assertEquals("59s", ProgressMonitor.formatDuration(Duration.ofSeconds(59)))
    }

    @Test
    fun `formatDuration handles minutes and seconds`() {
        assertEquals("1m 0s", ProgressMonitor.formatDuration(Duration.ofSeconds(60)))
        assertEquals("5m 30s", ProgressMonitor.formatDuration(Duration.ofSeconds(330)))
        assertEquals("59m 59s", ProgressMonitor.formatDuration(Duration.ofSeconds(3599)))
    }

    @Test
    fun `formatDuration handles hours and minutes`() {
        assertEquals("1h 0m", ProgressMonitor.formatDuration(Duration.ofSeconds(3600)))
        assertEquals("2h 30m", ProgressMonitor.formatDuration(Duration.ofSeconds(9000)))
        assertEquals("50h 0m", ProgressMonitor.formatDuration(Duration.ofSeconds(180000)))
    }

    @Test
    fun `formatDuration handles single second`() {
        assertEquals("1s", ProgressMonitor.formatDuration(Duration.ofSeconds(1)))
    }

    // === buildProgressBar ===

    @Test
    fun `buildProgressBar at 0 percent`() {
        val bar = ProgressMonitor.buildProgressBar(0, 10)
        assertEquals("[░░░░░░░░░░]", bar)
    }

    @Test
    fun `buildProgressBar at 100 percent`() {
        val bar = ProgressMonitor.buildProgressBar(100, 10)
        assertEquals("[██████████]", bar)
    }

    @Test
    fun `buildProgressBar at 50 percent`() {
        val bar = ProgressMonitor.buildProgressBar(50, 10)
        assertEquals("[█████░░░░░]", bar)
    }

    @Test
    fun `buildProgressBar at various percentages`() {
        val bar30 = ProgressMonitor.buildProgressBar(30, 10)
        assertEquals("[███░░░░░░░]", bar30)

        val bar70 = ProgressMonitor.buildProgressBar(70, 10)
        assertEquals("[███████░░░]", bar70)
    }

    @Test
    fun `buildProgressBar with different widths`() {
        val narrow = ProgressMonitor.buildProgressBar(50, 4)
        assertEquals("[██░░]", narrow)

        val wide = ProgressMonitor.buildProgressBar(50, 20)
        assertEquals("[██████████░░░░░░░░░░]", wide)
    }

    @Test
    fun `buildProgressBar clamps over 100`() {
        val bar = ProgressMonitor.buildProgressBar(150, 10)
        assertEquals("[██████████]", bar)
    }

    @Test
    fun `buildProgressBar handles negative percent`() {
        val bar = ProgressMonitor.buildProgressBar(-10, 10)
        assertEquals("[░░░░░░░░░░]", bar)
    }

    // === Integration: displayProgress and displaySummary don't crash ===

    @Test
    fun `displayProgress does not throw for valid input`() {
        val monitor = ProgressMonitor()
        val session = vision.salient.choam.sync.SyncSession(
            sourceMachine = "a",
            targetMachine = "b",
            repositories = listOf("test"),
            startTime = java.time.Instant.now(),
            status = vision.salient.choam.sync.SyncStatus.TRANSFERRING
        )
        val progress = TransferProgress(
            fileName = "/path/to/file.txt",
            bytesTransferred = 500,
            totalBytes = 1000,
            speedBytesPerSec = 100,
            eta = Duration.ofSeconds(5)
        )

        // Should not throw
        monitor.displayProgress(session, progress)
    }

    @Test
    fun `displayProgress handles zero total bytes`() {
        val monitor = ProgressMonitor()
        val session = vision.salient.choam.sync.SyncSession(
            sourceMachine = "a",
            targetMachine = "b",
            repositories = listOf("test"),
            startTime = java.time.Instant.now(),
            status = vision.salient.choam.sync.SyncStatus.TRANSFERRING
        )
        val progress = TransferProgress(
            fileName = "file.txt",
            bytesTransferred = 0,
            totalBytes = 0,
            speedBytesPerSec = 0,
            eta = null
        )

        // Should not throw
        monitor.displayProgress(session, progress)
    }

    @Test
    fun `displaySummary does not throw for completed session`() {
        val monitor = ProgressMonitor()
        val session = vision.salient.choam.sync.SyncSession(
            sourceMachine = "a",
            targetMachine = "b",
            repositories = listOf("test"),
            startTime = java.time.Instant.now().minusSeconds(60),
            endTime = java.time.Instant.now(),
            status = vision.salient.choam.sync.SyncStatus.COMPLETED,
            statistics = vision.salient.choam.sync.SyncStatistics(
                filesTransferred = 10,
                bytesTransferred = 1_000_000,
                errors = 0
            )
        )

        // Should not throw
        monitor.displaySummary(session)
    }

    @Test
    fun `displaySummary does not throw for failed session with errors`() {
        val monitor = ProgressMonitor()
        val session = vision.salient.choam.sync.SyncSession(
            sourceMachine = "a",
            targetMachine = "b",
            repositories = listOf("test"),
            startTime = java.time.Instant.now().minusSeconds(10),
            endTime = java.time.Instant.now(),
            status = vision.salient.choam.sync.SyncStatus.FAILED,
            statistics = vision.salient.choam.sync.SyncStatistics(
                filesTransferred = 5,
                bytesTransferred = 500_000,
                errors = 3,
                conflicts = 2,
                filesSkipped = 7
            )
        )

        // Should not throw
        monitor.displaySummary(session)
    }

    @Test
    fun `displaySummary handles null endTime`() {
        val monitor = ProgressMonitor()
        val session = vision.salient.choam.sync.SyncSession(
            sourceMachine = "a",
            targetMachine = "b",
            repositories = listOf("test"),
            startTime = java.time.Instant.now(),
            endTime = null,
            status = vision.salient.choam.sync.SyncStatus.CANCELLED
        )

        // Should not throw
        monitor.displaySummary(session)
    }
}

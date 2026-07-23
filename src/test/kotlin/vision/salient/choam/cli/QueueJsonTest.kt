package vision.salient.choam.cli

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import vision.salient.choam.sync.TransferMode
import vision.salient.choam.sync.TransferQueueEntry
import vision.salient.choam.sync.TransferStatus
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueueJsonTest {

    @Test
    fun `empty queue produces valid empty report`() {
        val report = buildQueueReport(emptyList()) { null }
        assertEquals(0, report.queue.size)
        assertEquals(0, report.summary.running)
        assertEquals(0, report.summary.pending)
        assertEquals(0, report.summary.failed)

        val s = renderQueueReportJson(emptyList()) { null }
        Json.parseToJsonElement(s) // throws if not valid JSON
        assertTrue(s.contains("\"queue\""))
        assertTrue(s.contains("\"summary\""))
    }

    @Test
    fun `populated queue maps fields and counts statuses`() {
        val entries = listOf(
            TransferQueueEntry(
                id = "p1", sourcePath = "/data/movie.mkv",
                destinationMachine = "vault", destinationPath = "/store/movie.mkv",
                mode = TransferMode.MOVE, status = TransferStatus.PENDING, totalBytes = 1000,
            ),
            TransferQueueEntry(
                id = "r1", sourcePath = "/data/show",
                destinationMachine = "vault", destinationPath = "/store/show",
                status = TransferStatus.RUNNING,
            ),
            TransferQueueEntry(
                id = "f1", sourcePath = "/data/x",
                destinationMachine = "vault", destinationPath = "/store/x",
                status = TransferStatus.FAILED,
            ),
            TransferQueueEntry(
                id = "c1", sourcePath = "/data/y",
                destinationMachine = "vault", destinationPath = "/store/y",
                status = TransferStatus.COMPLETED, bytesTransferred = 50, totalBytes = 50,
            ),
        )
        // Live sidecar only for the RUNNING entry.
        val live = { id: String ->
            if (id == "r1") QueueLiveProgress(512, 2048, 2, 5, 1024, "ep3.mkv") else null
        }

        val report = buildQueueReport(entries, live)

        assertEquals(4, report.queue.size)
        assertEquals(1, report.summary.running)
        assertEquals(1, report.summary.pending)
        assertEquals(1, report.summary.failed)

        val running = report.queue.first { it.id == "r1" }
        assertEquals("running", running.status)
        assertEquals(512, running.bytesTransferred)   // from the live sidecar, not the entry
        assertEquals(2048, running.bytesTotal)
        assertEquals(2, running.filesDone)
        assertEquals(5, running.filesTotal)
        assertEquals(1024, running.rateBytesPerSec)
        assertEquals("ep3.mkv", running.currentFile)

        val pending = report.queue.first { it.id == "p1" }
        assertEquals("pending", pending.status)
        assertEquals("move", pending.mode)
        assertEquals("vault:/store/movie.mkv", pending.dest)
        assertEquals(0, pending.bytesTransferred)     // not running → falls back to entry counters
        assertEquals(1000, pending.bytesTotal)
        assertEquals(0, pending.rateBytesPerSec)

        // Valid JSON, raw counters only (no precomputed percentage field).
        val s = renderQueueReportJson(entries, live)
        Json.parseToJsonElement(s)
        assertTrue(s.contains("\"rateBytesPerSec\""))
        assertFalse(s.contains("\"percent\""))
    }
}

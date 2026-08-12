package vision.salient.choam.receipt

import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QueueReceiptStoreTest {
    private fun store() = QueueReceiptStore(Files.createTempDirectory("receipt-store-test").resolve("queue.db"))
    private val route = TransferRoute(0, "route-fingerprint-v0")

    @Test fun `reopen uses a fresh attempt and lower or duplicate observations cannot replace it`() {
        val store = store()
        val admitted = assertIs<QueueReceiptStore.MutationResult.Applied>(store.admit("queue-001", 12, 1, route)).receipt
        val duplicate = TransferReceiptObservation("duplicate-001", admitted.transferId, admitted.attemptId, 2, Instant.now(), TransferReceiptState.ACTIVE)
        assertIs<QueueReceiptStore.MutationResult.Applied>(store.apply("queue-001", duplicate))
        assertIs<QueueReceiptStore.MutationResult.Rejected>(store.apply("queue-001", duplicate))
        val lower = duplicate.copy(observationId = "lower-001", sequence = 1, state = TransferReceiptState.FAILED, failureCode = "TRANSFER_FAILED")
        assertIs<QueueReceiptStore.MutationResult.Ignored>(store.apply("queue-001", lower))
        assertIs<QueueReceiptStore.MutationResult.Applied>(store.observe("queue-001", TransferReceiptState.FAILED, "TRANSFER_FAILED"))
        val reopened = assertIs<QueueReceiptStore.MutationResult.Applied>(store.admit("queue-001", 12, 1, TransferRoute(1, "route-fingerprint-v1"))).receipt
        assertTrue(reopened.attemptId != admitted.attemptId)
        assertEquals(TransferReceiptState.DEFERRED, reopened.state)
        assertEquals(0, reopened.lastAppliedObservationSequence)
    }

    @Test fun `legacy process success reaches only verifying files`() {
        val store = store()
        store.admit("queue-002", 12, 1, route)
        store.observe("queue-002", TransferReceiptState.ACTIVE)
        store.observe("queue-002", TransferReceiptState.VERIFYING_BYTES, processExitCode = 0)
        val receipt = assertIs<QueueReceiptStore.MutationResult.Applied>(store.observe("queue-002", TransferReceiptState.VERIFYING_FILES, processExitCode = 0)).receipt
        assertEquals(TransferReceiptState.VERIFYING_FILES, receipt.state)
        assertEquals(null, receipt.destinationEvidence)
        assertEquals(null, receipt.timestamps.destinationCommittedAt)
    }

    @Test fun `malformed stored JSON fails closed and raw error detail is rejected`() {
        val db = Files.createTempDirectory("receipt-store-test").resolve("queue.db")
        val store = QueueReceiptStore(db)
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.prepareStatement("INSERT INTO queue_transfer_receipts(queue_entry_id, receipt_json) VALUES (?, ?)").use {
                it.setString(1, "queue-003"); it.setString(2, "{not-json"); it.executeUpdate()
            }
        }
        assertIs<QueueReceiptStore.MutationResult.Rejected>(store.admit("queue-003", 12, 1, route))
        store.admit("queue-004", 12, 1, route); store.observe("queue-004", TransferReceiptState.ACTIVE)
        assertIs<QueueReceiptStore.MutationResult.Rejected>(store.observe("queue-004", TransferReceiptState.FAILED, "host.example/path secret"))
    }

    @Test fun `route retries isolate attempts and queue clearing retains receipts`() {
        val db = Files.createTempDirectory("receipt-store-test").resolve("queue.db")
        val store = QueueReceiptStore(db)
        store.admit("queue-005", 12, 1, route); store.observe("queue-005", TransferReceiptState.ACTIVE)
        store.observe("queue-005", TransferReceiptState.DEFERRED, "NETWORK_DEFERRED")
        val retry = assertIs<QueueReceiptStore.MutationResult.Applied>(store.admit("queue-005", 12, 1, TransferRoute(1, "route-fingerprint-v1"))).receipt
        assertEquals(1, retry.route.generation)
        assertEquals(null, retry.destinationEvidence)
        val queue = vision.salient.choam.sync.TransferQueueStore(db)
        queue.add(vision.salient.choam.sync.TransferQueueEntry("queue-005", "/synthetic/source", "server", "/synthetic/destination"))
        queue.clear(completedOnly = false)
        assertIs<QueueReceiptStore.ReadResult.Present>(store.load("queue-005"))
    }
}

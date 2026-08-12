package vision.salient.choam.receipt

import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueueReceiptStoreTest {
    private fun store() = QueueReceiptStore(Files.createTempDirectory("receipt-store-test").resolve("queue.db"))
    @Test fun `reopen uses a fresh attempt and lower or duplicate observations cannot replace it`() {
        val store = store()
        val admitted = assertIs<QueueReceiptStore.MutationResult.Applied>(store.admit("queue-001", 12, 1)).receipt
        val duplicate = TransferReceiptObservation("duplicate-001", admitted.transferId, admitted.attemptId, 2, Instant.now(), TransferReceiptState.ACTIVE)
        assertIs<QueueReceiptStore.MutationResult.Applied>(store.apply("queue-001", duplicate))
        assertIs<QueueReceiptStore.MutationResult.Rejected>(store.apply("queue-001", duplicate))
        val lower = duplicate.copy(observationId = "lower-001", sequence = 1, state = TransferReceiptState.FAILED, failureCode = "TRANSFER_FAILED")
        assertIs<QueueReceiptStore.MutationResult.Ignored>(store.apply("queue-001", lower))
        assertIs<QueueReceiptStore.MutationResult.Applied>(store.observe("queue-001", TransferReceiptState.FAILED, "TRANSFER_FAILED"))
        val reopened = assertIs<QueueReceiptStore.MutationResult.Applied>(store.admit("queue-001", 12, 1)).receipt
        assertTrue(reopened.attemptId != admitted.attemptId)
        assertEquals(TransferReceiptState.DEFERRED, reopened.state)
        assertEquals(0, reopened.lastAppliedObservationSequence)
    }

    @Test fun `legacy process success reaches only verifying files`() {
        val store = store()
        store.admit("queue-002", 12, 1)
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
        assertIs<QueueReceiptStore.MutationResult.Rejected>(store.admit("queue-003", 12, 1))
        store.admit("queue-004", 12, 1); store.observe("queue-004", TransferReceiptState.ACTIVE)
        assertIs<QueueReceiptStore.MutationResult.Rejected>(store.observe("queue-004", TransferReceiptState.FAILED, "host.example/path secret"))
    }

    @Test fun `route retries isolate attempts and queue clearing retains receipts`() {
        val db = Files.createTempDirectory("receipt-store-test").resolve("queue.db")
        val store = QueueReceiptStore(db)
        store.admit("queue-005", 12, 1); store.observe("queue-005", TransferReceiptState.ACTIVE)
        store.observe("queue-005", TransferReceiptState.DEFERRED, "NETWORK_DEFERRED")
        val retry = assertIs<QueueReceiptStore.MutationResult.Applied>(store.admit("queue-005", 12, 1)).receipt
        assertEquals(1, retry.route.generation)
        assertEquals(null, retry.destinationEvidence)
        val queue = vision.salient.choam.sync.TransferQueueStore(db)
        queue.add(vision.salient.choam.sync.TransferQueueEntry("queue-005", "/synthetic/source", "server", "/synthetic/destination"))
        queue.clear(completedOnly = false)
        assertIs<QueueReceiptStore.ReadResult.Present>(store.load("queue-005"))
    }

    @Test fun `stale active receipt is durably deferred then restarted with a fresh attempt`() {
        val store = store()
        val first = assertIs<QueueReceiptStore.MutationResult.Applied>(store.admit("queue-006", 12, 1)).receipt
        assertIs<QueueReceiptStore.MutationResult.Applied>(store.observe("queue-006", TransferReceiptState.ACTIVE))

        val restarted = assertIs<QueueReceiptStore.MutationResult.Applied>(
            store.admit("queue-006", 12, 1)
        ).receipt
        assertEquals(TransferReceiptState.DEFERRED, restarted.state)
        assertTrue(restarted.attemptId != first.attemptId)
        assertEquals("STALE_ATTEMPT_RECONCILED", restarted.priorAttempts.single().failureCode)

        store.observe("queue-006", TransferReceiptState.ACTIVE)
        store.observe("queue-006", TransferReceiptState.VERIFYING_BYTES, processExitCode = 0)
        store.observe("queue-006", TransferReceiptState.VERIFYING_FILES, processExitCode = 0)
        assertEquals(TransferReceiptState.VERIFYING_FILES, (store.load("queue-006") as QueueReceiptStore.ReadResult.Present).receipt.state)
    }

    @Test fun `terminal receipt remains fail closed during a later claim`() {
        val store = store()
        store.admit("queue-009", 12, 1)
        store.observe("queue-009", TransferReceiptState.CANCELLED)
        assertIs<QueueReceiptStore.MutationResult.Rejected>(
            store.admit("queue-009", 12, 1)
        )
    }

    @Test fun `receipt initialization failure is sanitized and does not manufacture an observation`() {
        // A file cannot be the parent directory. This is an injected filesystem failure seam;
        // the store must stay usable as an unavailable receipt side channel, not throw.
        val parentFile = Files.createTempFile("receipt-store-parent", ".tmp")
        val unavailable = QueueReceiptStore(parentFile.resolve("queue.db"))
        assertIs<QueueReceiptStore.MutationResult.Unavailable>(unavailable.admit("queue-007", 12, 1))
        assertIs<QueueReceiptStore.ReadResult.Unavailable>(unavailable.load("queue-007"))
    }

    @Test fun `busy receipt transaction is unavailable rather than a legacy retry signal`() {
        // Adversarial lock seam. This test is intentionally source-only in the constrained
        // hardening pass; a normal test run may exercise it with the SQLite JDBC driver.
        val db = Files.createTempDirectory("receipt-store-lock").resolve("queue.db")
        val store = QueueReceiptStore(db)
        store.admit("queue-008", 12, 1)
        DriverManager.getConnection("jdbc:sqlite:$db").use { locked ->
            locked.createStatement().use { it.execute("BEGIN EXCLUSIVE") }
            assertIs<QueueReceiptStore.MutationResult.Unavailable>(
                store.observe("queue-008", TransferReceiptState.ACTIVE)
            )
            locked.createStatement().use { it.execute("ROLLBACK") }
        }
    }

    @Test fun `stale optimistic CAS is unavailable and cannot replace a concurrent receipt`() {
        val db = Files.createTempDirectory("receipt-store-cas").resolve("queue.db")
        val first = QueueReceiptStore(db)
        val second = QueueReceiptStore(db)
        first.admit("queue-010", 12, 1)
        first.beforeCompareAndSwapForTest = {
            first.beforeCompareAndSwapForTest = null
            assertIs<QueueReceiptStore.MutationResult.Applied>(
                second.observe("queue-010", TransferReceiptState.ACTIVE)
            )
        }

        assertIs<QueueReceiptStore.MutationResult.Unavailable>(
            first.observe("queue-010", TransferReceiptState.DEFERRED, "NETWORK_DEFERRED")
        )
        val persisted = assertIs<QueueReceiptStore.ReadResult.Present>(first.load("queue-010")).receipt
        assertEquals(TransferReceiptState.ACTIVE, persisted.state)
    }

    @Test fun `route allocation is monotonic and independent of a reset retry count`() {
        val first = nextReceiptRoute(null, "route-nonce-000000")!!
        val previous = TransferReceiptV1(
            transferId = "transfer-001", attemptId = "attempt-001", queueEntryId = null,
            sourceAuthority = TransferAuthority("LOCAL"), destinationAuthority = TransferAuthority("DESTINATION"),
            route = first, expectedBytes = 1, timestamps = TransferTimestamps(Instant.EPOCH),
            priorAttempts = listOf(PriorAttemptSummary("attempt-000", TransferReceiptState.DEFERRED, Instant.EPOCH, route = TransferRoute(4, "route-nonce-previous"))),
        )
        val next = nextReceiptRoute(previous, "route-nonce-000001")!!
        assertEquals(5, next.generation)
        assertTrue(next.fingerprint != first.fingerprint)
    }

    @Test fun `route allocation fails closed at generation overflow`() {
        val exhausted = TransferReceiptV1(
            transferId = "transfer-002", attemptId = "attempt-002", queueEntryId = null,
            sourceAuthority = TransferAuthority("LOCAL"), destinationAuthority = TransferAuthority("DESTINATION"),
            route = TransferRoute(Long.MAX_VALUE, "route-nonce-exhausted"), expectedBytes = 1,
            timestamps = TransferTimestamps(Instant.EPOCH),
        )
        assertNull(nextReceiptRoute(exhausted, "route-nonce-unused"))
    }
}

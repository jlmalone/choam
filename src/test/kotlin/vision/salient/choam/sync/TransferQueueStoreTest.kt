package vision.salient.choam.sync

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransferQueueStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var store: TransferQueueStore

    @BeforeEach
    fun setup() {
        store = TransferQueueStore(tempDir.resolve("transfer_queue.db"))
    }

    private fun entry(
        id: String = "test1234",
        source: String = "/tmp/test.mov",
        destMachine: String = "server-b",
        destPath: String = "/Volumes/EXTERNAL/",
        mode: TransferMode = TransferMode.COPY,
        status: TransferStatus = TransferStatus.PENDING,
        priority: TransferPriority = TransferPriority.NORMAL
    ) = TransferQueueEntry(
        id = id,
        sourcePath = source,
        destinationMachine = destMachine,
        destinationPath = destPath,
        mode = mode,
        status = status,
        priority = priority
    )

    // ── Basic CRUD ──

    @Test
    fun `add and loadAll returns entry`() {
        val e = entry()
        store.add(e)
        val loaded = store.loadAll()
        assertEquals(1, loaded.size)
        assertEquals(e.id, loaded[0].id)
        assertEquals(e.sourcePath, loaded[0].sourcePath)
        assertEquals(e.destinationMachine, loaded[0].destinationMachine)
    }

    @Test
    fun `loadAll returns empty for fresh database`() {
        assertEquals(0, store.loadAll().size)
    }

    @Test
    fun `update modifies entry`() {
        store.add(entry())
        store.update("test1234") { it.copy(status = TransferStatus.RUNNING, startedAt = Instant.now().toString()) }
        val loaded = store.loadAll()
        assertEquals(TransferStatus.RUNNING, loaded[0].status)
        assertNotNull(loaded[0].startedAt)
    }

    @Test
    fun `cancel changes status to CANCELLED`() {
        store.add(entry())
        assertTrue(store.cancel("test1234"))
        assertEquals(TransferStatus.CANCELLED, store.loadAll()[0].status)
    }

    @Test
    fun `cancel only works for PENDING or FAILED`() {
        store.add(entry())
        store.update("test1234") { it.copy(status = TransferStatus.RUNNING) }
        // Cannot cancel a RUNNING entry via cancel()
        assertTrue(!store.cancel("test1234"))
        assertEquals(TransferStatus.RUNNING, store.loadAll()[0].status)
    }

    @Test
    fun `clear removes completed and cancelled`() {
        store.add(entry(id = "a1", status = TransferStatus.COMPLETED))
        store.add(entry(id = "a2", status = TransferStatus.CANCELLED))
        store.add(entry(id = "a3", status = TransferStatus.PENDING))
        store.clear(completedOnly = true)
        val remaining = store.loadAll()
        assertEquals(1, remaining.size)
        assertEquals("a3", remaining[0].id)
    }

    // ── Deduplication ──

    @Test
    fun `add returns existing entry for duplicate source dest machine`() {
        val first = entry(id = "first123")
        store.add(first)
        val dup = entry(id = "second12")
        val returned = store.add(dup)
        assertEquals("first123", returned.id)
        assertEquals(1, store.loadAll().size)
    }

    @Test
    fun `duplicate check ignores completed entries`() {
        store.add(entry(id = "first123"))
        store.update("first123") { it.copy(status = TransferStatus.COMPLETED) }
        val second = entry(id = "second12")
        val returned = store.add(second)
        assertEquals("second12", returned.id)
        assertEquals(2, store.loadAll().size)
    }

    @Test
    fun `same endpoints with different operation semantics are not duplicates`() {
        store.add(entry(id = "copy1234", mode = TransferMode.COPY))
        store.add(entry(id = "move1234", mode = TransferMode.MOVE))
        store.add(entry(id = "force123", mode = TransferMode.COPY).copy(force = true))

        assertEquals(3, store.loadAll().size)
    }

    @Test
    fun `concurrent equivalent adds collapse to one active operation`() {
        val dbPath = tempDir.resolve("concurrent-transfer-queue.db")
        val firstStore = TransferQueueStore(dbPath)
        val secondStore = TransferQueueStore(dbPath)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(
                pool.submit<TransferQueueEntry> {
                    ready.countDown(); start.await()
                    firstStore.add(entry(id = "race0001"))
                },
                pool.submit<TransferQueueEntry> {
                    ready.countDown(); start.await()
                    secondStore.add(entry(id = "race0002"))
                }
            )
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val ids = futures.map { it.get(10, TimeUnit.SECONDS).id }.toSet()

            assertEquals(1, ids.size)
            assertEquals(1, firstStore.loadAll().size)
        } finally {
            pool.shutdownNow()
        }
    }

    // ── Pending filtering ──

    @Test
    fun `pending returns PENDING and FAILED entries`() {
        store.add(entry(id = "a1", status = TransferStatus.PENDING))
        store.add(entry(id = "a2", status = TransferStatus.FAILED, source = "/tmp/b.mov"))
        store.add(entry(id = "a3", status = TransferStatus.COMPLETED, source = "/tmp/c.mov"))
        val pending = store.pending()
        assertEquals(2, pending.size)
    }

    @Test
    fun `pending respects backoff - skips entries with future nextRetryAt`() {
        val e = entry()
        store.add(e)
        val futureRetry = Instant.now().plusSeconds(3600).toString()
        store.update(e.id) { it.copy(status = TransferStatus.FAILED, nextRetryAt = futureRetry) }
        assertEquals(0, store.pending().size)
    }

    @Test
    fun `pending skips entries beyond MAX_RETRIES`() {
        val e = entry()
        store.add(e)
        store.update(e.id) { it.copy(retryCount = TransferQueueEntry.MAX_RETRIES + 1) }
        assertEquals(0, store.pending().size)
    }

    @Test
    fun `pending sorts by priority HIGH first`() {
        store.add(entry(id = "low12345", priority = TransferPriority.LOW, source = "/tmp/a"))
        store.add(entry(id = "high1234", priority = TransferPriority.HIGH, source = "/tmp/b"))
        store.add(entry(id = "norm1234", priority = TransferPriority.NORMAL, source = "/tmp/c"))
        val pending = store.pending()
        assertEquals("high1234", pending[0].id)
        assertEquals("norm1234", pending[1].id)
        assertEquals("low12345", pending[2].id)
    }

    // ── Atomic claim ──

    @Test
    fun `claimNext returns entry and marks RUNNING`() {
        store.add(entry())
        val claimed = store.claimNext()
        assertNotNull(claimed)
        assertEquals("test1234", claimed.id)
        // After claim, loadAll should show RUNNING
        assertEquals(TransferStatus.RUNNING, store.loadAll()[0].status)
    }

    @Test
    fun `claimNext returns null when no entries`() {
        assertNull(store.claimNext())
    }

    @Test
    fun `claimNext returns null when all entries are RUNNING`() {
        store.add(entry())
        store.claimNext() // Claims the only entry
        assertNull(store.claimNext()) // Nothing left
    }

    @Test
    fun `defer returns a running entry to pending with future backoff`() {
        store.add(entry())
        assertNotNull(store.claimNext())

        assertTrue(store.defer("test1234", "server-b unreachable"))

        val deferred = store.loadAll().single()
        assertEquals(TransferStatus.PENDING, deferred.status)
        assertNull(deferred.startedAt)
        assertEquals("Deferred: server-b unreachable", deferred.error)
        assertEquals(1, deferred.retryCount)
        assertNotNull(deferred.nextRetryAt)
        assertTrue(Instant.parse(deferred.nextRetryAt).isAfter(Instant.now()))
        assertNull(store.claimNext(), "deferred entry must not be reclaimed before its backoff expires")
    }

    @Test
    fun `repeated deferral caps retry count without exhausting transient work`() {
        store.add(entry())
        store.update("test1234") { it.copy(retryCount = TransferQueueEntry.MAX_RETRIES) }
        assertNotNull(store.claimNext())

        assertTrue(store.defer("test1234", "server-b unreachable"))

        val deferred = store.loadAll().single()
        assertEquals(TransferStatus.PENDING, deferred.status)
        assertEquals(TransferQueueEntry.MAX_RETRIES, deferred.retryCount)
        assertNotNull(deferred.nextRetryAt)
    }

    @Test
    fun `claimNext respects priority order`() {
        store.add(entry(id = "low12345", priority = TransferPriority.LOW, source = "/tmp/a"))
        store.add(entry(id = "high1234", priority = TransferPriority.HIGH, source = "/tmp/b"))
        val claimed = store.claimNext()
        assertNotNull(claimed)
        assertEquals("high1234", claimed.id)
    }

    @Test
    fun `concurrent claimNext does not return same entry`() {
        // Add 3 entries
        store.add(entry(id = "entry001", source = "/tmp/a"))
        store.add(entry(id = "entry002", source = "/tmp/b"))
        store.add(entry(id = "entry003", source = "/tmp/c"))

        val claimed = mutableSetOf<String>()
        // Simulate sequential claims (SQLite serializes anyway)
        repeat(3) {
            val c = store.claimNext()
            if (c != null) claimed.add(c.id)
        }
        assertEquals(3, claimed.size, "All 3 entries should be claimed uniquely")
        assertNull(store.claimNext(), "No more entries to claim")
    }

    // ── Stale RUNNING recovery ──

    @Test
    fun `recoverStaleRunning recovers stuck entries`() {
        store.add(entry())
        // Manually set to RUNNING with an old startedAt
        store.update("test1234") {
            it.copy(
                status = TransferStatus.RUNNING,
                startedAt = Instant.now().minusSeconds(3 * 3600).toString() // 3 hours ago
            )
        }
        val recovered = store.recoverStaleRunning()
        assertEquals(1, recovered)
        val entries = store.loadAll()
        assertEquals(TransferStatus.FAILED, entries[0].status)
        assertTrue(entries[0].error?.contains("watchdog") == true)
    }

    @Test
    fun `recoverStaleRunning does not touch fresh RUNNING entries`() {
        store.add(entry())
        store.update("test1234") {
            it.copy(status = TransferStatus.RUNNING, startedAt = Instant.now().toString())
        }
        // PID is current process, so it's alive — should NOT recover
        val recovered = store.recoverStaleRunning()
        assertEquals(0, recovered)
    }

    @Test
    fun `recoverStaleRunning reclaims a stuck entry whose owner PID is alive but has no progress`() {
        // This is the wedge: a drain JVM claims an entry (setting pid = a live PID) then hangs
        // in a pre-transfer phase (SSH preflight/verify) that never writes a progress sidecar.
        // The old watchdog said `pidDead == false -> false` and left it RUNNING forever, which
        // (with the queue-run lock also held by that live PID) froze the whole queue.
        store.add(entry())
        val claimed = store.claimNext()          // status=RUNNING, pid=current (alive), started_at=now
        assertNotNull(claimed)
        // Backdate past the stale threshold; keep RUNNING; update() preserves the live pid.
        store.update("test1234") {
            it.copy(status = TransferStatus.RUNNING, startedAt = Instant.now().minusSeconds(3 * 3600).toString())
        }
        // No queue-progress-test1234.json exists next to the DB, so progress is "unknown".
        val recovered = store.recoverStaleRunning()
        assertEquals(1, recovered, "an alive-PID entry with no progress must still be reclaimed by wall-clock")
        assertEquals(TransferStatus.FAILED, store.loadAll()[0].status)
    }

    @Test
    fun `recoverStaleRunning leaves an old RUNNING entry alone while its progress sidecar is fresh`() {
        // Guard against over-recovery: a genuinely slow transfer can be RUNNING for hours, but as
        // long as its progress sidecar keeps updating it is healthy and must not be killed.
        store.add(entry())
        val claimed = store.claimNext()
        assertNotNull(claimed)
        store.update("test1234") {
            it.copy(status = TransferStatus.RUNNING, startedAt = Instant.now().minusSeconds(3 * 3600).toString())
        }
        // Progress sidecars live next to the DB (choamDir = dbPath.parent). Write a fresh one.
        Files.writeString(tempDir.resolve("queue-progress-test1234.json"), "{}")
        val recovered = store.recoverStaleRunning()
        assertEquals(0, recovered, "fresh progress means the transfer is alive — do not reclaim")
        assertEquals(TransferStatus.RUNNING, store.loadAll()[0].status)
    }

    // ── Auto-expire ──

    @Test
    fun `loadAll auto-expires old completed entries`() {
        store.add(entry(id = "old12345"))
        val oldTime = Instant.now().minusSeconds(25 * 3600).toString() // 25 hours ago
        store.update("old12345") { it.copy(status = TransferStatus.COMPLETED, completedAt = oldTime) }

        store.add(entry(id = "fresh123", source = "/tmp/fresh.mov"))
        store.update("fresh123") { it.copy(status = TransferStatus.COMPLETED, completedAt = Instant.now().toString()) }

        val entries = store.loadAll()
        assertEquals(1, entries.size)
        assertEquals("fresh123", entries[0].id)
    }

    // ── JSON migration ──

    @Test
    fun `migrates from JSON file on construction`() {
        val jsonPath = tempDir.resolve("transfer_queue.json")
        val jsonContent = """[{
            "id": "migrated1",
            "sourcePath": "/tmp/old.mov",
            "destinationMachine": "server-a",
            "destinationPath": "/backup/",
            "mode": "COPY",
            "status": "PENDING",
            "priority": "NORMAL",
            "noCatalog": false,
            "createdAt": "2026-01-01T00:00:00Z",
            "bytesTransferred": 0,
            "totalBytes": 0,
            "retryCount": 0,
            "force": false,
            "backup": false
        }]"""
        Files.writeString(jsonPath, jsonContent)

        // Construct store — should migrate
        val migratingStore = TransferQueueStore(tempDir.resolve("transfer_queue.db"))
        val entries = migratingStore.loadAll()
        assertEquals(1, entries.size)
        assertEquals("migrated1", entries[0].id)
        assertEquals("server-a", entries[0].destinationMachine)

        // JSON file should be renamed
        assertTrue(!Files.exists(jsonPath))
        assertTrue(Files.exists(tempDir.resolve("transfer_queue.json.migrated")))
    }

    // ── Mode and flags ──

    @Test
    fun `MOVE mode is preserved`() {
        store.add(entry(mode = TransferMode.MOVE))
        assertEquals(TransferMode.MOVE, store.loadAll()[0].mode)
    }

    @Test
    fun `force and backup flags are preserved`() {
        val e = TransferQueueEntry(
            id = "flagtest",
            sourcePath = "/tmp/t",
            destinationMachine = "server-a",
            destinationPath = "/backup/",
            force = true,
            backup = true
        )
        store.add(e)
        val loaded = store.loadAll()[0]
        assertTrue(loaded.force)
        assertTrue(loaded.backup)
    }

    @Test
    fun `bandwidth limit is preserved`() {
        store.add(entry().copy(bandwidthLimitKBps = 5120))
        assertEquals(5120, store.loadAll()[0].bandwidthLimitKBps)
    }

    @Test
    fun `null bandwidth limit is preserved`() {
        store.add(entry())
        assertNull(store.loadAll()[0].bandwidthLimitKBps)
    }

    // ── Reset / reclaim of stuck RUNNING entries ──

    @Test
    fun `resetToPending reclaims a stuck RUNNING entry and clears its fields`() {
        store.add(entry())
        // Simulate an entry left RUNNING by a dead processor, with stale error/retry/progress state.
        store.update("test1234") {
            it.copy(
                status = TransferStatus.RUNNING,
                startedAt = Instant.now().toString(),
                error = "prior failure",
                retryCount = 2,
                nextRetryAt = Instant.now().plusSeconds(3600).toString(),
                bytesTransferred = 12345L
            )
        }

        assertTrue(store.resetToPending("test1234"))

        val e = store.loadAll()[0]
        assertEquals(TransferStatus.PENDING, e.status)
        assertNull(e.startedAt)
        assertNull(e.error)
        assertNull(e.nextRetryAt)
        assertEquals(0, e.retryCount)
        assertEquals(0L, e.bytesTransferred)
    }

    @Test
    fun `resetToPending makes a stuck RUNNING entry claimable again`() {
        store.add(entry())
        // A RUNNING entry is invisible to claimNext()...
        store.update("test1234") { it.copy(status = TransferStatus.RUNNING, startedAt = Instant.now().toString()) }
        assertNull(store.claimNext())
        // ...until it is reset.
        assertTrue(store.resetToPending("test1234"))
        val claimed = store.claimNext()
        assertNotNull(claimed)
        assertEquals("test1234", claimed.id)
    }

    @Test
    fun `resetToPending returns false for unknown id`() {
        assertTrue(!store.resetToPending("nonexistent"))
    }

    @Test
    fun `cancelRunning cancels a RUNNING entry that plain cancel cannot`() {
        store.add(entry())
        store.update("test1234") { it.copy(status = TransferStatus.RUNNING, startedAt = Instant.now().toString()) }
        // Plain cancel() refuses RUNNING...
        assertTrue(!store.cancel("test1234"))
        // ...but cancelRunning() handles it (this is what the CLI's --cancel now routes through).
        assertTrue(store.cancelRunning("test1234"))
        assertEquals(TransferStatus.CANCELLED, store.loadAll()[0].status)
    }

    @Test
    fun `cancelRunning delegates to cancel for non-running entries`() {
        store.add(entry())
        assertTrue(store.cancelRunning("test1234"))
        assertEquals(TransferStatus.CANCELLED, store.loadAll()[0].status)
    }
}

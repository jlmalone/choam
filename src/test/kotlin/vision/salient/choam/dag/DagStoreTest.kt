package vision.salient.choam.dag

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DagStoreTest {

    @TempDir
    lateinit var tempDir: Path

    // --- Basic CRUD ---

    @Test
    fun `append and retrieve event`() {
        val store = DagStore(tempDir.resolve("test.db").toString())
        val conn = store.open()
        val event = createTestEvent("sha256:aaa111", lamport = 1)
        store.append(conn, event)

        val retrieved = store.getEvent(conn, "sha256:aaa111")
        assertNotNull(retrieved)
        assertEquals("sha256:aaa111", retrieved.id)
        assertEquals("TEST_TYPE", retrieved.type)
        assertEquals(1L, retrieved.timestamp.lamport)
        conn.close()
    }

    @Test
    fun `getEvent returns null for missing ID`() {
        val store = DagStore(tempDir.resolve("test.db").toString())
        val conn = store.open()
        assertNull(store.getEvent(conn, "sha256:nonexistent"))
        conn.close()
    }

    @Test
    fun `append is idempotent — duplicate ID ignored`() {
        val store = DagStore(tempDir.resolve("test.db").toString())
        val conn = store.open()
        val event = createTestEvent("sha256:dup111")

        assertTrue(store.append(conn, event))  // First insert
        val second = store.append(conn, event) // Duplicate — OR IGNORE
        // Count should still be 1
        assertEquals(1, store.getEventCount(conn))
        conn.close()
    }

    // --- Ordering ---

    @Test
    fun `getAllEvents returns sorted by lamport then wall then id`() {
        val store = DagStore(tempDir.resolve("test.db").toString())
        val conn = store.open()

        store.append(conn, createTestEvent("sha256:ccc", lamport = 3))
        store.append(conn, createTestEvent("sha256:aaa", lamport = 1))
        store.append(conn, createTestEvent("sha256:bbb", lamport = 2))

        val events = store.getAllEvents(conn)
        assertEquals(3, events.size)
        assertEquals("sha256:aaa", events[0].id)
        assertEquals("sha256:bbb", events[1].id)
        assertEquals("sha256:ccc", events[2].id)
        conn.close()
    }

    @Test
    fun `same lamport sorts by wall time then id`() {
        val store = DagStore(tempDir.resolve("test.db").toString())
        val conn = store.open()

        store.append(conn, createTestEvent("sha256:bbb", lamport = 1, wall = "2026-03-06T12:00:01.000Z"))
        store.append(conn, createTestEvent("sha256:aaa", lamport = 1, wall = "2026-03-06T12:00:00.000Z"))

        val events = store.getAllEvents(conn)
        assertEquals("sha256:aaa", events[0].id) // Earlier wall time
        assertEquals("sha256:bbb", events[1].id)
        conn.close()
    }

    // --- Heads ---

    @Test
    fun `append updates head for house`() {
        val store = DagStore(tempDir.resolve("test.db").toString())
        val conn = store.open()

        store.append(conn, createTestEvent("sha256:first", lamport = 1))
        assertEquals("sha256:first", store.getHead(conn, "testHouseId1234567890ab"))

        store.append(conn, createTestEvent("sha256:second", lamport = 2))
        assertEquals("sha256:second", store.getHead(conn, "testHouseId1234567890ab"))
        conn.close()
    }

    @Test
    fun `getHead returns null for unknown house`() {
        val store = DagStore(tempDir.resolve("test.db").toString())
        val conn = store.open()
        assertNull(store.getHead(conn, "unknown"))
        conn.close()
    }

    // --- Counters ---

    @Test
    fun `getEventCount returns total events`() {
        val store = DagStore(tempDir.resolve("test.db").toString())
        val conn = store.open()
        assertEquals(0, store.getEventCount(conn))
        store.append(conn, createTestEvent("sha256:a1", lamport = 1))
        store.append(conn, createTestEvent("sha256:a2", lamport = 2))
        store.append(conn, createTestEvent("sha256:a3", lamport = 3))
        assertEquals(3, store.getEventCount(conn))
        conn.close()
    }

    @Test
    fun `getMaxLamport returns highest lamport clock`() {
        val store = DagStore(tempDir.resolve("test.db").toString())
        val conn = store.open()
        assertEquals(0, store.getMaxLamport(conn))
        store.append(conn, createTestEvent("sha256:a1", lamport = 5))
        store.append(conn, createTestEvent("sha256:a2", lamport = 3))
        store.append(conn, createTestEvent("sha256:a3", lamport = 10))
        assertEquals(10, store.getMaxLamport(conn))
        conn.close()
    }

    // --- Type filtering ---

    @Test
    fun `getEventsByType filters correctly`() {
        val store = DagStore(tempDir.resolve("test.db").toString())
        val conn = store.open()
        store.append(conn, createTestEvent("sha256:a1", lamport = 1, type = "MACHINE_JOINED"))
        store.append(conn, createTestEvent("sha256:a2", lamport = 2, type = "DRIVE_ADDED"))
        store.append(conn, createTestEvent("sha256:a3", lamport = 3, type = "MACHINE_JOINED"))

        val machines = store.getEventsByType(conn, "MACHINE_JOINED")
        assertEquals(2, machines.size)
        val drives = store.getEventsByType(conn, "DRIVE_ADDED")
        assertEquals(1, drives.size)
        conn.close()
    }

    // --- ID set ---

    @Test
    fun `getAllEventIds returns all IDs`() {
        val store = DagStore(tempDir.resolve("test.db").toString())
        val conn = store.open()
        store.append(conn, createTestEvent("sha256:x1", lamport = 1))
        store.append(conn, createTestEvent("sha256:x2", lamport = 2))

        val ids = store.getAllEventIds(conn)
        assertEquals(setOf("sha256:x1", "sha256:x2"), ids)
        conn.close()
    }

    // --- Local state ---

    @Test
    fun `setLocalState and getLocalState roundtrip`() {
        val store = DagStore(tempDir.resolve("test.db").toString())
        val conn = store.open()
        store.setLocalState(conn, "port", "8742")
        assertEquals("8742", store.getLocalState(conn, "port"))
        conn.close()
    }

    @Test
    fun `setLocalState overwrites existing value`() {
        val store = DagStore(tempDir.resolve("test.db").toString())
        val conn = store.open()
        store.setLocalState(conn, "key", "old")
        store.setLocalState(conn, "key", "new")
        assertEquals("new", store.getLocalState(conn, "key"))
        conn.close()
    }

    @Test
    fun `getLocalState returns null for missing key`() {
        val store = DagStore(tempDir.resolve("test.db").toString())
        val conn = store.open()
        assertNull(store.getLocalState(conn, "missing"))
        conn.close()
    }

    // --- Payload preservation ---

    @Test
    fun `payload map survives roundtrip`() {
        val store = DagStore(tempDir.resolve("test.db").toString())
        val conn = store.open()
        val payload = mapOf("name" to "test-machine", "ip" to "100.64.0.1", "port" to "22")
        store.append(conn, createTestEvent("sha256:p1", lamport = 1, payload = payload))

        val retrieved = store.getEvent(conn, "sha256:p1")!!
        assertEquals("test-machine", retrieved.payload["name"])
        assertEquals("100.64.0.1", retrieved.payload["ip"])
        assertEquals("22", retrieved.payload["port"])
        conn.close()
    }

    // --- Helpers ---

    private fun createTestEvent(
        id: String = "sha256:test",
        lamport: Long = 1,
        wall: String = "2026-03-06T12:00:00.000Z",
        type: String = "TEST_TYPE",
        payload: Map<String, String> = mapOf("key" to "value")
    ) = DagEvent(
        id = id,
        version = 1,
        parents = emptyList(),
        timestamp = DagTimestamp(lamport = lamport, wall = wall),
        author = DagAuthor(houseId = "testHouseId1234567890ab", machineId = "test-machine"),
        type = type,
        payload = payload
    )
}

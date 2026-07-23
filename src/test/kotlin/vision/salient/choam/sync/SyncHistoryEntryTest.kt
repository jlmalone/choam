package vision.salient.choam.sync

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class SyncHistoryEntryTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `entry serializes to JSON`() {
        val entry = SyncHistoryEntry(
            id = "test-id-123",
            repositories = listOf("media"),
            sourceMachine = "desktop",
            targetMachine = "laptop",
            startTime = "2026-03-01T10:00:00Z",
            endTime = "2026-03-01T10:05:00Z",
            status = "COMPLETED",
            filesTransferred = 42,
            bytesTransferred = 1_234_567,
            errors = 0
        )

        val serialized = json.encodeToString(SyncHistoryEntry.serializer(), entry)

        assertTrue(serialized.contains("\"id\":\"test-id-123\""))
        assertTrue(serialized.contains("\"sourceMachine\":\"desktop\""))
        assertTrue(serialized.contains("\"targetMachine\":\"laptop\""))
        assertTrue(serialized.contains("\"filesTransferred\":42"))
        assertTrue(serialized.contains("\"bytesTransferred\":1234567"))
    }

    @Test
    fun `entry deserializes from JSON`() {
        val jsonStr = """{"id":"abc","repositories":["media","archive"],"sourceMachine":"a","targetMachine":"b","startTime":"2026-01-01T00:00:00Z","endTime":"2026-01-01T01:00:00Z","status":"FAILED","filesTransferred":10,"bytesTransferred":5000,"errors":2}"""

        val entry = json.decodeFromString(SyncHistoryEntry.serializer(), jsonStr)

        assertEquals("abc", entry.id)
        assertEquals(listOf("media", "archive"), entry.repositories)
        assertEquals("a", entry.sourceMachine)
        assertEquals("b", entry.targetMachine)
        assertEquals("FAILED", entry.status)
        assertEquals(10, entry.filesTransferred)
        assertEquals(5000, entry.bytesTransferred)
        assertEquals(2, entry.errors)
    }

    @Test
    fun `entry round-trips through serialization`() {
        val original = SyncHistoryEntry(
            id = "round-trip-test",
            repositories = listOf("repo1", "repo2", "repo3"),
            sourceMachine = "machine-a",
            targetMachine = "machine-b",
            startTime = "2026-06-15T14:30:00Z",
            endTime = "2026-06-15T15:45:00Z",
            status = "COMPLETED",
            filesTransferred = 1000,
            bytesTransferred = 99_999_999_999,
            errors = 0
        )

        val serialized = json.encodeToString(SyncHistoryEntry.serializer(), original)
        val deserialized = json.decodeFromString(SyncHistoryEntry.serializer(), serialized)

        assertEquals(original, deserialized)
    }

    @Test
    fun `entry ignores unknown fields during deserialization`() {
        val jsonStr = """{"id":"x","repositories":["r"],"sourceMachine":"a","targetMachine":"b","startTime":"t1","endTime":"t2","status":"OK","filesTransferred":0,"bytesTransferred":0,"errors":0,"unknownField":"value","anotherUnknown":123}"""

        val entry = json.decodeFromString(SyncHistoryEntry.serializer(), jsonStr)
        assertEquals("x", entry.id)
    }

    @Test
    fun `entry handles empty repositories list`() {
        val entry = SyncHistoryEntry(
            id = "empty",
            repositories = emptyList(),
            sourceMachine = "a",
            targetMachine = "b",
            startTime = "t1",
            endTime = "t2",
            status = "COMPLETED",
            filesTransferred = 0,
            bytesTransferred = 0,
            errors = 0
        )

        val serialized = json.encodeToString(SyncHistoryEntry.serializer(), entry)
        val deserialized = json.decodeFromString(SyncHistoryEntry.serializer(), serialized)
        assertEquals(emptyList(), deserialized.repositories)
    }

    @Test
    fun `entry handles large byte counts`() {
        val entry = SyncHistoryEntry(
            id = "big",
            repositories = listOf("huge-repo"),
            sourceMachine = "server",
            targetMachine = "backup",
            startTime = "t1",
            endTime = "t2",
            status = "COMPLETED",
            filesTransferred = 1_270_000,
            bytesTransferred = 2_300_000_000_000, // 2.3 TB
            errors = 0
        )

        val serialized = json.encodeToString(SyncHistoryEntry.serializer(), entry)
        val deserialized = json.decodeFromString(SyncHistoryEntry.serializer(), serialized)
        assertEquals(2_300_000_000_000, deserialized.bytesTransferred)
    }

    @Test
    fun `SyncHistoryEntry from SyncSession conversion preserves data`() {
        val session = SyncSession(
            id = "session-abc",
            sourceMachine = "src",
            targetMachine = "tgt",
            repositories = listOf("media", "docs"),
            startTime = java.time.Instant.parse("2026-03-01T12:00:00Z"),
            endTime = java.time.Instant.parse("2026-03-01T12:30:00Z"),
            status = SyncStatus.COMPLETED,
            statistics = SyncStatistics(
                filesTransferred = 500,
                bytesTransferred = 50_000_000,
                errors = 1
            )
        )

        val entry = SyncHistoryEntry(
            id = session.id,
            repositories = session.repositories,
            sourceMachine = session.sourceMachine,
            targetMachine = session.targetMachine,
            startTime = session.startTime.toString(),
            endTime = session.endTime.toString(),
            status = session.status.name,
            filesTransferred = session.statistics.filesTransferred,
            bytesTransferred = session.statistics.bytesTransferred,
            errors = session.statistics.errors
        )

        assertEquals("session-abc", entry.id)
        assertEquals(listOf("media", "docs"), entry.repositories)
        assertEquals("src", entry.sourceMachine)
        assertEquals("tgt", entry.targetMachine)
        assertEquals("2026-03-01T12:00:00Z", entry.startTime)
        assertEquals("2026-03-01T12:30:00Z", entry.endTime)
        assertEquals("COMPLETED", entry.status)
        assertEquals(500, entry.filesTransferred)
        assertEquals(50_000_000, entry.bytesTransferred)
        assertEquals(1, entry.errors)
    }
}

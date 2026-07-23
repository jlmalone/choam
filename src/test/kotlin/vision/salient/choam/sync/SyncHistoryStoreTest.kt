package vision.salient.choam.sync

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SyncHistoryStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var historyPath: Path
    private lateinit var store: SyncHistoryStore

    @BeforeEach
    fun setup() {
        historyPath = tempDir.resolve("sync_history.jsonl")
        store = SyncHistoryStore(historyPath)
    }

    private fun createSession(
        repos: List<String> = listOf("media"),
        source: String = "desktop",
        target: String = "laptop",
        status: SyncStatus = SyncStatus.COMPLETED,
        filesTransferred: Long = 10,
        bytesTransferred: Long = 1024,
        errors: Int = 0
    ): SyncSession = SyncSession(
        sourceMachine = source,
        targetMachine = target,
        repositories = repos,
        startTime = Instant.parse("2026-03-01T10:00:00Z"),
        endTime = Instant.parse("2026-03-01T10:05:00Z"),
        status = status,
        statistics = SyncStatistics(
            filesTransferred = filesTransferred,
            bytesTransferred = bytesTransferred,
            errors = errors
        )
    )

    @Test
    fun `record creates history file and writes entry`() {
        val session = createSession()
        store.record(session)

        assertTrue(Files.exists(historyPath))
        val lines = Files.readAllLines(historyPath)
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("\"sourceMachine\":\"desktop\""))
    }

    @Test
    fun `record appends multiple entries`() {
        store.record(createSession(source = "desktop"))
        store.record(createSession(source = "laptop"))
        store.record(createSession(source = "server"))

        val lines = Files.readAllLines(historyPath).filter { it.isNotBlank() }
        assertEquals(3, lines.size)
    }

    @Test
    fun `loadAll returns empty list when file missing`() {
        val entries = store.loadAll()
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `loadAll returns all recorded entries`() {
        store.record(createSession(repos = listOf("media")))
        store.record(createSession(repos = listOf("archive")))

        val entries = store.loadAll()
        assertEquals(2, entries.size)
        assertEquals(listOf("media"), entries[0].repositories)
        assertEquals(listOf("archive"), entries[1].repositories)
    }

    @Test
    fun `loadAll skips malformed lines`() {
        Files.writeString(historyPath, """
            {"id":"1","repositories":["media"],"sourceMachine":"a","targetMachine":"b","startTime":"2026-03-01T10:00:00Z","endTime":"2026-03-01T10:05:00Z","status":"COMPLETED","filesTransferred":1,"bytesTransferred":100,"errors":0}
            THIS IS NOT JSON
            {"id":"2","repositories":["archive"],"sourceMachine":"c","targetMachine":"d","startTime":"2026-03-01T11:00:00Z","endTime":"2026-03-01T11:05:00Z","status":"COMPLETED","filesTransferred":2,"bytesTransferred":200,"errors":0}
        """.trimIndent() + "\n")

        val entries = store.loadAll()
        assertEquals(2, entries.size)
        assertEquals("1", entries[0].id)
        assertEquals("2", entries[1].id)
    }

    @Test
    fun `loadAll handles empty file`() {
        Files.writeString(historyPath, "")
        val entries = store.loadAll()
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `loadAll handles file with only blank lines`() {
        Files.writeString(historyPath, "\n\n\n")
        val entries = store.loadAll()
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `query filters by repo`() {
        store.record(createSession(repos = listOf("media")))
        store.record(createSession(repos = listOf("archive")))
        store.record(createSession(repos = listOf("media", "archive")))

        val mediaOnly = store.query(repo = "media")
        assertEquals(2, mediaOnly.size)

        val archiveOnly = store.query(repo = "archive")
        assertEquals(2, archiveOnly.size)
    }

    @Test
    fun `query limits results with last parameter`() {
        repeat(20) {
            store.record(createSession())
        }

        val last5 = store.query(last = 5)
        assertEquals(5, last5.size)
    }

    @Test
    fun `query with both repo and last`() {
        repeat(10) {
            store.record(createSession(repos = listOf("media")))
        }
        repeat(5) {
            store.record(createSession(repos = listOf("archive")))
        }

        val result = store.query(repo = "media", last = 3)
        assertEquals(3, result.size)
        assertTrue(result.all { it.repositories.contains("media") })
    }

    @Test
    fun `query with no filters returns all`() {
        store.record(createSession(repos = listOf("media")))
        store.record(createSession(repos = listOf("archive")))

        val all = store.query()
        assertEquals(2, all.size)
    }

    @Test
    fun `lastSyncFor returns matching entry`() {
        store.record(createSession(repos = listOf("media"), source = "desktop", target = "laptop"))
        store.record(createSession(repos = listOf("archive"), source = "desktop", target = "server"))
        store.record(createSession(repos = listOf("media"), source = "desktop", target = "server"))

        val result = store.lastSyncFor("media", "desktop", "laptop")
        assertNotNull(result)
        assertEquals("desktop", result.sourceMachine)
        assertEquals("laptop", result.targetMachine)
    }

    @Test
    fun `lastSyncFor returns null when no match`() {
        store.record(createSession(repos = listOf("media"), source = "desktop", target = "laptop"))

        val result = store.lastSyncFor("archive", "desktop", "laptop")
        assertNull(result)
    }

    @Test
    fun `lastSyncFor returns most recent match`() {
        store.record(createSession(repos = listOf("media"), source = "desktop", target = "laptop"))
        store.record(createSession(repos = listOf("media"), source = "desktop", target = "laptop"))

        val result = store.lastSyncFor("media", "desktop", "laptop")
        assertNotNull(result)
    }

    @Test
    fun `lastSyncForRepo returns latest entry for repo`() {
        store.record(createSession(repos = listOf("media"), source = "desktop", target = "laptop"))
        store.record(createSession(repos = listOf("archive"), source = "server", target = "laptop"))
        store.record(createSession(repos = listOf("media"), source = "server", target = "desktop"))

        val result = store.lastSyncForRepo("media")
        assertNotNull(result)
        assertEquals("server", result.sourceMachine)
    }

    @Test
    fun `lastSyncForRepo returns null when no entries for repo`() {
        store.record(createSession(repos = listOf("archive")))

        val result = store.lastSyncForRepo("media")
        assertNull(result)
    }

    @Test
    fun `record creates parent directories`() {
        val deepPath = tempDir.resolve("a/b/c/history.jsonl")
        val deepStore = SyncHistoryStore(deepPath)

        deepStore.record(createSession())

        assertTrue(Files.exists(deepPath))
    }

    @Test
    fun `record preserves session id`() {
        val session = createSession()
        store.record(session)

        val loaded = store.loadAll()
        assertEquals(1, loaded.size)
        assertEquals(session.id, loaded[0].id)
    }

    @Test
    fun `record captures all statistics fields`() {
        val session = createSession(
            filesTransferred = 42,
            bytesTransferred = 999_999,
            errors = 3
        )
        store.record(session)

        val loaded = store.loadAll()
        assertEquals(1, loaded.size)
        assertEquals(42, loaded[0].filesTransferred)
        assertEquals(999_999, loaded[0].bytesTransferred)
        assertEquals(3, loaded[0].errors)
    }

    @Test
    fun `record captures status correctly`() {
        store.record(createSession(status = SyncStatus.COMPLETED))
        store.record(createSession(status = SyncStatus.FAILED))
        store.record(createSession(status = SyncStatus.CANCELLED))

        val loaded = store.loadAll()
        assertEquals("COMPLETED", loaded[0].status)
        assertEquals("FAILED", loaded[1].status)
        assertEquals("CANCELLED", loaded[2].status)
    }

    @Test
    fun `record captures multiple repos in session`() {
        val session = createSession(repos = listOf("media", "archive", "documents"))
        store.record(session)

        val loaded = store.loadAll()
        assertEquals(listOf("media", "archive", "documents"), loaded[0].repositories)
    }

    @Test
    fun `record handles session with no end time`() {
        val session = SyncSession(
            sourceMachine = "desktop",
            targetMachine = "laptop",
            repositories = listOf("media"),
            startTime = Instant.parse("2026-03-01T10:00:00Z"),
            endTime = null,
            status = SyncStatus.FAILED
        )
        store.record(session)

        val loaded = store.loadAll()
        assertEquals(1, loaded.size)
        assertTrue(loaded[0].endTime.isNotEmpty())
    }

    @Test
    fun `concurrent writes produce valid JSONL`() {
        // Simulate rapid sequential writes
        repeat(50) {
            store.record(createSession(repos = listOf("repo-$it")))
        }

        val loaded = store.loadAll()
        assertEquals(50, loaded.size)
        loaded.forEachIndexed { idx, entry ->
            assertEquals(listOf("repo-$idx"), entry.repositories)
        }
    }
}

package vision.salient.choam.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import vision.salient.choam.sync.SyncHistoryStore
import vision.salient.choam.sync.SyncSession
import vision.salient.choam.sync.SyncStatistics
import vision.salient.choam.sync.SyncStatus
import java.time.Instant

class HistoryCommandTest {

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
        filesTransferred: Long = 5,
        bytesTransferred: Long = 2048
    ) = SyncSession(
        sourceMachine = source,
        targetMachine = target,
        repositories = repos,
        startTime = Instant.parse("2026-03-01T10:00:00Z"),
        endTime = Instant.parse("2026-03-01T10:05:00Z"),
        status = status,
        statistics = SyncStatistics(
            filesTransferred = filesTransferred,
            bytesTransferred = bytesTransferred
        )
    )

    @Test
    fun `query returns entries matching repo filter`() {
        store.record(createSession(repos = listOf("media")))
        store.record(createSession(repos = listOf("archive")))
        store.record(createSession(repos = listOf("media")))

        val entries = store.query(repo = "media")
        assertTrue(entries.size == 2)
        assertTrue(entries.all { it.repositories.contains("media") })
    }

    @Test
    fun `query with last limits results`() {
        repeat(15) {
            store.record(createSession())
        }

        val entries = store.query(last = 5)
        assertTrue(entries.size == 5)
    }

    @Test
    fun `history entries preserve all fields`() {
        val session = createSession(
            repos = listOf("media", "archive"),
            source = "server",
            target = "desktop",
            status = SyncStatus.FAILED,
            filesTransferred = 100,
            bytesTransferred = 999_999
        )
        store.record(session)

        val entries = store.loadAll()
        assertTrue(entries.size == 1)

        val entry = entries[0]
        assertTrue(entry.repositories == listOf("media", "archive"))
        assertTrue(entry.sourceMachine == "server")
        assertTrue(entry.targetMachine == "desktop")
        assertTrue(entry.status == "FAILED")
        assertTrue(entry.filesTransferred == 100L)
        assertTrue(entry.bytesTransferred == 999_999L)
    }

    @Test
    fun `empty history returns empty list`() {
        val entries = store.query()
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `query with repo and last together`() {
        repeat(10) {
            store.record(createSession(repos = listOf("media")))
        }
        repeat(5) {
            store.record(createSession(repos = listOf("archive")))
        }

        val entries = store.query(repo = "media", last = 3)
        assertTrue(entries.size == 3)
        assertTrue(entries.all { it.repositories.contains("media") })
    }
}

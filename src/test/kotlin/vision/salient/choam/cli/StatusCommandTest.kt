package vision.salient.choam.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import vision.salient.choam.sync.SyncHistoryStore
import vision.salient.choam.sync.SyncSession
import vision.salient.choam.sync.SyncStatistics
import vision.salient.choam.sync.SyncStatus
import java.time.Instant

class StatusCommandTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `SyncHistoryStore lastSyncForRepo returns correct entry`() {
        val historyPath = tempDir.resolve("history.jsonl")
        val store = SyncHistoryStore(historyPath)

        store.record(SyncSession(
            sourceMachine = "desktop",
            targetMachine = "laptop",
            repositories = listOf("media"),
            startTime = Instant.parse("2026-01-01T10:00:00Z"),
            endTime = Instant.parse("2026-01-01T10:05:00Z"),
            status = SyncStatus.COMPLETED,
            statistics = SyncStatistics(filesTransferred = 5, bytesTransferred = 1000)
        ))
        store.record(SyncSession(
            sourceMachine = "server",
            targetMachine = "desktop",
            repositories = listOf("archive"),
            startTime = Instant.parse("2026-02-01T10:00:00Z"),
            endTime = Instant.parse("2026-02-01T10:10:00Z"),
            status = SyncStatus.COMPLETED,
            statistics = SyncStatistics(filesTransferred = 50, bytesTransferred = 50000)
        ))
        store.record(SyncSession(
            sourceMachine = "desktop",
            targetMachine = "server",
            repositories = listOf("media"),
            startTime = Instant.parse("2026-03-01T10:00:00Z"),
            endTime = Instant.parse("2026-03-01T10:15:00Z"),
            status = SyncStatus.COMPLETED,
            statistics = SyncStatistics(filesTransferred = 100, bytesTransferred = 100000)
        ))

        val mediaLast = store.lastSyncForRepo("media")
        assertNotNull(mediaLast)
        assertEquals("server", mediaLast.targetMachine)
        assertEquals(100L, mediaLast.filesTransferred)

        val archiveLast = store.lastSyncForRepo("archive")
        assertNotNull(archiveLast)
        assertEquals("server", archiveLast.sourceMachine)

        val unknownLast = store.lastSyncForRepo("nonexistent")
        assertNull(unknownLast)
    }

    @Test
    fun `SyncHistoryStore lastSyncFor with source and target filters correctly`() {
        val historyPath = tempDir.resolve("history.jsonl")
        val store = SyncHistoryStore(historyPath)

        store.record(SyncSession(
            sourceMachine = "desktop",
            targetMachine = "laptop",
            repositories = listOf("media"),
            startTime = Instant.parse("2026-01-01T10:00:00Z"),
            endTime = Instant.parse("2026-01-01T10:05:00Z"),
            status = SyncStatus.COMPLETED,
            statistics = SyncStatistics(filesTransferred = 5, bytesTransferred = 1000)
        ))
        store.record(SyncSession(
            sourceMachine = "desktop",
            targetMachine = "server",
            repositories = listOf("media"),
            startTime = Instant.parse("2026-02-01T10:00:00Z"),
            endTime = Instant.parse("2026-02-01T10:05:00Z"),
            status = SyncStatus.COMPLETED,
            statistics = SyncStatistics(filesTransferred = 20, bytesTransferred = 5000)
        ))

        val laptopSync = store.lastSyncFor("media", "desktop", "laptop")
        assertNotNull(laptopSync)
        assertEquals(5L, laptopSync.filesTransferred)

        val serverSync = store.lastSyncFor("media", "desktop", "server")
        assertNotNull(serverSync)
        assertEquals(20L, serverSync.filesTransferred)

        val noneSync = store.lastSyncFor("media", "laptop", "server")
        assertNull(noneSync)
    }

    @Test
    fun `calculateDirSize returns zero for empty directory`() {
        val emptyDir = tempDir.resolve("empty")
        Files.createDirectory(emptyDir)

        val size = emptyDir.toFile().walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
        assertEquals(0L, size)
    }

    @Test
    fun `calculateDirSize sums file sizes correctly`() {
        val dir = tempDir.resolve("files")
        Files.createDirectory(dir)
        Files.writeString(dir.resolve("a.txt"), "hello") // 5 bytes
        Files.writeString(dir.resolve("b.txt"), "world!") // 6 bytes

        val size = dir.toFile().walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
        assertEquals(11L, size)
    }

    @Test
    fun `calculateDirSize includes nested files`() {
        val dir = tempDir.resolve("nested")
        Files.createDirectories(dir.resolve("sub/deep"))
        Files.writeString(dir.resolve("top.txt"), "abc") // 3 bytes
        Files.writeString(dir.resolve("sub/mid.txt"), "defg") // 4 bytes
        Files.writeString(dir.resolve("sub/deep/bottom.txt"), "hijkl") // 5 bytes

        val size = dir.toFile().walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
        assertEquals(12L, size)
    }
}

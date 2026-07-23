package vision.salient.choam.sync

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SyncStateTest {
    @Test
    fun `SyncSession creates with default values`() {
        val session = SyncSession(
            sourceMachine = "source",
            targetMachine = "target",
            repositories = listOf("repo1"),
            startTime = Instant.now(),
            status = SyncStatus.PREPARING
        )

        assertNotNull(session.id)
        assertEquals("source", session.sourceMachine)
        assertEquals("target", session.targetMachine)
        assertEquals(1, session.repositories.size)
        assertNull(session.endTime)
        assertEquals(SyncStatus.PREPARING, session.status)
        assertEquals(0, session.statistics.filesTransferred)
    }

    @Test
    fun `SyncSession generates unique IDs`() {
        val session1 = SyncSession(
            sourceMachine = "source",
            targetMachine = "target",
            repositories = listOf("repo1"),
            startTime = Instant.now(),
            status = SyncStatus.PREPARING
        )

        val session2 = SyncSession(
            sourceMachine = "source",
            targetMachine = "target",
            repositories = listOf("repo1"),
            startTime = Instant.now(),
            status = SyncStatus.PREPARING
        )

        assertNotEquals(session1.id, session2.id)
    }

    @Test
    fun `SyncSession tracks multiple repositories`() {
        val repos = listOf("repo1", "repo2", "repo3")
        val session = SyncSession(
            sourceMachine = "source",
            targetMachine = "target",
            repositories = repos,
            startTime = Instant.now(),
            status = SyncStatus.PREPARING
        )

        assertEquals(3, session.repositories.size)
        assertEquals(repos, session.repositories)
    }

    @Test
    fun `SyncSession can be completed with endTime`() {
        val start = Instant.now()
        val end = start.plusSeconds(60)

        val session = SyncSession(
            sourceMachine = "source",
            targetMachine = "target",
            repositories = listOf("repo1"),
            startTime = start,
            endTime = end,
            status = SyncStatus.COMPLETED
        )

        assertEquals(start, session.startTime)
        assertEquals(end, session.endTime)
        assertEquals(SyncStatus.COMPLETED, session.status)
    }

    @Test
    fun `SyncStatistics tracks all metrics`() {
        val stats = SyncStatistics(
            filesScanned = 100,
            filesTransferred = 50,
            bytesTransferred = 1024000,
            filesSkipped = 30,
            conflicts = 5,
            errors = 2
        )

        assertEquals(100, stats.filesScanned)
        assertEquals(50, stats.filesTransferred)
        assertEquals(1024000, stats.bytesTransferred)
        assertEquals(30, stats.filesSkipped)
        assertEquals(5, stats.conflicts)
        assertEquals(2, stats.errors)
    }

    @Test
    fun `SyncStatistics defaults to zero`() {
        val stats = SyncStatistics()

        assertEquals(0, stats.filesScanned)
        assertEquals(0, stats.filesTransferred)
        assertEquals(0, stats.bytesTransferred)
        assertEquals(0, stats.filesSkipped)
        assertEquals(0, stats.conflicts)
        assertEquals(0, stats.errors)
    }

    @Test
    fun `FileManifest stores file metadata`() {
        val now = Instant.now()
        val manifest = FileManifest(
            path = "/path/to/file.txt",
            size = 1024,
            modifiedTime = now,
            checksum = "abc123",
            exists = true
        )

        assertEquals("/path/to/file.txt", manifest.path)
        assertEquals(1024, manifest.size)
        assertEquals(now, manifest.modifiedTime)
        assertEquals("abc123", manifest.checksum)
        assertEquals(true, manifest.exists)
    }

    @Test
    fun `FileManifest defaults to null checksum and exists true`() {
        val manifest = FileManifest(
            path = "/path/to/file.txt",
            size = 1024,
            modifiedTime = Instant.now()
        )

        assertNull(manifest.checksum)
        assertEquals(true, manifest.exists)
    }

    @Test
    fun `FileManifest can represent deleted file`() {
        val manifest = FileManifest(
            path = "/path/to/deleted.txt",
            size = 0,
            modifiedTime = Instant.now(),
            exists = false
        )

        assertEquals(false, manifest.exists)
    }

    @Test
    fun `FileManifest handles different path formats`() {
        val unixPath = FileManifest(
            path = "/home/user/file.txt",
            size = 100,
            modifiedTime = Instant.now()
        )

        val relativePath = FileManifest(
            path = "relative/path/file.txt",
            size = 100,
            modifiedTime = Instant.now()
        )

        assertEquals("/home/user/file.txt", unixPath.path)
        assertEquals("relative/path/file.txt", relativePath.path)
    }

    @Test
    fun `SyncStatus enum has all expected states`() {
        val states = SyncStatus.values()

        assertEquals(8, states.size)
        assert(states.contains(SyncStatus.PREPARING))
        assert(states.contains(SyncStatus.CATALOGING))
        assert(states.contains(SyncStatus.COMPARING))
        assert(states.contains(SyncStatus.TRANSFERRING))
        assert(states.contains(SyncStatus.VERIFYING))
        assert(states.contains(SyncStatus.COMPLETED))
        assert(states.contains(SyncStatus.FAILED))
        assert(states.contains(SyncStatus.CANCELLED))
    }

    @Test
    fun `SyncSession with statistics`() {
        val stats = SyncStatistics(
            filesScanned = 200,
            filesTransferred = 150,
            bytesTransferred = 5000000,
            filesSkipped = 25,
            conflicts = 10,
            errors = 5
        )

        val session = SyncSession(
            sourceMachine = "desktop",
            targetMachine = "laptop",
            repositories = listOf("repo1", "repo2"),
            startTime = Instant.now(),
            status = SyncStatus.COMPLETED,
            statistics = stats
        )

        assertEquals(200, session.statistics.filesScanned)
        assertEquals(150, session.statistics.filesTransferred)
        assertEquals(5000000, session.statistics.bytesTransferred)
        assertEquals(25, session.statistics.filesSkipped)
        assertEquals(10, session.statistics.conflicts)
        assertEquals(5, session.statistics.errors)
    }

    @Test
    fun `FileManifest handles large file sizes`() {
        val largeSize = 10L * 1024 * 1024 * 1024 // 10 GB
        val manifest = FileManifest(
            path = "/large/file.bin",
            size = largeSize,
            modifiedTime = Instant.now()
        )

        assertEquals(largeSize, manifest.size)
    }

    @Test
    fun `FileManifest with checksum`() {
        val manifest = FileManifest(
            path = "/file.txt",
            size = 1024,
            modifiedTime = Instant.now(),
            checksum = "sha256:abcdef1234567890"
        )

        assertEquals("sha256:abcdef1234567890", manifest.checksum)
    }
}

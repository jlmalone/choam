package vision.salient.choam.cli

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import vision.salient.choam.config.ChoamConfig
import vision.salient.choam.config.ConflictStrategy
import vision.salient.choam.config.MachineProfile
import vision.salient.choam.config.MachineType
import vision.salient.choam.config.NetworkMode
import vision.salient.choam.config.RepositoryConfig
import vision.salient.choam.config.RepositoryType
import vision.salient.choam.config.SyncRules
import vision.salient.choam.network.NetworkRoute
import vision.salient.choam.network.ProgressMonitor
import vision.salient.choam.network.TransferManager
import vision.salient.choam.sync.ConflictResolver
import vision.salient.choam.sync.SyncEngine
import vision.salient.choam.sync.SyncHistoryStore
import vision.salient.choam.sync.SyncStatus

class PushPullIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var sourceDir: Path
    private lateinit var targetDir: Path
    private lateinit var historyPath: Path

    @BeforeEach
    fun setup() {
        sourceDir = tempDir.resolve("source/media")
        targetDir = tempDir.resolve("target/media")
        historyPath = tempDir.resolve("history.jsonl")
        Files.createDirectories(sourceDir)
        Files.createDirectories(targetDir)
    }

    private fun testMachine(name: String, repoPath: String) = MachineProfile(
        name = name,
        hostname = "localhost",
        type = MachineType.DESKTOP,
        repositories = mapOf("media" to repoPath)
    )

    private fun testConfig(source: MachineProfile, target: MachineProfile) = ChoamConfig(
        machines = mapOf(source.name to source, target.name to target),
        repositories = mapOf(
            "media" to RepositoryConfig(name = "media", type = RepositoryType.MEDIA)
        )
    )

    @Test
    fun `push transfers files from local to remote`() {
        // Create source files
        Files.writeString(sourceDir.resolve("movie.mkv"), "fake-video-content")
        Files.writeString(sourceDir.resolve("subtitle.srt"), "subtitle-content")

        val source = testMachine("local", sourceDir.toString())
        val target = testMachine("remote", targetDir.toString())
        val config = testConfig(source, target)

        val engine = SyncEngine(config, TransferManager(config), ConflictResolver())
        val route = NetworkRoute(mode = NetworkMode.LAN, sourceAddress = "localhost", targetAddress = "localhost")
        val rules = SyncRules()

        runBlocking {
            val session = engine.sync(
                source = source,
                target = target,
                repositories = listOf("media"),
                rules = rules,
                route = route,
                dryRun = false
            )

            assertEquals(SyncStatus.COMPLETED, session.status)
            assertEquals(2, session.statistics.filesTransferred)

            // Verify files arrived
            assertTrue(Files.exists(targetDir.resolve("movie.mkv")))
            assertTrue(Files.exists(targetDir.resolve("subtitle.srt")))
            assertEquals("fake-video-content", Files.readString(targetDir.resolve("movie.mkv")))
        }
    }

    @Test
    fun `pull transfers files from remote to local`() {
        // Create remote (source) files
        Files.writeString(targetDir.resolve("remote-file.txt"), "from-remote")

        // Pull = remote is source, local is target
        val local = testMachine("local", sourceDir.toString())
        val remote = testMachine("remote", targetDir.toString())
        val config = testConfig(local, remote)

        val engine = SyncEngine(config, TransferManager(config), ConflictResolver())
        val route = NetworkRoute(mode = NetworkMode.LAN, sourceAddress = "localhost", targetAddress = "localhost")

        runBlocking {
            val session = engine.sync(
                source = remote, // pull: remote is source
                target = local,  // local is target
                repositories = listOf("media"),
                rules = SyncRules(),
                route = route
            )

            assertEquals(SyncStatus.COMPLETED, session.status)
            assertEquals(1, session.statistics.filesTransferred)
            assertTrue(Files.exists(sourceDir.resolve("remote-file.txt")))
            assertEquals("from-remote", Files.readString(sourceDir.resolve("remote-file.txt")))
        }
    }

    @Test
    fun `push dry-run does not actually transfer files`() {
        Files.writeString(sourceDir.resolve("big-file.dat"), "large-content")

        val source = testMachine("local", sourceDir.toString())
        val target = testMachine("remote", targetDir.toString())
        val config = testConfig(source, target)
        val engine = SyncEngine(config, TransferManager(config), ConflictResolver())
        val route = NetworkRoute(mode = NetworkMode.LAN, sourceAddress = "localhost", targetAddress = "localhost")

        runBlocking {
            val session = engine.sync(
                source = source,
                target = target,
                repositories = listOf("media"),
                rules = SyncRules(),
                route = route,
                dryRun = true
            )

            // Dry run still counts what would transfer
            assertEquals(1, session.statistics.filesTransferred)
            // But file should NOT exist on target
            assertTrue(!Files.exists(targetDir.resolve("big-file.dat")))
        }
    }

    @Test
    fun `push records history after sync`() {
        Files.writeString(sourceDir.resolve("file.txt"), "content")

        val source = testMachine("local", sourceDir.toString())
        val target = testMachine("remote", targetDir.toString())
        val config = testConfig(source, target)
        val engine = SyncEngine(config, TransferManager(config), ConflictResolver())
        val route = NetworkRoute(mode = NetworkMode.LAN, sourceAddress = "localhost", targetAddress = "localhost")
        val historyStore = SyncHistoryStore(historyPath)

        runBlocking {
            val session = engine.sync(
                source = source,
                target = target,
                repositories = listOf("media"),
                rules = SyncRules(),
                route = route
            )

            historyStore.record(session)
        }

        val entries = historyStore.loadAll()
        assertEquals(1, entries.size)
        assertEquals("local", entries[0].sourceMachine)
        assertEquals("remote", entries[0].targetMachine)
        assertEquals("COMPLETED", entries[0].status)
    }

    @Test
    fun `push with progress callback invoked`() {
        Files.writeString(sourceDir.resolve("file.txt"), "content")

        val source = testMachine("local", sourceDir.toString())
        val target = testMachine("remote", targetDir.toString())
        val config = testConfig(source, target)
        val engine = SyncEngine(config, TransferManager(config), ConflictResolver())
        val route = NetworkRoute(mode = NetworkMode.LAN, sourceAddress = "localhost", targetAddress = "localhost")

        var callbackInvoked = false
        runBlocking {
            engine.sync(
                source = source,
                target = target,
                repositories = listOf("media"),
                rules = SyncRules(),
                route = route
            ) { _, _ ->
                callbackInvoked = true
            }
        }

        assertTrue(callbackInvoked)
    }

    @Test
    fun `push handles empty source directory`() {
        val source = testMachine("local", sourceDir.toString())
        val target = testMachine("remote", targetDir.toString())
        val config = testConfig(source, target)
        val engine = SyncEngine(config, TransferManager(config), ConflictResolver())
        val route = NetworkRoute(mode = NetworkMode.LAN, sourceAddress = "localhost", targetAddress = "localhost")

        runBlocking {
            val session = engine.sync(
                source = source,
                target = target,
                repositories = listOf("media"),
                rules = SyncRules(),
                route = route
            )

            assertEquals(SyncStatus.COMPLETED, session.status)
            assertEquals(0, session.statistics.filesTransferred)
        }
    }

    @Test
    fun `push transfers nested directory structure`() {
        Files.createDirectories(sourceDir.resolve("sub/deep"))
        Files.writeString(sourceDir.resolve("top.txt"), "top")
        Files.writeString(sourceDir.resolve("sub/mid.txt"), "mid")
        Files.writeString(sourceDir.resolve("sub/deep/bottom.txt"), "bottom")

        val source = testMachine("local", sourceDir.toString())
        val target = testMachine("remote", targetDir.toString())
        val config = testConfig(source, target)
        val engine = SyncEngine(config, TransferManager(config), ConflictResolver())
        val route = NetworkRoute(mode = NetworkMode.LAN, sourceAddress = "localhost", targetAddress = "localhost")

        runBlocking {
            val session = engine.sync(
                source = source,
                target = target,
                repositories = listOf("media"),
                rules = SyncRules(),
                route = route
            )

            assertEquals(SyncStatus.COMPLETED, session.status)
            assertEquals(3, session.statistics.filesTransferred)
            assertTrue(Files.exists(targetDir.resolve("top.txt")))
            assertTrue(Files.exists(targetDir.resolve("sub/mid.txt")))
            assertTrue(Files.exists(targetDir.resolve("sub/deep/bottom.txt")))
        }
    }

    @Test
    fun `push only copies new and modified files`() {
        // Both sides have file1 with same content
        Files.writeString(sourceDir.resolve("file1.txt"), "same")
        Files.writeString(targetDir.resolve("file1.txt"), "same")
        // Source has new file
        Files.writeString(sourceDir.resolve("file2.txt"), "new")

        val source = testMachine("local", sourceDir.toString())
        val target = testMachine("remote", targetDir.toString())
        val config = testConfig(source, target)
        val engine = SyncEngine(config, TransferManager(config), ConflictResolver())
        val route = NetworkRoute(mode = NetworkMode.LAN, sourceAddress = "localhost", targetAddress = "localhost")

        runBlocking {
            val session = engine.sync(
                source = source,
                target = target,
                repositories = listOf("media"),
                rules = SyncRules(),
                route = route
            )

            // file1 might be detected as modified if timestamps differ
            // file2 should definitely be transferred
            assertTrue(session.statistics.filesTransferred >= 1)
            assertTrue(Files.exists(targetDir.resolve("file2.txt")))
            assertEquals("new", Files.readString(targetDir.resolve("file2.txt")))
        }
    }

    @Test
    fun `sync records all repo names in history`() {
        val source = testMachine("local", sourceDir.toString())
            .copy(repositories = mapOf("media" to sourceDir.toString(), "archive" to sourceDir.toString()))
        val target = testMachine("remote", targetDir.toString())
            .copy(repositories = mapOf("media" to targetDir.toString(), "archive" to targetDir.toString()))
        val config = ChoamConfig(
            machines = mapOf(source.name to source, target.name to target),
            repositories = mapOf(
                "media" to RepositoryConfig(name = "media", type = RepositoryType.MEDIA),
                "archive" to RepositoryConfig(name = "archive", type = RepositoryType.ARCHIVE)
            )
        )

        val engine = SyncEngine(config, TransferManager(config), ConflictResolver())
        val route = NetworkRoute(mode = NetworkMode.LAN, sourceAddress = "localhost", targetAddress = "localhost")
        val store = SyncHistoryStore(historyPath)

        runBlocking {
            val session = engine.sync(
                source = source,
                target = target,
                repositories = listOf("media", "archive"),
                rules = SyncRules(),
                route = route
            )
            store.record(session)
        }

        val entries = store.loadAll()
        assertEquals(1, entries.size)
        assertTrue(entries[0].repositories.contains("media"))
        assertTrue(entries[0].repositories.contains("archive"))
    }
}

package vision.salient.choam.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChoamConfigLoaderTest {
    @Test
    fun `load throws exception when file does not exist`() {
        val nonExistentPath = Files.createTempDirectory("choam-test").resolve("nonexistent.json")

        val exception = assertFailsWith<IllegalStateException> {
            ChoamConfigLoader.load(nonExistentPath)
        }

        assertTrue(exception.message!!.contains("not found"))
    }

    @Test
    fun `load throws exception for invalid JSON`() {
        val tmpDir = Files.createTempDirectory("choam-invalid-json")
        val path: Path = tmpDir.resolve("config.json")

        Files.writeString(path, "{ invalid json content }")

        assertFailsWith<Exception> {
            ChoamConfigLoader.load(path)
        }
    }

    @Test
    fun `load handles empty JSON object`() {
        val tmpDir = Files.createTempDirectory("choam-empty-json")
        val path: Path = tmpDir.resolve("config.json")

        Files.writeString(path, "{}")

        val config = ChoamConfigLoader.load(path)

        assertNotNull(config)
        assertEquals(0, config.machines.size)
        assertEquals(0, config.repositories.size)
    }

    @Test
    fun `load ignores unknown keys in JSON`() {
        val tmpDir = Files.createTempDirectory("choam-unknown-keys")
        val path: Path = tmpDir.resolve("config.json")

        val jsonWithUnknownKeys = """
        {
            "version": "1.0.0",
            "machines": {},
            "repositories": {},
            "unknownField": "should be ignored",
            "anotherUnknownField": 12345
        }
        """.trimIndent()

        Files.writeString(path, jsonWithUnknownKeys)

        val config = ChoamConfigLoader.load(path)

        assertNotNull(config)
        assertEquals("1.0.0", config.version)
    }

    @Test
    fun `save creates parent directories if they do not exist`() {
        val tmpDir = Files.createTempDirectory("choam-save-test")
        val nestedPath = tmpDir.resolve("nested/deep/config.json")

        val config = ChoamConfig(
            machines = mapOf(
                "test" to MachineProfile(
                    name = "test",
                    hostname = "test.local",
                    type = MachineType.DESKTOP,
                    repositories = emptyMap()
                )
            )
        )

        ChoamConfigLoader.save(config, nestedPath)

        assertTrue(Files.exists(nestedPath))
        assertTrue(Files.exists(nestedPath.parent))
    }

    @Test
    fun `load and save preserve all data types`() {
        val tmpDir = Files.createTempDirectory("choam-data-types")
        val path: Path = tmpDir.resolve("config.json")

        val original = ChoamConfig(
            version = "2.0.0",
            machines = mapOf(
                "server" to MachineProfile(
                    name = "server",
                    hostname = "192.168.1.100",
                    type = MachineType.SERVER,
                    repositories = mapOf("repo1" to "/var/data/repo1"),
                    sshUser = "admin",
                    sshPort = 2222,
                    tailscaleIp = "100.64.1.50",
                    networkPreference = NetworkMode.TAILSCALE
                )
            ),
            repositories = mapOf(
                "repo1" to RepositoryConfig(
                    name = "repo1",
                    localPath = "/var/data/repo1",
                    type = RepositoryType.MEDIA,
                    databases = listOf("movies.db", "shows.db"),
                    excludePatterns = listOf("*.tmp", "*.log")
                )
            ),
            lockSearchPaths = listOf("~/data", "/Volumes/EXTERNAL"),
            defaultSyncRules = SyncRules(
                bidirectional = true,
                deleteRemoved = true,
                conflictResolution = ConflictStrategy.LARGER_WINS,
                bandwidthLimit = 5000,
                excludePatterns = listOf("*.cache", ".git")
            )
        )

        ChoamConfigLoader.save(original, path)
        val loaded = ChoamConfigLoader.load(path)

        assertEquals(original.version, loaded.version)
        assertEquals(1, loaded.machines.size)
        assertEquals(1, loaded.repositories.size)
        assertEquals(listOf("~/data", "/Volumes/EXTERNAL"), loaded.lockSearchPaths)

        val machine = loaded.machines["server"]!!
        assertEquals("server", machine.name)
        assertEquals("192.168.1.100", machine.hostname)
        assertEquals(MachineType.SERVER, machine.type)
        assertEquals("admin", machine.sshUser)
        assertEquals(2222, machine.sshPort)
        assertEquals("100.64.1.50", machine.tailscaleIp)
        assertEquals(NetworkMode.TAILSCALE, machine.networkPreference)

        val repo = loaded.repositories["repo1"]!!
        assertEquals("repo1", repo.name)
        assertEquals("/var/data/repo1", repo.localPath)
        assertEquals(RepositoryType.MEDIA, repo.type)
        assertEquals(2, repo.databases.size)
        assertEquals(2, repo.excludePatterns.size)

        assertEquals(true, loaded.defaultSyncRules.bidirectional)
        assertEquals(true, loaded.defaultSyncRules.deleteRemoved)
        assertEquals(ConflictStrategy.LARGER_WINS, loaded.defaultSyncRules.conflictResolution)
        assertEquals(5000, loaded.defaultSyncRules.bandwidthLimit)
        assertEquals(2, loaded.defaultSyncRules.excludePatterns.size)
    }

    @Test
    fun `save produces pretty printed JSON`() {
        val tmpDir = Files.createTempDirectory("choam-pretty-print")
        val path: Path = tmpDir.resolve("config.json")

        val config = ChoamConfig(
            machines = mapOf(
                "test" to MachineProfile(
                    name = "test",
                    hostname = "test.local",
                    type = MachineType.DESKTOP,
                    repositories = emptyMap()
                )
            )
        )

        ChoamConfigLoader.save(config, path)

        val content = Files.readString(path)

        assertTrue(content.contains("\n"))
        assertTrue(content.contains("  "))
    }

    @Test
    fun `load handles all repository types`() {
        val tmpDir = Files.createTempDirectory("choam-repo-types")
        val path: Path = tmpDir.resolve("config.json")

        val original = ChoamConfig(
            repositories = mapOf(
                "media" to RepositoryConfig(
                    name = "media",
                    localPath = "/path/media",
                    type = RepositoryType.MEDIA
                ),
                "archive" to RepositoryConfig(
                    name = "archive",
                    localPath = "/path/archive",
                    type = RepositoryType.ARCHIVE
                ),
                "generic" to RepositoryConfig(
                    name = "generic",
                    localPath = "/path/generic",
                    type = RepositoryType.GENERIC
                )
            )
        )

        ChoamConfigLoader.save(original, path)
        val loaded = ChoamConfigLoader.load(path)

        assertEquals(3, loaded.repositories.size)
        assertEquals(RepositoryType.MEDIA, loaded.repositories["media"]!!.type)
        assertEquals(RepositoryType.ARCHIVE, loaded.repositories["archive"]!!.type)
        assertEquals(RepositoryType.GENERIC, loaded.repositories["generic"]!!.type)
    }

    @Test
    fun `load handles all machine types`() {
        val tmpDir = Files.createTempDirectory("choam-machine-types")
        val path: Path = tmpDir.resolve("config.json")

        val original = ChoamConfig(
            machines = mapOf(
                "desktop" to MachineProfile(
                    name = "desktop",
                    hostname = "desktop.local",
                    type = MachineType.DESKTOP,
                    repositories = emptyMap()
                ),
                "laptop" to MachineProfile(
                    name = "laptop",
                    hostname = "laptop.local",
                    type = MachineType.LAPTOP,
                    repositories = emptyMap()
                ),
                "server" to MachineProfile(
                    name = "server",
                    hostname = "server.local",
                    type = MachineType.SERVER,
                    repositories = emptyMap()
                )
            )
        )

        ChoamConfigLoader.save(original, path)
        val loaded = ChoamConfigLoader.load(path)

        assertEquals(3, loaded.machines.size)
        assertEquals(MachineType.DESKTOP, loaded.machines["desktop"]!!.type)
        assertEquals(MachineType.LAPTOP, loaded.machines["laptop"]!!.type)
        assertEquals(MachineType.SERVER, loaded.machines["server"]!!.type)
    }

    @Test
    fun `defaultPath returns path in user home directory`() {
        val path = ChoamConfigLoader.defaultPath()
        val userHome = System.getProperty("user.home")

        assertTrue(path.toString().startsWith(userHome))
        assertTrue(path.toString().contains(".choam"))
        assertTrue(path.toString().endsWith("config.json"))
    }
}

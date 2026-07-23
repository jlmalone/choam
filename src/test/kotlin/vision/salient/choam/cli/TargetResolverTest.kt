package vision.salient.choam.cli

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import vision.salient.choam.config.ChoamConfig
import vision.salient.choam.config.MachineProfile
import vision.salient.choam.config.MachineType
import vision.salient.choam.config.NetworkMode
import vision.salient.choam.config.RepositoryConfig
import vision.salient.choam.config.RepositoryType

class TargetResolverTest {

    private fun testMachine(
        name: String,
        hostname: String,
        repos: Map<String, String>,
        tailscaleIp: String? = null
    ) = MachineProfile(
        name = name,
        hostname = hostname,
        type = MachineType.DESKTOP,
        repositories = repos,
        tailscaleIp = tailscaleIp
    )

    private fun configWith(
        machines: Map<String, MachineProfile>,
        repos: Map<String, RepositoryConfig> = emptyMap()
    ) = ChoamConfig(
        machines = machines,
        repositories = repos
    )

    // Use the actual hostname of this machine for local machine detection
    private val localHostname: String by lazy {
        try {
            java.net.InetAddress.getLocalHost().hostName
        } catch (e: Exception) {
            "localhost"
        }
    }

    private fun standardConfig(): ChoamConfig {
        val local = testMachine(
            "local",
            localHostname,
            mapOf("media" to "/data/media", "archive" to "/data/archive")
        )
        val remote = testMachine(
            "remote",
            "remote-box.local",
            mapOf("media" to "/data/media", "archive" to "/data/archive"),
            tailscaleIp = "100.64.0.2"
        )
        return configWith(
            machines = mapOf("local" to local, "remote" to remote),
            repos = mapOf(
                "media" to RepositoryConfig(name = "media", type = RepositoryType.MEDIA),
                "archive" to RepositoryConfig(name = "archive", type = RepositoryType.ARCHIVE)
            )
        )
    }

    @Test
    fun `resolveRepositories returns exact repo name match`() {
        val config = standardConfig()
        val resolver = TargetResolver(config)

        val repos = resolver.resolveRepositories("media")
        assertEquals(listOf("media"), repos)
    }

    @Test
    fun `resolveRepositories returns all repos for 'all'`() {
        val config = standardConfig()
        val resolver = TargetResolver(config)

        val repos = resolver.resolveRepositories("all")
        assertTrue(repos.contains("media"))
        assertTrue(repos.contains("archive"))
        assertEquals(2, repos.size)
    }

    @Test
    fun `resolveRepositories returns empty for unknown target`() {
        val config = standardConfig()
        val resolver = TargetResolver(config)

        val repos = resolver.resolveRepositories("nonexistent")
        assertTrue(repos.isEmpty())
    }

    @Test
    fun `resolveRepositories matches directory path prefix`() {
        val config = standardConfig()
        val resolver = TargetResolver(config)

        val repos = resolver.resolveRepositories("/data/media")
        assertEquals(listOf("media"), repos)
    }

    @Test
    fun `findLocalMachine matches by exact hostname`() {
        val config = standardConfig()
        val resolver = TargetResolver(config)

        val local = resolver.findLocalMachine()
        assertNotNull(local)
        assertEquals("local", local.name)
    }

    @Test
    fun `findRemoteMachines excludes local machine`() {
        val config = standardConfig()
        val resolver = TargetResolver(config)

        val local = resolver.findLocalMachine()!!
        val remotes = resolver.findRemoteMachines(listOf("media"), local)

        assertEquals(1, remotes.size)
        assertEquals("remote", remotes[0].name)
    }

    @Test
    fun `findRemoteMachines filters by repo availability`() {
        val local = testMachine("local", localHostname, mapOf("media" to "/data/media", "special" to "/data/special"))
        val remote1 = testMachine("remote1", "r1.local", mapOf("media" to "/data/media"))
        val remote2 = testMachine("remote2", "r2.local", mapOf("special" to "/data/special"))
        val config = configWith(
            machines = mapOf("local" to local, "remote1" to remote1, "remote2" to remote2),
            repos = mapOf(
                "media" to RepositoryConfig(name = "media", type = RepositoryType.MEDIA),
                "special" to RepositoryConfig(name = "special", type = RepositoryType.GENERIC)
            )
        )

        val resolver = TargetResolver(config)
        val remotes = resolver.findRemoteMachines(listOf("special"), local)
        assertEquals(1, remotes.size)
        assertEquals("remote2", remotes[0].name)
    }

    @Test
    fun `resolve with explicit machine name succeeds`() {
        val config = standardConfig()
        val resolver = TargetResolver(config)

        val (resolved, error) = resolver.resolve("media", "remote", Direction.PUSH)
        assertNull(error)
        assertNotNull(resolved)
        assertEquals(listOf("media"), resolved.repos)
        assertEquals("local", resolved.localMachine.name)
        assertEquals("remote", resolved.remoteMachine.name)
    }

    @Test
    fun `resolve auto-detects single remote`() {
        val config = standardConfig()
        val resolver = TargetResolver(config)

        val (resolved, error) = resolver.resolve("media", null, Direction.PUSH)
        assertNull(error)
        assertNotNull(resolved)
        assertEquals("remote", resolved.remoteMachine.name)
    }

    @Test
    fun `resolve errors on unknown target`() {
        val config = standardConfig()
        val resolver = TargetResolver(config)

        val (resolved, error) = resolver.resolve("nonexistent", null, Direction.PUSH)
        assertNull(resolved)
        assertNotNull(error)
        assertTrue(error.contains("Unknown target"))
    }

    @Test
    fun `resolve errors on unknown explicit machine`() {
        val config = standardConfig()
        val resolver = TargetResolver(config)

        val (resolved, error) = resolver.resolve("media", "ghost-machine", Direction.PUSH)
        assertNull(resolved)
        assertNotNull(error)
        assertTrue(error.contains("Unknown machine"))
    }

    @Test
    fun `resolve errors when multiple remotes and no explicit machine`() {
        val local = testMachine("local", localHostname, mapOf("media" to "/data/media"))
        val remote1 = testMachine("remote1", "r1.local", mapOf("media" to "/data/media"))
        val remote2 = testMachine("remote2", "r2.local", mapOf("media" to "/data/media"))
        val config = configWith(
            machines = mapOf("local" to local, "remote1" to remote1, "remote2" to remote2),
            repos = mapOf("media" to RepositoryConfig(name = "media", type = RepositoryType.MEDIA))
        )

        val resolver = TargetResolver(config)
        val (resolved, error) = resolver.resolve("media", null, Direction.PUSH)
        assertNull(resolved)
        assertNotNull(error)
        assertTrue(error.contains("Multiple machines"))
        assertTrue(error.contains("--to"))
    }

    @Test
    fun `resolve PULL uses --from in error message`() {
        val local = testMachine("local", localHostname, mapOf("media" to "/data/media"))
        val remote1 = testMachine("remote1", "r1.local", mapOf("media" to "/data/media"))
        val remote2 = testMachine("remote2", "r2.local", mapOf("media" to "/data/media"))
        val config = configWith(
            machines = mapOf("local" to local, "remote1" to remote1, "remote2" to remote2),
            repos = mapOf("media" to RepositoryConfig(name = "media", type = RepositoryType.MEDIA))
        )

        val resolver = TargetResolver(config)
        val (_, error) = resolver.resolve("media", null, Direction.PULL)
        assertNotNull(error)
        assertTrue(error.contains("--from"))
    }

    @Test
    fun `resolve errors when remote lacks repo`() {
        val local = testMachine("local", localHostname, mapOf("media" to "/data/media", "special" to "/data/special"))
        val remote = testMachine("remote", "r.local", mapOf("media" to "/data/media"))
        val config = configWith(
            machines = mapOf("local" to local, "remote" to remote),
            repos = mapOf(
                "media" to RepositoryConfig(name = "media", type = RepositoryType.MEDIA),
                "special" to RepositoryConfig(name = "special", type = RepositoryType.GENERIC)
            )
        )

        val resolver = TargetResolver(config)
        val (resolved, error) = resolver.resolve("special", "remote", Direction.PUSH)
        assertNull(resolved)
        assertNotNull(error)
        assertTrue(error.contains("doesn't have repositories"))
    }

    @Test
    fun `resolve all repos returns multiple repos`() {
        val config = standardConfig()
        val resolver = TargetResolver(config)

        val (resolved, error) = resolver.resolve("all", "remote", Direction.PUSH)
        assertNull(error)
        assertNotNull(resolved)
        assertEquals(2, resolved.repos.size)
        assertTrue(resolved.repos.contains("media"))
        assertTrue(resolved.repos.contains("archive"))
    }

    @Test
    fun `resolve errors when no remotes have the repo`() {
        val local = testMachine("local", localHostname, mapOf("special" to "/data/special"))
        val remote = testMachine("remote", "r.local", mapOf("media" to "/data/media"))
        val config = configWith(
            machines = mapOf("local" to local, "remote" to remote),
            repos = mapOf("special" to RepositoryConfig(name = "special", type = RepositoryType.GENERIC))
        )

        val resolver = TargetResolver(config)
        val (resolved, error) = resolver.resolve("special", null, Direction.PUSH)
        assertNull(resolved)
        assertNotNull(error)
        assertTrue(error.contains("No remote machines"))
    }

    @Test
    fun `findRemoteMachines returns all machines with any of the requested repos`() {
        val local = testMachine("local", localHostname, mapOf("media" to "/m", "archive" to "/a"))
        val remote1 = testMachine("remote1", "r1.local", mapOf("media" to "/m"))
        val remote2 = testMachine("remote2", "r2.local", mapOf("archive" to "/a"))
        val remote3 = testMachine("remote3", "r3.local", mapOf("other" to "/o"))
        val config = configWith(
            machines = mapOf("local" to local, "remote1" to remote1, "remote2" to remote2, "remote3" to remote3)
        )

        val resolver = TargetResolver(config)
        val remotes = resolver.findRemoteMachines(listOf("media", "archive"), local)
        assertEquals(2, remotes.size)
        assertTrue(remotes.any { it.name == "remote1" })
        assertTrue(remotes.any { it.name == "remote2" })
    }
}

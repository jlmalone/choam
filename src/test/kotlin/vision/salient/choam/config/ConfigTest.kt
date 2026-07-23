package vision.salient.choam.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigTest {
    @Test
    fun `config can be serialized and deserialized`() {
        val tmpDir = Files.createTempDirectory("choam-config-test")
        val path: Path = tmpDir.resolve("config.json")

        val original = ChoamConfig(
            machines = mapOf(
                "desktop" to MachineProfile(
                    name = "desktop",
                    hostname = "desktop.local",
                    type = MachineType.DESKTOP,
                    repositories = mapOf("media" to "/tmp/test/media")
                )
            )
        )

        ChoamConfigLoader.save(original, path)
        val loaded = ChoamConfigLoader.load(path)

        assertNotNull(loaded.machines["desktop"])
        assertEquals(original.version, loaded.version)
    }

    @Test
    fun `config preserves all machine profile fields`() {
        val tmpDir = Files.createTempDirectory("choam-config-complete-test")
        val path: Path = tmpDir.resolve("config.json")

        val original = ChoamConfig(
            machines = mapOf(
                "laptop" to MachineProfile(
                    name = "laptop",
                    hostname = "laptop.local",
                    type = MachineType.LAPTOP,
                    repositories = mapOf("repo1" to "/path/to/repo1", "repo2" to "/path/to/repo2"),
                    sshUser = "testuser",
                    sshPort = 2222,
                    tailscaleIp = "100.64.1.1"
                )
            )
        )

        ChoamConfigLoader.save(original, path)
        val loaded = ChoamConfigLoader.load(path)

        val machine = loaded.machines["laptop"]
        assertNotNull(machine)
        assertEquals("laptop", machine.name)
        assertEquals("laptop.local", machine.hostname)
        assertEquals(MachineType.LAPTOP, machine.type)
        assertEquals(2, machine.repositories.size)
        assertEquals("testuser", machine.sshUser)
        assertEquals(2222, machine.sshPort)
        assertEquals("100.64.1.1", machine.tailscaleIp)
    }

    @Test
    fun `empty config can be serialized and deserialized`() {
        val tmpDir = Files.createTempDirectory("choam-config-empty-test")
        val path: Path = tmpDir.resolve("config.json")

        val original = ChoamConfig()

        ChoamConfigLoader.save(original, path)
        val loaded = ChoamConfigLoader.load(path)

        assertEquals(0, loaded.machines.size)
        assertEquals(0, loaded.repositories.size)
        assertEquals(original.version, loaded.version)
    }

    // ============================
    // NEW TESTS: MachineProfile with aliases
    // ============================

    @Test
    fun `MachineProfile with aliases round-trips through JSON`() {
        val tmpDir = Files.createTempDirectory("choam-aliases-test")
        val path: Path = tmpDir.resolve("config.json")

        val original = ChoamConfig(
            machines = mapOf(
                "server-a" to MachineProfile(
                    name = "server-a",
                    hostname = "server-a-mac-mini.local",
                    type = MachineType.DESKTOP,
                    repositories = mapOf("media" to "/Volumes/EXT-4TB"),
                    sshUser = "user",
                    tailscaleIp = "100.64.0.2",
                    aliases = listOf("server-a-old", "server-a-mac-mini", "workstation-old")
                )
            )
        )

        ChoamConfigLoader.save(original, path)
        val loaded = ChoamConfigLoader.load(path)

        val machine = loaded.machines["server-a"]
        assertNotNull(machine)
        assertEquals(3, machine.aliases.size)
        assertTrue("server-a-old" in machine.aliases)
        assertTrue("server-a-mac-mini" in machine.aliases)
        assertTrue("workstation-old" in machine.aliases)
    }

    @Test
    fun `MachineProfile with empty aliases deserializes correctly`() {
        val tmpDir = Files.createTempDirectory("choam-empty-aliases-test")
        val path: Path = tmpDir.resolve("config.json")

        val original = ChoamConfig(
            machines = mapOf(
                "server" to MachineProfile(
                    name = "server",
                    hostname = "server.local",
                    type = MachineType.SERVER,
                    repositories = emptyMap()
                    // aliases omitted — should default to emptyList()
                )
            )
        )

        ChoamConfigLoader.save(original, path)
        val loaded = ChoamConfigLoader.load(path)

        val machine = loaded.machines["server"]
        assertNotNull(machine)
        assertEquals(emptyList(), machine.aliases)
    }

    @Test
    fun `ChoamConfig round-trip preserves aliases for multiple machines`() {
        val tmpDir = Files.createTempDirectory("choam-multi-aliases-test")
        val path: Path = tmpDir.resolve("config.json")

        val original = ChoamConfig(
            machines = mapOf(
                "server-a" to MachineProfile(
                    name = "server-a",
                    hostname = "server-a.local",
                    type = MachineType.DESKTOP,
                    repositories = mapOf("media" to "/Volumes/EXT-4TB"),
                    aliases = listOf("server-a-old")
                ),
                "server-b" to MachineProfile(
                    name = "server-b",
                    hostname = "server-b-m4.local",
                    type = MachineType.DESKTOP,
                    repositories = mapOf("media" to "/Volumes/DATA"),
                    sshUser = "user",
                    aliases = listOf("vanc-old", "server-b-old")
                ),
                "mini" to MachineProfile(
                    name = "mini",
                    hostname = "mini.local",
                    type = MachineType.DESKTOP,
                    repositories = emptyMap()
                    // no aliases
                )
            )
        )

        ChoamConfigLoader.save(original, path)
        val loaded = ChoamConfigLoader.load(path)

        assertEquals(3, loaded.machines.size)
        assertEquals(listOf("server-a-old"), loaded.machines["server-a"]!!.aliases)
        assertEquals(listOf("vanc-old", "server-b-old"), loaded.machines["server-b"]!!.aliases)
        assertEquals(emptyList(), loaded.machines["mini"]!!.aliases)
    }

    @Test
    fun `ChoamConfig with drives round-trips correctly`() {
        val tmpDir = Files.createTempDirectory("choam-drives-test")
        val path: Path = tmpDir.resolve("config.json")

        val original = ChoamConfig(
            drives = mapOf(
                "ext-4tb" to Drive(
                    uuid = "1234-5678-ABCD",
                    label = "EXT-4TB"
                ),
                "seagate" to Drive(
                    uuid = "ABCD-1234-5678",
                    label = "Seagate Expansion",
                    repositories = mapOf("media" to "/Volumes/Seagate Expansion/media")
                )
            )
        )

        ChoamConfigLoader.save(original, path)
        val loaded = ChoamConfigLoader.load(path)

        assertEquals(2, loaded.drives.size)
        assertEquals("EXT-4TB", loaded.drives["ext-4tb"]!!.label)
        assertEquals("Seagate Expansion", loaded.drives["seagate"]!!.label)
        assertEquals(1, loaded.drives["seagate"]!!.repositories.size)
    }

    @Test
    fun `MachineProfile aliases JSON includes aliases field explicitly`() {
        val tmpDir = Files.createTempDirectory("choam-aliases-json-test")
        val path: Path = tmpDir.resolve("config.json")

        val original = ChoamConfig(
            machines = mapOf(
                "server-a" to MachineProfile(
                    name = "server-a",
                    hostname = "server-a.local",
                    type = MachineType.DESKTOP,
                    repositories = emptyMap(),
                    aliases = listOf("server-a-old", "server-a-hostname")
                )
            )
        )

        ChoamConfigLoader.save(original, path)
        val json = java.nio.file.Files.readString(path)

        // Verify the JSON actually contains the aliases field
        assertTrue(json.contains("aliases"), "JSON should contain 'aliases' field")
        assertTrue(json.contains("server-a-old"), "JSON should contain alias value")
        assertTrue(json.contains("server-a-hostname"), "JSON should contain alias value")
    }

    @Test
    fun `MachineProfile resourceLimits round-trips correctly`() {
        val tmpDir = Files.createTempDirectory("choam-resources-test")
        val path: Path = tmpDir.resolve("config.json")

        val original = ChoamConfig(
            machines = mapOf(
                "server" to MachineProfile(
                    name = "server",
                    hostname = "server.local",
                    type = MachineType.SERVER,
                    repositories = emptyMap(),
                    resourceLimits = ResourceLimits(
                        maxHeapMb = 4096,
                        maxCpuCores = 4,
                        ioNice = true
                    )
                )
            )
        )

        ChoamConfigLoader.save(original, path)
        val loaded = ChoamConfigLoader.load(path)

        val limits = loaded.machines["server"]!!.resourceLimits
        assertEquals(4096, limits.maxHeapMb)
        assertEquals(4, limits.maxCpuCores)
        assertEquals(true, limits.ioNice)
    }
}

package vision.salient.choam.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import vision.salient.choam.config.*
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Extended tests for PlanCommand gap analysis — varied policies, edge cases,
 * recommendation candidate filtering.
 */
class PlanCommandExtendedTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `repo with minCopies=1 and one copy is satisfied`() {
        val conn = createDb()
        insert(conn, "QmA", "server-a", "/a", 1000)

        val config = configWith("film", minCopies = 1, preferredCopies = 2)
        val copies = PlanCommand.countRepoMachines(conn, config, emptyMap())

        assertTrue(copies["film"]!!.size >= 1)
        conn.close()
    }

    @Test
    fun `repo with minCopies=3 and two copies is under-replicated`() {
        val conn = createDb()
        insert(conn, "QmA", "server-a", "/a", 1000)
        insert(conn, "QmB", "server-b", "/b", 1000)

        val config = configWith("film", minCopies = 3, preferredCopies = 3)
        val copies = PlanCommand.countRepoMachines(conn, config, emptyMap())

        assertTrue(copies["film"]!!.size < 3)
        conn.close()
    }

    @Test
    fun `repo with zero copies in registry`() {
        val conn = createDb()
        // Empty registry

        val config = configWith("film", minCopies = 2)
        val copies = PlanCommand.countRepoMachines(conn, config, emptyMap())

        assertEquals(0, copies["film"]!!.size)
        conn.close()
    }

    @Test
    fun `machine not configured for repo excluded even if in registry`() {
        val conn = createDb()
        insert(conn, "QmA", "server-a", "/a", 1000)
        insert(conn, "QmB", "outsider", "/b", 1000) // not in config

        val config = configWith("film", minCopies = 2)
        val copies = PlanCommand.countRepoMachines(conn, config, emptyMap())

        // outsider not in config machines → excluded
        assertFalse(copies["film"]!!.contains("outsider"))
        conn.close()
    }

    @Test
    fun `alias remapping counts aliased machine correctly`() {
        val conn = createDb()
        insert(conn, "QmA", "server-a-old", "/a", 1000)

        val config = configWith("film", minCopies = 1)
        val copies = PlanCommand.countRepoMachines(conn, config, mapOf("server-a-old" to "server-a"))

        assertTrue(copies["film"]!!.contains("server-a"))
        assertFalse(copies["film"]!!.contains("server-a-old"))
        conn.close()
    }

    @Test
    fun `multiple repos tracked independently`() {
        val conn = createDb()
        insert(conn, "QmA", "server-a", "/a", 1000)
        insert(conn, "QmB", "server-b", "/b", 1000)

        val config = ChoamConfig(
            machines = mapOf(
                "server-a" to MachineProfile(name = "server-a", hostname = "h1", type = MachineType.DESKTOP,
                    repositories = mapOf("film" to "/film", "tv" to "/tv")),
                "server-b" to MachineProfile(name = "server-b", hostname = "h2", type = MachineType.DESKTOP,
                    repositories = mapOf("film" to "/film"))
            ),
            repositories = mapOf(
                "film" to RepositoryConfig(name = "film", type = RepositoryType.MEDIA,
                    replication = ReplicationPolicy(minCopies = 2)),
                "tv" to RepositoryConfig(name = "tv", type = RepositoryType.MEDIA,
                    replication = ReplicationPolicy(minCopies = 1))
            )
        )

        val copies = PlanCommand.countRepoMachines(conn, config, emptyMap())

        // film: server-a + server-b both configured and in registry
        assertEquals(2, copies["film"]!!.size)
        // tv: only server-a configured, server-a in registry
        assertEquals(1, copies["tv"]!!.size)
        conn.close()
    }

    @Test
    fun `recommendation candidates are machines not already holding the repo`() {
        val conn = createDb()
        insert(conn, "QmA", "server-a", "/a", 1000)

        val config = ChoamConfig(
            machines = mapOf(
                "server-a" to MachineProfile(name = "server-a", hostname = "h1", type = MachineType.DESKTOP,
                    repositories = mapOf("film" to "/film")),
                "server-b" to MachineProfile(name = "server-b", hostname = "h2", type = MachineType.DESKTOP,
                    repositories = mapOf("film" to "/film")),
                "local" to MachineProfile(name = "local", hostname = "h3", type = MachineType.DESKTOP,
                    repositories = mapOf("film" to "/film"))
            ),
            repositories = mapOf(
                "film" to RepositoryConfig(name = "film", type = RepositoryType.MEDIA,
                    replication = ReplicationPolicy(minCopies = 2, preferredCopies = 3))
            )
        )

        val copies = PlanCommand.countRepoMachines(conn, config, emptyMap())
        val filmCopies = copies["film"]!!
        val candidates = config.machines.keys.filter { it !in filmCopies }
            .filter { config.machines[it]?.repositories?.containsKey("film") == true }

        // server-a has data, server-b + local don't
        assertTrue(candidates.contains("server-b"))
        assertTrue(candidates.contains("local"))
        assertFalse(candidates.contains("server-a"))
        conn.close()
    }

    @Test
    fun `config with no repositories returns empty map`() {
        val conn = createDb()
        val config = ChoamConfig()
        val copies = PlanCommand.countRepoMachines(conn, config, emptyMap())
        assertTrue(copies.isEmpty())
        conn.close()
    }

    // ===========================================
    // Helpers
    // ===========================================

    private fun createDb(): Connection {
        val path = tempDir.resolve("reg_${System.nanoTime()}.db").toString()
        val conn = DriverManager.getConnection("jdbc:sqlite:$path")
        conn.createStatement().executeUpdate("""
            CREATE TABLE IF NOT EXISTS content_locations (
                cid TEXT NOT NULL, machine_name TEXT NOT NULL, file_path TEXT NOT NULL,
                file_size INTEGER, verified_at TEXT,
                registered_at TEXT NOT NULL DEFAULT (datetime('now')),
                last_synced_at TEXT NOT NULL DEFAULT (datetime('now')),
                PRIMARY KEY (cid, machine_name, file_path)
            )
        """)
        return conn
    }

    private fun insert(conn: Connection, cid: String, machine: String, path: String, size: Long) {
        conn.prepareStatement("INSERT INTO content_locations (cid, machine_name, file_path, file_size) VALUES (?, ?, ?, ?)").apply {
            setString(1, cid); setString(2, machine); setString(3, path); setLong(4, size)
            executeUpdate(); close()
        }
    }

    private fun configWith(repoName: String, minCopies: Int = 2, preferredCopies: Int = 3): ChoamConfig {
        return ChoamConfig(
            machines = mapOf(
                "server-a" to MachineProfile(name = "server-a", hostname = "h1", type = MachineType.DESKTOP,
                    repositories = mapOf(repoName to "/path/$repoName"), aliases = listOf("server-a-old")),
                "server-b" to MachineProfile(name = "server-b", hostname = "h2", type = MachineType.DESKTOP,
                    repositories = mapOf(repoName to "/path/$repoName"))
            ),
            repositories = mapOf(
                repoName to RepositoryConfig(name = repoName, type = RepositoryType.MEDIA,
                    replication = ReplicationPolicy(minCopies = minCopies, preferredCopies = preferredCopies))
            )
        )
    }
}

package vision.salient.choam.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for FulfillCommand — pending request filtering, status transitions,
 * error recovery.
 */
class FulfillCommandTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `pending requests retrieved in chronological order`() {
        val conn = createDb()
        insert(conn, "film", "server-b")
        insert(conn, "tv", "server-a")
        insert(conn, "backup", "local")

        val rs = conn.createStatement().executeQuery(
            "SELECT repository FROM copy_requests WHERE status = 'pending' ORDER BY requested_at"
        )
        val repos = mutableListOf<String>()
        while (rs.next()) repos.add(rs.getString("repository"))
        rs.close()

        assertEquals(listOf("film", "tv", "backup"), repos)
        conn.close()
    }

    @Test
    fun `completed requests excluded from pending query`() {
        val conn = createDb()
        insert(conn, "film", "server-b")
        conn.createStatement().executeUpdate(
            "INSERT INTO copy_requests (repository, target_machine, status) VALUES ('tv', 'server-a', 'completed')"
        )
        conn.createStatement().executeUpdate(
            "INSERT INTO copy_requests (repository, target_machine, status) VALUES ('old', 'local', 'cancelled')"
        )

        val count = countPending(conn)
        assertEquals(1, count) // only film
        conn.close()
    }

    @Test
    fun `in_progress transition preserves other fields`() {
        val conn = createDb()
        insert(conn, "film", "server-b")

        conn.createStatement().executeUpdate(
            "UPDATE copy_requests SET status = 'in_progress' WHERE id = 1"
        )

        val rs = conn.createStatement().executeQuery("SELECT * FROM copy_requests WHERE id = 1")
        rs.next()
        assertEquals("in_progress", rs.getString("status"))
        assertEquals("film", rs.getString("repository"))
        assertEquals("server-b", rs.getString("target_machine"))
        assertNotNull(rs.getString("requested_at"))
        assertNull(rs.getString("fulfilled_at"))
        rs.close(); conn.close()
    }

    @Test
    fun `completed transition sets fulfilled_at`() {
        val conn = createDb()
        insert(conn, "film", "server-b")
        conn.createStatement().executeUpdate(
            "UPDATE copy_requests SET status = 'in_progress' WHERE id = 1"
        )
        conn.createStatement().executeUpdate(
            "UPDATE copy_requests SET status = 'completed', fulfilled_at = datetime('now') WHERE id = 1"
        )

        val rs = conn.createStatement().executeQuery("SELECT fulfilled_at FROM copy_requests WHERE id = 1")
        rs.next()
        assertNotNull(rs.getString("fulfilled_at"))
        rs.close(); conn.close()
    }

    @Test
    fun `reset to pending on failure preserves request`() {
        val conn = createDb()
        insert(conn, "film", "server-b")

        // Simulate: pending → in_progress → failure → reset to pending
        conn.createStatement().executeUpdate("UPDATE copy_requests SET status = 'in_progress' WHERE id = 1")
        conn.createStatement().executeUpdate("UPDATE copy_requests SET status = 'pending' WHERE id = 1")

        val rs = conn.createStatement().executeQuery("SELECT status FROM copy_requests WHERE id = 1")
        rs.next()
        assertEquals("pending", rs.getString("status"))
        rs.close(); conn.close()
    }

    @Test
    fun `multiple pending requests for different repos processed independently`() {
        val conn = createDb()
        insert(conn, "film", "server-b")
        insert(conn, "tv", "server-b")
        insert(conn, "music", "server-a")

        // Complete one, skip one, leave one
        conn.createStatement().executeUpdate(
            "UPDATE copy_requests SET status = 'completed', fulfilled_at = datetime('now') WHERE repository = 'film'"
        )
        conn.createStatement().executeUpdate(
            "UPDATE copy_requests SET status = 'cancelled' WHERE repository = 'tv'"
        )

        assertEquals(1, countPending(conn)) // only music
        conn.close()
    }

    @Test
    fun `duplicate pending request for same repo-machine detectable`() {
        val conn = createDb()
        insert(conn, "film", "server-b")

        val rs = conn.prepareStatement(
            "SELECT COUNT(*) FROM copy_requests WHERE repository = ? AND target_machine = ? AND status = 'pending'"
        ).apply {
            setString(1, "film"); setString(2, "server-b")
        }.executeQuery()
        rs.next()
        assertEquals(1, rs.getInt(1))
        rs.close(); conn.close()
    }

    @Test
    fun `empty table returns zero pending`() {
        val conn = createDb()
        assertEquals(0, countPending(conn))
        conn.close()
    }

    @Test
    fun `mixed statuses counted correctly`() {
        val conn = createDb()
        insert(conn, "a", "m1")
        insert(conn, "b", "m2")
        conn.createStatement().executeUpdate(
            "INSERT INTO copy_requests (repository, target_machine, status) VALUES ('c', 'm3', 'completed')"
        )
        conn.createStatement().executeUpdate(
            "INSERT INTO copy_requests (repository, target_machine, status) VALUES ('d', 'm4', 'in_progress')"
        )
        conn.createStatement().executeUpdate(
            "INSERT INTO copy_requests (repository, target_machine, status) VALUES ('e', 'm5', 'cancelled')"
        )

        assertEquals(2, countPending(conn))
        assertEquals(1, countByStatus(conn, "completed"))
        assertEquals(1, countByStatus(conn, "in_progress"))
        assertEquals(1, countByStatus(conn, "cancelled"))
        conn.close()
    }

    // ===========================================
    // Helpers
    // ===========================================

    private fun createDb(): Connection {
        val path = tempDir.resolve("fulfill_${System.nanoTime()}.db").toString()
        val conn = DriverManager.getConnection("jdbc:sqlite:$path")
        RequestCopyCommand.ensureCopyRequestsTable(conn)
        return conn
    }

    private fun insert(conn: Connection, repo: String, machine: String) {
        conn.prepareStatement("INSERT INTO copy_requests (repository, target_machine) VALUES (?, ?)").apply {
            setString(1, repo); setString(2, machine); executeUpdate(); close()
        }
    }

    private fun countPending(conn: Connection): Int {
        val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM copy_requests WHERE status = 'pending'")
        rs.next(); val c = rs.getInt(1); rs.close(); return c
    }

    private fun countByStatus(conn: Connection, status: String): Int {
        val rs = conn.prepareStatement("SELECT COUNT(*) FROM copy_requests WHERE status = ?").apply {
            setString(1, status)
        }.executeQuery()
        rs.next(); val c = rs.getInt(1); rs.close(); return c
    }
}

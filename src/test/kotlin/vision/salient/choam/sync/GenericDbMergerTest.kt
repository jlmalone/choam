package vision.salient.choam.sync

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager

class GenericDbMergerTest {

    @TempDir
    lateinit var tempDir: File

    private fun createDb(name: String, block: (java.sql.Connection) -> Unit): String {
        val path = File(tempDir, name).absolutePath
        val conn = DriverManager.getConnection("jdbc:sqlite:$path")
        conn.createStatement().executeUpdate("PRAGMA journal_mode=WAL")
        block(conn)
        conn.close()
        return path
    }

    @Test
    fun `INSERT_OR_IGNORE adds missing rows and skips existing`() {
        val source = createDb("source.db") { conn ->
            conn.createStatement().executeUpdate(
                "CREATE TABLE items (key TEXT PRIMARY KEY, value TEXT)"
            )
            conn.prepareStatement("INSERT INTO items VALUES (?, ?)").apply {
                setString(1, "a"); setString(2, "alpha"); executeUpdate()
                setString(1, "b"); setString(2, "bravo"); executeUpdate()
                setString(1, "c"); setString(2, "charlie"); executeUpdate()
            }
        }

        val target = createDb("target.db") { conn ->
            conn.createStatement().executeUpdate(
                "CREATE TABLE items (key TEXT PRIMARY KEY, value TEXT)"
            )
            conn.prepareStatement("INSERT INTO items VALUES (?, ?)").apply {
                setString(1, "a"); setString(2, "EXISTING"); executeUpdate()
                setString(1, "d"); setString(2, "delta"); executeUpdate()
            }
        }

        val result = GenericDbMerger.merge(source, target, GenericDbMerger.MergeStrategy.INSERT_OR_IGNORE)

        assertEquals(1, result.tablesProcessed)
        assertEquals(2, result.rowsInserted) // b and c
        assertEquals(1, result.rowsSkipped)  // a (already exists)
        assertTrue(result.errors.isEmpty())

        // Verify 'a' was NOT overwritten
        val conn = DriverManager.getConnection("jdbc:sqlite:$target")
        val rs = conn.createStatement().executeQuery("SELECT value FROM items WHERE key = 'a'")
        assertTrue(rs.next())
        assertEquals("EXISTING", rs.getString(1))

        // Verify 'd' still exists
        val rs2 = conn.createStatement().executeQuery("SELECT value FROM items WHERE key = 'd'")
        assertTrue(rs2.next())
        assertEquals("delta", rs2.getString(1))

        // Verify total count
        val rs3 = conn.createStatement().executeQuery("SELECT COUNT(*) FROM items")
        rs3.next()
        assertEquals(4, rs3.getInt(1)) // a, b, c, d
        conn.close()
    }

    @Test
    fun `INSERT_OR_REPLACE overwrites existing rows`() {
        val source = createDb("source.db") { conn ->
            conn.createStatement().executeUpdate(
                "CREATE TABLE items (key TEXT PRIMARY KEY, value TEXT)"
            )
            conn.prepareStatement("INSERT INTO items VALUES (?, ?)").apply {
                setString(1, "a"); setString(2, "NEW_VALUE"); executeUpdate()
            }
        }

        val target = createDb("target.db") { conn ->
            conn.createStatement().executeUpdate(
                "CREATE TABLE items (key TEXT PRIMARY KEY, value TEXT)"
            )
            conn.prepareStatement("INSERT INTO items VALUES (?, ?)").apply {
                setString(1, "a"); setString(2, "OLD_VALUE"); executeUpdate()
            }
        }

        val result = GenericDbMerger.merge(source, target, GenericDbMerger.MergeStrategy.INSERT_OR_REPLACE)

        assertEquals(1, result.tablesProcessed)

        val conn = DriverManager.getConnection("jdbc:sqlite:$target")
        val rs = conn.createStatement().executeQuery("SELECT value FROM items WHERE key = 'a'")
        assertTrue(rs.next())
        assertEquals("NEW_VALUE", rs.getString(1))
        conn.close()
    }

    @Test
    fun `TIMESTAMP_WINS updates only when source row is newer`() {
        val source = createDb("source.db") { conn ->
            conn.createStatement().executeUpdate(
                "CREATE TABLE items (key TEXT PRIMARY KEY, value TEXT, updatedDate TEXT)"
            )
            conn.prepareStatement("INSERT INTO items VALUES (?, ?, ?)").apply {
                setString(1, "a"); setString(2, "source-newer"); setString(3, "2026-01-03T00:00:00Z"); executeUpdate()
                setString(1, "b"); setString(2, "source-older"); setString(3, "2026-01-01T00:00:00Z"); executeUpdate()
                setString(1, "c"); setString(2, "source-missing"); setString(3, "2026-01-02T00:00:00Z"); executeUpdate()
            }
        }

        val target = createDb("target.db") { conn ->
            conn.createStatement().executeUpdate(
                "CREATE TABLE items (key TEXT PRIMARY KEY, value TEXT, updatedDate TEXT)"
            )
            conn.prepareStatement("INSERT INTO items VALUES (?, ?, ?)").apply {
                setString(1, "a"); setString(2, "target-older"); setString(3, "2026-01-02T00:00:00Z"); executeUpdate()
                setString(1, "b"); setString(2, "target-newer"); setString(3, "2026-01-04T00:00:00Z"); executeUpdate()
            }
        }

        val result = GenericDbMerger.merge(source, target, GenericDbMerger.MergeStrategy.TIMESTAMP_WINS)

        assertEquals(1, result.rowsInserted)
        assertEquals(1, result.rowsUpdated)
        assertEquals(1, result.rowsSkipped)

        val conn = DriverManager.getConnection("jdbc:sqlite:$target")
        val rs = conn.createStatement().executeQuery("SELECT key, value FROM items ORDER BY key")
        assertTrue(rs.next())
        assertEquals("a", rs.getString("key"))
        assertEquals("source-newer", rs.getString("value"))
        assertTrue(rs.next())
        assertEquals("b", rs.getString("key"))
        assertEquals("target-newer", rs.getString("value"))
        assertTrue(rs.next())
        assertEquals("c", rs.getString("key"))
        assertEquals("source-missing", rs.getString("value"))
        conn.close()
    }

    @Test
    fun `TIMESTAMP_WINS uses removed_at as a row timestamp for tombstones`() {
        val schema = """
            CREATE TABLE video_downloaded_catalog (
                id TEXT PRIMARY KEY,
                title TEXT,
                downloadTimestamp DATETIME,
                removed_at TEXT
            )
        """.trimIndent()

        val source = createDb("source.db") { conn ->
            conn.createStatement().executeUpdate(schema)
            conn.prepareStatement("INSERT INTO video_downloaded_catalog VALUES (?, ?, ?, ?)").apply {
                setString(1, "deleted-after-target"); setString(2, "source-deleted"); setString(3, "2026-01-01 00:00:00"); setString(4, "2026-01-03T00:00:00Z"); executeUpdate()
                setString(1, "redownloaded-after-delete"); setString(2, "source-deleted"); setString(3, "2026-01-01 00:00:00"); setString(4, "2026-01-02T00:00:00Z"); executeUpdate()
            }
        }

        val target = createDb("target.db") { conn ->
            conn.createStatement().executeUpdate(schema)
            conn.prepareStatement("INSERT INTO video_downloaded_catalog VALUES (?, ?, ?, ?)").apply {
                setString(1, "deleted-after-target"); setString(2, "target-present"); setString(3, "2026-01-02 00:00:00"); setString(4, null); executeUpdate()
                setString(1, "redownloaded-after-delete"); setString(2, "target-present"); setString(3, "2026-01-03 00:00:00"); setString(4, null); executeUpdate()
            }
        }

        val result = GenericDbMerger.merge(source, target, GenericDbMerger.MergeStrategy.TIMESTAMP_WINS)

        assertEquals(0, result.rowsInserted)
        assertEquals(1, result.rowsUpdated)
        assertEquals(1, result.rowsSkipped)

        val conn = DriverManager.getConnection("jdbc:sqlite:$target")
        val deleted = conn.createStatement().executeQuery(
            "SELECT title, removed_at FROM video_downloaded_catalog WHERE id = 'deleted-after-target'"
        )
        assertTrue(deleted.next())
        assertEquals("source-deleted", deleted.getString("title"))
        assertEquals("2026-01-03T00:00:00Z", deleted.getString("removed_at"))

        val present = conn.createStatement().executeQuery(
            "SELECT title, removed_at FROM video_downloaded_catalog WHERE id = 'redownloaded-after-delete'"
        )
        assertTrue(present.next())
        assertEquals("target-present", present.getString("title"))
        assertNull(present.getString("removed_at"))
        conn.close()
    }

    @Test
    fun `TIMESTAMP_WINS handles nullable composite primary keys without duplicate inserts`() {
        val source = createDb("source.db") { conn ->
            conn.createStatement().executeUpdate(
                "CREATE TABLE lookup_check (type TEXT NOT NULL, id TEXT, updatedDate DATETIME NOT NULL, removed BOOLEAN NOT NULL DEFAULT 0, PRIMARY KEY (type, id))"
            )
            conn.prepareStatement("INSERT INTO lookup_check VALUES (?, ?, ?, ?)").apply {
                setString(1, "ALL_SUBSCRIPTIONS"); setString(2, null); setString(3, "2026-01-03T00:00:00Z"); setBoolean(4, true); executeUpdate()
            }
        }

        val target = createDb("target.db") { conn ->
            conn.createStatement().executeUpdate(
                "CREATE TABLE lookup_check (type TEXT NOT NULL, id TEXT, updatedDate DATETIME NOT NULL, removed BOOLEAN NOT NULL DEFAULT 0, PRIMARY KEY (type, id))"
            )
            conn.prepareStatement("INSERT INTO lookup_check VALUES (?, ?, ?, ?)").apply {
                setString(1, "ALL_SUBSCRIPTIONS"); setString(2, null); setString(3, "2026-01-02T00:00:00Z"); setBoolean(4, false); executeUpdate()
            }
        }

        val result = GenericDbMerger.merge(source, target, GenericDbMerger.MergeStrategy.TIMESTAMP_WINS)

        assertEquals(0, result.rowsInserted)
        assertEquals(1, result.rowsUpdated)

        val conn = DriverManager.getConnection("jdbc:sqlite:$target")
        val count = conn.createStatement().executeQuery("SELECT COUNT(*) FROM lookup_check")
        assertTrue(count.next())
        assertEquals(1, count.getInt(1))

        val row = conn.createStatement().executeQuery("SELECT removed FROM lookup_check WHERE type = 'ALL_SUBSCRIPTIONS' AND id IS NULL")
        assertTrue(row.next())
        assertEquals(1, row.getInt("removed"))
        conn.close()
    }

    @Test
    fun `tables without primary key are skipped`() {
        val source = createDb("source.db") { conn ->
            conn.createStatement().executeUpdate(
                "CREATE TABLE logs (message TEXT, ts TEXT)"
            )
            conn.prepareStatement("INSERT INTO logs VALUES (?, ?)").apply {
                setString(1, "hello"); setString(2, "2026-01-01"); executeUpdate()
            }
        }

        val target = createDb("target.db") { conn ->
            conn.createStatement().executeUpdate(
                "CREATE TABLE logs (message TEXT, ts TEXT)"
            )
        }

        val result = GenericDbMerger.merge(source, target)

        assertEquals(0, result.tablesProcessed)
        assertEquals(1, result.tablesSkipped.size)
        assertEquals("logs", result.tablesSkipped[0])
    }

    @Test
    fun `AUTOINCREMENT tables are skipped by default`() {
        val source = createDb("source.db") { conn ->
            conn.createStatement().executeUpdate(
                "CREATE TABLE runs (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)"
            )
            conn.prepareStatement("INSERT INTO runs (name) VALUES (?)").apply {
                setString(1, "run-1"); executeUpdate()
            }
        }

        val target = createDb("target.db") { conn ->
            conn.createStatement().executeUpdate(
                "CREATE TABLE runs (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)"
            )
            conn.prepareStatement("INSERT INTO runs (name) VALUES (?)").apply {
                setString(1, "run-A"); executeUpdate()
            }
        }

        val result = GenericDbMerger.merge(source, target)

        assertEquals(0, result.tablesProcessed)
        assertTrue(result.tablesSkipped.any { it == "runs" })
    }

    @Test
    fun `composite primary key works correctly`() {
        val source = createDb("source.db") { conn ->
            conn.createStatement().executeUpdate(
                "CREATE TABLE tested (hash TEXT NOT NULL, wallet TEXT NOT NULL, PRIMARY KEY (hash, wallet))"
            )
            conn.prepareStatement("INSERT INTO tested VALUES (?, ?)").apply {
                setString(1, "abc123"); setString(2, "wallet_a"); executeUpdate()
                setString(1, "def456"); setString(2, "wallet_a"); executeUpdate()
                setString(1, "abc123"); setString(2, "wallet_b"); executeUpdate()
            }
        }

        val target = createDb("target.db") { conn ->
            conn.createStatement().executeUpdate(
                "CREATE TABLE tested (hash TEXT NOT NULL, wallet TEXT NOT NULL, PRIMARY KEY (hash, wallet))"
            )
            conn.prepareStatement("INSERT INTO tested VALUES (?, ?)").apply {
                setString(1, "abc123"); setString(2, "wallet_a"); executeUpdate() // duplicate
                setString(1, "ghi789"); setString(2, "wallet_a"); executeUpdate()
            }
        }

        val result = GenericDbMerger.merge(source, target)

        assertEquals(1, result.tablesProcessed)
        assertEquals(2, result.rowsInserted) // def456/wallet_a and abc123/wallet_b
        assertEquals(1, result.rowsSkipped)  // abc123/wallet_a

        val conn = DriverManager.getConnection("jdbc:sqlite:$target")
        val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM tested")
        rs.next()
        assertEquals(4, rs.getInt(1))
        conn.close()
    }

    @Test
    fun `creates missing table in target`() {
        val source = createDb("source.db") { conn ->
            conn.createStatement().executeUpdate(
                "CREATE TABLE items (key TEXT PRIMARY KEY, value TEXT)"
            )
            conn.prepareStatement("INSERT INTO items VALUES (?, ?)").apply {
                setString(1, "x"); setString(2, "xray"); executeUpdate()
            }
        }

        val target = createDb("target.db") { conn ->
            // Empty DB, no tables
            conn.createStatement().executeUpdate(
                "CREATE TABLE other (id TEXT PRIMARY KEY)"
            )
        }

        val result = GenericDbMerger.merge(source, target)

        assertEquals(1, result.tablesProcessed)
        assertEquals(1, result.tablesCreated.size)
        assertEquals("items", result.tablesCreated[0])

        val conn = DriverManager.getConnection("jdbc:sqlite:$target")
        val rs = conn.createStatement().executeQuery("SELECT value FROM items WHERE key = 'x'")
        assertTrue(rs.next())
        assertEquals("xray", rs.getString(1))
        conn.close()
    }

    @Test
    fun `includeTables filters to only specified tables`() {
        val source = createDb("source.db") { conn ->
            conn.createStatement().executeUpdate("CREATE TABLE t1 (k TEXT PRIMARY KEY, v TEXT)")
            conn.createStatement().executeUpdate("CREATE TABLE t2 (k TEXT PRIMARY KEY, v TEXT)")
            conn.prepareStatement("INSERT INTO t1 VALUES ('a', '1')").executeUpdate()
            conn.prepareStatement("INSERT INTO t2 VALUES ('b', '2')").executeUpdate()
        }

        val target = createDb("target.db") { conn ->
            conn.createStatement().executeUpdate("CREATE TABLE t1 (k TEXT PRIMARY KEY, v TEXT)")
            conn.createStatement().executeUpdate("CREATE TABLE t2 (k TEXT PRIMARY KEY, v TEXT)")
        }

        val result = GenericDbMerger.merge(source, target, includeTables = listOf("t1"))

        assertEquals(1, result.tablesProcessed)
        assertEquals(1, result.rowsInserted)

        // t2 should still be empty
        val conn = DriverManager.getConnection("jdbc:sqlite:$target")
        val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM t2")
        rs.next()
        assertEquals(0, rs.getInt(1))
        conn.close()
    }

    @Test
    fun `idempotent - running merge twice produces same result`() {
        val source = createDb("source.db") { conn ->
            conn.createStatement().executeUpdate(
                "CREATE TABLE items (key TEXT PRIMARY KEY, value TEXT)"
            )
            conn.prepareStatement("INSERT INTO items VALUES ('a', 'alpha')").executeUpdate()
            conn.prepareStatement("INSERT INTO items VALUES ('b', 'bravo')").executeUpdate()
        }

        val target = createDb("target.db") { conn ->
            conn.createStatement().executeUpdate(
                "CREATE TABLE items (key TEXT PRIMARY KEY, value TEXT)"
            )
        }

        val result1 = GenericDbMerger.merge(source, target)
        assertEquals(2, result1.rowsInserted)

        val result2 = GenericDbMerger.merge(source, target)
        assertEquals(0, result2.rowsInserted)
        assertEquals(2, result2.rowsSkipped)
    }

    @Test
    fun `first sync copies source to target when target does not exist`() {
        val source = createDb("source.db") { conn ->
            conn.createStatement().executeUpdate("CREATE TABLE items (key TEXT PRIMARY KEY, value TEXT)")
            conn.prepareStatement("INSERT INTO items VALUES ('a', 'alpha')").executeUpdate()
        }

        val targetPath = File(tempDir, "nonexistent.db").absolutePath

        val result = GenericDbMerger.merge(source, targetPath)

        assertEquals(0, result.rowsInserted) // copied, not merged
        assertTrue(result.errors.isEmpty())
        assertTrue(File(targetPath).exists())

        val conn = DriverManager.getConnection("jdbc:sqlite:$targetPath")
        val rs = conn.createStatement().executeQuery("SELECT value FROM items WHERE key = 'a'")
        assertTrue(rs.next())
        assertEquals("alpha", rs.getString(1))
        conn.close()
    }

    @Test
    fun `source does not exist returns error`() {
        val target = createDb("target.db") { conn ->
            conn.createStatement().executeUpdate("CREATE TABLE items (key TEXT PRIMARY KEY)")
        }

        val result = GenericDbMerger.merge("/nonexistent/path.db", target)

        assertEquals(0, result.tablesProcessed)
        assertTrue(result.errors.isNotEmpty())
    }

    @Test
    fun `multiple tables merged in one pass`() {
        val source = createDb("source.db") { conn ->
            conn.createStatement().executeUpdate("CREATE TABLE t1 (k TEXT PRIMARY KEY, v TEXT)")
            conn.createStatement().executeUpdate("CREATE TABLE t2 (k TEXT PRIMARY KEY, v TEXT)")
            conn.prepareStatement("INSERT INTO t1 VALUES ('a', '1')").executeUpdate()
            conn.prepareStatement("INSERT INTO t2 VALUES ('b', '2')").executeUpdate()
        }

        val target = createDb("target.db") { conn ->
            conn.createStatement().executeUpdate("CREATE TABLE t1 (k TEXT PRIMARY KEY, v TEXT)")
            conn.createStatement().executeUpdate("CREATE TABLE t2 (k TEXT PRIMARY KEY, v TEXT)")
        }

        val result = GenericDbMerger.merge(source, target)

        assertEquals(2, result.tablesProcessed)
        assertEquals(2, result.rowsInserted)
    }
}

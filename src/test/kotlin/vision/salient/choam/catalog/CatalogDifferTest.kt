package vision.salient.choam.catalog

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogDifferTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createRegistry(path: String, entries: List<TestEntry>) {
        val conn = DriverManager.getConnection("jdbc:sqlite:$path")
        val stmt = conn.createStatement()
        stmt.executeUpdate("PRAGMA journal_mode=WAL")
        stmt.executeUpdate("""
            CREATE TABLE content_locations (
                cid TEXT NOT NULL,
                machine_name TEXT NOT NULL,
                file_path TEXT NOT NULL,
                file_size INTEGER,
                verified_at TEXT,
                registered_at TEXT NOT NULL DEFAULT (datetime('now')),
                last_synced_at TEXT NOT NULL DEFAULT (datetime('now')),
                PRIMARY KEY (cid, machine_name, file_path)
            )
        """)
        val insert = conn.prepareStatement(
            "INSERT INTO content_locations (cid, machine_name, file_path, file_size) VALUES (?, ?, ?, ?)"
        )
        for (e in entries) {
            insert.setString(1, e.cid)
            insert.setString(2, e.machine)
            insert.setString(3, e.filePath)
            insert.setLong(4, e.fileSize)
            insert.executeUpdate()
        }
        insert.close()
        stmt.close()
        conn.close()
    }

    data class TestEntry(val cid: String, val machine: String, val filePath: String, val fileSize: Long = 1024)

    // --- resolveAliases ---

    @Test
    fun `resolveAliases includes config key and reverse aliases`() {
        val map = mapOf("server-a-old" to "server-a", "server-a-mac-mini" to "server-a", "vanc-old" to "server-b")
        val result = CatalogDiffer.resolveAliases("server-a", map)
        assertEquals(listOf("server-a", "server-a-old", "server-a-mac-mini"), result)
    }

    @Test
    fun `resolveAliases with no aliases returns just the name`() {
        val result = CatalogDiffer.resolveAliases("server-a", emptyMap())
        assertEquals(listOf("server-a"), result)
    }

    // --- diffMachines basic ---

    @Test
    fun `diff two machines with no overlap`() {
        val dbPath = tempDir.resolve("registry.db").toString()
        createRegistry(dbPath, listOf(
            TestEntry("QmAAA", "server-a", "/Volumes/A/file1.mkv", 1_000_000),
            TestEntry("QmBBB", "server-a", "/Volumes/A/file2.mkv", 2_000_000),
            TestEntry("QmCCC", "server-b", "/Volumes/B/file3.mkv", 3_000_000),
        ))

        val diff = CatalogDiffer.diffMachines(dbPath, "server-a", "server-b")
        assertEquals(2, diff.totalA)
        assertEquals(1, diff.totalB)
        assertEquals(0, diff.onBoth)
        assertEquals(2, diff.onlyOnA.size)
        assertEquals(1, diff.onlyOnB.size)
    }

    @Test
    fun `diff two machines with full overlap`() {
        val dbPath = tempDir.resolve("registry.db").toString()
        createRegistry(dbPath, listOf(
            TestEntry("QmAAA", "server-a", "/Volumes/A/file1.mkv"),
            TestEntry("QmBBB", "server-a", "/Volumes/A/file2.mkv"),
            TestEntry("QmAAA", "server-b", "/Volumes/B/copy_of_file1.mkv"),
            TestEntry("QmBBB", "server-b", "/Volumes/B/copy_of_file2.mkv"),
        ))

        val diff = CatalogDiffer.diffMachines(dbPath, "server-a", "server-b")
        assertEquals(2, diff.totalA)
        assertEquals(2, diff.totalB)
        assertEquals(2, diff.onBoth)
        assertEquals(0, diff.onlyOnA.size)
        assertEquals(0, diff.onlyOnB.size)
    }

    @Test
    fun `diff two machines with partial overlap`() {
        val dbPath = tempDir.resolve("registry.db").toString()
        createRegistry(dbPath, listOf(
            TestEntry("QmAAA", "server-a", "/Volumes/A/shared.mkv"),
            TestEntry("QmBBB", "server-a", "/Volumes/A/only_server_a.mkv"),
            TestEntry("QmAAA", "server-b", "/Volumes/B/shared_copy.mkv"),
            TestEntry("QmCCC", "server-b", "/Volumes/B/only_vanc.mkv"),
        ))

        val diff = CatalogDiffer.diffMachines(dbPath, "server-a", "server-b")
        assertEquals(2, diff.totalA)
        assertEquals(2, diff.totalB)
        assertEquals(1, diff.onBoth)
        assertEquals(1, diff.onlyOnA.size)
        assertEquals("QmBBB", diff.onlyOnA[0].cid)
        assertEquals(1, diff.onlyOnB.size)
        assertEquals("QmCCC", diff.onlyOnB[0].cid)
    }

    // --- alias resolution ---

    @Test
    fun `diff resolves aliases in unified registry`() {
        val dbPath = tempDir.resolve("registry.db").toString()
        createRegistry(dbPath, listOf(
            TestEntry("QmAAA", "server-a-old", "/Volumes/A/file1.mkv"),
            TestEntry("QmBBB", "server-a-old", "/Volumes/A/file2.mkv"),
            TestEntry("QmAAA", "server-b", "/Volumes/B/file1_copy.mkv"),
        ))

        val aliasMap = mapOf("server-a-old" to "server-a")
        val diff = CatalogDiffer.diffMachines(dbPath, "server-a", "server-b", aliasMap)
        assertEquals(2, diff.totalA) // finds server-a-old rows via alias
        assertEquals(1, diff.totalB)
        assertEquals(1, diff.onBoth) // QmAAA shared
        assertEquals(1, diff.onlyOnA.size) // QmBBB only on server-a
    }

    // --- minSize filter ---

    @Test
    fun `diff with minSize filters small files`() {
        val dbPath = tempDir.resolve("registry.db").toString()
        createRegistry(dbPath, listOf(
            TestEntry("QmAAA", "server-a", "/Volumes/A/big.mkv", 1_000_000_000),
            TestEntry("QmBBB", "server-a", "/Volumes/A/small.txt", 100),
            TestEntry("QmCCC", "server-b", "/Volumes/B/big2.mkv", 2_000_000_000),
        ))

        val diff = CatalogDiffer.diffMachines(dbPath, "server-a", "server-b", minSize = 1_000_000)
        assertEquals(1, diff.totalA) // only big.mkv
        assertEquals(1, diff.totalB) // only big2.mkv
        assertEquals(0, diff.onBoth)
    }

    // --- limit ---

    @Test
    fun `diff respects limit on exclusive files`() {
        val dbPath = tempDir.resolve("registry.db").toString()
        val entries = (1..100).map { i ->
            TestEntry("QmCID_$i", "server-a", "/Volumes/A/file_$i.dat", i.toLong() * 1000)
        }
        createRegistry(dbPath, entries)

        val diff = CatalogDiffer.diffMachines(dbPath, "server-a", "server-b", limit = 10)
        assertEquals(10, diff.onlyOnA.size) // capped at 10
        assertEquals(100, diff.totalA)
    }

    // --- edge cases ---

    @Test
    fun `diff with nonexistent DB returns empty`() {
        val diff = CatalogDiffer.diffMachines("/nonexistent/db.sqlite", "a", "b")
        assertEquals(0, diff.totalA)
        assertEquals(0, diff.totalB)
        assertEquals(0, diff.onBoth)
    }

    @Test
    fun `diff with unknown machines returns zeros`() {
        val dbPath = tempDir.resolve("registry.db").toString()
        createRegistry(dbPath, listOf(
            TestEntry("QmAAA", "server-a", "/Volumes/A/file.mkv"),
        ))

        val diff = CatalogDiffer.diffMachines(dbPath, "nonexistent1", "nonexistent2")
        assertEquals(0, diff.totalA)
        assertEquals(0, diff.totalB)
    }

    @Test
    fun `diff with empty registry returns zeros`() {
        val dbPath = tempDir.resolve("registry.db").toString()
        createRegistry(dbPath, emptyList())

        val diff = CatalogDiffer.diffMachines(dbPath, "server-a", "server-b")
        assertEquals(0, diff.totalA)
        assertEquals(0, diff.totalB)
    }

    @Test
    fun `onlyOnA sorted by file_size descending`() {
        val dbPath = tempDir.resolve("registry.db").toString()
        createRegistry(dbPath, listOf(
            TestEntry("QmSmall", "server-a", "/Volumes/A/small.txt", 100),
            TestEntry("QmBig", "server-a", "/Volumes/A/big.mkv", 10_000_000),
            TestEntry("QmMid", "server-a", "/Volumes/A/mid.mp4", 5_000_000),
        ))

        val diff = CatalogDiffer.diffMachines(dbPath, "server-a", "server-b")
        assertEquals(3, diff.onlyOnA.size)
        assertEquals("QmBig", diff.onlyOnA[0].cid)
        assertEquals("QmMid", diff.onlyOnA[1].cid)
        assertEquals("QmSmall", diff.onlyOnA[2].cid)
    }

    @Test
    fun `CatalogDiff size calculations work`() {
        val dbPath = tempDir.resolve("registry.db").toString()
        createRegistry(dbPath, listOf(
            TestEntry("QmAAA", "server-a", "/Volumes/A/file1.mkv", 1_000_000),
            TestEntry("QmBBB", "server-a", "/Volumes/A/file2.mkv", 2_000_000),
            TestEntry("QmCCC", "server-b", "/Volumes/B/file3.mkv", 3_000_000),
        ))

        val diff = CatalogDiffer.diffMachines(dbPath, "server-a", "server-b")
        assertEquals(3_000_000, diff.onlyOnASize)
        assertEquals(3_000_000, diff.onlyOnBSize)
    }
}

package vision.salient.choam.sync

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DatabaseSyncTest {
    private lateinit var tempDir: Path
    private lateinit var sourceDbPath: String
    private lateinit var targetDbPath: String
    private val dbSync = DatabaseSync()

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("db_sync_test")
        sourceDbPath = tempDir.resolve("source.db").toString()
        targetDbPath = tempDir.resolve("target.db").toString()
    }

    @AfterTest
    fun cleanup() {
        Path.of(sourceDbPath).deleteIfExists()
        Path.of(targetDbPath).deleteIfExists()
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `test merge with non-existent source database`() = runBlocking {
        val result = dbSync.syncDatabase(
            sourcePath = tempDir.resolve("nonexistent.db").toString(),
            targetPath = targetDbPath,
            strategy = DatabaseSyncStrategy.MERGE
        )

        assertIs<DatabaseSyncResult.Failure>(result)
        assertTrue(result.message.contains("Source database does not exist"))
    }

    @Test
    fun `test merge when target does not exist`() = runBlocking {
        createSimpleDatabase(sourceDbPath)

        val result = dbSync.syncDatabase(
            sourcePath = sourceDbPath,
            targetPath = targetDbPath,
            strategy = DatabaseSyncStrategy.MERGE
        )

        assertIs<DatabaseSyncResult.Success>(result)
        assertTrue(File(targetDbPath).exists())
    }

    @Test
    fun `test merge with identical empty databases`() = runBlocking {
        createSimpleDatabase(sourceDbPath)
        createSimpleDatabase(targetDbPath)

        val result = dbSync.syncDatabase(
            sourcePath = sourceDbPath,
            targetPath = targetDbPath,
            strategy = DatabaseSyncStrategy.MERGE
        )

        assertIs<DatabaseSyncResult.MergeSuccess>(result)
        val report = result.report
        assertEquals(0, report.recordsInserted)
        assertEquals(0, report.recordsUpdated)
        assertEquals(0, report.recordsSkipped)
        assertEquals(0, report.conflicts.size)
    }

    @Test
    fun `test merge inserts new records from source`() = runBlocking {
        createDatabaseWithRecords(sourceDbPath, listOf(
            Movie(1, "Inception", 2010, System.currentTimeMillis()),
            Movie(2, "The Matrix", 1999, System.currentTimeMillis())
        ))
        createSimpleDatabase(targetDbPath)

        val result = dbSync.syncDatabase(
            sourcePath = sourceDbPath,
            targetPath = targetDbPath,
            strategy = DatabaseSyncStrategy.MERGE
        )

        assertIs<DatabaseSyncResult.MergeSuccess>(result)
        val report = result.report
        assertEquals(2, report.recordsInserted)
        assertEquals(0, report.recordsUpdated)

        val targetRecords = readMovies(targetDbPath)
        assertEquals(2, targetRecords.size)
    }

    @Test
    fun `test merge updates target with newer source records`() = runBlocking {
        val oldTimestamp = System.currentTimeMillis() - 10000
        val newTimestamp = System.currentTimeMillis()

        createDatabaseWithRecords(targetDbPath, listOf(
            Movie(1, "Inception", 2010, oldTimestamp)
        ))
        createDatabaseWithRecords(sourceDbPath, listOf(
            Movie(1, "Inception Updated", 2010, newTimestamp)
        ))

        val result = dbSync.syncDatabase(
            sourcePath = sourceDbPath,
            targetPath = targetDbPath,
            strategy = DatabaseSyncStrategy.MERGE
        )

        assertIs<DatabaseSyncResult.MergeSuccess>(result)
        val report = result.report
        assertEquals(0, report.recordsInserted)
        assertEquals(1, report.recordsUpdated)

        val targetRecords = readMovies(targetDbPath)
        assertEquals(1, targetRecords.size)
        assertEquals("Inception Updated", targetRecords[0].title)
    }

    @Test
    fun `test merge skips when target has newer records`() = runBlocking {
        val oldTimestamp = System.currentTimeMillis() - 10000
        val newTimestamp = System.currentTimeMillis()

        createDatabaseWithRecords(sourceDbPath, listOf(
            Movie(1, "Inception Old", 2010, oldTimestamp)
        ))
        createDatabaseWithRecords(targetDbPath, listOf(
            Movie(1, "Inception New", 2010, newTimestamp)
        ))

        val result = dbSync.syncDatabase(
            sourcePath = sourceDbPath,
            targetPath = targetDbPath,
            strategy = DatabaseSyncStrategy.MERGE
        )

        assertIs<DatabaseSyncResult.MergeSuccess>(result)
        val report = result.report
        assertEquals(0, report.recordsInserted)
        assertEquals(0, report.recordsUpdated)
        assertEquals(1, report.recordsSkipped)

        val targetRecords = readMovies(targetDbPath)
        assertEquals("Inception New", targetRecords[0].title)
    }

    @Test
    fun `test merge detects conflicts with same timestamp but different data`() = runBlocking {
        val timestamp = System.currentTimeMillis()

        createDatabaseWithRecords(sourceDbPath, listOf(
            Movie(1, "Inception A", 2010, timestamp)
        ))
        createDatabaseWithRecords(targetDbPath, listOf(
            Movie(1, "Inception B", 2010, timestamp)
        ))

        val result = dbSync.syncDatabase(
            sourcePath = sourceDbPath,
            targetPath = targetDbPath,
            strategy = DatabaseSyncStrategy.MERGE
        )

        assertIs<DatabaseSyncResult.MergeSuccess>(result)
        val report = result.report
        assertTrue(report.hasConflicts)
        assertEquals(1, report.conflicts.size)
        assertEquals("movies", report.conflicts[0].tableName)
    }

    @Test
    fun `test merge operations table deduplicates by primary key`() = runBlocking {
        createDatabaseWithOperations(sourceDbPath, listOf(
            Operation("op1", "sync", System.currentTimeMillis()),
            Operation("op2", "backup", System.currentTimeMillis())
        ))
        createDatabaseWithOperations(targetDbPath, listOf(
            Operation("op1", "sync", System.currentTimeMillis() - 5000)
        ))

        val result = dbSync.syncDatabase(
            sourcePath = sourceDbPath,
            targetPath = targetDbPath,
            strategy = DatabaseSyncStrategy.MERGE
        )

        assertIs<DatabaseSyncResult.MergeSuccess>(result)
        val report = result.report
        assertEquals(1, report.recordsInserted)
        assertEquals(1, report.recordsSkipped)

        val operations = readOperations(targetDbPath)
        assertEquals(2, operations.size)
    }

    @Test
    fun `test merge creates missing table in target`() = runBlocking {
        createDatabaseWithMultipleTables(sourceDbPath)
        createSimpleDatabase(targetDbPath)

        val result = dbSync.syncDatabase(
            sourcePath = sourceDbPath,
            targetPath = targetDbPath,
            strategy = DatabaseSyncStrategy.MERGE
        )

        assertIs<DatabaseSyncResult.MergeSuccess>(result)

        val conn = openConnection(targetDbPath)
        val tables = getTableNames(conn)
        conn.close()

        assertTrue(tables.contains("movies"))
        assertTrue(tables.contains("actors"))
    }

    @Test
    fun `test merge adds missing column to target`() = runBlocking {
        createDatabaseWithExtraColumn(sourceDbPath)
        createSimpleDatabase(targetDbPath)

        val result = dbSync.syncDatabase(
            sourcePath = sourceDbPath,
            targetPath = targetDbPath,
            strategy = DatabaseSyncStrategy.MERGE
        )

        assertIs<DatabaseSyncResult.MergeSuccess>(result)

        val conn = openConnection(targetDbPath)
        val columns = getColumnNames(conn, "movies")
        conn.close()

        assertTrue(columns.contains("rating"))
    }

    @Test
    fun `test merge fails with incompatible column types`() = runBlocking {
        createDatabaseWithTypeMismatch(sourceDbPath)
        createSimpleDatabase(targetDbPath)

        val result = dbSync.syncDatabase(
            sourcePath = sourceDbPath,
            targetPath = targetDbPath,
            strategy = DatabaseSyncStrategy.MERGE
        )

        assertIs<DatabaseSyncResult.Failure>(result)
        assertTrue(result.message.contains("Incompatible schemas"))
    }

    @Test
    fun `test merge detects primary key mismatch`() = runBlocking {
        createSimpleDatabase(sourceDbPath)
        createDatabaseWithDifferentPK(targetDbPath)

        val result = dbSync.syncDatabase(
            sourcePath = sourceDbPath,
            targetPath = targetDbPath,
            strategy = DatabaseSyncStrategy.MERGE
        )

        assertIs<DatabaseSyncResult.Failure>(result)
        assertTrue(result.message.contains("Incompatible schemas"))
    }

    @Test
    fun `test merge skips table without primary key`() = runBlocking {
        createDatabaseWithNoPK(sourceDbPath)
        createDatabaseWithNoPK(targetDbPath)

        val result = dbSync.syncDatabase(
            sourcePath = sourceDbPath,
            targetPath = targetDbPath,
            strategy = DatabaseSyncStrategy.MERGE
        )

        assertIs<DatabaseSyncResult.MergeSuccess>(result)
    }

    @Test
    fun `test complex merge with multiple tables`() = runBlocking {
        val sourceConn = openConnection(sourceDbPath)
        createMoviesTable(sourceConn)
        createOperationsTable(sourceConn)
        insertMovie(sourceConn, Movie(1, "Inception", 2010, System.currentTimeMillis()))
        insertMovie(sourceConn, Movie(2, "The Matrix", 1999, System.currentTimeMillis()))
        insertOperation(sourceConn, Operation("op1", "sync", System.currentTimeMillis()))
        sourceConn.close()

        val targetConn = openConnection(targetDbPath)
        createMoviesTable(targetConn)
        createOperationsTable(targetConn)
        insertMovie(targetConn, Movie(1, "Inception Old", 2010, System.currentTimeMillis() - 10000))
        targetConn.close()

        val result = dbSync.syncDatabase(
            sourcePath = sourceDbPath,
            targetPath = targetDbPath,
            strategy = DatabaseSyncStrategy.MERGE
        )

        assertIs<DatabaseSyncResult.MergeSuccess>(result)
        val report = result.report
        assertEquals(2, report.recordsInserted)
        assertEquals(1, report.recordsUpdated)
    }

    @Test
    fun `test database integrity is verified after merge`() = runBlocking {
        createDatabaseWithRecords(sourceDbPath, listOf(
            Movie(1, "Inception", 2010, System.currentTimeMillis()),
            Movie(2, "The Matrix", 1999, System.currentTimeMillis())
        ))
        createSimpleDatabase(targetDbPath)

        val result = dbSync.syncDatabase(
            sourcePath = sourceDbPath,
            targetPath = targetDbPath,
            strategy = DatabaseSyncStrategy.MERGE
        )

        assertIs<DatabaseSyncResult.MergeSuccess>(result)

        val conn = openConnection(targetDbPath)
        val stmt = conn.createStatement()
        val rs = stmt.executeQuery("PRAGMA integrity_check")
        assertTrue(rs.next())
        assertEquals("ok", rs.getString(1))
        conn.close()
    }

    @Test
    fun `test merge with catalog database structure`() = runBlocking {
        createCatalogDatabase(sourceDbPath, listOf(
            CatalogEntry(1, "movie1.mp4", "/path/to/movie1.mp4", 1024000, System.currentTimeMillis()),
            CatalogEntry(2, "movie2.mp4", "/path/to/movie2.mp4", 2048000, System.currentTimeMillis())
        ))
        createCatalogDatabase(targetDbPath, listOf(
            CatalogEntry(1, "movie1.mp4", "/old/path/movie1.mp4", 1024000, System.currentTimeMillis() - 10000)
        ))

        val result = dbSync.syncDatabase(
            sourcePath = sourceDbPath,
            targetPath = targetDbPath,
            strategy = DatabaseSyncStrategy.MERGE
        )

        assertIs<DatabaseSyncResult.MergeSuccess>(result)
        val report = result.report
        assertEquals(1, report.recordsInserted)
        assertEquals(1, report.recordsUpdated)

        val entries = readCatalogEntries(targetDbPath)
        assertEquals(2, entries.size)
        assertEquals("/path/to/movie1.mp4", entries.find { it.id == 1 }?.path)
    }

    @Test
    fun `test merge with table without timestamp column reports conflicts`() = runBlocking {
        createDatabaseWithoutTimestamp(sourceDbPath, listOf(
            SimpleRecord(1, "Data A")
        ))
        createDatabaseWithoutTimestamp(targetDbPath, listOf(
            SimpleRecord(1, "Data B")
        ))

        val result = dbSync.syncDatabase(
            sourcePath = sourceDbPath,
            targetPath = targetDbPath,
            strategy = DatabaseSyncStrategy.MERGE
        )

        assertIs<DatabaseSyncResult.MergeSuccess>(result)
        val report = result.report
        assertTrue(report.hasConflicts)
        assertTrue(report.conflicts[0].reason.contains("No timestamp column"))
    }

    @Test
    fun `test REPLACE strategy replaces target with source`() = runBlocking {
        createDatabaseWithRecords(sourceDbPath, listOf(
            Movie(1, "New Movie", 2023, System.currentTimeMillis())
        ))
        createDatabaseWithRecords(targetDbPath, listOf(
            Movie(99, "Old Movie", 2000, System.currentTimeMillis())
        ))

        val result = dbSync.syncDatabase(
            sourcePath = sourceDbPath,
            targetPath = targetDbPath,
            strategy = DatabaseSyncStrategy.REPLACE
        )

        assertIs<DatabaseSyncResult.Success>(result)

        val targetRecords = readMovies(targetDbPath)
        assertEquals(1, targetRecords.size)
        assertEquals("New Movie", targetRecords[0].title)
    }

    @Test
    fun `test BACKUP_AND_REPLACE strategy creates backup file`() = runBlocking {
        createSimpleDatabase(sourceDbPath)
        createSimpleDatabase(targetDbPath)

        val result = dbSync.syncDatabase(
            sourcePath = sourceDbPath,
            targetPath = targetDbPath,
            strategy = DatabaseSyncStrategy.BACKUP_AND_REPLACE
        )

        assertIs<DatabaseSyncResult.Success>(result)

        val backupPath = "$targetDbPath.bak"
        assertTrue(File(backupPath).exists())

        Path.of(backupPath).deleteIfExists()
    }

    // ===== Helper Functions =====

    private fun createSimpleDatabase(path: String) {
        val conn = openConnection(path)
        createMoviesTable(conn)
        conn.close()
    }

    private fun createDatabaseWithRecords(path: String, movies: List<Movie>) {
        val conn = openConnection(path)
        createMoviesTable(conn)
        movies.forEach { insertMovie(conn, it) }
        conn.close()
    }

    private fun createDatabaseWithOperations(path: String, operations: List<Operation>) {
        val conn = openConnection(path)
        createOperationsTable(conn)
        operations.forEach { insertOperation(conn, it) }
        conn.close()
    }

    private fun createDatabaseWithMultipleTables(path: String) {
        val conn = openConnection(path)
        createMoviesTable(conn)
        conn.createStatement().execute("""
            CREATE TABLE actors (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                birth_year INTEGER
            )
        """)
        conn.commit()
        conn.close()
    }

    private fun createDatabaseWithExtraColumn(path: String) {
        val conn = openConnection(path)
        conn.createStatement().execute("""
            CREATE TABLE movies (
                id INTEGER PRIMARY KEY,
                title TEXT NOT NULL,
                year INTEGER,
                rating REAL,
                updated_at INTEGER
            )
        """)
        conn.commit()
        conn.close()
    }

    private fun createDatabaseWithTypeMismatch(path: String) {
        val conn = openConnection(path)
        conn.createStatement().execute("""
            CREATE TABLE movies (
                id INTEGER PRIMARY KEY,
                title INTEGER,
                year TEXT,
                updated_at INTEGER
            )
        """)
        conn.commit()
        conn.close()
    }

    private fun createDatabaseWithDifferentPK(path: String) {
        val conn = openConnection(path)
        conn.createStatement().execute("""
            CREATE TABLE movies (
                id INTEGER,
                title TEXT NOT NULL,
                year INTEGER,
                updated_at INTEGER,
                PRIMARY KEY (id, title)
            )
        """)
        conn.commit()
        conn.close()
    }

    private fun createDatabaseWithNoPK(path: String) {
        val conn = openConnection(path)
        conn.createStatement().execute("""
            CREATE TABLE logs (
                message TEXT,
                timestamp INTEGER
            )
        """)
        conn.commit()
        conn.close()
    }

    private fun createCatalogDatabase(path: String, entries: List<CatalogEntry>) {
        val conn = openConnection(path)
        conn.createStatement().execute("""
            CREATE TABLE catalog (
                id INTEGER PRIMARY KEY,
                filename TEXT NOT NULL,
                path TEXT NOT NULL,
                size INTEGER,
                modified_at INTEGER
            )
        """)
        entries.forEach { entry ->
            conn.prepareStatement(
                "INSERT INTO catalog (id, filename, path, size, modified_at) VALUES (?, ?, ?, ?, ?)"
            ).use { stmt ->
                stmt.setInt(1, entry.id)
                stmt.setString(2, entry.filename)
                stmt.setString(3, entry.path)
                stmt.setLong(4, entry.size)
                stmt.setLong(5, entry.modifiedAt)
                stmt.executeUpdate()
            }
        }
        conn.commit()
        conn.close()
    }

    private fun createDatabaseWithoutTimestamp(path: String, records: List<SimpleRecord>) {
        val conn = openConnection(path)
        conn.createStatement().execute("""
            CREATE TABLE simple (
                id INTEGER PRIMARY KEY,
                data TEXT NOT NULL
            )
        """)
        records.forEach { record ->
            conn.prepareStatement("INSERT INTO simple (id, data) VALUES (?, ?)").use { stmt ->
                stmt.setInt(1, record.id)
                stmt.setString(2, record.data)
                stmt.executeUpdate()
            }
        }
        conn.commit()
        conn.close()
    }

    private fun openConnection(path: String): Connection {
        return DriverManager.getConnection("jdbc:sqlite:$path").apply {
            autoCommit = false
        }
    }

    private fun createMoviesTable(conn: Connection) {
        conn.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS movies (
                id INTEGER PRIMARY KEY,
                title TEXT NOT NULL,
                year INTEGER,
                updated_at INTEGER
            )
        """)
        conn.commit()
    }

    private fun createOperationsTable(conn: Connection) {
        conn.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS operations (
                operation_id TEXT PRIMARY KEY,
                operation_type TEXT NOT NULL,
                timestamp INTEGER
            )
        """)
        conn.commit()
    }

    private fun insertMovie(conn: Connection, movie: Movie) {
        conn.prepareStatement(
            "INSERT INTO movies (id, title, year, updated_at) VALUES (?, ?, ?, ?)"
        ).use { stmt ->
            stmt.setInt(1, movie.id)
            stmt.setString(2, movie.title)
            stmt.setInt(3, movie.year)
            stmt.setLong(4, movie.updatedAt)
            stmt.executeUpdate()
        }
        conn.commit()
    }

    private fun insertOperation(conn: Connection, operation: Operation) {
        conn.prepareStatement(
            "INSERT INTO operations (operation_id, operation_type, timestamp) VALUES (?, ?, ?)"
        ).use { stmt ->
            stmt.setString(1, operation.id)
            stmt.setString(2, operation.type)
            stmt.setLong(3, operation.timestamp)
            stmt.executeUpdate()
        }
        conn.commit()
    }

    private fun readMovies(path: String): List<Movie> {
        val conn = openConnection(path)
        val movies = mutableListOf<Movie>()

        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT id, title, year, updated_at FROM movies")
            while (rs.next()) {
                movies.add(
                    Movie(
                        id = rs.getInt("id"),
                        title = rs.getString("title"),
                        year = rs.getInt("year"),
                        updatedAt = rs.getLong("updated_at")
                    )
                )
            }
        }

        conn.close()
        return movies
    }

    private fun readOperations(path: String): List<Operation> {
        val conn = openConnection(path)
        val operations = mutableListOf<Operation>()

        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT operation_id, operation_type, timestamp FROM operations")
            while (rs.next()) {
                operations.add(
                    Operation(
                        id = rs.getString("operation_id"),
                        type = rs.getString("operation_type"),
                        timestamp = rs.getLong("timestamp")
                    )
                )
            }
        }

        conn.close()
        return operations
    }

    private fun readCatalogEntries(path: String): List<CatalogEntry> {
        val conn = openConnection(path)
        val entries = mutableListOf<CatalogEntry>()

        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT id, filename, path, size, modified_at FROM catalog")
            while (rs.next()) {
                entries.add(
                    CatalogEntry(
                        id = rs.getInt("id"),
                        filename = rs.getString("filename"),
                        path = rs.getString("path"),
                        size = rs.getLong("size"),
                        modifiedAt = rs.getLong("modified_at")
                    )
                )
            }
        }

        conn.close()
        return entries
    }

    private fun getTableNames(conn: Connection): List<String> {
        val tables = mutableListOf<String>()
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
            )
            while (rs.next()) {
                tables.add(rs.getString("name"))
            }
        }
        return tables
    }

    private fun getColumnNames(conn: Connection, tableName: String): List<String> {
        val columns = mutableListOf<String>()
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("PRAGMA table_info('$tableName')")
            while (rs.next()) {
                columns.add(rs.getString("name"))
            }
        }
        return columns
    }

    data class Movie(val id: Int, val title: String, val year: Int, val updatedAt: Long)
    data class Operation(val id: String, val type: String, val timestamp: Long)
    data class CatalogEntry(val id: Int, val filename: String, val path: String, val size: Long, val modifiedAt: Long)
    data class SimpleRecord(val id: Int, val data: String)
}

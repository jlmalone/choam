package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import mu.KotlinLogging
import java.io.File
import java.sql.DriverManager

private val logger = KotlinLogging.logger {}

class CatalogPurgeCommand : CliktCommand(
    name = "catalog-purge",
    help = """
        Remove junk entries from the unified registry and reclaim disk space via VACUUM.

        Deletes rows matching macOS metadata patterns (.DS_Store, .Spotlight-V100, .fseventsd, .Trashes, ._* resource forks, *.tmp, *.part) from ~/.choam/unified_registry.db. Processes in 100K-row batches, then runs SQLite VACUUM to shrink the file.

        Key behaviors:
          - Applies the same exclude patterns used by rebuild-index
          - Matches both filename and directory path segments
          - Shows before/after DB size and row counts
          - Reminds you to run 'choam rebuild-index' afterward to refresh FTS5 search

        Safety: Destructive — permanently removes rows from unified_registry.db. Does NOT affect per-machine sietch_registry.db files. Re-running catalog-sync will restore purged rows if they still exist on remotes.

        Examples:
          choam catalog-purge
    """.trimIndent()
) {
    override fun run() {
        val home = System.getProperty("user.home")
        val unifiedDbPath = "$home/.choam/unified_registry.db"
        val unifiedFile = File(unifiedDbPath)

        if (!unifiedFile.exists()) {
            echo("No unified registry at $unifiedDbPath — nothing to purge.", err = true)
            return
        }

        val sizeBefore = unifiedFile.length()

        echo("Catalog Purge")
        echo("  Database: $unifiedDbPath (${sizeBefore / (1024 * 1024)}MB)")
        echo("  Patterns: .DS_Store, Thumbs.db, ._*, *.tmp, *.part, .Spotlight-V100/, .fseventsd/, .Trashes/, .TemporaryItems/, .DocumentRevisions-V100/")
        echo()

        val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedDbPath")
        val stmt = conn.createStatement()
        stmt.executeUpdate("PRAGMA journal_mode=WAL")

        // Count total rows
        val totalRs = stmt.executeQuery("SELECT COUNT(*) FROM content_locations")
        totalRs.next()
        val totalBefore = totalRs.getLong(1)
        totalRs.close()

        // Build SQL WHERE clause for bulk deletion — much faster than row-by-row
        // Filename patterns: .DS_Store, Thumbs.db, ._*, *.tmp, *.part
        // Directory patterns: .Spotlight-V100, .fseventsd, .Trashes, .TemporaryItems, .DocumentRevisions-V100
        val filenameClauses = listOf(
            "file_path LIKE '%/.DS_Store'",
            "file_path LIKE '%/Thumbs.db'",
            "file_path LIKE '%/._%'",       // ._* resource forks
            "file_path LIKE '%.tmp'",
            "file_path LIKE '%.part'"
        )
        val dirClauses = listOf(
            "file_path LIKE '%/.Spotlight-V100/%'",
            "file_path LIKE '%/.fseventsd/%'",
            "file_path LIKE '%/.Trashes/%'",
            "file_path LIKE '%/.TemporaryItems/%'",
            "file_path LIKE '%/.DocumentRevisions-V100/%'"
        )
        val whereClause = (filenameClauses + dirClauses).joinToString(" OR ")

        // Count matching junk rows
        val countRs = stmt.executeQuery("SELECT COUNT(*) FROM content_locations WHERE $whereClause")
        countRs.next()
        val junkCount = countRs.getLong(1)
        countRs.close()

        if (junkCount == 0L) {
            echo("No junk rows found. Registry is clean.")
            conn.close()
            return
        }

        echo("Found ${"%,d".format(junkCount)} junk rows out of ${"%,d".format(totalBefore)} total (${junkCount * 100 / totalBefore}%)")
        echo()

        // Bulk delete in a single statement
        echo("Deleting...")
        val deleted = stmt.executeUpdate("DELETE FROM content_locations WHERE $whereClause").toLong()

        val totalAfter = totalBefore - deleted
        echo("  Deleted ${"%,d".format(deleted)} rows. ${"%,d".format(totalAfter)} rows remain.")
        echo()

        // VACUUM to reclaim disk space
        echo("Vacuuming...")
        stmt.executeUpdate("VACUUM")
        stmt.close()
        conn.close()

        val sizeAfter = File(unifiedDbPath).length()
        echo("  ${sizeBefore / (1024 * 1024)}MB → ${sizeAfter / (1024 * 1024)}MB (saved ${(sizeBefore - sizeAfter) / (1024 * 1024)}MB)")
        echo()
        echo("Purge complete. Run 'choam rebuild-index' to refresh the search index.")
    }
}

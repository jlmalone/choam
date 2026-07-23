package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import vision.salient.choam.catalog.CatalogIndex
import vision.salient.choam.catalog.SearchFilters
import vision.salient.choam.catalog.SearchResult
import vision.salient.sietch.core.formatSize
import java.io.File
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class GlobalSearchCommand : CliktCommand(
    name = "search",
    help = """
        Full-text search for files across all synced machines using the FTS5 catalog index.

        Queries ~/.choam/catalog-index.db built by catalog-sync or rebuild-index. Results are grouped by machine and drive, showing filename, size, CID, and full path. Stale machines (>30 days since last sync) are flagged.

        Key behaviors:
          - Uses SQLite FTS5 for fast substring and keyword matching
          - Groups results by machine, then by drive label
          - Marks machines as [stale] if last sync was over 30 days ago
          - Run 'choam catalog-sync' first to populate the index

        Safety: Read-only. Queries local index only — no network access.

        Examples:
          choam search "Aliens"
          choam search "mkv" --machine server --limit 10
          choam search --ext mkv,mp4 --min-size 1073741824
          choam search --cid QmABC123
          choam search "backup" --after 2026-01-01 --path "*/tv/*"
    """.trimIndent()
) {
    private val query by argument(help = "Search term — filename, extension, or partial path to match (optional with filters)").default("")
    private val machine by option("--machine", "-m", help = "Show results only from this machine name")
    private val limit by option("--limit", "-n", help = "Maximum number of results to return").default("50")

    // Advanced filters
    private val minSize by option("--min-size", help = "Minimum file size in bytes (e.g. 1073741824 for 1GB+)")
    private val maxSize by option("--max-size", help = "Maximum file size in bytes")
    private val ext by option("--ext", help = "Comma-separated extension filter (e.g. mkv,mp4,avi)")
    private val after by option("--after", help = "Only files cataloged after this date (YYYY-MM-DD)")
    private val before by option("--before", help = "Only files cataloged before this date (YYYY-MM-DD)")
    private val cid by option("--cid", help = "Exact CID lookup — returns all locations for that content")
    private val pathGlob by option("--path", help = "Glob pattern on full path (e.g. \"*/tv/*\")")

    override fun run() {
        val indexDbPath = "${System.getProperty("user.home")}/.choam/catalog-index.db"
        val indexDbFile = File(indexDbPath)

        if (!indexDbFile.exists()) {
            echo("No search index exists yet.", err = true)
            echo("Run 'choam catalog-sync' to sync remote catalogs and build the index.", err = true)
            return
        }

        val filters = buildFilters()

        if (query.isBlank() && !filters.hasAny) {
            echo("Provide a search term or at least one filter (--ext, --min-size, --cid, etc.)", err = true)
            return
        }

        val catalogIndex = CatalogIndex(indexDbPath)
        val conn = catalogIndex.open()

        val results = if (filters.hasAny) {
            catalogIndex.advancedSearch(conn, query, filters, limit.toInt())
        } else {
            catalogIndex.search(conn, query, limit.toInt())
        }

        if (results.isEmpty()) {
            echo("No results for: ${describeSearch()}")
            conn.close()
            return
        }

        // Filter by machine if specified
        val filtered = if (machine != null) {
            results.filter { it.machine.equals(machine, ignoreCase = true) }
        } else {
            results
        }

        if (filtered.isEmpty()) {
            echo("No results for ${describeSearch()} on machine '$machine'")
            echo("(${results.size} results exist on other machines)")
            conn.close()
            return
        }

        // Check for staleness info from unified registry
        val stalenessMap = loadStalenessMap()

        echo("${filtered.size} results for ${describeSearch()}:")
        echo()

        // Group by machine > drive_label
        val grouped = filtered.groupBy { it.machine }
        for ((machineName, machineResults) in grouped) {
            val staleMarker = stalenessMap[machineName]?.let { lastSync ->
                if (isStale(lastSync)) " \u001b[33m[stale]\u001b[0m" else ""
            } ?: ""

            echo("\u001b[1m$machineName\u001b[0m$staleMarker")

            val byDrive = machineResults.groupBy { it.driveLabel }
            for ((driveLabel, driveResults) in byDrive) {
                echo("  $driveLabel:")
                for (r in driveResults) {
                    echo("    ${r.filename}  ${formatSize(r.size).padStart(10)}")
                    echo("      ${r.path}")
                    if (r.cid.isNotEmpty()) {
                        echo("      CID:  ${r.cid}")
                        echo("      IPFS: https://ipfs.io/ipfs/${r.cid}")
                    }
                }
            }
            echo()
        }
        conn.close()
    }

    private fun buildFilters(): SearchFilters {
        val extensions = ext?.split(",")?.map { it.trim().lowercase().removePrefix(".") }?.filter { it.isNotEmpty() }
        val globLike = pathGlob?.let { globToSqlLike(it) }

        return SearchFilters(
            minSize = minSize?.toLongOrNull(),
            maxSize = maxSize?.toLongOrNull(),
            extensions = extensions,
            after = after,
            before = before,
            cid = cid,
            pathGlob = globLike
        )
    }

    private fun describeSearch(): String {
        val parts = mutableListOf<String>()
        if (query.isNotBlank()) parts.add("\"$query\"")
        ext?.let { parts.add("ext:$it") }
        minSize?.let { parts.add("min:${formatSize(it.toLong())}") }
        maxSize?.let { parts.add("max:${formatSize(it.toLong())}") }
        after?.let { parts.add("after:$it") }
        before?.let { parts.add("before:$it") }
        cid?.let { parts.add("cid:$it") }
        pathGlob?.let { parts.add("path:$it") }
        return parts.joinToString(" ")
    }

    private fun loadStalenessMap(): Map<String, String> {
        val unifiedDbPath = "${System.getProperty("user.home")}/.choam/unified_registry.db"
        val file = File(unifiedDbPath)
        if (!file.exists()) return emptyMap()

        return try {
            val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedDbPath")
            val rs = conn.createStatement().executeQuery(
                "SELECT machine_name, MAX(last_synced_at) as last_sync FROM content_locations GROUP BY machine_name"
            )
            val map = mutableMapOf<String, String>()
            while (rs.next()) {
                val name = rs.getString("machine_name")
                val lastSync = rs.getString("last_sync")
                if (lastSync != null) map[name] = lastSync
            }
            rs.close()
            conn.close()
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    companion object {
        private const val STALE_DAYS = 30L

        fun isStale(lastSyncedAt: String): Boolean {
            return try {
                val syncTime = LocalDateTime.parse(lastSyncedAt, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                val daysSince = ChronoUnit.DAYS.between(syncTime, LocalDateTime.now())
                daysSince > STALE_DAYS
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Convert a user-facing glob pattern to SQL LIKE syntax.
         * * → %  ,  ? → _  ,  literal % and _ escaped
         */
        fun globToSqlLike(glob: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < glob.length) {
                when (glob[i]) {
                    '*' -> sb.append('%')
                    '?' -> sb.append('_')
                    '%' -> sb.append("\\%")
                    '_' -> sb.append("\\_")
                    else -> sb.append(glob[i])
                }
                i++
            }
            return sb.toString()
        }
    }
}

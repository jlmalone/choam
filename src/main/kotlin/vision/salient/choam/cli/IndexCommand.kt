package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import vision.salient.choam.catalog.CatalogIndex
import vision.salient.sietch.core.formatSize
import java.io.File

private fun defaultDbPath(): String =
    "${System.getProperty("user.home")}/.choam/catalog-index.db"

class IndexCommand : CliktCommand(
    name = "index",
    help = """
        Manage the local file catalog index at ~/.choam/catalog-index.db.

        Without a subcommand, shows index statistics: database size, total files indexed, and per-source breakdown (drive, machine, file count, total size, catalog date, hash algorithm).

        Subcommands: ingest, search, duplicates, at-risk.

        Safety: Read-only when invoked without subcommands.

        Examples:
          choam index
          choam index ingest ./catalog.txt --drive movies-4tb --machine desktop
          choam index search "mp4"
          choam index duplicates --min-size 1048576
          choam index at-risk --min-size 104857600
    """.trimIndent(),
    invokeWithoutSubcommand = true
) {
    override fun run() {
        if (currentContext.invokedSubcommand != null) return

        val index = CatalogIndex(defaultDbPath())
        val dbFile = File(defaultDbPath())
        if (!dbFile.exists()) {
            echo("No index exists yet. Ingest catalogs with: choam index ingest")
            return
        }
        val conn = index.open()
        val stats = index.stats(conn)

        echo("CHOAM Catalog Index: ${dbFile.absolutePath}")
        echo("Database size: ${formatSize(dbFile.length())}")
        echo("Total files indexed: ${"%,d".format(stats.totalFiles)}")
        echo()
        echo("Sources:")
        for (s in stats.sources) {
            echo("  ${s.driveLabel.padEnd(20)} ${s.machine.padEnd(16)} ${"%,9d".format(s.fileCount)} files  ${formatSize(s.totalSize).padStart(10)}  (${s.catalogDate}, ${s.hashAlgorithm})")
        }
        conn.close()
    }
}

class IngestCommand : CliktCommand(
    name = "ingest",
    help = """
        Import a Sietch catalog text file into the local index database.

        Parses the catalog file and inserts all entries into ~/.choam/catalog-index.db. Auto-detects CID format (with IPFS content identifiers) vs legacy format (path/hash/size). Drive label defaults to the filename stem if not specified.

        Key behaviors:
          - Auto-detects format by checking for '# Machine:' header lines
          - Strips date and format suffixes from filename to derive drive label
          - Reports file count, total size, and time elapsed after ingest

        Safety: Additive — inserts into the index DB. Does not modify source catalog files.

        Examples:
          choam index ingest ./my-ext-drive-cid-2026-02-20.txt
          choam index ingest ./catalog.txt --drive movies-4tb --machine server
          choam index ingest ./catalog.txt --cid
    """.trimIndent()
) {
    private val catalogFile by argument(help = "Path to a Sietch catalog .txt file to import")
    private val driveLabel by option("--drive", "-d", help = "Drive label to tag entries with (e.g. movies-4tb). Defaults to catalog filename stem").default("")
    private val machine by option("--machine", "-m", help = "Machine name to tag entries with (e.g. desktop, server)").default("")
    private val cidFormat by option("--cid", help = "Force CID format parsing (path/cid/sha256/size) instead of auto-detection").flag()

    override fun run() {
        val file = File(catalogFile)
        if (!file.exists()) {
            echo("Error: $catalogFile not found", err = true)
            return
        }

        val effectiveDrive = driveLabel.ifEmpty {
            file.nameWithoutExtension
                .replace("-nohash", "").replace("-full", "").replace("-cid", "")
                .replace(Regex("-\\d{8}$"), "")
                .ifEmpty { "unknown" }
        }
        val effectiveMachine = machine.ifEmpty { "unknown" }

        // Auto-detect CID format by checking header
        val isCid = cidFormat || file.useLines { lines ->
            lines.any { it.startsWith("# Machine:") }
        }

        val index = CatalogIndex(defaultDbPath())

        echo("Ingesting: ${file.name}")
        echo("  Drive:   $effectiveDrive")
        echo("  Machine: $effectiveMachine")
        echo("  Format:  ${if (isCid) "CID catalog" else "legacy catalog"}")

        val start = System.currentTimeMillis()
        val stats = if (isCid) {
            index.ingestCidCatalog(file, effectiveDrive, effectiveMachine)
        } else {
            index.ingest(file, effectiveDrive, effectiveMachine)
        }
        val elapsed = System.currentTimeMillis() - start

        echo("  Files:   ${"%,d".format(stats.fileCount)}")
        echo("  Size:    ${formatSize(stats.totalSize)}")
        echo("  Time:    ${elapsed / 1000}s")
        echo()
        echo("Total index: ${"%,d".format(stats.totalFiles)} files across ${stats.sourceCount} sources")
    }
}

class SearchCommand : CliktCommand(
    name = "search",
    help = """
        Search the local index for files matching a query across all indexed drives.

        Uses FTS5 full-text search on the catalog-index.db. Results show filename, size, drive label, machine, CID, and full path. This is the index subcommand variant — the top-level 'choam search' also works.

        Safety: Read-only. Queries local index only.

        Examples:
          choam index search "Aliens"
          choam index search "mkv" --limit 10
    """.trimIndent()
) {
    private val query by argument(help = "Search term — filename, extension, or partial path to match")
    private val limit by option("--limit", "-n", help = "Maximum number of results to return").default("30")

    override fun run() {
        val dbFile = File(defaultDbPath())
        if (!dbFile.exists()) {
            echo("No index exists. Run 'choam index ingest' first.", err = true)
            return
        }

        val index = CatalogIndex(defaultDbPath())
        val conn = index.open()
        val results = index.search(conn, query, limit.toInt())

        if (results.isEmpty()) {
            echo("No results for: $query")
            conn.close()
            return
        }

        echo("${results.size} results for \"$query\":")
        echo()
        for (r in results) {
            echo("  ${r.filename}")
            val cidInfo = if (r.cid.isNotEmpty()) "  cid:${r.cid.take(16)}..." else ""
            echo("    ${formatSize(r.size).padStart(10)}  ${r.driveLabel} (${r.machine})$cidInfo")
            echo("    ${r.path}")
            echo()
        }
        conn.close()
    }
}

class DuplicatesCommand : CliktCommand(
    name = "duplicates",
    help = """
        Find files that exist on multiple drives, useful for identifying redundant copies.

        Prefers CID-based deduplication (exact content match via IPFS hash) when available, falling back to filename+size matching. Shows each duplicated file with its size and which drives hold copies. Limited to first 50 results.

        Key behaviors:
          - CID match is content-identical; filename+size match may have false positives
          - Filters by minimum file size to skip small files
          - Default minimum: 1MB (1048576 bytes)

        Safety: Read-only. Queries local index only — does not delete anything.

        Examples:
          choam index duplicates
          choam index duplicates --min-size 104857600
    """.trimIndent()
) {
    private val minSize by option("--min-size", help = "Minimum file size in bytes to consider (default: 1MB / 1048576)").default("1048576")

    override fun run() {
        val index = CatalogIndex(defaultDbPath())
        val conn = index.open()

        // Prefer CID-based deduplication if available, fall back to filename+size
        val cidDupes = index.findCidDuplicates(conn, minSize.toLong())
        val dupes = cidDupes.ifEmpty { index.findDuplicates(conn, minSize.toLong()) }
        val method = if (cidDupes.isNotEmpty()) "CID" else "filename+size"

        if (dupes.isEmpty()) {
            echo("No duplicates found above ${formatSize(minSize.toLong())}")
            conn.close()
            return
        }

        echo("${dupes.size} files duplicated across drives by $method (>${formatSize(minSize.toLong())}):")
        echo()
        for (d in dupes.take(50)) {
            echo("  ${d.filename}")
            echo("    ${formatSize(d.size).padStart(10)}  on ${d.driveCount} drives: ${d.drives}")
        }
        if (dupes.size > 50) echo("  ... and ${dupes.size - 50} more")
        conn.close()
    }
}

class AtRiskCommand : CliktCommand(
    name = "at-risk",
    help = """
        Identify large files that exist on only one drive — candidates for backup.

        Finds files with no redundant copies across your drives. These are at risk of data loss if that single drive fails. Prefers CID-based matching (exact content) when available, falling back to hash-based matching.

        Key behaviors:
          - Default minimum: 100MB (104857600 bytes)
          - Shows filename, size, and the single drive/machine holding the file
          - Limited to first 50 results

        Safety: Read-only. Queries local index only — does not move or copy files.

        Examples:
          choam index at-risk
          choam index at-risk --min-size 1073741824
    """.trimIndent()
) {
    private val minSize by option("--min-size", help = "Minimum file size in bytes to consider (default: 100MB / 104857600)").default("104857600")

    override fun run() {
        val index = CatalogIndex(defaultDbPath())
        val conn = index.open()

        val cidRisk = index.findCidSingleCopy(conn, minSize.toLong())
        val risk = cidRisk.ifEmpty { index.findSingleCopyFiles(conn, minSize.toLong()) }
        val method = if (cidRisk.isNotEmpty()) "CID" else "hash"

        if (risk.isEmpty()) {
            echo("No single-copy files found above ${formatSize(minSize.toLong())}")
            conn.close()
            return
        }

        echo("${risk.size} files on only one drive by $method (>${formatSize(minSize.toLong())}):")
        echo()
        for (r in risk.take(50)) {
            echo("  ${r.filename}")
            echo("    ${formatSize(r.size).padStart(10)}  only on: ${r.driveLabel} (${r.machine})")
        }
        if (risk.size > 50) echo("  ... and ${risk.size - 50} more")
        conn.close()
    }
}

fun indexCommand(): IndexCommand = IndexCommand().subcommands(
    IngestCommand(),
    SearchCommand(),
    DuplicatesCommand(),
    AtRiskCommand()
)

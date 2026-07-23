package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long
import mu.KotlinLogging
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.sync.*
import java.io.File
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * choam db-transfer — Database-aware chunked transfer.
 *
 * Splits a large SQLite database into standalone sub-databases by rowid range,
 * transfers each via choam send, and optionally shrinks the source after each
 * verified chunk.
 *
 * Usage:
 *   choam db-transfer ~/data/big.db external-store:/backup/         # plan + execute
 *   choam db-transfer ~/data/big.db external-store:/backup/ --plan  # dry run
 *   choam db-transfer ~/data/big.db external-store:/backup/ --shrink
 *   choam db-transfer ~/data/big.db external-store:/backup/ --resume
 */
class DbTransferCommand : CliktCommand(
    name = "db-transfer",
    help = "Transfer a large SQLite database by splitting into standalone sub-database chunks"
) {
    private val sourceDb by argument(help = "Path to source SQLite database")
    private val destination by argument(help = "Destination: DRIVE:/path/ or machine:/path/")

    private val chunkSize by option("--chunk-size", help = "Target chunk size in MB (default: 1024)")
        .long().default(1024)
    private val tables by option("--tables", help = "Comma-separated table names (default: all)")
    private val planOnly by option("--plan", help = "Show transfer plan without executing").flag()
    private val shrink by option("--shrink", help = "DELETE transferred rows from source + VACUUM to reclaim space").flag()
    private val resume by option("--resume", help = "Resume a previously interrupted transfer").flag()

    private val config by lazy { ChoamConfigLoader.load() }

    override fun run() {
        val sourceFile = File(sourceDb)
        if (!sourceFile.exists()) {
            echo("ERROR: Source database not found: $sourceDb")
            return
        }
        if (!sourceFile.name.endsWith(".db") && !sourceFile.name.endsWith(".sqlite") && !sourceFile.name.endsWith(".sqlite3")) {
            echo("WARNING: Source doesn't look like a SQLite file: ${sourceFile.name}")
        }

        echo("Introspecting ${sourceFile.name}...")
        val includeTables = tables?.split(",")?.map { it.trim() }
        val tableInfos = SchemaIntrospector.introspect(sourceFile.absolutePath, includeTables)

        if (tableInfos.isEmpty()) {
            echo("No tables found in $sourceDb")
            return
        }

        val plan = ChunkPlanner.plan(tableInfos, chunkSize * 1024 * 1024)
        printPlan(plan)

        if (planOnly) {
            echo("\n--plan mode: no transfers executed.")
            return
        }

        executePlan(sourceFile, plan)
    }

    private fun printPlan(plan: TransferPlan) {
        echo("\n── Transfer Plan ──")
        echo("  Tables:")
        for (table in plan.tables) {
            val sizeStr = formatBytes(table.estimatedBytes)
            val pkStr = if (table.primaryKeyColumns.isNotEmpty())
                "PK: (${table.primaryKeyColumns.joinToString(", ")})" else "no PK"
            val autoStr = if (table.hasAutoIncrement) " AUTOINCREMENT" else ""
            val category = if (table.name in plan.smallTables) " [small → bundled]" else ""
            echo("    ${table.name}: ${table.rowCount} rows, $sizeStr, $pkStr$autoStr$category")
        }

        if (plan.chunks.isNotEmpty()) {
            echo("\n  Chunks: ${plan.chunks.size} × ~${formatBytes(chunkSize * 1024 * 1024)}")
            for (chunk in plan.chunks.take(5)) {
                echo("    ${chunk.table} chunk ${chunk.chunkIndex}: rows ${chunk.rowidStart}-${chunk.rowidEnd} (~${formatBytes(chunk.estimatedBytes)})")
            }
            if (plan.chunks.size > 5) {
                echo("    ... and ${plan.chunks.size - 5} more")
            }
        }

        if (plan.smallTables.isNotEmpty()) {
            echo("\n  Small tables (bundled): ${plan.smallTables.joinToString(", ")}")
        }

        echo("\n  Total: ${plan.totalRows} rows, ${formatBytes(plan.totalBytes)}")
        echo("  Destination: $destination")
    }

    private fun executePlan(sourceFile: File, plan: TransferPlan) {
        val chunksDir = File(sourceFile.parentFile, "${sourceFile.nameWithoutExtension}_chunks")
        chunksDir.mkdirs()

        val progressFile = File(chunksDir, "_progress.json")
        val existing = if (resume) DbTransferProgress.load(progressFile) else null
        val completedChunks = existing?.chunksCompleted?.toMutableSet() ?: mutableSetOf()
        val smallTablesDone = existing?.smallTablesTransferred ?: false

        val totalSteps = plan.chunks.size + (if (plan.smallTables.isNotEmpty()) 1 else 0)
        var step = 0
        var totalRowsExported = 0L

        echo("\n── Executing Transfer ──\n")

        // Step 0: small tables
        if (plan.smallTables.isNotEmpty() && !smallTablesDone) {
            step++
            val smallDbPath = File(chunksDir, "small_tables.db").absolutePath
            echo("[${step}/$totalSteps] Exporting ${plan.smallTables.size} small tables...")

            val result = DbChunkExporter.exportSmallTables(
                sourceFile.absolutePath, smallDbPath, plan.smallTables
            )
            totalRowsExported += result.rowsExported
            echo("  Exported ${result.rowsExported} rows")

            // Transfer via CHOAM send (inline, not queued)
            if (transferChunkFile(File(smallDbPath), "small_tables.db")) {
                File(smallDbPath).delete()
                echo("  Transferred + local chunk deleted")
                saveProgress(progressFile, sourceFile, completedChunks, true)
            } else {
                echo("  TRANSFER FAILED — chunk preserved at $smallDbPath")
                echo("  Re-run with --resume to continue.")
                return
            }
        } else if (smallTablesDone) {
            step++
            echo("[${step}/$totalSteps] Small tables already transferred, skipping")
        }

        // Steps 1-N: large table chunks
        for (chunk in plan.chunks) {
            step++
            val chunkId = "${chunk.table}_${chunk.chunkIndex}"

            if (chunk.chunkIndex in completedChunks) {
                echo("[${step}/$totalSteps] $chunkId already transferred, skipping")
                continue
            }

            val chunkFileName = "${chunk.table}_chunk_${String.format("%03d", chunk.chunkIndex)}.db"
            val chunkDbPath = File(chunksDir, chunkFileName).absolutePath

            echo("[${step}/$totalSteps] Exporting $chunkId (rows ${chunk.rowidStart}-${chunk.rowidEnd})...")

            val result = DbChunkExporter.exportChunk(sourceFile.absolutePath, chunkDbPath, chunk)
            totalRowsExported += result.rowsExported
            echo("  Exported ${result.rowsExported} rows (${formatBytes(File(chunkDbPath).length())})")

            // Transfer
            if (transferChunkFile(File(chunkDbPath), chunkFileName)) {
                File(chunkDbPath).delete()
                completedChunks.add(chunk.chunkIndex)
                saveProgress(progressFile, sourceFile, completedChunks, true)
                echo("  Transferred + local chunk deleted")

                // Optional shrink
                if (shrink) {
                    shrinkSource(sourceFile, chunk)
                }
            } else {
                echo("  TRANSFER FAILED — chunk preserved at $chunkDbPath")
                echo("  Re-run with --resume to continue.")
                return
            }

            echo("")
        }

        echo("══════════════════════════════════════")
        echo(" DB-TRANSFER COMPLETE")
        echo(" $totalRowsExported rows across $totalSteps chunks")
        echo(" Destination: $destination")
        echo("══════════════════════════════════════")

        if (!shrink) {
            echo("\nTo import on remote:")
            echo("  choam db-import <chunks-dir>/ --into <target.db>")
        }
    }

    // Lazily resolved destination (parsed once, reused for all chunks)
    private var resolvedMachine: vision.salient.choam.config.MachineProfile? = null
    private var resolvedPath: String = ""
    private var destinationResolved = false

    private fun resolveDestination(): Boolean {
        if (destinationResolved) return resolvedMachine != null

        val colonIdx = destination.indexOf(':')
        if (colonIdx < 1) {
            echo("Invalid destination format. Use MACHINE:/path/ or DRIVE:/path/")
            destinationResolved = true
            return false
        }

        val destTarget = destination.substring(0, colonIdx)
        resolvedPath = destination.substring(colonIdx + 1).ifEmpty { "/" }

        // Resolve: machine name → machine alias → drive label
        resolvedMachine = config.machines[destTarget]
            ?: config.machines[destTarget.lowercase()]
            ?: run {
                val drive = config.drives.values.find { it.label.equals(destTarget, ignoreCase = true) }
                if (drive != null) {
                    val machineEntry = config.machines.entries.find { (_, machine) ->
                        machine.repositories.values.any { it.startsWith("/Volumes/${drive.label}/") }
                    }
                    if (machineEntry != null) {
                        if (!resolvedPath.startsWith("/Volumes/${drive.label}")) {
                            resolvedPath = "/Volumes/${drive.label}/${resolvedPath.removePrefix("/")}"
                        }
                        machineEntry.value
                    } else null
                } else {
                    config.machines.values.find { m ->
                        m.name.equals(destTarget, ignoreCase = true) ||
                            m.aliases.any { it.equals(destTarget, ignoreCase = true) }
                    }
                }
            }

        destinationResolved = true
        if (resolvedMachine == null) {
            echo("Unknown destination '$destTarget'. Available: ${config.machines.keys.joinToString()}")
        }
        return resolvedMachine != null
    }

    /**
     * Transfer a chunk file using CHOAM's rsync engine.
     */
    private fun transferChunkFile(chunkFile: File, remoteName: String): Boolean {
        if (!resolveDestination()) return false

        val machine = resolvedMachine!!
        val engine = RsyncTransferEngine()
        val networkDetector = vision.salient.choam.network.NetworkDetector()
        val targetResolver = TargetResolver(config)
        val localMachine = targetResolver.findLocalMachine()

        val route = if (localMachine != null) {
            networkDetector.detectBestRoute(localMachine, machine)
        } else null

        val remotePath = "${resolvedPath.removeSuffix("/")}/$remoteName"

        val result = engine.transfer(
            sourcePath = chunkFile.absolutePath,
            targetPath = remotePath,
            targetMachine = machine,
            route = route
        )

        return when (result) {
            is vision.salient.choam.network.TransferResult.Success -> true
            is vision.salient.choam.network.TransferResult.Failure -> {
                echo("  rsync failed: ${result.message}")
                false
            }
        }
    }

    private fun shrinkSource(sourceFile: File, chunk: ChunkPlan) {
        echo("  Shrinking source (DELETE rows ${chunk.rowidStart}-${chunk.rowidEnd} from ${chunk.table})...")
        try {
            val conn = java.sql.DriverManager.getConnection("jdbc:sqlite:${sourceFile.absolutePath}?journal_mode=WAL&busy_timeout=5000")
            conn.use { c ->
                c.prepareStatement("DELETE FROM \"${chunk.table}\" WHERE rowid >= ? AND rowid <= ?").use { ps ->
                    ps.setLong(1, chunk.rowidStart)
                    ps.setLong(2, chunk.rowidEnd)
                    val deleted = ps.executeUpdate()
                    echo("  Deleted $deleted rows")
                }
                // Incremental vacuum to reclaim pages without full rewrite
                c.createStatement().execute("PRAGMA incremental_vacuum(1000)")
            }
        } catch (e: Exception) {
            echo("  WARNING: Shrink failed: ${e.message}")
            logger.warn(e) { "Shrink failed for ${chunk.table}" }
        }
    }

    private fun saveProgress(
        progressFile: File,
        sourceFile: File,
        completedChunks: Set<Int>,
        smallTablesDone: Boolean
    ) {
        DbTransferProgress.save(
            DbTransferProgress(
                sourceDbPath = sourceFile.absolutePath,
                destination = destination,
                chunksCompleted = completedChunks.sorted(),
                smallTablesTransferred = smallTablesDone,
                startedAt = java.time.Instant.now().toString(),
                lastUpdatedAt = java.time.Instant.now().toString()
            ),
            progressFile
        )
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "%.1f GB".format(bytes.toDouble() / (1024 * 1024 * 1024))
    }
}

/**
 * choam db-import — Import sub-database chunks into a target database.
 */
class DbImportCommand : CliktCommand(
    name = "db-import",
    help = "Import sub-database chunks into a target SQLite database"
) {
    private val chunksDir by argument(help = "Directory containing sub-database chunks")
    private val into by option("--into", help = "Target database path (required)").default("")

    override fun run() {
        if (into.isEmpty()) {
            echo("ERROR: --into <target.db> is required")
            return
        }

        val dir = File(chunksDir)
        if (!dir.isDirectory) {
            echo("ERROR: Not a directory: $chunksDir")
            return
        }

        val chunkFiles = dir.listFiles()
            ?.filter { it.extension == "db" && it.name != "_progress.json" }
            ?.sortedBy { it.name }
            ?: emptyList()

        if (chunkFiles.isEmpty()) {
            echo("No .db chunk files found in $chunksDir")
            return
        }

        echo("Importing ${chunkFiles.size} chunks into $into\n")

        var totalImported = 0L
        var totalSkipped = 0L

        for ((i, chunkFile) in chunkFiles.withIndex()) {
            echo("[${i + 1}/${chunkFiles.size}] ${chunkFile.name}...")
            val result = DbChunkImporter.import(chunkFile.absolutePath, into)
            totalImported += result.rowsImported
            totalSkipped += result.rowsSkipped
            echo("  ${result.rowsImported} imported, ${result.rowsSkipped} skipped")
        }

        echo("\n══════════════════════════════════════")
        echo(" IMPORT COMPLETE")
        echo(" $totalImported rows imported, $totalSkipped duplicates skipped")
        echo(" Target: $into")
        echo("══════════════════════════════════════")
    }
}

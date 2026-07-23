package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import vision.salient.choam.catalog.CatalogMerger
import vision.salient.choam.catalog.MergeConflictStrategy
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.dag.ConfigResolver
import java.io.File

class CatalogMergeCommand : CliktCommand(
    name = "catalog-merge",
    help = """
        Merge a source registry DB into the unified registry with conflict resolution.

        Unlike catalog-sync (which uses INSERT OR REPLACE and silently overwrites), this command tracks conflicts where two registries have different CIDs for the same file path and applies a configurable resolution strategy.

        Key behaviors:
          - Detects CID conflicts: same (machine, path) with different content hashes
          - Three strategies: newer-wins (default), keep-existing, incoming-wins
          - Reports conflict count and details with --verbose
          - Applies machine name aliases from config

        Use cases:
          - Merging a backup registry after corruption recovery
          - Combining registries from machines that scanned the same drive independently
          - Auditing divergence between two registry snapshots

        Safety: Modifies unified_registry.db. The source DB is read-only. Use --strategy keep-existing to avoid any overwrites.

        Examples:
          choam catalog-merge /path/to/backup_registry.db
          choam catalog-merge /path/to/other.db --strategy keep-existing --verbose
    """.trimIndent()
) {
    private val sourceDb by argument(help = "Path to the source registry DB to merge from")
    private val strategy by option("--strategy", "-s", help = "Conflict resolution: newer-wins (default), keep-existing, incoming-wins")
        .default("newer-wins")
    private val verbose by option("--verbose", "-v", help = "Show individual conflict details").flag()

    override fun run() {
        val config = try {
            ConfigResolver.resolve()
        } catch (e: Exception) {
            echo("Failed to load config: ${e.message}", err = true)
            return
        }

        val sourceFile = File(sourceDb)
        if (!sourceFile.exists()) {
            echo("Source DB not found: $sourceDb", err = true)
            return
        }

        val unifiedDbPath = "${System.getProperty("user.home")}/.choam/unified_registry.db"

        val machineNameMap = mutableMapOf<String, String>()
        for ((configKey, profile) in config.machines) {
            for (alias in profile.aliases) {
                machineNameMap[alias] = configKey
            }
        }

        val mergeStrategy = when (strategy.lowercase()) {
            "newer-wins", "newer" -> MergeConflictStrategy.NEWER_WINS
            "keep-existing", "keep" -> MergeConflictStrategy.KEEP_EXISTING
            "incoming-wins", "incoming" -> MergeConflictStrategy.INCOMING_WINS
            else -> {
                echo("Unknown strategy '$strategy'. Use: newer-wins, keep-existing, incoming-wins", err = true)
                return
            }
        }

        echo("Catalog Merge")
        echo("  Source: $sourceDb (${sourceFile.length() / (1024 * 1024)}MB)")
        echo("  Target: $unifiedDbPath")
        echo("  Strategy: $mergeStrategy")
        if (machineNameMap.isNotEmpty()) {
            echo("  Aliases: ${machineNameMap.entries.joinToString(", ") { "${it.key} → ${it.value}" }}")
        }
        echo()

        val result = CatalogMerger.merge(
            sourceDbPath = sourceDb,
            targetDbPath = unifiedDbPath,
            machineNameMap = machineNameMap,
            strategy = mergeStrategy,
            trackConflicts = true
        )

        echo("Merge complete:")
        echo("  Processed: ${"%,d".format(result.totalProcessed)} rows")
        echo("  Inserted:  ${"%,d".format(result.inserted)} new rows")
        echo("  Updated:   ${"%,d".format(result.updated)} existing rows")
        echo("  Skipped:   ${"%,d".format(result.skipped)} (kept existing)")
        echo("  Conflicts: ${"%,d".format(result.conflicts.size)}")

        if (result.hasConflicts && verbose) {
            echo()
            echo("Conflict details:")
            for (c in result.conflicts.take(50)) {
                echo("  ${c.filePath}")
                echo("    existing: ${c.existingCid.take(20)}... (${c.existingRegisteredAt})")
                echo("    incoming: ${c.incomingCid.take(20)}... (${c.incomingRegisteredAt})")
                echo("    → ${c.resolution}")
            }
            if (result.conflicts.size > 50) {
                echo("  ... and ${result.conflicts.size - 50} more")
            }
        }

        if (result.inserted > 0 || result.updated > 0) {
            echo()
            echo("Run 'choam rebuild-index' to refresh the search index.")
        }
    }
}

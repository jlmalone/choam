package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import vision.salient.choam.catalog.CatalogDiffer
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.dag.ConfigResolver
import vision.salient.sietch.core.formatSize
import java.io.File

class DiffCommand : CliktCommand(
    name = "diff",
    help = """
        Compare catalog contents between two machines by CID.

        Shows which files exist on one machine but not the other, based on content-addressed identifiers. Two files with the same CID are the same content regardless of path or filename.

        Key behaviors:
          - Matches by CID (content hash), not filename or path
          - Resolves machine name aliases from config
          - Shows summary counts + largest exclusive files per side
          - Use --min-size to focus on large files only

        Safety: Read-only. Queries local unified_registry.db only.

        Examples:
          choam diff server laptop
          choam diff server laptop --min-size 1073741824 --verbose
    """.trimIndent()
) {
    private val machineA by argument(help = "First machine name (config key)")
    private val machineB by argument(help = "Second machine name (config key)")
    private val minSize by option("--min-size", "-s", help = "Minimum file size in bytes to consider").default("0")
    private val verbose by option("--verbose", "-v", help = "List the largest exclusive files per machine").flag()
    private val limit by option("--limit", "-n", help = "Max exclusive files to show per side").default("50")

    override fun run() {
        val config = try {
            ConfigResolver.resolve()
        } catch (e: Exception) {
            echo("Failed to load config: ${e.message}", err = true)
            return
        }

        val unifiedDbPath = "${System.getProperty("user.home")}/.choam/unified_registry.db"
        if (!File(unifiedDbPath).exists()) {
            echo("No unified registry at $unifiedDbPath — run 'choam catalog-sync' first.", err = true)
            return
        }

        // Build alias map
        val machineNameMap = mutableMapOf<String, String>()
        for ((configKey, profile) in config.machines) {
            for (alias in profile.aliases) {
                machineNameMap[alias] = configKey
            }
        }

        echo("Comparing $machineA vs $machineB...")
        echo()

        val diff = CatalogDiffer.diffMachines(
            unifiedDbPath = unifiedDbPath,
            machineA = machineA,
            machineB = machineB,
            machineNameMap = machineNameMap,
            minSize = minSize.toLongOrNull() ?: 0,
            limit = limit.toIntOrNull() ?: 50
        )

        if (diff.totalA == 0L && diff.totalB == 0L) {
            echo("No catalog data found for '$machineA' or '$machineB'.")
            echo("Available machines can be seen with 'choam status'.")
            return
        }

        // Summary
        echo("  ${machineA.padEnd(20)} ${"%,d".format(diff.totalA)} unique CIDs")
        echo("  ${machineB.padEnd(20)} ${"%,d".format(diff.totalB)} unique CIDs")
        echo()
        echo("  Shared CIDs:       ${"%,d".format(diff.onBoth)}")
        echo("  Only on $machineA: ${"%,d".format(diff.totalA - diff.onBoth)} (${formatSize(diff.onlyOnASize)})")
        echo("  Only on $machineB: ${"%,d".format(diff.totalB - diff.onBoth)} (${formatSize(diff.onlyOnBSize)})")

        if (verbose || diff.onlyOnA.isNotEmpty() || diff.onlyOnB.isNotEmpty()) {
            if (diff.onlyOnA.isNotEmpty()) {
                echo()
                echo("  Largest files only on $machineA:")
                for (entry in diff.onlyOnA) {
                    echo("    ${formatSize(entry.fileSize).padStart(10)}  ${entry.filePath.substringAfterLast("/")}")
                    if (verbose) echo("               ${entry.filePath}")
                }
            }
            if (diff.onlyOnB.isNotEmpty()) {
                echo()
                echo("  Largest files only on $machineB:")
                for (entry in diff.onlyOnB) {
                    echo("    ${formatSize(entry.fileSize).padStart(10)}  ${entry.filePath.substringAfterLast("/")}")
                    if (verbose) echo("               ${entry.filePath}")
                }
            }
        }
    }
}

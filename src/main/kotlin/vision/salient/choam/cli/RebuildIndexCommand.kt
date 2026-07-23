package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import vision.salient.choam.catalog.CatalogIndex
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.dag.ConfigResolver
import java.io.File

class RebuildIndexCommand : CliktCommand(
    name = "rebuild-index",
    help = """
        Rebuild the FTS5 search index from the unified registry — no network access needed.

        Reads all entries from ~/.choam/unified_registry.db, filters out macOS junk files, applies machine name aliases from config, and rebuilds ~/.choam/catalog-index.db. Runs a sanity check search for 'mkv' afterward and prints per-source stats.

        Key behaviors:
          - Filters .DS_Store, .Spotlight-V100, .fseventsd, .Trashes, ._*, *.tmp, *.part
          - Applies machine name aliases (e.g. old hostnames -> config keys)
          - Completely rebuilds the index — safe to run repeatedly
          - Run after catalog-purge to reflect removed rows

        Safety: Overwrites ~/.choam/catalog-index.db. Does not touch the unified registry.

        Examples:
          choam rebuild-index
    """.trimIndent()
) {
    override fun run() {
        val config = try {
            ConfigResolver.resolve()
        } catch (e: Exception) {
            echo("Failed to load config: ${e.message}", err = true)
            return
        }

        val home = System.getProperty("user.home")
        val unifiedDbPath = "$home/.choam/unified_registry.db"
        val indexDbPath = "$home/.choam/catalog-index.db"

        if (!File(unifiedDbPath).exists()) {
            echo("No unified registry at $unifiedDbPath — run 'catalog-sync' first.", err = true)
            return
        }

        // Build machine name alias map from config
        val machineNameMap = mutableMapOf<String, String>()
        for ((configKey, profile) in config.machines) {
            for (alias in profile.aliases) {
                machineNameMap[alias] = configKey
            }
        }

        echo("Rebuilding search index from $unifiedDbPath...")
        if (machineNameMap.isNotEmpty()) {
            echo("  Aliases: ${machineNameMap.entries.joinToString(", ") { "${it.key} → ${it.value}" }}")
        }
        val start = System.currentTimeMillis()

        val catalogIndex = CatalogIndex(indexDbPath)
        val conn = catalogIndex.open()
        val count = catalogIndex.rebuildFromRegistry(conn, unifiedDbPath, config.drives, machineNameMap)
        val elapsed = (System.currentTimeMillis() - start) / 1000

        echo("Rebuilt: ${"%,d".format(count)} files in ${elapsed}s")

        // Quick search sanity check
        val testResults = catalogIndex.search(conn, "mkv", 5)
        echo("Sanity check — search 'mkv': ${testResults.size} results")
        testResults.forEach { echo("  ${it.machine}/${it.driveLabel}: ${it.filename}") }

        val stats = catalogIndex.stats(conn)
        echo("Index: ${stats.totalFiles} files across ${stats.sources.size} sources")
        stats.sources.forEach { echo("  ${it.machine}/${it.driveLabel}: ${"%,d".format(it.fileCount)} files") }

        conn.close()
    }
}

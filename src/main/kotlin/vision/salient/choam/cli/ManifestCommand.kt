package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.types.file
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import vision.salient.choam.catalog.DiffCalculator
import vision.salient.choam.catalog.RepositoryCatalog
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.dag.ConfigResolver
import vision.salient.choam.sync.SyncEngine

class ManifestCommand : CliktCommand(
    name = "manifest",
    help = """
        Build file manifests for a repository across machines and show the diff between them.

        Connects to each machine (or all configured machines), scans the repository directory, and computes a manifest of all files. Then compares the first machine's manifest against each subsequent one, showing new, modified, and deleted files.

        Key behaviors:
          - Uses the first machine as the base for diff comparisons
          - Requires at least two machines to produce a diff
          - Optionally writes a JSON summary to a file with --output
          - Applies exclude patterns from config to skip junk files

        Safety: Read-only on file systems. Optionally writes a JSON summary file.

        Examples:
          choam manifest media
          choam manifest media --machines desktop,laptop
          choam manifest archive --output /tmp/manifest.json
    """.trimIndent()
) {
    private val repository by argument(help = "Repository name to build manifests for (e.g. media, archive)")
    private val machines by option("--machines", "-m", help = "Comma-separated machine names to compare (default: all configured machines)").multiple()
    private val output by option("--output", "-o", help = "Write a JSON manifest summary to this file path").file()

    override fun run() {
        val config = ConfigResolver.resolve()
        val repoConfig = config.repositories[repository]
            ?: error("Unknown repository: $repository")

        val machineNames = if (machines.isEmpty()) {
            config.machines.keys.toList()
        } else {
            machines
        }

        val engine = SyncEngine(config, transferManager = vision.salient.choam.network.TransferManager(config), conflictResolver = vision.salient.choam.sync.ConflictResolver())
        val catalogs = mutableListOf<RepositoryCatalog>()

        runBlocking {
            for (name in machineNames) {
                val machine = config.machines[name] ?: continue
                val path = machine.repositories[repoConfig.name] ?: continue
                val manifests = engine.buildCatalog(
                    machine = machine,
                    repoPath = path,
                    excludePatterns = config.defaultSyncRules.excludePatterns
                )
                catalogs += RepositoryCatalog(
                    machineName = machine.name,
                    repositoryName = repoConfig.name,
                    manifests = manifests
                )
            }
        }

        if (catalogs.size < 2) {
            echo("Need at least two machines to compare manifests.")
            return
        }

        val diffCalculator = DiffCalculator()
        val base = catalogs.first()
        catalogs.drop(1).forEach { other ->
            val diff = diffCalculator.calculateDiff(base, other)
            echo("Diff: ${base.machineName} -> ${other.machineName}")
            echo("  New files: ${diff.newFiles.size}")
            echo("  Modified files: ${diff.modifiedFiles.size}")
            echo("  Deleted files: ${diff.deletedFiles.size}")
            echo()
        }

        output?.let { file ->
            val builder = StringBuilder()
            builder.append("[\n")
            catalogs.forEachIndexed { index, catalog ->
                builder.append("  {\n")
                builder.append("    \"machine\": \"${catalog.machineName}\",\n")
                builder.append("    \"repository\": \"${catalog.repositoryName}\",\n")
                builder.append("    \"files\": ${catalog.manifests.size}\n")
                builder.append("  }")
                if (index < catalogs.size - 1) {
                    builder.append(",")
                }
                builder.append("\n")
            }
            builder.append("]\n")

            Files.writeString(file.toPath(), builder.toString())
            echo("Wrote manifest summary to ${file.absolutePath}")
        }
    }
}

package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import vision.salient.choam.network.ProgressMonitor
import vision.salient.choam.sync.SyncHistoryStore

class HistoryCommand : CliktCommand(
    name = "history",
    help = """
        Display a log of past sync operations with status, file counts, and transfer sizes.

        Shows completed, failed, and cancelled syncs from the local history store. Each entry includes timestamp, repositories synced, source/target machines, bytes transferred, file count, and error count.

        Key behaviors:
          - Color-coded status symbols: green check (completed), red X (failed), yellow circle (cancelled)
          - Sorted by most recent first
          - Filterable by repository name
          - Defaults to last 10 entries

        Safety: Read-only. Reads from local sync history database.

        Examples:
          choam history
          choam history --repo media --last 5
          choam history --last 20
    """.trimIndent()
) {
    private val repo by option("--repo", help = "Show only syncs involving this repository name")
    private val last by option("--last", help = "Number of most recent entries to display").int().default(10)

    override fun run() {
        val store = SyncHistoryStore()
        val entries = store.query(repo = repo, last = last)

        if (entries.isEmpty()) {
            echo("No sync history found.")
            return
        }

        echo("CHOAM Sync History")
        echo()

        for (entry in entries) {
            val statusSymbol = when (entry.status) {
                "COMPLETED" -> "\u001b[32m✓\u001b[0m"
                "FAILED" -> "\u001b[31m✗\u001b[0m"
                "CANCELLED" -> "\u001b[33m⊘\u001b[0m"
                else -> "•"
            }

            val repos = entry.repositories.joinToString(", ")
            val bytes = ProgressMonitor.formatBytes(entry.bytesTransferred)
            val timestamp = entry.startTime.replace("T", " ").substringBefore(".")

            echo("$statusSymbol  $timestamp  $repos  ${entry.sourceMachine}→${entry.targetMachine}  " +
                "$bytes  ${entry.filesTransferred} files" +
                if (entry.errors > 0) "  \u001b[31m${entry.errors} errors\u001b[0m" else "")
        }
    }
}

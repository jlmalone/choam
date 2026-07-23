package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.dag.ConfigResolver
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Queue a migration request to copy a repository to a target machine.
 * Requests are persisted in unified_registry.db and fulfilled later via `choam fulfill`.
 */
class RequestCopyCommand : CliktCommand(
    name = "request-copy",
    help = """
        Queue a migration request to copy a repository to a target machine.

        Creates a pending copy request that will be executed when 'choam fulfill' is run
        and the target machine is reachable. Useful for planning transfers to machines
        that may not always be online.

        Key behaviors:
          - Validates repository and target machine exist in config
          - Stores request in unified_registry.db copy_requests table
          - Does not transfer files — use 'choam fulfill' to execute

        Safety: Only writes a queue entry. No files are transferred.

        Examples:
          choam request-copy film --to laptop
          choam request-copy tv --to server
    """.trimIndent()
) {
    private val repository by argument(help = "Repository name to copy")
    private val to by option("--to", help = "Target machine to copy to").required()

    override fun run() {
        val config = try {
            ConfigResolver.resolve()
        } catch (e: Exception) {
            echo("Failed to load CHOAM config: ${e.message}")
            return
        }

        // Validate repo exists
        if (!config.repositories.containsKey(repository)) {
            echo("Unknown repository '$repository'. Available: ${config.repositories.keys.joinToString()}")
            return
        }

        // Validate machine exists
        if (!config.machines.containsKey(to)) {
            echo("Unknown machine '$to'. Available: ${config.machines.keys.joinToString()}")
            return
        }

        // Validate target machine has the repo configured
        val targetMachine = config.machines[to]!!
        if (!targetMachine.repositories.containsKey(repository)) {
            echo("Machine '$to' does not have repository '$repository' configured.")
            echo("Add it to the machine's repositories in ~/.choam/config.json first.")
            return
        }

        val unifiedDbPath = "${System.getProperty("user.home")}/.choam/unified_registry.db"
        ensureUnifiedDb(unifiedDbPath)

        try {
            val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedDbPath")
            ensureCopyRequestsTable(conn)

            // Check for existing pending request
            val checkStmt = conn.prepareStatement(
                "SELECT id FROM copy_requests WHERE repository = ? AND target_machine = ? AND status = 'pending'"
            )
            checkStmt.setString(1, repository)
            checkStmt.setString(2, to)
            val existing = checkStmt.executeQuery()
            if (existing.next()) {
                echo("A pending request already exists to copy '$repository' to '$to' (id: ${existing.getInt("id")})")
                existing.close()
                checkStmt.close()
                conn.close()
                return
            }
            existing.close()
            checkStmt.close()

            val stmt = conn.prepareStatement(
                "INSERT INTO copy_requests (repository, target_machine) VALUES (?, ?)"
            )
            stmt.setString(1, repository)
            stmt.setString(2, to)
            stmt.executeUpdate()
            stmt.close()

            val idRs = conn.createStatement().executeQuery("SELECT last_insert_rowid()")
            idRs.next()
            val requestId = idRs.getInt(1)
            idRs.close()
            conn.close()

            echo("Queued: copy $repository to $to (request #$requestId)")
            echo("Run 'choam fulfill' when $to is reachable.")
        } catch (e: Exception) {
            echo("Error creating copy request: ${e.message}")
        }
    }

    companion object {
        fun ensureCopyRequestsTable(conn: Connection) {
            conn.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS copy_requests (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    repository TEXT NOT NULL,
                    target_machine TEXT NOT NULL,
                    requested_at TEXT NOT NULL DEFAULT (datetime('now')),
                    fulfilled_at TEXT,
                    status TEXT NOT NULL DEFAULT 'pending'
                )
            """)
        }

        private fun ensureUnifiedDb(path: String) {
            val file = File(path)
            if (!file.parentFile.exists()) {
                file.parentFile.mkdirs()
            }
            if (!file.exists()) {
                val conn = DriverManager.getConnection("jdbc:sqlite:$path")
                conn.close()
            }
        }
    }
}

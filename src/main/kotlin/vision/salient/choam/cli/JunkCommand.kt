package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import vision.salient.choam.lowPriority
import mu.KotlinLogging
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.dag.ConfigResolver
import vision.salient.choam.network.ProgressMonitor
import java.io.File
import java.net.InetAddress
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}
private val prettyJson = Json { prettyPrint = true }

/**
 * Two-phase deletion system. `junk` marks content for deletion (reversible),
 * `purge` permanently deletes all junked content (irreversible).
 */
class JunkParentCommand : CliktCommand(
    name = "junk",
    help = """
        Two-phase content deletion — mark files as junk, then purge when ready.

        The junk system provides safe deletion:
        1. 'choam junk mark <CID>' marks content for deletion (reversible)
        2. 'choam junk list' shows all marked content
        3. 'choam junk unjunk <CID>' removes the mark (content is safe again)
        4. 'choam junk purge' permanently deletes all marked content

        Safety: 'mark' and 'unjunk' are reversible. 'purge' is NOT — it deletes files
        from all reachable machines and removes them from the registry.

        Examples:
          choam junk mark QmABC123
          choam junk mark QmABC123 --reason "duplicate"
          choam junk list
          choam junk unjunk QmABC123
          choam junk purge
    """.trimIndent(),
    invokeWithoutSubcommand = true
) {
    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            // Default: show junk list
            JunkListSubcommand().parse(emptyList())
        }
    }
}

class JunkMarkSubcommand : CliktCommand(
    name = "mark",
    help = """
        Mark a CID for deletion. This is REVERSIBLE — use 'junk unjunk' to unmark.

        Looks up the CID in the unified registry, shows all locations where the content
        exists, and requires typing JUNK to confirm. Prevents duplicate marks.

        Key behaviors:
          - Shows filename, size, and all machine locations before confirming
          - Requires interactive JUNK confirmation (not scriptable by accident)
          - Rejects CIDs not found in the registry
          - Rejects CIDs already marked as junk

        Safety: Reversible. Content is NOT deleted — only marked for future purge.

        Examples:
          choam junk mark QmABC123
          choam junk mark QmABC123 --reason "duplicate of QmDEF456"
    """.trimIndent()
) {
    private val cidArg by argument("cid", help = "The CID to mark as junk")
    private val reason by option("--reason", "-r", help = "Why this content is being junked")

    override fun run() {
        val unifiedDbPath = "${System.getProperty("user.home")}/.choam/unified_registry.db"
        if (!File(unifiedDbPath).exists()) {
            echo("No unified registry found. Run 'choam catalog-sync' first.")
            return
        }

        try {
            val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedDbPath")
            ensureJunkTable(conn)

            // Look up CID in registry to show what will be junked
            val locations = lookupCid(conn, cidArg)
            if (locations.isEmpty()) {
                echo("CID '$cidArg' not found in the unified registry.")
                conn.close()
                return
            }

            // Check if already junked
            val checkStmt = conn.prepareStatement("SELECT cid FROM junk_list WHERE cid = ?")
            checkStmt.setString(1, cidArg)
            val existing = checkStmt.executeQuery()
            if (existing.next()) {
                echo("CID '$cidArg' is already marked as junk.")
                existing.close()
                checkStmt.close()
                conn.close()
                return
            }
            existing.close()
            checkStmt.close()

            // Show what will be junked
            echo("Content to mark as junk:")
            val sampleFile = locations.first()
            val filename = sampleFile.filePath.substringAfterLast("/")
            echo("  $filename (${ProgressMonitor.formatBytes(sampleFile.fileSize)})")
            echo("  Exists on: ${locations.map { it.machineName }.distinct().joinToString(", ")}")
            echo()

            // Require confirmation
            echo("Type JUNK to confirm: ", trailingNewline = false)
            val confirmation = readlnOrNull()?.trim()
            if (confirmation != "JUNK") {
                echo("Cancelled.")
                conn.close()
                return
            }

            val stmt = conn.prepareStatement(
                "INSERT INTO junk_list (cid, reason, file_sample) VALUES (?, ?, ?)"
            )
            stmt.setString(1, cidArg)
            stmt.setString(2, reason)
            stmt.setString(3, filename)
            stmt.executeUpdate()
            stmt.close()
            conn.close()

            echo("Marked as junk: $cidArg ($filename)")
            echo("Use 'choam junk unjunk $cidArg' to unmark, or 'choam junk purge' to delete.")
        } catch (e: Exception) {
            echo("Error marking junk: ${e.message}")
        }
    }
}

class JunkUnjunkSubcommand : CliktCommand(
    name = "unjunk",
    help = """
        Remove a CID from the junk list — content is safe again.

        Reverses a previous 'junk mark' operation. The CID will no longer be included
        in any future 'junk purge' run.

        Key behaviors:
          - Silently succeeds if CID was not in the junk list
          - No confirmation required (unjunking is always safe)

        Safety: Always safe. Protects content from deletion.

        Examples:
          choam junk unjunk QmABC123
    """.trimIndent()
) {
    private val cidArg by argument("cid", help = "The CID to remove from junk list")

    override fun run() {
        val unifiedDbPath = "${System.getProperty("user.home")}/.choam/unified_registry.db"
        if (!File(unifiedDbPath).exists()) {
            echo("No unified registry found.")
            return
        }

        try {
            val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedDbPath")
            ensureJunkTable(conn)

            val stmt = conn.prepareStatement("DELETE FROM junk_list WHERE cid = ?")
            stmt.setString(1, cidArg)
            val deleted = stmt.executeUpdate()
            stmt.close()
            conn.close()

            if (deleted > 0) {
                echo("Unjunked: $cidArg — content is safe.")
            } else {
                echo("CID '$cidArg' was not in the junk list.")
            }
        } catch (e: Exception) {
            echo("Error unjunking: ${e.message}")
        }
    }
}

class JunkListSubcommand : CliktCommand(
    name = "list",
    help = """
        Show all content currently marked as junk.

        Lists every CID in the junk list with its mark timestamp, reason (if provided),
        and a sample filename. Use this to review before running 'junk purge'.

        Key behaviors:
          - Shows CID (truncated), filename sample, mark date, and reason
          - Ordered by mark date (oldest first)
          - Reports total count at the end

        Safety: Read-only. No files or entries are modified.

        Examples:
          choam junk list
    """.trimIndent()
) {
    override fun run() {
        val unifiedDbPath = "${System.getProperty("user.home")}/.choam/unified_registry.db"
        if (!File(unifiedDbPath).exists()) {
            echo("No unified registry found.")
            return
        }

        try {
            val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedDbPath")
            ensureJunkTable(conn)

            val rs = conn.createStatement().executeQuery(
                "SELECT cid, marked_at, reason, file_sample FROM junk_list ORDER BY marked_at"
            )

            var count = 0
            while (rs.next()) {
                val cid = rs.getString("cid")
                val markedAt = rs.getString("marked_at")
                val reason = rs.getString("reason") ?: ""
                val fileSample = rs.getString("file_sample") ?: ""

                echo("  ${cid.take(20).padEnd(22)} $fileSample")
                echo("    Marked: $markedAt${if (reason.isNotEmpty()) "  Reason: $reason" else ""}")
                count++
            }
            rs.close()
            conn.close()

            if (count == 0) {
                echo("No content marked as junk.")
            } else {
                echo()
                echo("$count item(s) marked as junk. Run 'choam junk purge' to permanently delete.")
            }
        } catch (e: Exception) {
            echo("Error listing junk: ${e.message}")
        }
    }
}

class JunkPurgeSubcommand : CliktCommand(
    name = "purge",
    help = """
        PERMANENTLY delete all junked content from all reachable machines.

        This is IRREVERSIBLE. For each junk-listed CID:
        1. Deletes the file from all reachable machines (SSH rm for remote, local delete for local)
        2. Removes the entry from the content_locations registry
        3. Removes from the junk list
        4. Logs the action to ~/.choam/purge_history.jsonl

        Safety: DESTRUCTIVE and IRREVERSIBLE. Requires typing PURGE to confirm.

        Examples:
          choam junk purge
    """.trimIndent()
) {
    override fun run() {
        val config = try {
            ConfigResolver.resolve()
        } catch (e: Exception) {
            echo("Failed to load CHOAM config: ${e.message}")
            return
        }

        val unifiedDbPath = "${System.getProperty("user.home")}/.choam/unified_registry.db"
        if (!File(unifiedDbPath).exists()) {
            echo("No unified registry found.")
            return
        }

        try {
            val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedDbPath")
            ensureJunkTable(conn)

            // Load junk list
            val rs = conn.createStatement().executeQuery(
                "SELECT cid, marked_at, reason, file_sample FROM junk_list ORDER BY marked_at"
            )

            data class JunkEntry(val cid: String, val markedAt: String, val reason: String?, val fileSample: String?)
            val junkEntries = mutableListOf<JunkEntry>()
            while (rs.next()) {
                junkEntries.add(JunkEntry(
                    cid = rs.getString("cid"),
                    markedAt = rs.getString("marked_at"),
                    reason = rs.getString("reason"),
                    fileSample = rs.getString("file_sample")
                ))
            }
            rs.close()

            if (junkEntries.isEmpty()) {
                echo("Nothing to purge. Junk list is empty.")
                conn.close()
                return
            }

            echo("Items to PERMANENTLY DELETE:")
            echo()
            for (entry in junkEntries) {
                val locations = lookupCid(conn, entry.cid)
                val machines = locations.map { it.machineName }.distinct()
                echo("  ${entry.cid.take(20)}...  ${entry.fileSample ?: "unknown"}")
                echo("    Locations: ${machines.joinToString(", ")}")
                echo("    Marked: ${entry.markedAt}${if (entry.reason != null) "  Reason: ${entry.reason}" else ""}")
            }
            echo()
            echo("\u001b[31mThis action is IRREVERSIBLE.\u001b[0m")
            echo("Type PURGE to confirm: ", trailingNewline = false)
            val confirmation = readlnOrNull()?.trim()
            if (confirmation != "PURGE") {
                echo("Cancelled.")
                conn.close()
                return
            }

            // Build alias map
            val machineNameMap = mutableMapOf<String, String>()
            for ((configKey, profile) in config.machines) {
                machineNameMap[configKey] = configKey
                for (alias in profile.aliases) {
                    machineNameMap[alias] = configKey
                }
            }

            val purgeLogPath = "${System.getProperty("user.home")}/.choam/purge_history.jsonl"
            val purgeLogFile = File(purgeLogPath)
            purgeLogFile.parentFile?.mkdirs()

            var purgedFiles = 0
            var purgedCids = 0
            var errors = 0

            for (entry in junkEntries) {
                val locations = lookupCid(conn, entry.cid)
                val deletedLocations = mutableListOf<String>()

                for (loc in locations) {
                    val machineName = machineNameMap[loc.machineName] ?: loc.machineName
                    val machineProfile = config.machines[machineName]

                    if (machineProfile == null) {
                        echo("    Skipping ${loc.filePath} — machine '$machineName' not in config")
                        continue
                    }

                    val isLocal = try {
                        val hostname = InetAddress.getLocalHost().hostName
                        machineProfile.hostname == hostname || machineProfile.hostname.startsWith(hostname)
                    } catch (_: Exception) { false }

                    val deleted = if (isLocal) {
                        deleteLocal(loc.filePath)
                    } else {
                        deleteRemote(machineProfile, loc.filePath)
                    }

                    if (deleted) {
                        deletedLocations.add("${loc.machineName}:${loc.filePath}")
                        purgedFiles++
                    } else {
                        errors++
                    }
                }

                // Remove from content_locations
                val deleteLocStmt = conn.prepareStatement(
                    "DELETE FROM content_locations WHERE cid = ?"
                )
                deleteLocStmt.setString(1, entry.cid)
                deleteLocStmt.executeUpdate()
                deleteLocStmt.close()

                // Remove from junk_list
                val deleteJunkStmt = conn.prepareStatement(
                    "DELETE FROM junk_list WHERE cid = ?"
                )
                deleteJunkStmt.setString(1, entry.cid)
                deleteJunkStmt.executeUpdate()
                deleteJunkStmt.close()

                purgedCids++

                // Log to purge history
                val logEntry = PurgeLogEntry(
                    cid = entry.cid,
                    fileSample = entry.fileSample ?: "",
                    reason = entry.reason ?: "",
                    purgedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    deletedFrom = deletedLocations
                )
                purgeLogFile.appendText(prettyJson.encodeToString(logEntry) + "\n")

                echo("  Purged: ${entry.cid.take(20)}... (${deletedLocations.size} locations)")
            }

            conn.close()

            echo()
            echo("Purge complete: $purgedCids CIDs, $purgedFiles files deleted, $errors errors")
            echo("History logged to: $purgeLogPath")
        } catch (e: Exception) {
            echo("Error during purge: ${e.message}")
            logger.error(e) { "Purge failed" }
        }
    }

    private fun deleteLocal(filePath: String): Boolean {
        val file = File(filePath)
        return if (file.exists()) {
            val deleted = file.delete()
            if (deleted) {
                logger.info { "Deleted local: $filePath" }
            } else {
                logger.warn { "Failed to delete local: $filePath" }
            }
            deleted
        } else {
            logger.info { "Already missing: $filePath" }
            true // Already gone
        }
    }

    private fun deleteRemote(
        machine: vision.salient.choam.config.MachineProfile,
        filePath: String
    ): Boolean {
        val ip = machine.tailscaleIp ?: machine.hostname
        val user = machine.sshUser ?: return false
        val port = machine.sshPort

        return try {
            val reachable = InetAddress.getByName(ip).isReachable(5000)
            if (!reachable) {
                logger.info { "Machine ${machine.name} unreachable, skipping delete of $filePath" }
                return false
            }

            val cmd = lowPriority(listOf("ssh", "-p", "$port", "$user@$ip", "rm", "-f", filePath))
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                logger.info { "Deleted remote: $user@$ip:$filePath" }
                true
            } else {
                logger.warn { "Failed to delete remote $filePath on ${machine.name}: $output" }
                false
            }
        } catch (e: Exception) {
            logger.error(e) { "SSH delete failed for $filePath on ${machine.name}" }
            false
        }
    }
}

@Serializable
data class PurgeLogEntry(
    val cid: String,
    val fileSample: String,
    val reason: String,
    val purgedAt: String,
    val deletedFrom: List<String>
)

// Shared utilities for junk commands

data class CidLocation(
    val cid: String,
    val machineName: String,
    val filePath: String,
    val fileSize: Long
)

fun lookupCid(conn: Connection, cid: String): List<CidLocation> {
    val stmt = conn.prepareStatement(
        "SELECT cid, machine_name, file_path, file_size FROM content_locations WHERE cid = ?"
    )
    stmt.setString(1, cid)
    val rs = stmt.executeQuery()
    val results = mutableListOf<CidLocation>()
    while (rs.next()) {
        results.add(CidLocation(
            cid = rs.getString("cid"),
            machineName = rs.getString("machine_name"),
            filePath = rs.getString("file_path"),
            fileSize = rs.getLong("file_size")
        ))
    }
    rs.close()
    stmt.close()
    return results
}

fun ensureJunkTable(conn: Connection) {
    conn.createStatement().executeUpdate("""
        CREATE TABLE IF NOT EXISTS junk_list (
            cid TEXT PRIMARY KEY,
            marked_at TEXT NOT NULL DEFAULT (datetime('now')),
            reason TEXT,
            file_sample TEXT
        )
    """)
}

fun junkCommand(): JunkParentCommand = JunkParentCommand().subcommands(
    JunkMarkSubcommand(),
    JunkUnjunkSubcommand(),
    JunkListSubcommand(),
    JunkPurgeSubcommand()
)

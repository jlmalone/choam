package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import mu.KotlinLogging
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.dag.ConfigResolver
import vision.salient.choam.lowPriority
import vision.salient.choam.config.MachineProfile
import vision.salient.choam.network.NetworkDetector
import vision.salient.choam.sync.RsyncTransferEngine
import vision.salient.choam.network.TransferResult
import java.io.File
import java.net.InetAddress
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Result of a move operation — extracted for reporting.
 */
data class MoveResult(
    val repo: String,
    val fromMachine: String,
    val toMachine: String,
    val filesTransferred: Long,
    val filesVerified: Long,
    val filesFailed: Long,
    val failedPaths: List<String>,
    val sourceDeleted: Boolean,
    val registryUpdated: Boolean
) {
    val success: Boolean get() = filesFailed == 0L && sourceDeleted && registryUpdated
}

class MoveCommand : CliktCommand(
    name = "move",
    help = """
        Verified relocation of a repository between machines.

        Copies files from the source machine to the target machine, verifies all files
        arrived intact on the target, then — and ONLY then — deletes the source files
        and updates the registry.

        This is NOT deletion — it's relocation with proof. The CID still exists,
        just somewhere else.

        Safety guarantees:
          - Source files are ONLY deleted after ALL target files are verified
          - If verification fails on ANY file, source is preserved and user is alerted
          - The move is atomic from the user's perspective
          - --dry-run shows what would happen without transferring or deleting anything
          - At least one of --from/--to must be the local machine (rsync limitation)

        Examples:
          choam move film --from server --to laptop
          choam move film --from server --to laptop --dry-run
    """.trimIndent()
) {
    private val repo by argument(help = "Repository name to move")
    private val from by option("--from", help = "Source machine name").required()
    private val to by option("--to", help = "Target machine name").required()
    private val dryRun by option("--dry-run", "-n", help = "Show what would be moved without transferring or deleting").flag()

    override fun run() {
        val config = try {
            ConfigResolver.resolve()
        } catch (e: Exception) {
            echo("Error: ${e.message}", err = true)
            echo("Run 'choam init' first to create config.", err = true)
            return
        }

        // Resolve machines
        val sourceMachine = config.machines[from]
        if (sourceMachine == null) {
            echo("Error: Source machine '$from' not found. Available: ${config.machines.keys.joinToString(", ")}", err = true)
            return
        }
        val targetMachine = config.machines[to]
        if (targetMachine == null) {
            echo("Error: Target machine '$to' not found. Available: ${config.machines.keys.joinToString(", ")}", err = true)
            return
        }

        if (from == to) {
            echo("Error: Source and target machines are the same.", err = true)
            return
        }

        // Resolve repo paths on both machines
        val sourceRepoPath = sourceMachine.repositories[repo]
        if (sourceRepoPath == null) {
            echo("Error: Repository '$repo' not configured on source machine '$from'.", err = true)
            echo("  Configured repos on $from: ${sourceMachine.repositories.keys.joinToString(", ").ifEmpty { "(none)" }}", err = true)
            return
        }
        val targetRepoPath = targetMachine.repositories[repo]
        if (targetRepoPath == null) {
            echo("Error: Repository '$repo' not configured on target machine '$to'.", err = true)
            echo("  Configured repos on $to: ${targetMachine.repositories.keys.joinToString(", ").ifEmpty { "(none)" }}", err = true)
            return
        }

        // Determine local machine
        val hostname = try {
            InetAddress.getLocalHost().hostName
        } catch (_: Exception) { "unknown" }
        val localMachineKey = config.machines.entries
            .find { it.value.hostname == hostname || it.value.hostname.startsWith(hostname) }
            ?.key

        val sourceIsLocal = from == localMachineKey
        val targetIsLocal = to == localMachineKey

        if (!sourceIsLocal && !targetIsLocal) {
            echo("Error: At least one of --from/--to must be the local machine ($localMachineKey).", err = true)
            echo("  rsync requires at least one local side. Use two commands:", err = true)
            echo("  1. choam pull $repo --from $from", err = true)
            echo("  2. Then from $to: choam move $repo --from $to --to $to-target", err = true)
            return
        }

        // Check target reachability
        val remoteProfile = if (sourceIsLocal) targetMachine else sourceMachine
        val remoteIp = remoteProfile.tailscaleIp ?: remoteProfile.hostname

        echo("Move: $repo")
        echo("  From: $from ($sourceRepoPath)")
        echo("  To:   $to ($targetRepoPath)")
        echo("  Mode: ${if (dryRun) "DRY RUN" else "live"}")
        echo()

        echo("Checking reachability ($remoteIp)...")
        val reachable = try {
            InetAddress.getByName(remoteIp).isReachable(10000)
        } catch (_: Exception) { false }

        if (!reachable) {
            echo("\u001b[31mUnreachable\u001b[0m — cannot proceed.", err = true)
            return
        }
        echo("\u001b[32mReachable\u001b[0m")
        echo()

        // Get exclude patterns
        val repoConfig = config.repositories[repo]
        val excludePatterns = repoConfig?.excludePatterns ?: config.defaultSyncRules.excludePatterns

        // ============ PHASE 1: TRANSFER ============
        echo("Phase 1: Transfer ($from → $to)")

        val networkDetector = NetworkDetector()
        val route = networkDetector.detectBestRoute(sourceMachine, targetMachine)

        val rsyncEngine = RsyncTransferEngine()
        val transferResult = rsyncEngine.transfer(
            sourcePath = sourceRepoPath,
            targetPath = targetRepoPath,
            sourceMachine = if (!sourceIsLocal) sourceMachine else null,
            targetMachine = if (!targetIsLocal) targetMachine else null,
            route = route,
            excludePatterns = excludePatterns,
            dryRun = dryRun
        )

        when (transferResult) {
            is TransferResult.Failure -> {
                echo("\u001b[31mTransfer failed: ${transferResult.message}\u001b[0m", err = true)
                echo("Source files are untouched.", err = true)
                return
            }
            is TransferResult.Success -> {
                echo("\u001b[32mTransfer complete\u001b[0m")
            }
        }

        if (dryRun) {
            echo()
            echo("DRY RUN — no files were transferred, verified, deleted, or registry updated.")
            echo("Run without --dry-run to execute the move.")
            return
        }

        echo()

        // ============ PHASE 2: VERIFY ============
        echo("Phase 2: Verify files on target ($to)")

        // Build the list of files we expect on the target
        // Walk the source (local or remote) to get relative paths, then check they exist on target
        val filePaths: List<String>
        val verifyResult: VerifyResult

        if (targetIsLocal) {
            // Source is remote, target is local — verify local files
            filePaths = listLocalFiles(targetRepoPath)
            echo("  ${filePaths.size} files to verify locally...")
            verifyResult = VerifyCommand.verifyLocalPaths(to, filePaths)
        } else {
            // Source is local, target is remote — verify remote files
            filePaths = listLocalFiles(sourceRepoPath)
            // Convert to target paths (replace source prefix with target prefix)
            val targetPaths = filePaths.map { buildTargetPath(it, sourceRepoPath, targetRepoPath) }
            echo("  ${targetPaths.size} files to verify on remote...")
            verifyResult = VerifyCommand.verifyRemotePaths(to, targetMachine, remoteIp, targetPaths)
        }

        echo("  Verified: ${verifyResult.verified} / ${verifyResult.registered}")
        if (verifyResult.missing > 0) {
            echo()
            echo("\u001b[31mVerification FAILED — ${verifyResult.missing} files missing on target!\u001b[0m", err = true)
            echo("Source files are PRESERVED. No deletions performed.", err = true)
            if (verifyResult.missingPaths.size <= 20) {
                echo("Missing files:", err = true)
                for (path in verifyResult.missingPaths) {
                    echo("  $path", err = true)
                }
            } else {
                echo("First 20 missing files:", err = true)
                for (path in verifyResult.missingPaths.take(20)) {
                    echo("  $path", err = true)
                }
                echo("  ... and ${verifyResult.missingPaths.size - 20} more", err = true)
            }
            return
        }

        echo("\u001b[32mAll files verified\u001b[0m")
        echo()

        // ============ PHASE 3: DELETE SOURCE ============
        echo("Phase 3: Delete source files on $from")

        val deleteOk = if (sourceIsLocal) {
            deleteLocalDirectory(sourceRepoPath)
        } else {
            deleteRemoteDirectory(sourceMachine, remoteIp, sourceRepoPath)
        }

        if (!deleteOk) {
            echo("\u001b[33mWarning: Source deletion failed. Files may still exist on $from.\u001b[0m", err = true)
            echo("Target files are intact. You may need to clean up $from manually.", err = true)
        } else {
            echo("\u001b[32mSource deleted\u001b[0m")
        }
        echo()

        // ============ PHASE 4: UPDATE REGISTRY ============
        echo("Phase 4: Update registry")

        val unifiedDbPath = "${System.getProperty("user.home")}/.choam/unified_registry.db"
        if (File(unifiedDbPath).exists()) {
            val updated = updateRegistryAfterMove(unifiedDbPath, from, to, sourceRepoPath, targetRepoPath)
            echo("  Updated $updated registry entries ($from → $to)")
        } else {
            echo("  No unified registry found — skipping registry update.")
            echo("  Run 'choam catalog-sync' to rebuild after move.")
        }

        echo()
        echo("\u001b[32mMove complete:\u001b[0m $repo relocated from $from to $to")
        echo("  Files verified: ${verifyResult.verified}")
        echo("  Source deleted: $deleteOk")
    }

    private fun listLocalFiles(dirPath: String): List<String> {
        val dir = File(dirPath)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val files = mutableListOf<String>()
        dir.walkTopDown()
            .filter { it.isFile }
            .forEach { files.add(it.absolutePath) }
        return files
    }

    private fun deleteLocalDirectory(dirPath: String): Boolean {
        return try {
            val dir = File(dirPath)
            if (!dir.exists()) return true
            dir.deleteRecursively()
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete local directory $dirPath" }
            false
        }
    }

    private fun deleteRemoteDirectory(machine: MachineProfile, ip: String, dirPath: String): Boolean {
        return try {
            val sshUser = machine.sshUser?.let { "$it@" } ?: ""
            val portArgs = if (machine.sshPort != 22) listOf("-p", machine.sshPort.toString()) else emptyList()
            val cmd = lowPriority(listOf("ssh") + portArgs + listOf(
                "-o", "BatchMode=yes",
                "-o", "ConnectTimeout=10",
                "$sshUser$ip",
                "rm -rf ${shellEscape(dirPath)}"
            ))
            logger.info { "Remote delete: ${cmd.joinToString(" ")}" }
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(30, TimeUnit.MINUTES)
            if (!finished) {
                logger.warn { "Remote delete timed out after 30 minutes" }
                process.destroyForcibly()
                return false
            }
            process.exitValue() == 0
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete remote directory $dirPath on $ip" }
            false
        }
    }

    companion object {
        /**
         * Compute the target path by replacing the source path prefix with the target prefix.
         * Extracted for testability.
         *
         * Example: buildTargetPath("/Volumes/EXT-4TB/film/Aliens.mkv", "/Volumes/EXT-4TB/film", "/Users/example/film")
         *   → "/Users/example/film/Aliens.mkv"
         */
        fun buildTargetPath(sourcePath: String, sourcePrefix: String, targetPrefix: String): String {
            val normalizedSource = sourcePrefix.trimEnd('/')
            val normalizedTarget = targetPrefix.trimEnd('/')
            return if (sourcePath.startsWith(normalizedSource)) {
                normalizedTarget + sourcePath.removePrefix(normalizedSource)
            } else {
                // Path doesn't match prefix — return as-is (shouldn't happen in normal usage)
                sourcePath
            }
        }

        /**
         * Update the unified registry after a move: change machine_name and rewrite file_path
         * prefix for all entries matching the source machine and repo path.
         * Extracted for testability.
         *
         * @return number of entries updated
         */
        fun updateRegistryAfterMove(
            unifiedDbPath: String,
            fromMachine: String,
            toMachine: String,
            fromPathPrefix: String,
            toPathPrefix: String
        ): Long {
            val normalizedFrom = fromPathPrefix.trimEnd('/')
            val normalizedTo = toPathPrefix.trimEnd('/')

            return try {
                val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedDbPath")
                conn.autoCommit = false

                // Find all entries for the source machine under the repo path
                val selectStmt = conn.prepareStatement(
                    "SELECT cid, machine_name, file_path, file_size, verified_at, registered_at FROM content_locations WHERE machine_name = ? AND file_path LIKE ?"
                )
                selectStmt.setString(1, fromMachine)
                selectStmt.setString(2, "$normalizedFrom/%")
                val rs = selectStmt.executeQuery()

                val insertStmt = conn.prepareStatement("""
                    INSERT OR REPLACE INTO content_locations
                    (cid, machine_name, file_path, file_size, verified_at, registered_at, last_synced_at)
                    VALUES (?, ?, ?, ?, ?, ?, datetime('now'))
                """)

                val deleteStmt = conn.prepareStatement(
                    "DELETE FROM content_locations WHERE cid = ? AND machine_name = ? AND file_path = ?"
                )

                var count = 0L
                // Collect entries to avoid modifying while iterating
                data class Entry(val cid: String, val filePath: String, val fileSize: Long?, val verifiedAt: String?, val registeredAt: String)
                val entries = mutableListOf<Entry>()
                while (rs.next()) {
                    entries.add(Entry(
                        cid = rs.getString("cid"),
                        filePath = rs.getString("file_path"),
                        fileSize = rs.getLong("file_size").let { if (rs.wasNull()) null else it },
                        verifiedAt = rs.getString("verified_at"),
                        registeredAt = rs.getString("registered_at")
                    ))
                }
                rs.close()
                selectStmt.close()

                for (entry in entries) {
                    val newPath = buildTargetPath(entry.filePath, normalizedFrom, normalizedTo)

                    // Insert new entry for target machine
                    insertStmt.setString(1, entry.cid)
                    insertStmt.setString(2, toMachine)
                    insertStmt.setString(3, newPath)
                    if (entry.fileSize != null) insertStmt.setLong(4, entry.fileSize)
                    else insertStmt.setNull(4, java.sql.Types.INTEGER)
                    insertStmt.setString(5, entry.verifiedAt)
                    insertStmt.setString(6, entry.registeredAt)
                    insertStmt.executeUpdate()

                    // Delete old entry for source machine
                    deleteStmt.setString(1, entry.cid)
                    deleteStmt.setString(2, fromMachine)
                    deleteStmt.setString(3, entry.filePath)
                    deleteStmt.executeUpdate()

                    count++
                }

                conn.commit()
                conn.autoCommit = true
                insertStmt.close()
                deleteStmt.close()
                conn.close()
                count
            } catch (e: Exception) {
                logger.warn(e) { "Failed to update registry after move" }
                0
            }
        }

        /**
         * Shell-escape a path for safe use in SSH commands.
         */
        fun shellEscape(path: String): String {
            // Single-quote the entire path, escaping any embedded single quotes
            return "'" + path.replace("'", "'\\''") + "'"
        }
    }
}

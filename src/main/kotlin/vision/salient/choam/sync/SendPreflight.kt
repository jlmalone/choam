package vision.salient.choam.sync

import mu.KotlinLogging
import vision.salient.choam.SSH_PROBE_TIMEOUT_SEC
import vision.salient.choam.config.MachineProfile
import vision.salient.choam.lowPriority
import vision.salient.choam.network.NetworkRoute
import vision.salient.choam.runBounded
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Outcome of a pre-flight check. Replaces nullable SendPreflightResult.
 *
 * Callers use `when (outcome)` to handle all three cases.
 * UnsafeAbort is NOT overridable — it means remote state is untrustworthy.
 */
sealed class PreflightOutcome {
    /** Pre-flight completed. Inspect result for NEW/IDENTICAL/CONFLICT. */
    data class Resolved(val result: SendPreflightResult) : PreflightOutcome()

    /** Remote unreachable or clean absence (e.g., no manifest file). Safe to fall through. */
    object FallThrough : PreflightOutcome()

    /** Corrupt data observed (truncated output, bad manifest, stale state). MUST NOT proceed. */
    data class UnsafeAbort(val reason: String) : PreflightOutcome()
}

/** Classification of a file in the pre-flight manifest. */
enum class SendFileStatus {
    /** File does not exist at destination — safe to send. */
    NEW,
    /** File exists at destination with identical size and checksum — skip. */
    IDENTICAL,
    /** File exists at destination with different size or checksum — REFUSE without --force. */
    CONFLICT
}

/** A single file in the pre-flight manifest. */
data class SendManifestEntry(
    val localPath: String,
    val remotePath: String,
    val localSize: Long,
    val remoteSize: Long,
    val localChecksum: String?,  // SHA-256 hex, null if not computed
    val remoteChecksum: String?, // SHA-256 hex, null if not computed or sizes differ
    val status: SendFileStatus,
    val isDatabase: Boolean
)

private val DATABASE_EXTENSIONS = setOf(".db", ".sqlite", ".sqlite3", ".db-wal", ".db-shm", ".db-journal")

/** Result of a pre-flight check across all files in a send operation. */
data class SendPreflightResult(
    val entries: List<SendManifestEntry>
) {
    val newFiles get() = entries.filter { it.status == SendFileStatus.NEW }
    val identicalFiles get() = entries.filter { it.status == SendFileStatus.IDENTICAL }
    val conflictFiles get() = entries.filter { it.status == SendFileStatus.CONFLICT }
    val databaseConflicts get() = conflictFiles.filter { it.isDatabase }
    val hasConflicts get() = conflictFiles.isNotEmpty()
    val safeToSend get() = !hasConflicts
}

/**
 * Pre-flight safety check for `choam send`.
 *
 * SSHes to the remote machine and checks every destination file for existence,
 * size, and (when sizes match) SHA-256 checksum. Classifies each file as
 * NEW / IDENTICAL / CONFLICT so the caller can refuse overwrites by default.
 */
object SendPreflight {

    /**
     * Check remote files for conflicts before sending.
     *
     * @param localPaths  Expanded list of local file paths to send
     * @param destPath    Remote destination path (may end with / for directory target)
     * @param machine     Remote machine profile (provides SSH user, port)
     * @param route       Network route (provides target IP address)
     * @return PreflightOutcome: Resolved (with result), FallThrough (SSH unreachable), or UnsafeAbort (corrupt/stale)
     */
    fun checkRemoteFiles(
        localPaths: List<String>,
        destPath: String,
        machine: MachineProfile,
        route: NetworkRoute
    ): PreflightOutcome {
        val user = machine.sshUser ?: run {
            logger.warn { "No SSH user for ${machine.name} — cannot run pre-flight check" }
            return PreflightOutcome.FallThrough
        }
        val host = route.targetAddress
        val port = machine.sshPort

        // Build list of (localFile, remotePath) pairs
        val filePairs = expandFilePairs(localPaths, destPath)
        if (filePairs.isEmpty()) return PreflightOutcome.Resolved(SendPreflightResult(emptyList()))

        val portArgs = if (port != 22) listOf("-p", "$port") else emptyList()

        // Auto-skip pre-flight when destination directory doesn't exist.
        // If nothing exists at the destination, every file is NEW — no conflicts possible.
        // This avoids 94K+ individual stat calls for first-time transfers.
        if (filePairs.size > 1) {
            val destDir = if (destPath.endsWith("/")) destPath.dropLast(1) else destPath
            val existsCmd = lowPriority(
                listOf("ssh") + portArgs + listOf(
                    "-o", "ConnectTimeout=10",
                    "-o", "BatchMode=yes",
                    "-o", "ServerAliveInterval=15",
                    "-o", "ServerAliveCountMax=4",
                    "$user@$host",
                    "test -d '$destDir' && echo EXISTS || echo MISSING"
                )
            )
            try {
                val res = runBounded(existsCmd, timeoutSeconds = SSH_PROBE_TIMEOUT_SEC)
                val out = res.output.trim()
                // On timeout/failure `out` won't equal MISSING, so we fall through to the
                // full pre-flight (safe: never assume the destination is empty).
                if (!res.timedOut && out == "MISSING") {
                    logger.info { "Destination $destDir does not exist — skipping pre-flight (${filePairs.size} files all NEW)" }
                    val entries = filePairs.map { (localFile, remotePath) ->
                        SendManifestEntry(
                            localPath = localFile.absolutePath,
                            remotePath = remotePath,
                            localSize = localFile.length(),
                            remoteSize = 0,
                            localChecksum = null,
                            remoteChecksum = null,
                            status = SendFileStatus.NEW,
                            isDatabase = DATABASE_EXTENSIONS.any { remotePath.lowercase().endsWith(it) }
                        )
                    }
                    return PreflightOutcome.Resolved(SendPreflightResult(entries))
                }
            } catch (e: Exception) {
                logger.debug { "Destination existence check failed: ${e.message} — proceeding with full pre-flight" }
            }
        }

        // ── DIRECTORY CASCADE: manifest fast path + Merkle diff fallback ──
        // If the source is a single directory, try the optimized path before per-file checks.
        val singleSourceDir = if (localPaths.size == 1) File(localPaths[0]).takeIf { it.isDirectory } else null
        if (singleSourceDir != null) {
            val cascadeResult = directoryPreflightCascade(singleSourceDir, destPath, machine, route, filePairs)
            if (cascadeResult != null) return cascadeResult
            // null = cascade couldn't resolve, fall through to per-file check
        }

        logger.info { "Pre-flight check: ${filePairs.size} file(s) on ${machine.name} (per-file fallback)" }

        val perFileResult = runPerFileCheck(filePairs, machine, route)
        if (perFileResult == null) {
            // Fail closed: if per-file check failed (truncated SSH output, parse error),
            // we can't safely classify files. Treating this as FallThrough would let
            // rsync overwrite existing remote files without warning.
            logger.error { "Per-file pre-flight check failed — aborting to prevent silent overwrites" }
            return PreflightOutcome.UnsafeAbort("Pre-flight safety check failed: SSH output was truncated or unparseable. Cannot safely classify remote files.")
        }
        return PreflightOutcome.Resolved(perFileResult)
    }

    /**
     * Rename conflicting remote files to .backup_YYYYMMDD_HHMMSS before overwriting.
     */
    fun backupRemoteFiles(
        conflicts: List<SendManifestEntry>,
        machine: MachineProfile,
        route: NetworkRoute
    ): Boolean {
        if (conflicts.isEmpty()) return true

        val user = machine.sshUser ?: return false
        val host = route.targetAddress
        val port = machine.sshPort

        val timestamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))

        val renameCommands = conflicts.joinToString("; ") { entry ->
            val remotePath = entry.remotePath
            val backupPath = "${remotePath}.backup_$timestamp"
            "mv '$remotePath' '$backupPath' && echo 'BACKED_UP:$remotePath'"
        }

        val portArgs = if (port != 22) listOf("-p", "$port") else emptyList()
        val cmd = lowPriority(
            listOf("ssh") + portArgs + listOf(
                "-o", "ConnectTimeout=10",
                "-o", "BatchMode=yes",
                "-o", "ServerAliveInterval=15",
                "-o", "ServerAliveCountMax=4",
                "$user@$host",
                renameCommands
            )
        )

        return try {
            val res = runBounded(cmd, timeoutSeconds = SSH_PROBE_TIMEOUT_SEC)
            if (res.timedOut) {
                logger.error { "Remote backup timed out — cannot confirm backup, refusing to overwrite (source kept)" }
                return false
            }
            val backedUp = res.output.lines().count { it.startsWith("BACKED_UP:") }
            logger.info { "Backed up $backedUp/${conflicts.size} remote files on ${machine.name}" }
            res.exitCode == 0
        } catch (e: Exception) {
            logger.error { "Remote backup failed: ${e.message}" }
            false
        }
    }

    /**
     * Print a color-coded manifest to the terminal.
     */
    fun printManifest(result: SendPreflightResult, echo: (String) -> Unit) {
        val green = "\u001b[32m"
        val gray = "\u001b[90m"
        val red = "\u001b[31m"
        val yellow = "\u001b[33m"
        val reset = "\u001b[0m"

        echo("\n  Pre-flight manifest:")
        echo("  " + "─".repeat(80))

        for (entry in result.entries) {
            val fileName = File(entry.localPath).name
            val dbTag = if (entry.isDatabase) " ${yellow}[DATABASE]${reset}" else ""

            when (entry.status) {
                SendFileStatus.NEW -> {
                    echo("  ${green}NEW${reset}        $fileName (${formatBytes(entry.localSize)})$dbTag")
                }
                SendFileStatus.IDENTICAL -> {
                    echo("  ${gray}IDENTICAL${reset}  $fileName (${formatBytes(entry.localSize)}) — skipping$dbTag")
                }
                SendFileStatus.CONFLICT -> {
                    echo("  ${red}CONFLICT${reset}   $fileName$dbTag")
                    echo("               local:  ${formatBytes(entry.localSize)}" +
                        (entry.localChecksum?.let { " sha256:${it.take(12)}..." } ?: ""))
                    echo("               remote: ${formatBytes(entry.remoteSize)}" +
                        (entry.remoteChecksum?.let { " sha256:${it.take(12)}..." } ?: ""))
                }
            }
        }

        echo("  " + "─".repeat(80))
        echo("  ${green}${result.newFiles.size} new${reset}, " +
            "${gray}${result.identicalFiles.size} identical${reset}, " +
            "${red}${result.conflictFiles.size} conflict(s)${reset}")

        if (result.databaseConflicts.isNotEmpty()) {
            echo("")
            echo("  ${red}WARNING: ${result.databaseConflicts.size} database file(s) would be overwritten.${reset}")
            echo("  ${red}Overwriting database files may destroy data.${reset}")
            echo("  ${red}Use --force to confirm or consider SQL-level merge instead.${reset}")
        }

        if (result.hasConflicts) {
            echo("")
            echo("  ${red}REFUSED${reset} — ${result.conflictFiles.size} file(s) already exist at destination with different content.")
            echo("  Use ${yellow}--force${reset} to overwrite or ${yellow}--backup${reset} to rename remote copies first.")
        }
    }

    // --- Directory cascade (Phase 9.7) ---

    /**
     * Optimized pre-flight for directory sources.
     *
     * Cascade (uses internal types, maps to PreflightOutcome at boundary):
     *   a. loadRemote manifest → if fresh + rootHash match → Resolved
     *   b. buildRemote tree → diff with local → per-file on changed set
     *   c. null → fall through to legacy per-file check
     *
     * Returns null to indicate "cascade couldn't resolve, use per-file fallback."
     */
    private fun directoryPreflightCascade(
        sourceDir: File,
        destPath: String,
        machine: MachineProfile,
        route: NetworkRoute,
        filePairs: List<Pair<File, String>>
    ): PreflightOutcome? {
        val remoteDirPath = if (destPath.endsWith("/")) {
            "${destPath}${sourceDir.name}"
        } else {
            destPath
        }

        // Build local tree (manifest-aware)
        val localManifest = ChoamManifest.load(sourceDir)
        val localTree = DirectoryMerkleTree.buildLocal(sourceDir, localManifest)

        // Step (a): Try remote manifest fast path
        val manifestResult = ChoamManifest.loadRemote(remoteDirPath, machine, route)
        when (manifestResult) {
            is ManifestLoadResult.Corrupt -> {
                // A corrupt or stale manifest only invalidates the FAST PATH. The
                // tree diff and per-file checks below are authoritative and fail
                // closed on their own, so keep going instead of wedging the entry:
                // aborting here made every re-transfer of a completed directory
                // fail forever on a manifest quirk.
                logger.warn { "Remote manifest unusable (${manifestResult.reason}) — falling back to tree diff" }
            }
            is ManifestLoadResult.Loaded -> {
                val remoteManifest = manifestResult.manifest
                if (remoteManifest.rootHash == localTree.rootHash) {
                    if (remoteManifest.hashConfidence == "CONTENT") {
                        // CONTENT confidence: content-hash backed rootHash match → safe to declare IDENTICAL.
                        // Populate checksums from manifest so MOVE verification (which requires non-null
                        // localChecksum/remoteChecksum) can confirm identity without a separate SSH call.
                        logger.info { "Manifest fast path: rootHash match with CONTENT confidence — all IDENTICAL" }
                        val entries = filePairs.map { (localFile, remotePath) ->
                            val relPath = localFile.relativeTo(sourceDir).path
                            val manifestHash = remoteManifest.files[relPath]?.contentHash
                            SendManifestEntry(
                                localPath = localFile.absolutePath,
                                remotePath = remotePath,
                                localSize = localFile.length(),
                                remoteSize = localFile.length(),
                                localChecksum = manifestHash,
                                remoteChecksum = manifestHash,
                                status = SendFileStatus.IDENTICAL,
                                isDatabase = DATABASE_EXTENSIONS.any { remotePath.lowercase().endsWith(it) }
                            )
                        }
                        return PreflightOutcome.Resolved(SendPreflightResult(entries))
                    } else {
                        // METADATA confidence: rootHash match but no content hashes.
                        // Metadata match is NOT authoritative for identity — a file could change
                        // without its size/mtime changing. Fall through to per-file check.
                        logger.info { "Manifest fast path: rootHash match but METADATA only — falling through to per-file check" }
                        return null  // cascade can't resolve → per-file fallback
                    }
                }
                // Root hash mismatch — fall through to tree diff
                logger.info { "Manifest rootHash mismatch (local=${localTree.rootHash.take(12)}, remote=${remoteManifest.rootHash.take(12)}) — diffing trees" }
            }
            is ManifestLoadResult.Missing -> {
                // No manifest — continue to (b)
                logger.debug { "No remote manifest — trying remote tree build" }
            }
        }

        // Step (b): Build remote tree and diff
        val remoteTreeResult = DirectoryMerkleTree.buildRemote(remoteDirPath, machine, route)
        when (remoteTreeResult) {
            is RemoteTreeResult.Failed -> {
                return PreflightOutcome.UnsafeAbort(remoteTreeResult.reason)
            }
            is RemoteTreeResult.NetworkUnavailable -> {
                // Fall through to per-file check (returns null → caller uses legacy path)
                return null
            }
            is RemoteTreeResult.Built -> {
                val diff = localTree.diff(remoteTreeResult.tree)
                if (diff.isEmpty) {
                    // Trees are metadata-identical — but metadata match is not authoritative.
                    // Fall through to per-file check to get real conflict detection.
                    logger.info { "Remote tree diff: metadata-identical (${localTree.fileCount} files) — falling through to per-file check" }
                    return null  // cascade can't authoritatively resolve → per-file fallback
                }

                // Narrow to changed files only. Run per-file SSH check on the
                // changed subset to get proper CONFLICT classification. Unchanged
                // files are skipped (rsync no-ops them), but changed files MUST go
                // through the existing per-file check to preserve overwrite protection.
                //
                // deletedFiles is included deliberately: this is localTree.diff(remoteTree),
                // where "deleted" means present LOCALLY but ABSENT on the remote — i.e. files
                // that still need to be sent (the signature of a partially-transferred / resumed
                // directory). Omitting them made the missing files fall into the "unchanged →
                // IDENTICAL" bucket, so a MOVE took the "already at destination" path, skipped
                // rsync entirely, and failed verification forever. Routing them through the
                // per-file check classifies them NEW (remote MISSING) so the transfer proceeds.
                logger.info { "Remote tree diff: ${diff.newFiles.size} new, ${diff.modifiedFiles.size} modified, ${diff.deletedFiles.size} deleted — running per-file check on changed set" }
                val changedRelPaths = changedPathsFromDiff(diff)

                val changedPairs = filePairs.filter { (localFile, _) ->
                    val relPath = localFile.relativeTo(sourceDir).path
                    relPath in changedRelPaths || changedRelPaths.any { changed ->
                        relPath.endsWith(changed) || changed.endsWith(relPath)
                    }
                }
                val unchangedPairs = filePairs.filter { (localFile, _) ->
                    val relPath = localFile.relativeTo(sourceDir).path
                    relPath !in changedRelPaths && !changedRelPaths.any { changed ->
                        relPath.endsWith(changed) || changed.endsWith(relPath)
                    }
                }

                if (changedPairs.isEmpty()) {
                    // Diff found changes but none matched our file pairs — fall through
                    return null
                }

                // Run the per-file SSH check on the narrowed set only
                // This preserves CONFLICT detection for modified remote files
                val narrowedResult = runPerFileCheck(changedPairs, machine, route)
                if (narrowedResult == null) {
                    // SSH failed on the narrowed check — fall through entirely
                    return null
                }

                // Combine: unchanged files are IDENTICAL, changed files get their real status
                val unchangedEntries = unchangedPairs.map { (localFile, remotePath) ->
                    SendManifestEntry(
                        localPath = localFile.absolutePath,
                        remotePath = remotePath,
                        localSize = localFile.length(),
                        remoteSize = localFile.length(),
                        localChecksum = null,
                        remoteChecksum = null,
                        status = SendFileStatus.IDENTICAL,
                        isDatabase = DATABASE_EXTENSIONS.any { remotePath.lowercase().endsWith(it) }
                    )
                }
                return PreflightOutcome.Resolved(
                    SendPreflightResult(narrowedResult.entries + unchangedEntries)
                )
            }
        }
    }

    // --- Internal helpers ---

    /**
     * Files that must be inspected (and potentially transferred) before a directory send:
     * everything the Merkle diff flags as new, modified, OR deleted.
     *
     * "deleted" is the critical, easy-to-miss one: for `localTree.diff(remoteTree)` it means a
     * file present LOCALLY but ABSENT on the remote — the signature of a partial / resumed
     * transfer. Excluding it lets missing files masquerade as "unchanged → IDENTICAL", so a MOVE
     * takes the already-at-destination path, skips rsync entirely, and fails verification forever.
     * Files in none of the three buckets are genuinely unchanged and safe to treat as IDENTICAL
     * without a per-file check.
     */
    internal fun changedPathsFromDiff(diff: MerkleDiff): Set<String> =
        (diff.newFiles + diff.modifiedFiles + diff.deletedFiles).toSet()

    /**
     * Run per-file SSH check on a set of (localFile, remotePath) pairs.
     * Returns SendPreflightResult classifying each file, or null if SSH fails.
     *
     * This is the core conflict-detection logic. Both the main path and the
     * cascade narrowed path call this to preserve overwrite protection.
     */
    private fun runPerFileCheck(
        filePairs: List<Pair<File, String>>,
        machine: MachineProfile,
        route: NetworkRoute
    ): SendPreflightResult? {
        val user = machine.sshUser ?: return null
        val host = route.targetAddress
        val port = machine.sshPort
        val portArgs = if (port != 22) listOf("-p", "$port") else emptyList()

        val script = buildCheckScript(filePairs)

        val cmd = lowPriority(
            listOf("ssh") + portArgs + listOf(
                "-o", "ConnectTimeout=10",
                "-o", "BatchMode=yes",
                "-o", "ServerAliveInterval=15",
                "-o", "ServerAliveCountMax=4",
                "$user@$host",
                "bash -s"
            )
        )

        val res = try {
            runBounded(cmd, stdin = script, timeoutSeconds = SSH_PROBE_TIMEOUT_SEC)
        } catch (e: Exception) {
            logger.error { "Per-file SSH check failed: ${e.message}" }
            return null
        }
        if (res.timedOut) {
            // Returning null → checkRemoteFiles emits UnsafeAbort → entry fails closed
            // (source kept), never a silent overwrite.
            logger.error { "Per-file SSH check timed out — cannot classify remote files (fail closed)" }
            return null
        }

        return parsePerFileCheckOutput(filePairs, res.output, res.exitCode)
    }

    internal fun parsePerFileCheckOutput(
        filePairs: List<Pair<File, String>>,
        output: String,
        exitCode: Int
    ): SendPreflightResult? {
        // Fail-closed: any non-zero exit means we can't trust the output.
        // Don't try to parse partial data — return null so the caller can decide.
        if (exitCode != 0) {
            logger.error { "Per-file SSH check returned exit code $exitCode — cannot trust output" }
            return null
        }

        val lines = output.trim().lines().filter { it.isNotBlank() }

        // Fail-closed: truncated output (fewer lines than files) means some
        // files weren't checked. We can't classify them safely, so abort.
        if (lines.size < filePairs.size) {
            logger.error { "Per-file SSH check returned ${lines.size} lines for ${filePairs.size} files — truncated output" }
            return null
        }

        val entries = mutableListOf<SendManifestEntry>()

        for ((idx, pair) in filePairs.withIndex()) {
            val (localFile, remotePath) = pair
            val localSize = localFile.length()
            val isDb = DATABASE_EXTENSIONS.any { remotePath.lowercase().endsWith(it) }

            val responseLine = lines[idx]  // guaranteed non-null by truncation check above
            val parts = responseLine.split("|")
            if (parts.size < 3) {
                logger.error { "Per-file SSH check returned malformed line for $remotePath: $responseLine" }
                return null
            }

            when (parts[0]) {
                "MISSING" -> {
                    entries.add(SendManifestEntry(
                        localPath = localFile.absolutePath,
                        remotePath = remotePath,
                        localSize = localSize,
                        remoteSize = 0,
                        localChecksum = null,
                        remoteChecksum = null,
                        status = SendFileStatus.NEW,
                        isDatabase = isDb
                    ))
                    continue
                }
                "EXISTS" -> Unit
                else -> {
                    logger.error { "Per-file SSH check returned unknown status '${parts[0]}' for $remotePath: $responseLine" }
                    return null
                }
            }

            val remoteSize = parts[1].toLongOrNull()
            if (remoteSize == null) {
                logger.error { "Per-file SSH check returned invalid size for $remotePath: $responseLine" }
                return null
            }

            val remoteChecksum = if (parts[2] != "NONE") parts[2] else null

            if (remoteSize != localSize) {
                entries.add(SendManifestEntry(
                    localPath = localFile.absolutePath,
                    remotePath = remotePath,
                    localSize = localSize,
                    remoteSize = remoteSize,
                    localChecksum = null,
                    remoteChecksum = null,
                    status = SendFileStatus.CONFLICT,
                    isDatabase = isDb
                ))
                continue
            }

            if (remoteChecksum != null) {
                val localChecksum = computeLocalSha256(localFile)
                if (localChecksum == remoteChecksum) {
                    entries.add(SendManifestEntry(
                        localPath = localFile.absolutePath,
                        remotePath = remotePath,
                        localSize = localSize,
                        remoteSize = remoteSize,
                        localChecksum = localChecksum,
                        remoteChecksum = remoteChecksum,
                        status = SendFileStatus.IDENTICAL,
                        isDatabase = isDb
                    ))
                } else {
                    entries.add(SendManifestEntry(
                        localPath = localFile.absolutePath,
                        remotePath = remotePath,
                        localSize = localSize,
                        remoteSize = remoteSize,
                        localChecksum = localChecksum,
                        remoteChecksum = remoteChecksum,
                        status = SendFileStatus.CONFLICT,
                        isDatabase = isDb
                    ))
                }
            } else {
                entries.add(SendManifestEntry(
                    localPath = localFile.absolutePath,
                    remotePath = remotePath,
                    localSize = localSize,
                    remoteSize = remoteSize,
                    localChecksum = null,
                    remoteChecksum = null,
                    status = SendFileStatus.IDENTICAL,
                    isDatabase = isDb
                ))
            }
        }

        return SendPreflightResult(entries)
    }

    /**
     * Expand local paths into (File, remotePath) pairs, handling directories recursively.
     */
    private fun expandFilePairs(localPaths: List<String>, destPath: String): List<Pair<File, String>> {
        val pairs = mutableListOf<Pair<File, String>>()
        for (localPath in localPaths) {
            val localFile = File(localPath)
            if (localFile.isDirectory) {
                // Walk directory and preserve relative structure, excluding the manifest and
                // volatile junk (.DS_Store, *.tmp, *.part). These are never transferred, so they
                // must not be conflict-checked either — a differing .DS_Store would otherwise be
                // classified CONFLICT and refuse an otherwise byte-identical directory MOVE.
                Files.walk(localFile.toPath())
                    .filter { Files.isRegularFile(it) && !isExcludedFromTree(it.toFile().name) }
                    .forEach { path ->
                        val relativePath = localFile.toPath().relativize(path).toString()
                        val destDir = if (destPath.endsWith("/")) destPath else "$destPath/"
                        val remoteFull = "$destDir${localFile.name}/$relativePath"
                        pairs.add(path.toFile() to remoteFull)
                    }
            } else {
                val remoteFull = if (destPath.endsWith("/")) {
                    "$destPath${localFile.name}"
                } else {
                    destPath
                }
                pairs.add(localFile to remoteFull)
            }
        }
        return pairs
    }

    /**
     * Build a bash script that checks all remote files in one SSH call.
     * For each file outputs: EXISTS|SIZE|SHA256  or  MISSING|0|NONE
     * SHA-256 is only computed when the remote file size matches the expected local size.
     */
    private fun buildCheckScript(filePairs: List<Pair<File, String>>): String {
        val sb = StringBuilder()
        for ((localFile, remotePath) in filePairs) {
            val expectedSize = localFile.length()
            val escapedPath = remotePath.replace("'", "'\\''")
            sb.appendLine("""
                if [ -e '$escapedPath' ]; then
                    SIZE=${'$'}(stat -f '%z' '$escapedPath' 2>/dev/null || stat -c '%s' '$escapedPath' 2>/dev/null)
                    if [ "${'$'}SIZE" = "$expectedSize" ]; then
                        HASH=${'$'}(shasum -a 256 '$escapedPath' 2>/dev/null | cut -d' ' -f1)
                        if [ -z "${'$'}HASH" ]; then
                            HASH=${'$'}(sha256sum '$escapedPath' 2>/dev/null | cut -d' ' -f1)
                        fi
                        if [ -z "${'$'}HASH" ]; then
                            HASH="NONE"
                        fi
                        echo "EXISTS|${'$'}SIZE|${'$'}HASH"
                    else
                        echo "EXISTS|${'$'}SIZE|NONE"
                    fi
                else
                    echo "MISSING|0|NONE"
                fi
            """.trimIndent())
        }
        return sb.toString()
    }

    private fun computeLocalSha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        file.inputStream().use { fis ->
            var bytesRead = fis.read(buffer)
            while (bytesRead != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesRead = fis.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun formatBytesPublic(bytes: Long): String = formatBytes(bytes)

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

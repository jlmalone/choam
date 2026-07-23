package vision.salient.choam.sync

import mu.KotlinLogging
import vision.salient.choam.cli.TargetResolver
import vision.salient.choam.config.ChoamConfig
import vision.salient.choam.config.MachineProfile
import vision.salient.choam.lowPriority
import vision.salient.choam.network.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

enum class ProcessingMode {
    /** User is at the terminal — show colored output */
    INTERACTIVE,
    /** Daemon or web trigger — log only, fail-closed on pre-flight */
    UNATTENDED
}

data class ProcessingResult(
    val completed: Int,
    val failed: Int,
    val deferred: Int
)

/**
 * Unified queue processor used by CLI, daemon, and web.
 *
 * Eliminates the three duplicated processing implementations with a single
 * class that handles both interactive and unattended modes.
 */
class QueueProcessor(
    private val config: ChoamConfig,
    private val queue: TransferQueueStore,
    private val mode: ProcessingMode = ProcessingMode.UNATTENDED,
    private val bandwidthOverride: Int? = null,
    private val echo: (String) -> Unit = { logger.info { it } }
) {
    private val rsyncEngine = RsyncTransferEngine()
    private val historyStore = SyncHistoryStore()
    private val networkDetector = NetworkDetector()

    /**
     * Process all pending queue entries.
     * Returns a summary of results.
     */
    fun processAll(): ProcessingResult {
        // Watchdog: recover stale RUNNING entries
        val recovered = queue.recoverStaleRunning()
        if (recovered > 0) {
            echo("Watchdog: recovered $recovered stale transfers stuck in RUNNING state")
        }

        // Check network quality before claiming any entries
        val networkStatus = networkDetector.assessNetwork()
        when (networkStatus.quality) {
            NetworkQuality.HOTSPOT -> {
                echo("Skipping queue — connected to hotspot: ${networkStatus.ssid}")
                return ProcessingResult(0, 0, 0)
            }
            NetworkQuality.OFFLINE -> {
                echo("Skipping queue — no network connectivity.")
                return ProcessingResult(0, 0, 0)
            }
            else -> {}
        }

        val localMachine = TargetResolver(config).findLocalMachine()
        if (localMachine == null) {
            echo("Cannot determine local machine.")
            return ProcessingResult(0, 0, 0)
        }

        // Atomic claim loop — two concurrent processors will never claim the same entry.
        // claimNext() uses SELECT+UPDATE in a transaction with PID tracking.
        var completed = 0
        var failed = 0
        var deferred = 0

        while (true) {
            val entry = queue.claimNext() ?: break

            try {
                val result = processSingle(entry, localMachine)
                when (result) {
                    SingleResult.COMPLETED -> completed++
                    SingleResult.FAILED -> failed++
                    SingleResult.DEFERRED -> deferred++
                }
            } catch (e: Exception) {
                logger.error(e) { "Unexpected error processing ${entry.id}" }
                queue.update(entry.id) {
                    it.copy(status = TransferStatus.FAILED, error = "Internal error: ${e.message}")
                }
                failed++
            }
        }

        if (completed + failed + deferred == 0) {
            echo("No pending transfers to process.")
        } else {
            echo("Queue complete: $completed succeeded, $failed failed, $deferred deferred")
        }
        return ProcessingResult(completed, failed, deferred)
    }

    enum class SingleResult { COMPLETED, FAILED, DEFERRED }

    /**
     * Mark an entry FAILED with bounded-retry semantics: increment the retry counter and
     * schedule exponential backoff, or give up once MAX_RETRIES is exceeded.
     *
     * Verification failures (layout/hash mismatch) are deterministic — without a retry bump
     * the entry keeps retry_count=0 / next_retry_at=NULL and claimNext() re-claims it
     * instantly, spinning in a tight loop (this is what produced 10k+ retries on a single
     * mis-placed directory). Routing every verification FAILED transition through here lets
     * the existing claimNext() cap + backoff actually take effect.
     */
    private fun failVerificationWithBackoff(entry: TransferQueueEntry, message: String, bytes: Long? = null) {
        val newRetryCount = entry.retryCount + 1
        val exhausted = newRetryCount > TransferQueueEntry.MAX_RETRIES
        val nextRetry = if (exhausted) null else TransferQueueEntry.nextBackoff(newRetryCount)
        val suffix = if (exhausted) " [max retries exhausted]" else ""
        queue.update(entry.id) {
            it.copy(
                status = TransferStatus.FAILED,
                error = message + suffix,
                retryCount = newRetryCount,
                nextRetryAt = nextRetry?.toString(),
                bytesTransferred = bytes ?: it.bytesTransferred
            )
        }
    }

    /**
     * After a MOVE deletes its source, confirm no real payload survived. Returns the surviving
     * non-junk files (empty = clean). A silent partial delete — e.g. `File.delete()` /
     * `deleteRecursively()` failing on a locked file, or another process re-creating a file mid-walk
     * — must NEVER be reported as COMPLETED while real source files remain. Volatile junk
     * (.DS_Store, *.tmp, *.part) is ignored: it is not payload, was never transferred, and an
     * external writer re-creating it should not fail an otherwise-complete move.
     */
    private fun survivingSourceFiles(srcFile: File): List<File> {
        if (!srcFile.exists()) return emptyList()
        // Best-effort sweep of leftover junk + now-empty dirs so a clean move fully removes the
        // source folder even when only excluded files remained after the payload delete.
        srcFile.walkBottomUp().forEach { f ->
            if (f.isFile && isExcludedFromTree(f.name)) runCatching { f.delete() }
        }
        srcFile.walkBottomUp().filter { it.isDirectory }.forEach { d ->
            if (d.listFiles()?.isEmpty() == true) runCatching { d.delete() }
        }
        if (!srcFile.exists()) return emptyList()
        return srcFile.walkTopDown().filter { it.isFile && !isExcludedFromTree(it.name) }.toList()
    }

    private fun processSingle(
        entry: TransferQueueEntry,
        localMachine: MachineProfile
    ): SingleResult {
        val remoteMachine = config.machines[entry.destinationMachine]
        if (remoteMachine == null) {
            echo("  ${entry.id}: unknown machine '${entry.destinationMachine}'")
            queue.update(entry.id) { it.copy(status = TransferStatus.FAILED, error = "Unknown machine") }
            return SingleResult.FAILED
        }

        val srcFile = File(entry.sourcePath)
        if (!srcFile.exists()) {
            echo("  ${entry.id}: source not found: ${entry.sourcePath}")
            // Back off instead of a bare FAILED. A bare FAILED keeps retry_count=0 /
            // next_retry_at=NULL, so claimNext() re-claims this same (oldest) entry
            // immediately and the drain spins on it, never reaching the others — the queue
            // looks frozen. Backoff makes the drain skip past to other transfers and lets a
            // permanently-missing source exhaust its retries and drop out of contention.
            failVerificationWithBackoff(entry, "Source not found")
            return SingleResult.FAILED
        }

        val route = networkDetector.detectBestRoute(localMachine, remoteMachine)
        val connectivity = networkDetector.testConnectivity(route)
        if (!connectivity.reachable) {
            echo("  ${entry.id}: ${remoteMachine.name} unreachable — will retry later")
            // Reset from RUNNING back to PENDING so it's picked up next cycle
            queue.update(entry.id) { it.copy(status = TransferStatus.PENDING, startedAt = null,
                error = "Deferred: ${remoteMachine.name} unreachable") }
            return SingleResult.DEFERRED
        }

        // ── SOURCE GUARD: acquire lock, lsof check, fingerprint ──
        val guard = try {
            SourceGuard.acquire(entry.sourcePath, entry.mode, entry.id)
        } catch (e: SourceGuardException) {
            echo("  ${entry.id}: SourceGuard: ${e.message}")
            queue.update(entry.id) { it.copy(status = TransferStatus.FAILED, error = "[SG_ACQUIRE] ${e.message}") }
            return SingleResult.FAILED
        }

        // For SQLite MOVE: checkpoint WAL and verify quiescent before any transfer work
        if (entry.mode == TransferMode.MOVE && SourceGuard.isSqlite(entry.sourcePath)) {
            try {
                guard.checkpointAndVerifyWal()
            } catch (e: SourceGuardException) {
                echo("  ${entry.id}: SQLite not quiescent: ${e.message}")
                queue.update(entry.id) { it.copy(status = TransferStatus.FAILED, error = "[SG_WAL] ${e.message}") }
                guard.close()
                return SingleResult.FAILED
            }
        }

        try {
            // Update daemon health with active transfer info
            vision.salient.choam.daemon.DaemonState.activeTransferId = entry.id
            vision.salient.choam.daemon.DaemonState.activeTransferName = java.io.File(entry.sourcePath).name
            vision.salient.choam.daemon.DaemonState.writeHealth()

            return processSingleGuarded(entry, localMachine, remoteMachine, route, guard)
        } finally {
            guard.close()
            vision.salient.choam.daemon.DaemonState.activeTransferId = null
            vision.salient.choam.daemon.DaemonState.activeTransferName = null
            vision.salient.choam.daemon.DaemonState.writeHealth()
        }
    }

    /**
     * Process a single entry with an active SourceGuard.
     * Guard is acquired before this method and released in the caller's finally block.
     */
    private fun processSingleGuarded(
        entry: TransferQueueEntry,
        localMachine: MachineProfile,
        remoteMachine: MachineProfile,
        route: NetworkRoute,
        guard: SourceGuard
    ): SingleResult {
        val srcFile = File(entry.sourcePath)
        val effectiveBwLimit = bandwidthOverride ?: entry.bandwidthLimitKBps

        // ── PRE-FLIGHT SAFETY CHECK ──
        val preflightOutcome = SendPreflight.checkRemoteFiles(
            listOf(entry.sourcePath), entry.destinationPath, remoteMachine, route
        )

        when (preflightOutcome) {
            is PreflightOutcome.UnsafeAbort -> {
                echo("  ${entry.id}: pre-flight unsafe — ${preflightOutcome.reason}")
                // Backoff (not a bare FAILED) so a persistently-unsafe/timing-out pre-flight
                // can't be instantly re-claimed and spin the drain on one entry.
                failVerificationWithBackoff(entry, preflightOutcome.reason)
                return SingleResult.FAILED
            }
            is PreflightOutcome.FallThrough -> {
                // SSH unreachable for pre-flight — defer rather than proceeding blind
                echo("  ${entry.id}: pre-flight SSH unreachable — deferring")
                queue.update(entry.id) { it.copy(status = TransferStatus.PENDING, startedAt = null,
                    error = "Deferred: pre-flight SSH unreachable") }
                return SingleResult.DEFERRED
            }
            is PreflightOutcome.Resolved -> {
                val preflight = preflightOutcome.result

                if (preflight.hasConflicts && !entry.force && !entry.backup) {
                    // A conflict is DETERMINISTIC: the same preflight will keep producing it until
                    // a human resolves the destination. A bare FAILED here (retry_count=0,
                    // next_retry_at=NULL) is re-claimed by claimNext() instantly, spinning a tight
                    // loop that wedges the whole queue behind this one entry (observed: 94 min at
                    // ~26 s/iteration on a phantom .DS_Store conflict). Route through backoff so the
                    // drain moves on to other transfers and this entry exhausts its retries instead.
                    val conflictNames = preflight.conflictFiles.take(3).joinToString { File(it.localPath).name }
                    echo("  ${entry.id}: CONFLICT — ${preflight.conflictFiles.size} destination file(s) differ: $conflictNames")
                    failVerificationWithBackoff(entry,
                        "Conflict: ${preflight.conflictFiles.size} destination file(s) exist with different content ($conflictNames). " +
                            "Use --force to overwrite or --backup to preserve the remote copies.")
                    return SingleResult.FAILED
                }

                if (preflight.hasConflicts && entry.backup) {
                    val backupOk = SendPreflight.backupRemoteFiles(preflight.conflictFiles, remoteMachine, route)
                    if (!backupOk) {
                        echo("  ${entry.id}: remote backup failed — refusing to overwrite without backup")
                        queue.update(entry.id) {
                            it.copy(status = TransferStatus.FAILED, error = "Remote backup failed — refusing to overwrite without backup")
                        }
                        return SingleResult.FAILED
                    }
                }

                if (preflight.identicalFiles.isNotEmpty() && preflight.newFiles.isEmpty() && preflight.conflictFiles.isEmpty()) {
                    // Already at destination — for MOVE, verify full integrity then delete source
                    if (entry.mode == TransferMode.MOVE) {
                        // ── SOURCE GUARD: verify source unchanged since guard acquisition ──
                        // This covers the race where source changes between preflight and deletion.
                        val sourceCheck = guard.verifySourceUnchanged()
                        if (!sourceCheck.passed) {
                            echo("  ${entry.id}: source changed since guard: ${sourceCheck.detail} — source kept")
                            queue.update(entry.id) {
                                it.copy(status = TransferStatus.FAILED, error = "[SG_VERIFY] Source changed: ${sourceCheck.detail}")
                            }
                            return SingleResult.FAILED
                        }

                        // For SQLite MOVE: verify still quiescent
                        if (SourceGuard.isSqlite(entry.sourcePath)) {
                            val quiescentCheck = guard.verifySqliteMoveQuiescent()
                            if (!quiescentCheck.passed) {
                                echo("  ${entry.id}: SQLite modified: ${quiescentCheck.detail} — source kept")
                                queue.update(entry.id) {
                                    it.copy(status = TransferStatus.FAILED, error = "[SG_VERIFY] SQLite modified: ${quiescentCheck.detail}")
                                }
                                return SingleResult.FAILED
                            }
                        }

                        val totalFiles = preflight.identicalFiles.size

                        val unverified = preflight.identicalFiles.filter { manifest ->
                            manifest.localChecksum == null || manifest.remoteChecksum == null ||
                                manifest.localChecksum != manifest.remoteChecksum ||
                                manifest.localSize != manifest.remoteSize
                        }

                        if (unverified.isNotEmpty()) {
                            // Preflight lacked content-hash PROOF for some files — e.g. a mixed
                            // tree-diff bucketed unchanged files with null checksums, or the remote
                            // manifest was metadata-only (contentHash: null). That is "unproven", NOT
                            // "different". Before refusing a move whose data may well be byte-identical,
                            // get authoritative proof by re-hashing every file on both sides. This is the
                            // "if it's byte-for-byte on the other side, delete the source" guarantee:
                            // trust real SHA-256 over stale/partial manifest state so a genuinely-present
                            // copy completes instead of looping.
                            val authoritative = if (srcFile.isDirectory) {
                                val remoteDirPath = ManifestWriter.resolveRemoteDirPath(srcFile, entry.destinationPath)
                                val vr = PostTransferVerifier.verifyDirectory(srcFile, remoteDirPath, remoteMachine, route)
                                if (vr.hashMismatches.isNotEmpty()) {
                                    echo("  ${entry.id}: HASH MISMATCH on ${vr.hashMismatches.size} file(s) — possible corruption")
                                }
                                vr.failed.isEmpty()
                            } else {
                                PostTransferVerifier.verifySingleFile(srcFile, entry.destinationPath, remoteMachine, route)
                            }
                            if (!authoritative) {
                                echo("  ${entry.id}: ${unverified.size}/$totalFiles file(s) not confirmed byte-identical at destination — source kept")
                                failVerificationWithBackoff(entry,
                                    "Move aborted: destination copy not confirmed byte-identical (${unverified.size}/$totalFiles file(s) unproven)")
                                return SingleResult.FAILED
                            }
                            echo("  ${entry.id}: $totalFiles file(s) authoritatively verified by SHA-256 at destination")
                        }

                        // Build manifest BEFORE deleting source
                        val precomputedManifest = if (srcFile.isDirectory) {
                            try {
                                val existingManifest = ChoamManifest.load(srcFile)
                                val tree = DirectoryMerkleTree.buildLocal(srcFile, existingManifest)
                                tree.toManifest(srcFile)
                            } catch (e: Exception) {
                                logger.warn { "Pre-move manifest build failed (non-fatal): ${e.message}" }
                                null
                            }
                        } else null

                        echo("  ${entry.id}: $totalFiles file(s) verified (size + SHA-256) at destination")
                        if (srcFile.isDirectory) {
                            srcFile.walkBottomUp().forEach { it.delete() }
                            // Honesty check: never report COMPLETED while real source files survive
                            // (locked file, or an external process re-creating one mid-walk). The
                            // destination copy is verified safe, so this is a source-reclaim failure,
                            // not data loss — fail with backoff instead of a false success.
                            val survivors = survivingSourceFiles(srcFile)
                            if (survivors.isNotEmpty()) {
                                echo("  ${entry.id}: ${survivors.size} source file(s) survived deletion — destination copy is verified/safe, source kept")
                                failVerificationWithBackoff(entry,
                                    "Move incomplete: ${survivors.size} source file(s) could not be deleted " +
                                        "(destination copy is verified and safe): ${survivors.take(3).joinToString { it.name }}")
                                return SingleResult.FAILED
                            }
                            echo("  ${entry.id}: deleted local ${srcFile.name}/ ($totalFiles files)")
                        } else {
                            Files.deleteIfExists(Path.of(entry.sourcePath))
                            // For SQLite MOVE: also delete WAL and SHM
                            if (SourceGuard.isSqlite(entry.sourcePath)) {
                                Files.deleteIfExists(Path.of("${entry.sourcePath}-wal"))
                                Files.deleteIfExists(Path.of("${entry.sourcePath}-shm"))
                            }
                            echo("  ${entry.id}: deleted local ${srcFile.name}")
                        }

                        // Backfill remote manifest for directory moves
                        if (precomputedManifest != null) {
                            val remoteDirPath = ManifestWriter.resolveRemoteDirPath(srcFile, entry.destinationPath)
                            ManifestWriter.writeRemoteManifestOnly(
                                precomputedManifest, remoteDirPath, remoteMachine, route, echo
                            )
                        }
                    } else {
                        echo("  ${entry.id}: already present at destination — skipping")
                        // Backfill manifests on already-identical directory copies
                        if (srcFile.isDirectory) {
                            val remoteDirPath = ManifestWriter.resolveRemoteDirPath(srcFile, entry.destinationPath)
                            ManifestWriter.writeManifestsAfterTransfer(
                                sourceDir = srcFile,
                                remoteDirPath = remoteDirPath,
                                machine = remoteMachine,
                                route = route,
                                writeLocal = true,
                                echo = echo
                            )
                        }
                    }
                    queue.update(entry.id) {
                        it.copy(status = TransferStatus.COMPLETED, completedAt = Instant.now().toString(), error = null)
                    }
                    return SingleResult.COMPLETED
                }
            }
        }

        // ── TRANSFER ──
        queue.update(entry.id) { it.copy(status = TransferStatus.RUNNING, startedAt = Instant.now().toString()) }

        val startTime = Instant.now()
        val progressFile = Paths.get(System.getProperty("user.home"), ".choam", "queue-progress-${entry.id}.json")
        Files.createDirectories(progressFile.parent)

        // Pre-compute aggregate stats for directory transfers.
        // Subtract identical files (already at destination) — rsync skips them,
        // so including them in the denominator would make progress misleadingly low.
        val isDir = srcFile.isDirectory
        // preflightOutcome is always Resolved here — UnsafeAbort and FallThrough return early above
        val resolvedPreflight = (preflightOutcome as PreflightOutcome.Resolved).result
        val identicalCount = resolvedPreflight.identicalFiles.size
        val identicalBytes = resolvedPreflight.identicalFiles.sumOf { it.localSize ?: 0L }

        val dirTotalFiles: Int
        val dirTotalBytes: Long
        if (isDir) {
            var count = 0
            var bytes = 0L
            srcFile.walkTopDown().filter { it.isFile && it.name != MANIFEST_FILENAME }.forEach {
                count++
                bytes += it.length()
            }
            dirTotalFiles = (count - identicalCount).coerceAtLeast(1)
            dirTotalBytes = (bytes - identicalBytes).coerceAtLeast(0)
        } else {
            dirTotalFiles = 1
            dirTotalBytes = srcFile.length()
        }

        // Aggregate progress tracker for directory transfers
        var lastFileName = ""
        var filesCompleted = 0
        var completedBytes = 0L      // sum of sizes of fully transferred files
        var lastFileSize = 0L        // estimated size of the file currently in progress
        var lastBytesTransferred = 0L // for detecting file transitions when filename parsing fails
        var lastStatusSnapshotAt = 0L

        val result = rsyncEngine.transfer(
            sourcePath = entry.sourcePath,
            targetPath = entry.destinationPath,
            targetMachine = remoteMachine,
            route = route,
            bandwidthLimitKBps = effectiveBwLimit,
            // Exclude the manifest AND volatile junk (.DS_Store, *.tmp, *.part) so the transfer
            // matches the tree/preflight accounting — otherwise a transferred .DS_Store diverges
            // per-directory and manufactures a phantom CONFLICT on the next re-move.
            // alwaysExcludedRsyncPatterns() already includes the manifest name.
            excludePatterns = alwaysExcludedRsyncPatterns(),
            // Place the source under the destination by basename (<dest>/<name>), matching
            // what PostTransferVerifier checks. Never merge contents into the parent.
            appendSourceSlash = false
        ) { progress ->
            // Detect file transitions via two signals:
            // 1. fileName changed (primary — works for most files)
            // 2. bytesTransferred dropped significantly (fallback — catches filenames
            //    containing '%' that the rsync parser misses as filename lines)
            val fileChanged = progress.fileName.isNotEmpty() && progress.fileName != lastFileName
            val bytesReset = lastBytesTransferred > 0 && progress.bytesTransferred < lastBytesTransferred / 2

            if (fileChanged || bytesReset) {
                if (lastFileName.isNotEmpty() || bytesReset) {
                    filesCompleted++
                    completedBytes += lastFileSize
                }
                lastFileName = progress.fileName
                lastFileSize = progress.totalBytes
            }
            lastBytesTransferred = progress.bytesTransferred

            // Update estimated size of current file from rsync's own calculation
            if (progress.totalBytes > 0) {
                lastFileSize = progress.totalBytes
            }

            val overallBytes = completedBytes + progress.bytesTransferred

            // Write progress sidecar for web dashboard
            try {
                val escapedName = progress.fileName.replace("\"", "\\\"")
                val json = buildString {
                    append("{")
                    append("\"bytesTransferred\":${progress.bytesTransferred},")
                    append("\"totalBytes\":${progress.totalBytes},")
                    append("\"speedBytesPerSec\":${progress.speedBytesPerSec},")
                    append("\"fileName\":\"$escapedName\",")
                    append("\"filesCompleted\":$filesCompleted,")
                    append("\"totalFiles\":$dirTotalFiles,")
                    append("\"overallBytesTransferred\":$overallBytes,")
                    append("\"overallTotalBytes\":$dirTotalBytes")
                    append("}")
                }
                Files.writeString(progressFile, json)
                val now = System.currentTimeMillis()
                if (now - lastStatusSnapshotAt >= 1_000) {
                    writeQueueStatusSnapshot(queue.loadAll())
                    lastStatusSnapshotAt = now
                }
            } catch (_: Exception) { }
        }

        try { Files.deleteIfExists(progressFile) } catch (_: Exception) { }

        return when (result) {
            is TransferResult.Success -> {
                val bytes = result.bytesTransferred.coerceAtLeast(srcFile.length())

                // ── SOURCE GUARD: post-transfer verification ──
                val sourceCheck = guard.verifySourceUnchanged()
                if (!sourceCheck.passed) {
                    echo("  ${entry.id}: source changed during transfer: ${sourceCheck.detail}")
                    queue.update(entry.id) {
                        it.copy(status = TransferStatus.FAILED, error = "[SG_VERIFY] Source changed during transfer: ${sourceCheck.detail}",
                            bytesTransferred = bytes)
                    }
                    return SingleResult.FAILED
                }

                // Track whether MOVE verification succeeded (COPY always succeeds here)
                var moveVerificationFailed = false
                var moveVerificationError: String? = null

                if (entry.mode == TransferMode.MOVE) {
                    // For SQLite MOVE: verify still quiescent (WAL/SHM/lsof)
                    if (SourceGuard.isSqlite(entry.sourcePath)) {
                        val quiescentCheck = guard.verifySqliteMoveQuiescent()
                        if (!quiescentCheck.passed) {
                            echo("  ${entry.id}: SQLite modified during transfer: ${quiescentCheck.detail} — source kept")
                            queue.update(entry.id) {
                                it.copy(status = TransferStatus.FAILED, error = "[SG_VERIFY] SQLite modified: ${quiescentCheck.detail}",
                                    bytesTransferred = bytes)
                            }
                            return SingleResult.FAILED
                        }
                    }

                    if (srcFile.isDirectory) {
                        // Build manifest BEFORE verification (source will be deleted)
                        val precomputedManifest = try {
                            val existingManifest = ChoamManifest.load(srcFile)
                            val tree = DirectoryMerkleTree.buildLocal(srcFile, existingManifest)
                            tree.toManifest(srcFile)
                        } catch (e: Exception) {
                            logger.warn { "Pre-move manifest build failed (non-fatal): ${e.message}" }
                            null
                        }

                        val remoteDirPath = ManifestWriter.resolveRemoteDirPath(srcFile, entry.destinationPath)
                        val verifyResult = PostTransferVerifier.verifyDirectory(
                            srcFile, remoteDirPath, remoteMachine, route
                        )

                        if (verifyResult.hashMismatches.isNotEmpty()) {
                            echo("  ${entry.id}: HASH MISMATCH on ${verifyResult.hashMismatches.size} file(s) — possible corruption:")
                            verifyResult.hashMismatches.forEach { echo("    MISMATCH: $it") }
                        }

                        if (verifyResult.failed.isEmpty()) {
                            // All files verified by size + SHA-256 — delete source
                            srcFile.deleteRecursively()
                            // Honesty check: a discarded deleteRecursively() return (locked file, or
                            // an external writer re-creating one) must not masquerade as a clean move.
                            val survivors = survivingSourceFiles(srcFile)
                            if (survivors.isNotEmpty()) {
                                moveVerificationFailed = true
                                moveVerificationError = "Move incomplete: ${survivors.size} source file(s) survived deletion " +
                                    "(destination copy verified/safe): ${survivors.take(3).joinToString { it.name }}"
                            } else {
                                echo("  ${entry.id}: moved ${srcFile.name}/ (${verifyResult.verified} files verified by SHA-256)")
                                if (precomputedManifest != null) {
                                    ManifestWriter.writeRemoteManifestOnly(
                                        precomputedManifest, remoteDirPath, remoteMachine, route, echo
                                    )
                                }
                            }
                        } else {
                            // Partial: delete only verified files, keep unverified
                            val allFiles = srcFile.walkTopDown().filter { it.isFile }.toList()
                            val failedSet = verifyResult.failed.toSet()
                            for (file in allFiles) {
                                val relPath = file.relativeTo(srcFile).path
                                if (relPath !in failedSet) file.delete()
                            }
                            srcFile.walkBottomUp().filter { it.isDirectory }.forEach { dir ->
                                if (dir.listFiles()?.isEmpty() == true) dir.delete()
                            }
                            echo("  ${entry.id}: partial move — ${verifyResult.verified} verified, ${verifyResult.failed.size} UNVERIFIED (source kept):")
                            verifyResult.failed.forEach { echo("    UNVERIFIED: $it") }
                            moveVerificationFailed = true
                            moveVerificationError = "Move verification failed: ${verifyResult.failed.size} file(s) unverified" +
                                if (verifyResult.hashMismatches.isNotEmpty()) " (${verifyResult.hashMismatches.size} hash mismatch)" else ""
                        }
                    } else {
                        val verified = PostTransferVerifier.verifySingleFile(
                            srcFile, entry.destinationPath, remoteMachine, route
                        )
                        if (verified) {
                            Files.deleteIfExists(Path.of(entry.sourcePath))
                            // For SQLite MOVE: also delete WAL and SHM
                            if (SourceGuard.isSqlite(entry.sourcePath)) {
                                Files.deleteIfExists(Path.of("${entry.sourcePath}-wal"))
                                Files.deleteIfExists(Path.of("${entry.sourcePath}-shm"))
                            }
                            echo("  ${entry.id}: moved ${srcFile.name} (SHA-256 verified, source guarded)")
                        } else {
                            echo("  ${entry.id}: transferred but verification failed — source kept")
                            moveVerificationFailed = true
                            moveVerificationError = "Move verification failed: SHA-256 mismatch or unavailable for ${srcFile.name}"
                        }
                    }
                } else {
                    echo("  ${entry.id}: sent ${srcFile.name}")
                    // COPY: write manifests on both sides for directory transfers
                    if (srcFile.isDirectory) {
                        val remoteDirPath = ManifestWriter.resolveRemoteDirPath(srcFile, entry.destinationPath)
                        ManifestWriter.writeManifestsAfterTransfer(
                            sourceDir = srcFile,
                            remoteDirPath = remoteDirPath,
                            machine = remoteMachine,
                            route = route,
                            writeLocal = true,
                            echo = echo
                        )
                    }
                }

                if (moveVerificationFailed) {
                    // Transfer succeeded but MOVE verification failed — mark FAILED, not COMPLETED.
                    // Bounded retry + backoff so a deterministic verify failure can't spin forever.
                    failVerificationWithBackoff(entry,
                        moveVerificationError ?: "Move verification failed", bytes)
                    historyStore.record(SyncSession(
                        sourceMachine = localMachine.name,
                        targetMachine = remoteMachine.name,
                        repositories = listOf("send:${srcFile.name}"),
                        startTime = startTime,
                        endTime = Instant.now(),
                        status = SyncStatus.FAILED,
                        statistics = SyncStatistics(filesTransferred = 1, bytesTransferred = bytes, errors = 1)
                    ))
                    SingleResult.FAILED
                } else {
                    queue.update(entry.id) {
                        it.copy(
                            status = TransferStatus.COMPLETED,
                            completedAt = Instant.now().toString(),
                            bytesTransferred = bytes,
                            error = null
                        )
                    }

                    historyStore.record(SyncSession(
                        sourceMachine = localMachine.name,
                        targetMachine = remoteMachine.name,
                        repositories = listOf("send:${srcFile.name}"),
                        startTime = startTime,
                        endTime = Instant.now(),
                        status = SyncStatus.COMPLETED,
                        statistics = SyncStatistics(filesTransferred = 1, bytesTransferred = bytes)
                    ))

                    SingleResult.COMPLETED
                }
            }
            is TransferResult.Failure -> {
                // Transient failures don't count against retry limit
                val isTransient = result.errorClass == RsyncErrorClass.TRANSIENT
                val newRetryCount = if (isTransient) entry.retryCount else entry.retryCount + 1
                val exhausted = newRetryCount > TransferQueueEntry.MAX_RETRIES
                val nextRetry = if (exhausted) null else TransferQueueEntry.nextBackoff(
                    if (isTransient) entry.retryCount else newRetryCount
                )

                val suffix = buildString {
                    if (isTransient) append(" [connectivity — retry preserved]")
                    if (exhausted) append(" [max retries exhausted]")
                }

                echo("  ${entry.id}: ${result.message}$suffix")

                queue.update(entry.id) {
                    it.copy(
                        status = TransferStatus.FAILED,
                        error = result.message + suffix,
                        retryCount = newRetryCount,
                        nextRetryAt = nextRetry?.toString()
                    )
                }

                SingleResult.FAILED
            }
        }.also {
            // Cleanup progress file after transfer completes (success or failure)
            try { Files.deleteIfExists(progressFile) } catch (_: Exception) {}
        }
    }

}

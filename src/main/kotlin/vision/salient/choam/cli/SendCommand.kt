package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import vision.salient.choam.DEFAULT_BWLIMIT_KBPS
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.lowPriority
import vision.salient.choam.niceRemote
import vision.salient.choam.network.NetworkDetector
import vision.salient.choam.network.ProgressMonitor
import vision.salient.choam.network.TransferProgress
import vision.salient.choam.sync.*
import vision.salient.sietch.core.ipfs.IpfsClient
import vision.salient.sietch.core.registry.ContentLocationRegistry
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

class SendCommand : CliktCommand(
    name = "send",
    help = """
        Send files or directories to a remote machine or drive.

        Ad-hoc, resumable file transfer with optional move semantics and Sietch CID registration.
        Resolves machine names and drive labels from CHOAM config — no need to remember IPs or SSH users.

        Destination format: MACHINE:/path or DRIVE:/path
          - MACHINE is a name from config (e.g., server, laptop)
          - DRIVE is a drive label (e.g., media-4tb, backup-2tb) — resolves to the machine it's attached to

        Examples:
          choam send ~/Desktop/recording.mov server:/Volumes/media-4tb/
          choam send ~/Desktop/recording.mov media-4tb:/
          choam send ~/Desktop/*.mov media-4tb:/archive/ --move
          choam send ~/Downloads/snapshots/ backup-2tb:/backup/ --move --queue
          choam send big.tar.gz server:/tmp/ --bwlimit 5120
    """.trimIndent()
) {
    private val sources by argument(help = "Source file(s) or directory(ies) to send").multiple(required = true)
    private val destination by argument(help = "Destination in MACHINE:/path or DRIVE:/path format")
    private val move by option("--move", "-m", help = "Delete source after verified transfer (move semantics)").flag()
    private val queue by option("--queue", "-q", help = "Add to transfer queue instead of executing immediately").flag()
    private val dryRun by option("--dry-run", "-n", help = "Show what would be transferred without copying").flag()
    private val bwlimit by option("--bwlimit", help = "Bandwidth limit in KB/s (default: ${DEFAULT_BWLIMIT_KBPS})").int()
    private val noCatalog by option("--no-catalog", help = "Skip Sietch CID registration (raw transfer only)").flag()
    private val force by option("--force", "-f", help = "Allow overwriting conflicting files at destination").flag()
    private val backup by option("--backup", "-b", help = "Rename conflicting remote files to .backup_TIMESTAMP before overwriting").flag()
    private val priority by option("--priority", "-p", help = "Queue priority: low, normal, high (default: normal)")

    override fun run() {
        val config = try {
            ChoamConfigLoader.load()
        } catch (e: Exception) {
            echo("Failed to load CHOAM config: ${e.message}")
            exitProcess(1)
        }

        // Parse destination: MACHINE:/path or DRIVE:/path
        val colonIdx = destination.indexOf(':')
        if (colonIdx < 1) {
            echo("Invalid destination format. Use MACHINE:/path or DRIVE:/path")
            echo("  Available machines: ${config.machines.keys.joinToString()}")
            echo("  Available drives: ${config.drives.values.joinToString { it.label }}")
            exitProcess(1)
        }

        val destTarget = destination.substring(0, colonIdx)
        var destPath = destination.substring(colonIdx + 1)
        if (destPath.isEmpty()) destPath = "/"

        // Resolve destination: try machine name first, then drive label
        val remoteMachine = config.machines[destTarget]
            ?: config.machines[destTarget.lowercase()]
            ?: run {
                // Try drive label lookup
                val drive = config.drives.values.find {
                    it.label.equals(destTarget, ignoreCase = true)
                }
                if (drive != null) {
                    // Find which machine has this drive mounted
                    // Check repo paths for /Volumes/<label>/ prefix
                    val driveLabel = drive.label
                    val machineEntry = config.machines.entries.find { (_, machine) ->
                        machine.repositories.values.any { repoPath ->
                            repoPath.startsWith("/Volumes/$driveLabel/") ||
                                repoPath.startsWith("/Volumes/${driveLabel.lowercase()}/")
                        }
                    }
                    if (machineEntry != null) {
                        // When destination is a drive label, ALL paths are relative to the drive mount
                        // DRIVE:/foo → /Volumes/DRIVE/foo
                        // DRIVE:/ → /Volumes/DRIVE/
                        // Only skip if path already includes the full mount point
                        if (!destPath.startsWith("/Volumes/${drive.label}")) {
                            destPath = "/Volumes/${drive.label}/${destPath.removePrefix("/")}"
                        }
                        machineEntry.value
                    } else {
                        echo("Drive '${drive.label}' found in config but no machine has repositories on it")
                        exitProcess(1)
                    }
                } else {
                    // Try machine aliases
                    config.machines.values.find { machine ->
                        machine.name.equals(destTarget, ignoreCase = true) ||
                            machine.aliases.any { it.equals(destTarget, ignoreCase = true) }
                    }
                }
            }

        if (remoteMachine == null) {
            echo("Unknown destination '$destTarget'")
            echo("  Available machines: ${config.machines.keys.joinToString()}")
            echo("  Available drives: ${config.drives.values.joinToString { it.label }}")
            exitProcess(1)
        }

        // Validate sources exist
        val expandedSources = mutableListOf<String>()
        for (src in sources) {
            val file = File(src)
            if (file.exists()) {
                expandedSources.add(file.absolutePath)
            } else {
                // Try glob expansion in parent directory
                val parent = file.parentFile ?: File(".")
                val pattern = file.name.replace("*", ".*")
                val matches = parent.listFiles { _, name -> name.matches(Regex(pattern)) }
                if (matches != null && matches.isNotEmpty()) {
                    expandedSources.addAll(matches.map { it.absolutePath })
                } else {
                    // Fuzzy match: macOS uses Unicode narrow no-break spaces (U+202F) in timestamps
                    // that get lost when passed through CLI args. Try normalizing whitespace for matching.
                    val normalizedTarget = file.name.replace(Regex("\\s+"), " ")
                    val fuzzyMatches = parent.listFiles { _, name ->
                        name.replace(Regex("[\\s\\u00a0\\u202f]+"), " ") == normalizedTarget
                    }
                    if (fuzzyMatches != null && fuzzyMatches.isNotEmpty()) {
                        expandedSources.addAll(fuzzyMatches.map { it.absolutePath })
                    } else {
                        echo("Source not found: $src")
                        exitProcess(1)
                    }
                }
            }
        }

        val transferPriority = when (priority?.lowercase()) {
            "low" -> TransferPriority.LOW
            "high" -> TransferPriority.HIGH
            else -> TransferPriority.NORMAL
        }

        // Guard: reject direct directory --move (must use --queue --move)
        if (move && !queue) {
            val hasDirectory = expandedSources.any { File(it).isDirectory }
            if (hasDirectory) {
                echo("Error: Direct directory --move is not supported.")
                echo("Use: choam send <dir> <dest> --move --queue")
                echo("Queued moves use tree-aware SHA-256 verification before deleting source files.")
                exitProcess(1)
            }
        }

        // Queue mode: add to queue and exit
        if (queue) {
            val transferQueue = TransferQueueStore()
            for (src in expandedSources) {
                val entry = TransferQueueEntry(
                    sourcePath = src,
                    destinationMachine = remoteMachine.name,
                    destinationPath = destPath,
                    mode = if (move) TransferMode.MOVE else TransferMode.COPY,
                    priority = transferPriority,
                    bandwidthLimitKBps = bwlimit,
                    noCatalog = noCatalog,
                    totalBytes = File(src).let { if (it.isFile) it.length() else 0L },
                    force = force,
                    backup = backup
                )
                transferQueue.add(entry)
                val modeLabel = if (move) "move" else "copy"
                echo("Queued [$modeLabel] ${entry.id}: ${File(src).name} → ${remoteMachine.name}:$destPath")
            }
            echo("\nRun 'choam queue --run' to process the queue.")
            return
        }

        // Immediate execution
        val networkDetector = NetworkDetector()
        val localMachine = TargetResolver(config).findLocalMachine()
        if (localMachine == null) {
            echo("Cannot determine local machine. Check config hostname.")
            exitProcess(1)
        }

        val route = networkDetector.detectBestRoute(localMachine, remoteMachine)
        val connectivity = networkDetector.testConnectivity(route)

        if (!connectivity.reachable) {
            echo("${remoteMachine.name} is unreachable at ${route.targetAddress}")
            exitProcess(1)
        }

        val latencyStr = connectivity.latency?.toMillis()?.let { "${it}ms" } ?: "unknown"
        echo("Send: ${expandedSources.size} item(s) → ${remoteMachine.name} (${route.mode}, ${latencyStr} latency)")
        if (dryRun) echo("[DRY RUN]")
        if (move) echo("[MOVE — source will be deleted after verified transfer]")

        // ── PRE-FLIGHT SAFETY CHECK ──
        // Default: REFUSE to overwrite. The user must explicitly opt in via --force or --backup.
        var filesToSend: List<String> = expandedSources
        val preflightOutcome = SendPreflight.checkRemoteFiles(expandedSources, destPath, remoteMachine, route)

        when (preflightOutcome) {
            is PreflightOutcome.Resolved -> {
                val preflight = preflightOutcome.result
                SendPreflight.printManifest(preflight, ::echo)

                if (preflight.hasConflicts && !force && !backup) {
                    exitProcess(1)
                }

                if (preflight.hasConflicts && backup) {
                    echo("\n  Backing up ${preflight.conflictFiles.size} conflicting remote file(s)...")
                    val backed = SendPreflight.backupRemoteFiles(preflight.conflictFiles, remoteMachine, route)
                    if (!backed) {
                        echo("  \u001b[31mRemote backup failed — aborting.\u001b[0m")
                        exitProcess(1)
                    }
                    echo("  \u001b[32m✓\u001b[0m Remote backups created.")
                }

                if (preflight.hasConflicts && force && !backup) {
                    echo("\n  \u001b[33m--force: overwriting ${preflight.conflictFiles.size} conflicting file(s)\u001b[0m")
                }

                // Filter out IDENTICAL files — no point re-sending them
                if (preflight.identicalFiles.isNotEmpty()) {
                    val identicalPaths = preflight.identicalFiles.map { it.localPath }.toSet()
                    filesToSend = expandedSources.filter { it !in identicalPaths }
                    echo("  Skipping ${identicalPaths.size} identical file(s).")
                }

                if (filesToSend.isEmpty()) {
                    echo("\n\u001b[32m✓\u001b[0m All files already present at destination. Nothing to transfer.")
                    // Backfill manifests for already-identical directory copies (seeds the fast path)
                    if (!dryRun && !move) {
                        for (src in expandedSources) {
                            val srcFile = File(src)
                            if (srcFile.isDirectory) {
                                try {
                                    val remoteDirPath = ManifestWriter.resolveRemoteDirPath(srcFile, destPath)
                                    ManifestWriter.writeManifestsAfterTransfer(
                                        sourceDir = srcFile,
                                        remoteDirPath = remoteDirPath,
                                        machine = remoteMachine,
                                        route = route,
                                        writeLocal = true,
                                        echo = ::echo
                                    )
                                } catch (e: Exception) {
                                    echo("  \u001b[33m⚠ Manifest write failed for ${srcFile.name}: ${e.message}\u001b[0m")
                                    logger.warn(e) { "Manifest write failed for ${srcFile.name}" }
                                }
                            }
                        }
                    }
                    return
                }
            }
            is PreflightOutcome.FallThrough -> {
                // SSH pre-flight failed — can't check remote state, but that doesn't mean there's a conflict.
                // Proceed with the transfer; rsync will handle connectivity errors on its own.
                echo("\n  \u001b[33mPre-flight check skipped (SSH unreachable). Transfer will proceed — rsync handles retries.\u001b[0m")
            }
            is PreflightOutcome.UnsafeAbort -> {
                echo("\n  \u001b[31mPre-flight ABORTED: ${preflightOutcome.reason}\u001b[0m")
                echo("  Transfer cancelled. Resolve the issue and retry.")
                exitProcess(1)
            }
        }

        val rsyncEngine = RsyncTransferEngine()
        val historyStore = SyncHistoryStore()
        val progressMonitor = ProgressMonitor()
        val startTime = Instant.now()
        var totalBytesTransferred = 0L
        var totalFiles = 0
        var errors = 0

        for (src in filesToSend) {
            val srcFile = File(src)
            val srcName = srcFile.name
            val displaySize = if (srcFile.isDirectory) {
                srcFile.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } else srcFile.length()
            echo("\n  ${srcName} (${ProgressMonitor.formatBytes(displaySize)})")

            val transferMode = if (move) TransferMode.MOVE else TransferMode.COPY
            val guard = try {
                SourceGuard.acquire(src, transferMode, UUID.randomUUID().toString().take(8))
            } catch (e: SourceGuardException) {
                echo("  \u001b[31m✗\u001b[0m SourceGuard: ${e.message}")
                errors++
                continue
            }

            // For SQLite MOVE: checkpoint WAL and verify quiescent before transfer
            // Skip on dry-run — checkpointing mutates the source, violating dry-run expectations
            if (move && !dryRun && SourceGuard.isSqlite(src)) {
                try {
                    guard.checkpointAndVerifyWal()
                } catch (e: SourceGuardException) {
                    echo("  \u001b[31m✗\u001b[0m SQLite not quiescent: ${e.message}")
                    guard.close()
                    errors++
                    continue
                }
            }

            try {
                // Retry transient failures (SSH drop, timeout) up to 3 times with backoff
                var result: vision.salient.choam.network.TransferResult? = null
                for (attempt in 1..3) {
                    result = rsyncEngine.transfer(
                        sourcePath = src,
                        targetPath = destPath,
                        targetMachine = remoteMachine,
                        route = route,
                        bandwidthLimitKBps = bwlimit,
                        excludePatterns = listOf(MANIFEST_FILENAME),
                        dryRun = dryRun,
                        // Place the source under the destination by basename (<dest>/<name>),
                        // matching post-transfer verification. Never merge contents into the parent.
                        appendSourceSlash = false
                    ) { progress ->
                        progressMonitor.displayProgress(
                            SyncSession(
                                sourceMachine = localMachine.name,
                                targetMachine = remoteMachine.name,
                                repositories = listOf("send"),
                                startTime = startTime,
                                status = SyncStatus.TRANSFERRING
                            ),
                            progress
                        )
                    }

                    if (result is vision.salient.choam.network.TransferResult.Success) break
                    val failure = result as vision.salient.choam.network.TransferResult.Failure
                    if (failure.errorClass != vision.salient.choam.network.RsyncErrorClass.TRANSIENT || attempt == 3) break
                    val delaySec = attempt * 10L
                    echo("  \u001b[33m⚠\u001b[0m Transient failure (attempt $attempt/3), retrying in ${delaySec}s...")
                    Thread.sleep(delaySec * 1000)
                }

                when (result) {
                    is vision.salient.choam.network.TransferResult.Success -> {
                        totalBytesTransferred += result.bytesTransferred.coerceAtLeast(srcFile.length())
                        totalFiles++
                        // Clear progress line
                        print("\r\u001b[K")

                        // ── SOURCE GUARD: post-transfer verification ──
                        val sourceCheck = guard.verifySourceUnchanged()
                        if (!sourceCheck.passed) {
                            echo("  \u001b[31m✗\u001b[0m Source changed during transfer: ${sourceCheck.detail}")
                            echo("  Transfer completed but source integrity lost — treating as failed")
                            errors++
                            continue
                        }

                        if (!dryRun && !noCatalog) {
                            registerWithSietch(src, remoteMachine, destPath, route)
                        }

                        if (!dryRun && move) {
                            // For SQLite MOVE: verify still quiescent (WAL/SHM/lsof)
                            if (SourceGuard.isSqlite(src)) {
                                val quiescentCheck = guard.verifySqliteMoveQuiescent()
                                if (!quiescentCheck.passed) {
                                    echo("  \u001b[31m✗\u001b[0m SQLite modified during transfer: ${quiescentCheck.detail}")
                                    echo("  Source NOT deleted")
                                    errors++
                                    continue
                                }
                            }

                            // Verify remote file by size + SHA-256 before deleting
                            val verified = PostTransferVerifier.verifySingleFile(
                                srcFile, destPath, remoteMachine, route
                            )
                            if (verified) {
                                Files.deleteIfExists(Path.of(src))
                                // For SQLite MOVE: also delete WAL and SHM
                                if (SourceGuard.isSqlite(src)) {
                                    Files.deleteIfExists(Path.of("$src-wal"))
                                    Files.deleteIfExists(Path.of("$src-shm"))
                                }
                                echo("  \u001b[32m✓\u001b[0m Moved: $srcName (SHA-256 verified, source guarded)")
                            } else {
                                echo("  \u001b[33m⚠\u001b[0m Transferred but verification failed — source NOT deleted: $srcName")
                                errors++
                            }
                        } else if (!dryRun) {
                            echo("  \u001b[32m✓\u001b[0m Sent: $srcName (source guarded)")
                            // COPY: write manifests on both sides for directory transfers
                            if (srcFile.isDirectory) {
                                try {
                                    val remoteDirPath = ManifestWriter.resolveRemoteDirPath(srcFile, destPath)
                                    ManifestWriter.writeManifestsAfterTransfer(
                                        sourceDir = srcFile,
                                        remoteDirPath = remoteDirPath,
                                        machine = remoteMachine,
                                        route = route,
                                        writeLocal = true,
                                        echo = ::echo
                                    )
                                } catch (e: Exception) {
                                    echo("  \u001b[33m⚠ Manifest write failed for ${srcFile.name}: ${e.message}\u001b[0m")
                                    logger.warn(e) { "Manifest write failed for ${srcFile.name}" }
                                }
                            }
                        } else {
                            echo("  Would send: $srcName")
                        }
                    }
                    is vision.salient.choam.network.TransferResult.Failure -> {
                        print("\r\u001b[K")
                        echo("  \u001b[31m✗\u001b[0m Failed: $srcName — ${result.message}")
                        errors++
                    }
                    null -> {
                        echo("  \u001b[31m✗\u001b[0m Failed: $srcName — no transfer result")
                        errors++
                    }
                }
            } finally {
                guard.close()
            }
        }

        val endTime = Instant.now()
        val duration = Duration.between(startTime, endTime)
        echo("\n${if (errors == 0) "\u001b[32m✓\u001b[0m" else "\u001b[31m✗\u001b[0m"} " +
            "${ProgressMonitor.formatBytes(totalBytesTransferred)} transferred, " +
            "$totalFiles file(s), ${ProgressMonitor.formatDuration(duration)}" +
            if (errors > 0) ", $errors error(s)" else "")

        // Record in history
        if (!dryRun) {
            val session = SyncSession(
                sourceMachine = localMachine.name,
                targetMachine = remoteMachine.name,
                repositories = listOf("send:${filesToSend.first().substringAfterLast('/')}"),
                startTime = startTime,
                endTime = endTime,
                status = if (errors == 0) SyncStatus.COMPLETED else SyncStatus.FAILED,
                statistics = SyncStatistics(
                    filesTransferred = totalFiles.toLong(),
                    bytesTransferred = totalBytesTransferred,
                    errors = errors
                )
            )
            historyStore.record(session)
        }

        if (errors > 0) exitProcess(1)
    }

    /**
     * Register the transferred file in the unified registry so other tools
     * (search, inspect, etc.) can discover it immediately.
     *
     * CID strategy: try IPFS computeCid via local Kubo node first (gives a real
     * IPFS-compatible CID). If IPFS is unavailable, fall back to sha256:<hex> hash.
     */
    private fun registerWithSietch(
        localPath: String,
        remoteMachine: vision.salient.choam.config.MachineProfile,
        remoteDestPath: String,
        route: vision.salient.choam.network.NetworkRoute
    ) {
        try {
            val srcFile = File(localPath)
            val fileName = srcFile.name
            val remoteFullPath = if (remoteDestPath.endsWith("/")) "$remoteDestPath$fileName" else remoteDestPath
            val fileSize = srcFile.length()

            // Compute CID: try IPFS first, fall back to SHA-256
            val cid = computeCidForFile(srcFile)

            // Open the unified registry (same DB that catalog-sync, search, inspect all use)
            val unifiedDbPath = Path.of(System.getProperty("user.home"), ".choam", "unified_registry.db")
            val registry = ContentLocationRegistry(unifiedDbPath)
            try {
                registry.register(cid, remoteMachine.name, remoteFullPath, fileSize)
            } finally {
                registry.close()
            }

            echo("  Registered: $fileName on ${remoteMachine.name}")
            logger.info { "Registered in unified registry: cid=$cid machine=${remoteMachine.name} path=$remoteFullPath size=$fileSize" }
        } catch (e: Exception) {
            // Non-fatal — transfer already succeeded, registration is best-effort
            logger.warn { "Sietch registration failed (non-fatal): ${e.message}" }
            echo("  Warning: catalog registration failed — ${e.message}")
        }
    }

    /**
     * Compute a content identifier for the file.
     * Tries IPFS (Kubo) computeCid first for a real IPFS CID.
     * Falls back to sha256:<hex> if IPFS is unavailable.
     */
    private fun computeCidForFile(file: File): String {
        // Try IPFS via local Kubo node
        try {
            val ipfsClient = IpfsClient("http://127.0.0.1:5001")
            try {
                val available = runBlocking { ipfsClient.isAvailable() }
                if (available) {
                    val cid = runBlocking { ipfsClient.computeCid(file.toPath()) }
                    logger.info { "Computed IPFS CID for ${file.name}: $cid" }
                    return cid
                }
            } finally {
                ipfsClient.close()
            }
        } catch (e: Exception) {
            logger.debug { "IPFS CID computation failed, falling back to SHA-256: ${e.message}" }
        }

        // Fallback: SHA-256 hash with streaming reads
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        FileInputStream(file).use { fis ->
            var bytesRead = fis.read(buffer)
            while (bytesRead != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesRead = fis.read(buffer)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        logger.info { "Computed SHA-256 for ${file.name}: sha256:$hex" }
        return "sha256:$hex"
    }

}

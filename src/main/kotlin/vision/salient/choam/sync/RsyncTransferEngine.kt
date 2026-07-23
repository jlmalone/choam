package vision.salient.choam.sync

import mu.KotlinLogging
import vision.salient.choam.config.MachineProfile
import vision.salient.choam.network.NetworkRoute
import vision.salient.choam.network.RsyncErrorClass
import vision.salient.choam.network.TransferProgress
import vision.salient.choam.network.TransferResult
import vision.salient.choam.network.classifyRsyncExit
import vision.salient.choam.DEFAULT_BWLIMIT_KBPS
import vision.salient.choam.lowPriority
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

private val logger = KotlinLogging.logger {}

class RsyncTransferEngine {

    companion object {
        /**
         * Apple ships openrsync as /usr/bin/rsync (protocol 29). Its --partial-dir handling is
         * unreliable: partials from dropped connections are often left behind as orphaned temp
         * files, and a salvaged partial is not reused as a delta basis, so every retry of a
         * large transfer restarts from byte zero. Prefer a real rsync 3.x when installed.
         */
        private val REAL_RSYNC_PATHS = listOf("/opt/homebrew/bin/rsync", "/usr/local/bin/rsync")

        val localRsyncBinary: String by lazy {
            System.getenv("CHOAM_RSYNC_PATH")?.takeIf { File(it).canExecute() }
                ?: REAL_RSYNC_PATHS.firstOrNull { File(it).canExecute() }
                ?: "rsync"
        }

        // "user@host:port" -> probed remote rsync path ("" = probed, none found)
        private val remoteRsyncCache = ConcurrentHashMap<String, String>()

        /**
         * Resume needs a real rsync on the RECEIVING side too: the receiver is what salvages a
         * dropped transfer into --partial-dir and reuses it as the delta basis on retry. Probe
         * once per host per process; on any failure fall back to the remote default binary,
         * which is exactly the pre-probe behavior.
         */
        fun probeRemoteRsync(sshUser: String?, host: String, sshPort: Int): String? {
            val key = "${sshUser ?: ""}@$host:$sshPort"
            remoteRsyncCache[key]?.let { return it.ifEmpty { null } }
            val found = runCatching {
                val target = if (sshUser != null) "$sshUser@$host" else host
                val script = REAL_RSYNC_PATHS.joinToString(" ") {
                    "[ -x $it ] && { echo $it; exit 0; };"
                } + " exit 1"
                val cmd = buildList {
                    add("ssh")
                    if (sshPort != 22) { add("-p"); add(sshPort.toString()) }
                    add("-o"); add("ConnectTimeout=10")
                    add("-o"); add("BatchMode=yes")
                    add(target)
                    add(script)
                }
                val process = ProcessBuilder(cmd).start()
                val output = process.inputStream.bufferedReader().readText().trim()
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return@runCatching null
                }
                output.takeIf { process.exitValue() == 0 && it.startsWith("/") }
            }.getOrNull()
            remoteRsyncCache[key] = found ?: ""
            if (found != null) {
                logger.info { "Remote rsync for $host: $found" }
            } else {
                logger.info { "No real rsync found on $host; resume of interrupted transfers may be unreliable (openrsync)" }
            }
            return found
        }
    }

    /**
     * Build and execute an rsync command for file transfer.
     * Returns the transfer result.
     */
    fun transfer(
        sourcePath: String,
        targetPath: String,
        sourceMachine: MachineProfile? = null,
        targetMachine: MachineProfile? = null,
        route: NetworkRoute? = null,
        bandwidthLimitKBps: Int? = null,
        excludePatterns: List<String> = emptyList(),
        dryRun: Boolean = false,
        useChecksum: Boolean = false,
        timeoutSeconds: Int? = null,
        appendSourceSlash: Boolean? = null,
        progressCallback: (TransferProgress) -> Unit = {}
    ): TransferResult {
        // The remote end must also run a real rsync for --partial-dir salvage/reuse to work.
        val remoteEndpoint = when {
            targetMachine != null && route != null -> targetMachine to route.targetAddress
            sourceMachine != null && route != null -> sourceMachine to route.sourceAddress
            else -> null
        }
        val remoteRsyncPath = remoteEndpoint?.let { (machine, host) ->
            probeRemoteRsync(machine.sshUser, host, machine.sshPort)
        }

        val command = buildRsyncCommand(
            sourcePath = sourcePath,
            targetPath = targetPath,
            sourceMachine = sourceMachine,
            targetMachine = targetMachine,
            route = route,
            bandwidthLimitKBps = bandwidthLimitKBps,
            excludePatterns = excludePatterns,
            dryRun = dryRun,
            useChecksum = useChecksum,
            timeoutSeconds = timeoutSeconds,
            appendSourceSlash = appendSourceSlash,
            remoteRsyncPath = remoteRsyncPath
        )

        val wrappedCommand = lowPriority(command)
        logger.info { "Executing: ${wrappedCommand.joinToString(" ")}" }

        return try {
            val process = ProcessBuilder(wrappedCommand)
                .start() // Don't merge stderr — capture it separately

            // Capture stderr in a background thread
            val stderrBuilder = StringBuilder()
            val stderrThread = Thread {
                try {
                    process.errorStream.bufferedReader().forEachLine { line ->
                        stderrBuilder.appendLine(line)
                    }
                } catch (_: Exception) { }
            }.apply {
                isDaemon = true
                start()
            }

            // Parse rsync progress output from stdout
            val reader = process.inputStream.bufferedReader()
            val actualBytesTransferred = parseRsyncOutput(reader, progressCallback)

            val exitCode = process.waitFor()
            stderrThread.join(5000) // Wait up to 5s for stderr to finish
            val stderrText = stderrBuilder.toString().trim()

            if (exitCode == 0) {
                logger.info { "rsync completed successfully ($actualBytesTransferred bytes)" }
                TransferResult.Success(bytesTransferred = actualBytesTransferred)
            } else {
                val errorClass = classifyRsyncExit(exitCode)
                val errorDetail = if (stderrText.isNotEmpty()) stderrText else "no stderr output"
                val errorMsg = "rsync failed with exit code $exitCode ($errorDetail)"
                logger.error { errorMsg }
                TransferResult.Failure(errorMsg, errorClass = errorClass)
            }
        } catch (e: IOException) {
            logger.error(e) { "Failed to execute rsync" }
            TransferResult.Failure("Failed to execute rsync: ${e.message}", e)
        }
    }

    /**
     * Build the rsync command line arguments.
     * Visible for testing.
     */
    fun buildRsyncCommand(
        sourcePath: String,
        targetPath: String,
        sourceMachine: MachineProfile? = null,
        targetMachine: MachineProfile? = null,
        route: NetworkRoute? = null,
        bandwidthLimitKBps: Int? = null,
        excludePatterns: List<String> = emptyList(),
        dryRun: Boolean = false,
        useChecksum: Boolean = false,
        timeoutSeconds: Int? = null,
        appendSourceSlash: Boolean? = null,
        rsyncBinary: String = localRsyncBinary,
        remoteRsyncPath: String? = null
    ): List<String> = buildList {
        add(rsyncBinary)
        add("--archive")
        add("--verbose")
        add("--progress")
        add("--partial-dir=.rsync-partial")

        if (useChecksum) {
            add("--checksum")
        }

        // Adaptive timeout: caller can override, otherwise use sensible default
        val timeout = timeoutSeconds ?: 300  // 5 minutes — generous for WiFi/Tailscale
        add("--timeout=$timeout")

        if (dryRun) {
            add("--dry-run")
            add("--itemize-changes")
        }

        val effectiveBwLimit = bandwidthLimitKBps ?: DEFAULT_BWLIMIT_KBPS
        add("--bwlimit=$effectiveBwLimit")

        for (pattern in excludePatterns) {
            add("--exclude=$pattern")
        }

        // Determine if transfer requires SSH
        // rsync can't handle "remote to remote" - both source and dest can't use @host: syntax
        // Therefore: if BOTH machines are specified, that's an error; if ONE is specified, it's remote

        val isRemoteSource = sourceMachine != null && route != null
        val isRemoteTarget = targetMachine != null && route != null

        // Can't transfer remote-to-remote (rsync limitation)
        if (isRemoteSource && isRemoteTarget) {
            logger.warn {
                "Warning: rsync does not support direct remote-to-remote transfers. " +
                "This requires SSH chaining or local staging. Proceeding with best-effort approach."
            }
        }

        if (isRemoteSource || isRemoteTarget) {
            val sshPort = if (isRemoteTarget) targetMachine?.sshPort ?: 22
                         else sourceMachine?.sshPort ?: 22
            // Always use SSH with keepalive to prevent connection drops over Tailscale/VPN
            val sshOpts = buildString {
                append("ssh")
                if (sshPort != 22) append(" -p $sshPort")
                append(" -o ServerAliveInterval=30 -o ServerAliveCountMax=5")
            }
            add("-e")
            add(sshOpts)
            if (remoteRsyncPath != null) {
                add("--rsync-path=$remoteRsyncPath")
            }
        }

        // Build source spec
        val sourceSpec = if (isRemoteSource) {
            val user = sourceMachine!!.sshUser
            val host = route!!.sourceAddress
            if (user != null) "$user@$host:$sourcePath" else "$host:$sourcePath"
        } else {
            sourcePath
        }

        // Build target spec
        val targetSpec = if (isRemoteTarget) {
            val user = targetMachine!!.sshUser
            val host = route!!.targetAddress
            if (user != null) "$user@$host:$targetPath" else "$host:$targetPath"
        } else {
            targetPath
        }

        // Source slash controls rsync placement semantics:
        //   NO trailing slash -> rsync creates <target>/<basename>      (place item by name)
        //   trailing slash    -> rsync merges <source>/* into <target>  (merge contents)
        // The send/move/queue flows verify at <target>/<basename> (see
        // ManifestWriter.resolveRemoteDirPath + PostTransferVerifier), so they MUST pass
        // appendSourceSlash=false. When null we fall back to the legacy name-based heuristic,
        // which guesses file-vs-dir from a dot in the name — unreliable (it broke dotless
        // directory names like "sol-bianca-the-legacy"), but preserved for sync/push/pull/move
        // callers that depend on content-merge semantics.
        val sourceArg = when (appendSourceSlash) {
            false -> stripTrailingSlash(sourceSpec)
            true -> ensureTrailingSlash(sourceSpec)
            null -> ensureTrailingSlashForDirectories(sourceSpec)
        }
        add(sourceArg)
        // Target is always a directory destination here; a trailing slash is benign and the
        // verifier resolves <target>/<basename> from it.
        add(ensureTrailingSlashForDirectories(targetSpec))
    }

    /** Apply [transform] to the path portion of a possibly-remote (user@host:path) spec. */
    private fun withPathComponent(path: String, transform: (String) -> String): String {
        val colonIdx = path.indexOf(':')
        return if (colonIdx >= 0) {
            path.substring(0, colonIdx + 1) + transform(path.substring(colonIdx + 1))
        } else {
            transform(path)
        }
    }

    /** Force a trailing slash on the path component (merge-contents semantics). */
    private fun ensureTrailingSlash(path: String): String =
        withPathComponent(path) { if (it.endsWith("/")) it else "$it/" }

    /** Strip a trailing slash from the path component (place-by-basename semantics). */
    private fun stripTrailingSlash(path: String): String =
        withPathComponent(path) { it.trimEnd('/').ifEmpty { it } }

    private fun ensureTrailingSlashForDirectories(path: String): String {
        // Extract the actual path from remote specs (user@host:path)
        val colonIdx = path.indexOf(':')
        val (prefix, actualPath) = if (colonIdx >= 0) {
            path.substring(0, colonIdx + 1) to path.substring(colonIdx + 1)
        } else {
            "" to path
        }

        // Already has trailing slash, keep it
        if (actualPath.endsWith("/")) return path

        // Directory patterns get trailing slash
        if (actualPath.endsWith("*")) return "$path/"

        // Files with extensions don't get trailing slash
        // A simple heuristic: if path contains a dot in the last component, treat as file
        val lastComponent = actualPath.substringAfterLast("/")
        if (lastComponent.contains(".")) {
            // This looks like a file (has extension), don't add trailing slash
            return path
        }

        // No extension - treat as directory and add trailing slash
        return "$path/"
    }

    /**
     * Parse rsync --progress output to extract transfer statistics.
     */
    private fun parseRsyncOutput(
        reader: BufferedReader,
        progressCallback: (TransferProgress) -> Unit
    ): Long {
        // rsync progress line pattern: "  1,234,567  45%  12.34MB/s  0:01:23"
        val progressPattern = Pattern.compile(
            """^\s*([\d,]+)\s+(\d+)%\s+([\d.]+\w+/s)\s+(\S+)"""
        )

        var currentFile = ""
        var completedBytes = 0L   // Accumulated bytes from finished files
        var currentFileBytes = 0L // Latest cumulative bytes for the current file

        reader.forEachLine { line ->
            val trimmed = line.trim()

            // Detect file being transferred (lines that are just filenames)
            if (!trimmed.startsWith(" ") && !trimmed.contains("%") && trimmed.isNotEmpty()) {
                // New file starting — bank the previous file's bytes
                if (currentFile.isNotEmpty()) {
                    completedBytes += currentFileBytes
                    currentFileBytes = 0
                }
                currentFile = trimmed
            }

            // Parse progress lines — bytes are cumulative per file (10MB, 20MB, 30MB)
            val matcher = progressPattern.matcher(trimmed)
            if (matcher.find()) {
                val bytesStr = matcher.group(1).replace(",", "")
                val percent = matcher.group(2).toIntOrNull() ?: 0
                val speedStr = matcher.group(3)

                val bytes = bytesStr.toLongOrNull() ?: 0
                val speedBps = parseSpeed(speedStr)
                val totalBytes = if (percent > 0) bytes * 100 / percent else bytes

                currentFileBytes = bytes // Overwrite — rsync progress is cumulative

                progressCallback(
                    TransferProgress(
                        fileName = currentFile,
                        bytesTransferred = bytes,
                        totalBytes = totalBytes,
                        speedBytesPerSec = speedBps,
                        eta = null
                    )
                )
            }
        }
        return completedBytes + currentFileBytes
    }

    /**
     * Parse rsync speed string like "12.34MB/s" into bytes/sec.
     */
    private fun parseSpeed(speedStr: String): Long {
        val pattern = Pattern.compile("""([\d.]+)\s*(B|KB|MB|GB|TB)/s""", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(speedStr)
        if (!matcher.find()) return 0

        val value = matcher.group(1).toDoubleOrNull() ?: return 0
        val unit = matcher.group(2).uppercase()

        return when (unit) {
            "B" -> value.toLong()
            "KB" -> (value * 1024).toLong()
            "MB" -> (value * 1024 * 1024).toLong()
            "GB" -> (value * 1024 * 1024 * 1024).toLong()
            "TB" -> (value * 1024 * 1024 * 1024 * 1024).toLong()
            else -> 0
        }
    }
}

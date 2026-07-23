package vision.salient.choam.network

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import vision.salient.choam.config.ChoamConfig
import vision.salient.choam.sync.RsyncTransferEngine

private val transferLogger = KotlinLogging.logger {}

class TransferManager(
    private val config: ChoamConfig,
    private val useRsync: Boolean = true
) {
    private val rsyncEngine = RsyncTransferEngine()

    /**
     * Transfer a directory using rsync.
     * This is the preferred method for syncing entire repositories.
     */
    fun transferDirectory(
        sourcePath: String,
        targetPath: String,
        sourceMachine: vision.salient.choam.config.MachineProfile,
        targetMachine: vision.salient.choam.config.MachineProfile,
        route: NetworkRoute,
        bandwidthLimitKBps: Int? = null,
        excludePatterns: List<String> = emptyList(),
        dryRun: Boolean = false,
        progressCallback: (TransferProgress) -> Unit = {}
    ): TransferResult {
        val isRemote = sourceMachine.name != targetMachine.name

        return rsyncEngine.transfer(
            sourcePath = sourcePath,
            targetPath = targetPath,
            sourceMachine = if (isRemote) sourceMachine else null,
            targetMachine = if (isRemote) targetMachine else null,
            route = if (isRemote) route else null,
            bandwidthLimitKBps = bandwidthLimitKBps,
            excludePatterns = excludePatterns,
            dryRun = dryRun,
            progressCallback = progressCallback
        )
    }

    suspend fun transferFile(
        source: FileLocation,
        target: FileLocation,
        route: NetworkRoute,
        bandwidthLimitKBps: Int? = null,
        progressCallback: (TransferProgress) -> Unit
    ): TransferResult =
        withContext(Dispatchers.IO) {
            // Check if this is a remote transfer
            // Consider it local if both machines resolve to localhost/same address
            val isRemote = source.machine.name != target.machine.name &&
                route.sourceAddress != route.targetAddress

            if (isRemote) {
                return@withContext transferRemote(source, target, route, bandwidthLimitKBps, progressCallback)
            }

            // Local transfer
            val sourcePath = Path.of(source.path)
            val targetPath = Path.of(target.path)

            if (!Files.exists(sourcePath)) {
                return@withContext TransferResult.Failure("Source file does not exist: $sourcePath")
            }

            try {
                targetPath.parent?.let { Files.createDirectories(it) }
                val totalBytes = Files.size(sourcePath)

                var bytesCopied = 0L
                val startTime = System.nanoTime()
                var intervalStart = startTime
                var intervalBytes = 0L

                // Convert bandwidth limit from KB/s to bytes/second
                val bandwidthLimitBytesPerSec = bandwidthLimitKBps?.let { it * 1024L }

                if (bandwidthLimitBytesPerSec != null) {
                    transferLogger.debug { "Bandwidth limit: ${bandwidthLimitKBps} KB/s" }
                }

                Files.newInputStream(sourcePath).use { input ->
                    Files.newOutputStream(targetPath).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            bytesCopied += read
                            intervalBytes += read

                            // Apply bandwidth throttling if configured
                            if (bandwidthLimitBytesPerSec != null && intervalBytes > buffer.size) {
                                val now = System.nanoTime()
                                val intervalElapsedNanos = now - intervalStart
                                val intervalElapsedSec = intervalElapsedNanos / 1_000_000_000.0
                                val allowedBytes = (bandwidthLimitBytesPerSec * intervalElapsedSec).toLong()

                                if (intervalBytes > allowedBytes) {
                                    val excessBytes = intervalBytes - allowedBytes
                                    val sleepMs = (excessBytes * 1000L / bandwidthLimitBytesPerSec)
                                    if (sleepMs > 0) {
                                        Thread.sleep(sleepMs.coerceAtMost(1000)) // Max 1 second sleep
                                    }
                                }

                                // Reset interval tracking every second
                                if (intervalElapsedSec >= 1.0) {
                                    intervalStart = System.nanoTime()
                                    intervalBytes = 0L
                                }
                            }

                            val elapsedNanos = System.nanoTime() - startTime
                            val speedBytesPerSec =
                                if (elapsedNanos > 0) {
                                    bytesCopied * 1_000_000_000L / elapsedNanos
                                } else {
                                    0L
                                }
                            val remaining = (totalBytes - bytesCopied).coerceAtLeast(0)
                            val eta =
                                if (speedBytesPerSec > 0 && remaining > 0) {
                                    Duration.ofSeconds(remaining / speedBytesPerSec)
                                } else {
                                    null
                                }

                            progressCallback(
                                TransferProgress(
                                    fileName = source.path,
                                    bytesTransferred = bytesCopied,
                                    totalBytes = totalBytes,
                                    speedBytesPerSec = speedBytesPerSec,
                                    eta = eta
                                )
                            )
                        }
                    }
                }

                val sourceChecksum = checksum(sourcePath)
                val targetChecksum = checksum(targetPath)
                if (sourceChecksum != targetChecksum) {
                    transferLogger.error {
                        "Checksum mismatch after transfer: $sourcePath -> $targetPath"
                    }
                    try {
                        Files.deleteIfExists(targetPath)
                        transferLogger.warn { "Deleted corrupted target file $targetPath after checksum mismatch" }
                    } catch (e: IOException) {
                        transferLogger.error(e) {
                            "Failed to delete corrupted target file $targetPath after checksum mismatch"
                        }
                    }
                    return@withContext TransferResult.Failure("Checksum mismatch for ${source.path}")
                }

                transferLogger.info {
                    "Transferred $bytesCopied bytes from $sourcePath to $targetPath (checksum verified)"
                }
                TransferResult.Success(bytesTransferred = bytesCopied)
            } catch (e: IOException) {
                TransferResult.Failure("File transfer failed: ${e.message}", e)
            }
        }

    private fun checksum(path: Path, algorithm: String = "SHA-256"): String {
        val digest = MessageDigest.getInstance(algorithm)
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun transferRemote(
        source: FileLocation,
        target: FileLocation,
        route: NetworkRoute,
        bandwidthLimitKBps: Int?,
        progressCallback: (TransferProgress) -> Unit
    ): TransferResult = withContext(Dispatchers.IO) {
        transferLogger.info {
            "Remote transfer via rsync: ${source.machine.name}:${source.path} -> " +
                "${target.machine.name}:${target.path} via ${route.mode}"
        }

        // rsync can't handle both source and target being remote specifications
        // Check if source path is accessible locally; if so, don't specify sourceMachine
        val sourcePath = Path.of(source.path)
        val sourceIsLocal = try {
            Files.exists(sourcePath) && Files.isReadable(sourcePath)
        } catch (e: Exception) {
            false
        }

        // Determine which side(s) need SSH specifications
        // If source is locally accessible, only specify targetMachine for remote access
        val rsyncSourceMachine = if (sourceIsLocal) null else source.machine
        val rsyncTargetMachine = if (sourceIsLocal) target.machine else null

        rsyncEngine.transfer(
            sourcePath = source.path,
            targetPath = target.path,
            sourceMachine = rsyncSourceMachine,
            targetMachine = rsyncTargetMachine,
            route = route,
            bandwidthLimitKBps = bandwidthLimitKBps,
            progressCallback = progressCallback
        )
    }
}

data class FileLocation(
    val machine: vision.salient.choam.config.MachineProfile,
    val path: String
)

data class TransferProgress(
    val fileName: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long,
    val eta: Duration?
)

sealed class TransferResult {
    data class Success(val bytesTransferred: Long) : TransferResult()
    data class Failure(
        val message: String,
        val cause: Throwable? = null,
        val errorClass: RsyncErrorClass = RsyncErrorClass.PERMANENT
    ) : TransferResult()
}

enum class RsyncErrorClass {
    /** Transient failures that may succeed on retry (connection drop, timeout, SSH fail) */
    TRANSIENT,
    /** Permanent failures that won't succeed on retry (syntax, protocol, I/O errors) */
    PERMANENT,
    /** Partial transfer — some files succeeded, some failed */
    PARTIAL
}

fun classifyRsyncExit(exitCode: Int): RsyncErrorClass = when (exitCode) {
    12 -> RsyncErrorClass.TRANSIENT    // rsync protocol data stream error (connection dropped)
    20 -> RsyncErrorClass.TRANSIENT    // received SIGUSR1 or SIGINT
    30 -> RsyncErrorClass.TRANSIENT    // timeout in data send/receive
    35 -> RsyncErrorClass.TRANSIENT    // timeout waiting for daemon connection
    255 -> RsyncErrorClass.TRANSIENT   // SSH connection failed
    137 -> RsyncErrorClass.TRANSIENT   // killed by signal (OOM, etc.)
    23 -> RsyncErrorClass.PARTIAL      // partial transfer due to error
    24 -> RsyncErrorClass.PARTIAL      // partial transfer due to vanished source files
    else -> RsyncErrorClass.PERMANENT  // syntax, protocol, I/O errors
}

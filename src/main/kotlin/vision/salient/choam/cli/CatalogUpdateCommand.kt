package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import vision.salient.choam.catalog.CatalogState
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.dag.ConfigResolver
import vision.salient.choam.config.MountedDrive
import vision.salient.choam.drive.DriveDetector
import vision.salient.sietch.core.DEFAULT_EXCLUDE_PATTERNS
import vision.salient.sietch.core.computeHash
import vision.salient.sietch.core.formatSize
import vision.salient.sietch.core.ipfs.IpfsClient
import vision.salient.sietch.core.registry.ContentLocationRegistry
import vision.salient.sietch.core.walkTree
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

class CatalogUpdateCommand : CliktCommand(
    name = "catalog-update",
    help = """
        Incrementally update the catalog by hashing only new or modified files.

        Instead of rescanning every file (which takes 50+ hours on a 4TB drive), this command
        checks filesystem modification times (mtime) against the last scan timestamp and only
        processes files that have changed since then.

        Tracks per-drive scan state in ~/.choam/catalog_state.json. First run on a drive behaves
        like catalog-all (all files are "new").

        Key behaviors:
          - Uses mtime to identify new/modified files since last scan
          - Computes IPFS CID + SHA-256 for each new file (same as catalog-all)
          - Falls back to SHA-256 only if Kubo IPFS daemon is unavailable
          - Updates the Sietch content registry incrementally
          - Saves scan timestamp on completion for future incremental runs

        Safety: Read-only on source files. Writes only to registry DB and state file.
        Safe to interrupt — partial progress is saved to the registry.

        Examples:
          choam catalog-update                           # update all mounted drives
          choam catalog-update --drive my-ext-drive        # update one drive only
          choam catalog-update --dry-run                 # show what would be indexed
          choam catalog-update --ipfs http://10.0.0.5:5001  # use remote Kubo node
    """.trimIndent()
) {
    private val ipfsUrl by option(
        "--ipfs", "-i",
        help = "Kubo IPFS API endpoint URL"
    ).default("http://127.0.0.1:5001")

    private val ipfsBinary by option(
        "--ipfs-binary",
        help = "Path to the ipfs CLI binary for CID computation"
    ).default("ipfs")

    private val driveLabel by option(
        "--drive", "-d",
        help = "Only scan a specific drive (by label, e.g. my-ext-drive)"
    )

    private val dryRun by option(
        "--dry-run", "-n",
        help = "Show what would be re-indexed without actually hashing"
    ).flag(default = false)

    private val registryDb by option(
        "--registry", "-r",
        help = "Path to the Sietch content location registry SQLite database"
    ).default("${System.getProperty("user.home")}/.choam/catalogs/sietch_registry.db")

    private val stateFile by option(
        "--state-file",
        help = "Path to the catalog state JSON file"
    ).default("${System.getProperty("user.home")}/.choam/catalog_state.json")

    override fun run() {
        val config = try {
            ConfigResolver.resolve()
        } catch (e: Exception) {
            echo("Error: ${e.message}", err = true)
            echo("Run 'choam init' first to create config.", err = true)
            return
        }

        // Determine machine name
        val hostname = java.net.InetAddress.getLocalHost().hostName
        val machineEntry = config.machines.entries
            .find { it.value.hostname == hostname || it.value.hostname.startsWith(hostname) }
        val machineName = machineEntry?.key ?: hostname

        // Detect mounted drives
        val detector = DriveDetector()
        val mountedDrives = detector.detectConfiguredDrives(config.drives)

        if (mountedDrives.isEmpty()) {
            echo("No configured drives currently mounted.")
            return
        }

        // Filter to specific drive if --drive specified
        val drivesToScan = if (driveLabel != null) {
            val match = mountedDrives.entries.find {
                it.value.label.equals(driveLabel, ignoreCase = true)
            }
            if (match == null) {
                echo("Error: Drive '$driveLabel' not found among mounted drives.", err = true)
                echo("Mounted drives: ${mountedDrives.values.joinToString(", ") { it.label }}", err = true)
                return
            }
            mapOf(match.key to match.value)
        } else {
            mountedDrives
        }

        // Check IPFS availability
        val ipfsClient = IpfsClient(ipfsUrl)
        val ipfsAvailable = runBlocking {
            try {
                ipfsClient.isAvailable()
            } catch (e: Exception) {
                false
            }
        }

        if (ipfsAvailable) {
            echo("IPFS node: available at $ipfsUrl")
        } else {
            echo("IPFS node: not available at $ipfsUrl — falling back to SHA-256 only")
        }

        echo("Machine: $machineName ($hostname)")
        echo("Mode: ${if (dryRun) "DRY RUN" else "live"}")

        // Load catalog state
        val stateFilePath = File(stateFile)
        val state = CatalogState.load(stateFilePath)

        // Merge exclude patterns
        val excludePatterns = (config.defaultSyncRules.excludePatterns + DEFAULT_EXCLUDE_PATTERNS).distinct()

        val overallStart = System.currentTimeMillis()
        var totalNewFiles = 0L
        var totalNewBytes = 0L
        var totalScanned = 0L

        // Open registry only if not dry-run
        val registry = if (!dryRun) ContentLocationRegistry(Path.of(registryDb)) else null

        try {
            for ((driveKey, mounted) in drivesToScan) {
                val result = scanDrive(
                    driveKey = driveKey,
                    mounted = mounted,
                    state = state,
                    machineName = machineName,
                    excludePatterns = excludePatterns,
                    ipfsClient = if (ipfsAvailable) ipfsClient else null,
                    registry = registry
                )
                totalScanned += result.scannedFiles
                totalNewFiles += result.newFiles
                totalNewBytes += result.newBytes

                // Update state after successful scan (not dry-run)
                if (!dryRun) {
                    state.updateDrive(driveKey, mounted.label, result.scannedFiles, result.newFiles)
                }
            }

            // Save state
            if (!dryRun) {
                CatalogState.save(state, stateFilePath)
                echo("State saved to ${stateFilePath.absolutePath}")
            }

            val elapsed = (System.currentTimeMillis() - overallStart) / 1000
            echo("")
            echo("catalog-update ${if (dryRun) "(DRY RUN) " else ""}COMPLETE:")
            echo("  Scanned: $totalScanned files")
            echo("  New/modified: $totalNewFiles files (${formatSize(totalNewBytes)})")
            echo("  Time: ${formatDuration(elapsed)}")
            if (!ipfsAvailable && !dryRun) {
                echo("  Note: CIDs computed as sha256:<hash> (IPFS was unavailable)")
            }
        } finally {
            registry?.close()
            ipfsClient.close()
            logger.info { "All resources closed" }
        }
    }

    private fun scanDrive(
        driveKey: String,
        mounted: MountedDrive,
        state: CatalogState,
        machineName: String,
        excludePatterns: List<String>,
        ipfsClient: IpfsClient?,
        registry: ContentLocationRegistry?
    ): ScanResult {
        val lastScan = state.getLastScanInstant(driveKey)
        val lastScanMs = lastScan?.toEpochMilli() ?: 0L

        echo("")
        if (lastScan == null) {
            echo("Scanning ${mounted.label} at ${mounted.mountPoint} (first scan — all files are new)")
        } else {
            echo("Scanning ${mounted.label} at ${mounted.mountPoint} (last scan: $lastScan)")
        }

        var scannedFiles = 0L
        var newFiles = 0L
        var newBytes = 0L
        val driveStart = System.currentTimeMillis()

        walkTree(File(mounted.mountPoint), excludePatterns) { file ->
            scannedFiles++

            // Check mtime — file is "new" if modified after last scan
            val mtime = file.lastModified()
            if (mtime <= lastScanMs) return@walkTree

            newFiles++
            newBytes += file.length()

            if (dryRun) {
                if (newFiles <= 20) {
                    echo("  [new] ${file.absolutePath} (${formatSize(file.length())})")
                } else if (newFiles == 21L) {
                    echo("  ... (showing first 20 only)")
                }
            } else {
                // Hash the file
                try {
                    val cid = if (ipfsClient != null) {
                        ipfsClient.computeCidViaCli(file.toPath(), ipfsBinary)
                    } else {
                        "sha256:${computeHash(file, "SHA-256")}"
                    }

                    registry?.register(
                        cid = cid,
                        machine = machineName,
                        path = file.absolutePath,
                        fileSize = file.length()
                    )
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to hash ${file.absolutePath}" }
                    System.err.println("  Warning: failed to hash ${file.absolutePath}: ${e.message}")
                }

                if (newFiles % 1000 == 0L) {
                    echo("  [${mounted.label}] $newFiles new files hashed... last: ${file.name}")
                }
            }

            if (scannedFiles % 50_000 == 0L) {
                echo("  [${mounted.label}] $scannedFiles files scanned, $newFiles new so far...")
            }
        }

        val elapsed = (System.currentTimeMillis() - driveStart) / 1000
        echo("  [${mounted.label}] Done: $scannedFiles scanned, $newFiles new/modified (${formatSize(newBytes)}), ${formatDuration(elapsed)}")

        return ScanResult(scannedFiles, newFiles, newBytes)
    }

    data class ScanResult(
        val scannedFiles: Long,
        val newFiles: Long,
        val newBytes: Long
    )
}

private fun formatDuration(seconds: Long): String = when {
    seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m ${seconds % 60}s"
    seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds}s"
}

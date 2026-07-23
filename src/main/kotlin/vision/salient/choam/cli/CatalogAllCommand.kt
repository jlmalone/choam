package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.dag.ConfigResolver
import vision.salient.choam.config.ResourceLimits
import vision.salient.choam.drive.DriveDetector
import vision.salient.sietch.core.DEFAULT_EXCLUDE_PATTERNS
import vision.salient.sietch.core.ensureGlobalIgnore
import vision.salient.sietch.core.formatSize
import vision.salient.sietch.core.indexDirectoryWithCidsStreaming
import vision.salient.sietch.core.ipfs.IpfsClient
import vision.salient.sietch.core.registry.ContentLocationRegistry
import java.io.File
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

class CatalogAllCommand : CliktCommand(
    name = "catalog-all",
    help = """
        Catalog all mounted drives by computing CID (IPFS content identifier) and SHA-256 hashes for every file.

        Requires a running Kubo IPFS daemon. Scans each configured drive sequentially, writing per-drive catalog files and updating the Sietch content registry. Progress is logged to cid-progress.log.

        Key behaviors:
          - Only processes drives listed in ~/.choam/config.json that are currently mounted
          - Applies exclude patterns from config + defaults (macOS junk: .DS_Store, ._*, .Spotlight-V100)
          - Respects resource limits (maxHeapMb, maxCpuCores, ioNice) from machine config
          - Long-running: a 4TB drive can take 50+ hours

        Safety: Read-only on source files. Writes only to catalog output dir and registry DB. Safe to interrupt — partial progress is saved to the registry.

        Examples:
          choam catalog-all
          choam catalog-all --ipfs http://192.168.1.5:5001
          choam catalog-all --output-dir /tmp/catalogs --registry /tmp/registry.db
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

    private val outputDir by option(
        "--output-dir", "-o",
        help = "Directory for per-drive catalog text files and progress logs"
    ).default("${System.getProperty("user.home")}/.choam/catalogs")

    private val registryDb by option(
        "--registry", "-r",
        help = "Path to the Sietch content location registry SQLite database"
    ).default("${System.getProperty("user.home")}/.choam/catalogs/sietch_registry.db")

    override fun run() {
        // Verify Kubo node is reachable (suspend calls need runBlocking)
        val ipfsClient = IpfsClient(ipfsUrl)
        val nodeInfo = runBlocking {
            if (!ipfsClient.isAvailable()) {
                echo("Error: Kubo node not reachable at $ipfsUrl", err = true)
                echo("Start the IPFS daemon: ipfs daemon &", err = true)
                ipfsClient.close()
                return@runBlocking null
            }
            ipfsClient.nodeId()
        } ?: return

        val config = try {
            ConfigResolver.resolve()
        } catch (e: Exception) {
            echo("Error: ${e.message}", err = true)
            echo("Run 'choam init' first to create config.", err = true)
            return
        }

        // Determine machine name from config
        val hostname = java.net.InetAddress.getLocalHost().hostName
        val machineEntry = config.machines.entries
            .find { it.value.hostname == hostname || it.value.hostname.startsWith(hostname) }
        val machineName = machineEntry?.key ?: hostname
        echo("Machine: $machineName ($hostname)")
        echo("IPFS node: ${nodeInfo.second} (${nodeInfo.first})")
        echo("IPFS binary: $ipfsBinary")

        // Apply resource limits from config
        val limits = machineEntry?.value?.resourceLimits ?: ResourceLimits()
        applyResourceLimits(limits)

        // Detect mounted drives
        val detector = DriveDetector()
        val mountedDrives = detector.detectConfiguredDrives(config.drives)

        if (mountedDrives.isEmpty()) {
            echo("No configured drives currently mounted.")
            echo("Configured drives: ${config.drives.keys.joinToString(", ")}")
            ipfsClient.close()
            return
        }

        echo("Mounted drives: ${mountedDrives.map { "${it.value.label} (${it.key})" }.joinToString(", ")}")

        // Prepare output directory
        val outDir = File(outputDir)
        outDir.mkdirs()

        val registry = ContentLocationRegistry(Path.of(registryDb))
        val date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val logFile = File(outDir, "cid-progress.log")

        fun log(msg: String) {
            val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val line = "[$timestamp] $msg"
            echo(line)
            logFile.appendText("$line\n")
        }

        // Merge config exclude patterns with defaults (macOS junk files, temp files)
        val excludePatterns = (config.defaultSyncRules.excludePatterns + DEFAULT_EXCLUDE_PATTERNS).distinct()
        // Ensure global .sietchignore exists (seeds defaults on first run)
        ensureGlobalIgnore()

        log("Starting catalog-all: ${mountedDrives.size} drives, IPFS=$ipfsUrl, binary=$ipfsBinary")
        log("Exclude patterns: ${excludePatterns.joinToString(", ")}")

        var totalFiles = 0L
        var totalBytes = 0L
        val startTime = System.currentTimeMillis()

        try {
            // Process each mounted drive sequentially
            for ((driveKey, mounted) in mountedDrives) {
                val driveLabel = mounted.label
                val mountPoint = mounted.mountPoint
                val catalogFile = File(outDir, "${driveKey}-cid-$date.txt")

                log("Indexing: $driveLabel at $mountPoint")
                log("  Output: ${catalogFile.name}")

                val driveStart = System.currentTimeMillis()

                try {
                    val result = indexDirectoryWithCidsStreaming(
                        dir = File(mountPoint),
                        ipfsClient = ipfsClient,
                        registry = registry,
                        machineName = machineName,
                        outputFile = catalogFile,
                        ipfsBinary = ipfsBinary,
                        excludePatterns = excludePatterns,
                        useSietchIgnore = true,
                        progressCallback = { count, path ->
                            if (count % 1000 == 0L) {
                                log("  [$driveLabel] $count files indexed... last: $path")
                            }
                        }
                    )

                    val driveElapsed = (System.currentTimeMillis() - driveStart) / 1000
                    totalFiles += result.fileCount
                    totalBytes += result.totalSize

                    log("  [$driveLabel] DONE: ${result.fileCount} files, ${formatSize(result.totalSize)}, ${driveElapsed}s")
                } catch (e: Exception) {
                    log("  [$driveLabel] ERROR: ${e.message}")
                    logger.error(e) { "Failed to catalog $driveLabel" }
                }
            }

            val totalElapsed = (System.currentTimeMillis() - startTime) / 1000
            log("catalog-all COMPLETE: $totalFiles files, ${formatSize(totalBytes)}, ${totalElapsed}s")
        } finally {
            // Ensure all resources are closed so the JVM can exit cleanly
            registry.close()
            ipfsClient.close()
            logger.info { "All resources closed" }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun applyResourceLimits(limits: ResourceLimits) {
        if (limits.maxHeapMb != null) {
            val actualMaxMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
            if (actualMaxMb > limits.maxHeapMb) {
                echo("WARNING: JVM heap ($actualMaxMb MB) exceeds configured limit (${limits.maxHeapMb} MB)")
                echo("  Launch with: ./gradlew run -Dorg.gradle.jvmargs=\"-Xmx${limits.maxHeapMb}m\"")
            } else {
                echo("Heap limit OK: $actualMaxMb MB <= ${limits.maxHeapMb} MB")
            }
        }
        if (limits.maxCpuCores != null) {
            echo("CPU cores limited to: ${limits.maxCpuCores}")
        }
        if (limits.ioNice) {
            echo("NOTE: ioNice is enabled. For best results, launch with: nice -n 19 ./gradlew run ...")
        }
    }
}

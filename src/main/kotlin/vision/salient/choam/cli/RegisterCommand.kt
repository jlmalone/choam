package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import mu.KotlinLogging
import vision.salient.sietch.core.registry.ContentLocationRegistry
import java.io.File
import java.io.FileInputStream
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

class RegisterCommand : CliktCommand(
    name = "register",
    help = """
        Register a file in the unified catalog registry.

        Adds a file location entry to ~/.choam/unified_registry.db so that
        search, inspect, and other tools can discover it. Useful for manually
        registering files that were transferred outside of CHOAM (e.g., via
        scp, ADB sneakernet, or direct copy).

        If no --cid is provided, computes SHA-256 of the file as the content
        identifier (sha256:<hex>). Use --cid to supply a pre-computed IPFS CID
        or other content hash.

        Examples:
          choam register /Volumes/media-4tb/media/movie.mkv server
          choam register ~/Desktop/backup.tar.gz laptop --cid bafkrei...
          choam register /path/to/file.mov local --cid sha256:abcdef...
    """.trimIndent()
) {
    private val filePath by argument(help = "Path to the file to register")
    private val machineName by argument(help = "Machine name where this file resides (as in CHOAM config)")
    private val cid by option("--cid", "-c", help = "Pre-computed CID (IPFS CID or sha256:<hex>). If omitted, SHA-256 is computed from the file.")

    override fun run() {
        val file = File(filePath)

        // Resolve the content identifier
        val contentId = if (cid != null) {
            cid!!
        } else {
            // File must exist locally to compute hash
            if (!file.exists()) {
                echo("File not found: $filePath")
                echo("Provide --cid if the file is on a remote machine and not locally accessible.")
                exitProcess(1)
            }
            if (!file.isFile) {
                echo("Not a regular file: $filePath")
                exitProcess(1)
            }
            echo("Computing SHA-256...")
            computeSha256(file)
        }

        val fileSize = if (file.exists() && file.isFile) file.length() else null
        val absolutePath = file.absolutePath

        // Open the unified registry and register
        val unifiedDbPath = Path.of(System.getProperty("user.home"), ".choam", "unified_registry.db")
        val registry = ContentLocationRegistry(unifiedDbPath)
        try {
            registry.register(contentId, machineName, absolutePath, fileSize)
        } finally {
            registry.close()
        }

        echo("Registered: ${file.name} on $machineName")
        echo("  CID:  $contentId")
        echo("  Path: $absolutePath")
        if (fileSize != null) {
            echo("  Size: ${formatBytes(fileSize)}")
        }
        logger.info { "Registered in unified registry: cid=$contentId machine=$machineName path=$absolutePath size=$fileSize" }
    }

    /**
     * Compute SHA-256 of a file using streaming reads (no full file in memory).
     */
    private fun computeSha256(file: File): String {
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
        return "sha256:$hex"
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
            else -> "$bytes bytes"
        }
    }
}

package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import vision.salient.choam.sync.ChoamManifest
import vision.salient.choam.sync.MANIFEST_FILENAME
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.util.zip.GZIPOutputStream

private val logger = KotlinLogging.logger {}

class ManifestLifecycleCommand : CliktCommand(
    name = "manifest-cache",
    help = """
        Manage .choam_manifest.json cache files.

        --list: Show all known manifests under the given paths (or common data directories)
        --cleanup: Archive manifests older than 30 days, delete archived ones older than 6 months

        Examples:
          choam manifest-cache --list ~/project_data ~/media
          choam manifest-cache --cleanup ~/project_data ~/media
          choam manifest-cache --list   (scans common CHOAM data paths)
    """.trimIndent()
) {
    private val list by option("--list", "-l", help = "List all manifest files and their status").flag()
    private val cleanup by option("--cleanup", "-c", help = "Archive old manifests, delete very old ones").flag()
    private val paths by argument(help = "Directories to scan for manifests").multiple()

    companion object {
        private val ARCHIVE_DIR = Paths.get(System.getProperty("user.home"), ".choam", "manifest_archive")
        private val ARCHIVE_AGE = Duration.ofDays(30)
        private val DELETE_AGE = Duration.ofDays(180)

        private val json = Json {
            ignoreUnknownKeys = true
        }
    }

    override fun run() {
        if (!list && !cleanup) {
            echo("Specify --list or --cleanup. Use --help for details.")
            return
        }

        val searchPaths = if (paths.isNotEmpty()) {
            paths.map { File(it) }
        } else {
            // Scan home directory and all mounted volumes (external drives)
            val home = File(System.getProperty("user.home"))
            val volumes = File("/Volumes").listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?: emptyList()
            listOf(home) + volumes
        }

        val manifests = findManifests(searchPaths)

        if (list) {
            listManifests(manifests)
        }

        if (cleanup) {
            cleanupManifests(manifests)
        }
    }

    private fun findManifests(roots: List<File>): List<ManifestInfo> {
        val results = mutableListOf<ManifestInfo>()
        for (root in roots) {
            if (!root.exists() || !root.isDirectory) continue
            root.walkTopDown()
                .maxDepth(10) // deep enough for most CHOAM data trees on external drives
                .filter { it.name == MANIFEST_FILENAME && it.isFile }
                .forEach { file ->
                    try {
                        val manifest = json.decodeFromString(ChoamManifest.serializer(), file.readText())
                        val builtAt = try { Instant.parse(manifest.builtAt) } catch (_: Exception) { null }
                        results.add(ManifestInfo(
                            path = file,
                            directory = file.parentFile,
                            manifest = manifest,
                            builtAt = builtAt
                        ))
                    } catch (e: Exception) {
                        results.add(ManifestInfo(
                            path = file,
                            directory = file.parentFile,
                            manifest = null,
                            builtAt = null,
                            parseError = e.message
                        ))
                    }
                }
        }
        return results.sortedBy { it.path.absolutePath }
    }

    private fun listManifests(manifests: List<ManifestInfo>) {
        if (manifests.isEmpty()) {
            echo("No manifests found.")
            return
        }

        echo("Found ${manifests.size} manifest(s):\n")
        val now = Instant.now()

        for (info in manifests) {
            val dir = info.directory.absolutePath
            if (info.manifest != null && info.builtAt != null) {
                val age = Duration.between(info.builtAt, now)
                val ageStr = when {
                    age.toDays() > 0 -> "${age.toDays()}d ago"
                    age.toHours() > 0 -> "${age.toHours()}h ago"
                    else -> "${age.toMinutes()}m ago"
                }
                val stale = if (age > ARCHIVE_AGE) " [STALE]" else ""
                echo("  $dir")
                echo("    files=${info.manifest.fileCount}  confidence=${info.manifest.hashConfidence}  built=$ageStr$stale")
            } else {
                echo("  $dir")
                echo("    CORRUPT: ${info.parseError ?: "unknown error"}")
            }
        }
    }

    private fun cleanupManifests(manifests: List<ManifestInfo>) {
        val now = Instant.now()
        var archived = 0
        var deleted = 0
        var skipped = 0

        // First, clean up old archived manifests based on the original builtAt
        // stored inside the compressed JSON, approximated by archive file mtime.
        // Archives older than DELETE_AGE are removed entirely.
        if (Files.exists(ARCHIVE_DIR)) {
            ARCHIVE_DIR.toFile().walkTopDown()
                .filter { it.name.endsWith(".json.gz") && it.isFile }
                .forEach { archiveFile ->
                    val fileAge = Duration.between(
                        Instant.ofEpochMilli(archiveFile.lastModified()), now
                    )
                    if (fileAge > DELETE_AGE) {
                        archiveFile.delete()
                        deleted++
                        logger.info { "Deleted old archived manifest: ${archiveFile.absolutePath}" }
                    }
                }
        }

        // Process current manifests based on builtAt age:
        // - < 30 days: keep in place
        // - 30-180 days: archive to ~/.choam/manifest_archive/
        // - > 180 days: delete immediately (no point archiving)
        for (info in manifests) {
            if (info.builtAt == null) {
                // Corrupt manifest — archive it
                archiveManifest(info.path)
                archived++
                continue
            }

            val age = Duration.between(info.builtAt, now)
            if (age > DELETE_AGE) {
                // Very old (>6 months) — delete directly, skip archive
                info.path.delete()
                deleted++
                logger.info { "Deleted very old manifest (${age.toDays()}d): ${info.path.absolutePath}" }
            } else if (age > ARCHIVE_AGE) {
                // Stale (30-180 days) — archive
                archiveManifest(info.path)
                archived++
            } else {
                skipped++
            }
        }

        echo("Cleanup complete: $archived archived, $deleted deleted from archive, $skipped current (kept)")
    }

    /**
     * Move a manifest from a content directory to ~/.choam/manifest_archive/.
     * The file is compressed and stored by a hash of its parent directory path.
     * The original file is deleted from the content directory.
     */
    private fun archiveManifest(manifestFile: File) {
        try {
            Files.createDirectories(ARCHIVE_DIR)
            val dirHash = manifestFile.parentFile.absolutePath.hashCode().toUInt().toString(16)
            val archiveFile = ARCHIVE_DIR.resolve("${dirHash}_manifest.json.gz").toFile()

            // Compress to archive
            manifestFile.inputStream().use { input ->
                GZIPOutputStream(archiveFile.outputStream()).use { gzip ->
                    input.copyTo(gzip)
                }
            }

            // Delete from content directory
            manifestFile.delete()
            logger.info { "Archived manifest: ${manifestFile.absolutePath} -> ${archiveFile.absolutePath}" }
        } catch (e: Exception) {
            logger.warn { "Failed to archive manifest ${manifestFile.absolutePath}: ${e.message}" }
        }
    }

    private data class ManifestInfo(
        val path: File,
        val directory: File,
        val manifest: ChoamManifest?,
        val builtAt: Instant?,
        val parseError: String? = null
    )
}

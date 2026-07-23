package vision.salient.choam.drive

import mu.KotlinLogging
import vision.salient.choam.config.Drive
import vision.salient.choam.config.MountedDrive
import java.io.File
import java.io.IOException

private val logger = KotlinLogging.logger {}

class DriveDetector {

    /**
     * Scan /Volumes/ for mounted external drives and return their UUIDs via diskutil.
     */
    fun scanMountedDrives(): List<MountedDrive> {
        val volumes = File("/Volumes")
        if (!volumes.exists() || !volumes.isDirectory) {
            logger.warn { "/Volumes not found — not macOS?" }
            return emptyList()
        }

        val results = mutableListOf<MountedDrive>()
        val children = volumes.listFiles() ?: return emptyList()

        for (vol in children) {
            if (!vol.isDirectory) continue
            // Skip the root volume (Macintosh HD)
            if (vol.absolutePath == "/Volumes/Macintosh HD") continue

            val info = getDiskUtilInfo(vol.absolutePath)
            val uuid = info["Volume UUID"] ?: info["Disk / Partition UUID"]
            if (uuid == null) {
                logger.debug { "No UUID found for ${vol.absolutePath}, skipping" }
                continue
            }

            val label = info["Volume Name"] ?: vol.name
            results.add(
                MountedDrive(
                    uuid = uuid,
                    label = label,
                    mountPoint = vol.absolutePath,
                    totalSpace = vol.totalSpace,
                    freeSpace = vol.freeSpace
                )
            )
        }

        return results
    }

    /**
     * Given a set of configured drives, find which ones are currently mounted.
     * Returns a map of drive label -> MountedDrive for all drives that are present.
     */
    fun detectConfiguredDrives(configuredDrives: Map<String, Drive>): Map<String, MountedDrive> {
        val mounted = scanMountedDrives()
        val result = mutableMapOf<String, MountedDrive>()

        for ((key, drive) in configuredDrives) {
            val match = mounted.find { it.uuid == drive.uuid }
            if (match != null) {
                result[key] = match.copy(storageClass = drive.storageClass)
            }
        }

        return result
    }

    /**
     * Resolve the absolute path for a repository on a drive.
     * If the drive is mounted, returns mountPoint/relativePath.
     * If the drive is not mounted, returns null.
     */
    fun resolveRepositoryPath(
        drive: Drive,
        repoName: String,
        mountedDrives: Map<String, MountedDrive>
    ): String? {
        val mounted = mountedDrives.values.find { it.uuid == drive.uuid } ?: return null
        val relativePath = drive.repositories[repoName] ?: return null
        return "${mounted.mountPoint}/$relativePath"
    }

    /**
     * Parse `diskutil info <mountPoint>` output into key-value pairs.
     */
    internal fun getDiskUtilInfo(mountPoint: String): Map<String, String> {
        return try {
            val process = ProcessBuilder("diskutil", "info", mountPoint)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                logger.debug { "diskutil info failed for $mountPoint (exit $exitCode)" }
                return emptyMap()
            }

            parseDiskUtilOutput(output)
        } catch (e: IOException) {
            logger.debug { "Failed to run diskutil: ${e.message}" }
            emptyMap()
        }
    }

    internal fun parseDiskUtilOutput(output: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (line in output.lines()) {
            val colonIndex = line.indexOf(':')
            if (colonIndex < 0) continue
            val key = line.substring(0, colonIndex).trim()
            val value = line.substring(colonIndex + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                result[key] = value
            }
        }
        return result
    }

    fun formatDriveSize(bytes: Long): String = when {
        bytes >= 1_099_511_627_776 -> "%.1f TB".format(bytes / 1_099_511_627_776.0)
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

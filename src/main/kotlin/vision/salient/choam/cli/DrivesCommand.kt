package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.dag.ConfigResolver
import vision.salient.choam.drive.DriveDetector

class DrivesCommand : CliktCommand(
    name = "drives",
    help = """
        List configured drives and their current mount status.

        Without a subcommand, shows all drives from config with UUID, label, mount point (if mounted), free/total space, and associated repositories. Drives are identified by UUID for reliable detection across mounts.

        Key behaviors:
          - Detects mounted drives by scanning /Volumes/ and matching UUIDs
          - Shows repository mappings per drive
          - Use 'drives scan' subcommand to discover new drives

        Safety: Read-only. Does not modify drives or config.

        Examples:
          choam drives
          choam drives scan
    """.trimIndent(),
    invokeWithoutSubcommand = true
) {
    override fun run() {
        if (currentContext.invokedSubcommand != null) return
        // No subcommand given — show configured drives and their mount status
        val config = try {
            ConfigResolver.resolve()
        } catch (e: Exception) {
            echo("Failed to load CHOAM config: ${e.message}")
            return
        }

        if (config.drives.isEmpty()) {
            echo("No drives configured. Run 'choam drives scan' to detect connected drives.")
            return
        }

        val detector = DriveDetector()
        val mounted = detector.detectConfiguredDrives(config.drives)

        echo("Configured Drives:")
        echo()
        for ((key, drive) in config.drives) {
            val mountInfo = mounted[key]
            val status = if (mountInfo != null) {
                "MOUNTED at ${mountInfo.mountPoint} " +
                    "(${detector.formatDriveSize(mountInfo.freeSpace)} free / " +
                    "${detector.formatDriveSize(mountInfo.totalSpace)} total)"
            } else {
                "NOT MOUNTED"
            }

            echo("  ${key.padEnd(20)} $status")
            echo("    UUID:  ${drive.uuid}")
            echo("    Label: ${drive.label}")
            echo("    Class: ${drive.storageClass}")
            if (drive.repositories.isNotEmpty()) {
                echo("    Repositories:")
                for ((repo, path) in drive.repositories) {
                    val fullPath = if (mountInfo != null) "${mountInfo.mountPoint}/$path" else path
                    echo("      $repo -> $fullPath")
                }
            }
            echo()
        }
    }
}

class DrivesScanCommand : CliktCommand(
    name = "scan",
    help = """
        Scan /Volumes/ for connected external drives and display their UUIDs, labels, and sizes.

        Outputs a ready-to-paste config snippet for each detected drive, making it easy to add new drives to ~/.choam/config.json.

        Key behaviors:
          - Discovers drives by scanning macOS /Volumes/ mount points
          - Retrieves UUID via diskutil for reliable identification
          - Shows total and free space for each drive

        Safety: Read-only. Does not modify any drives or config files.

        Examples:
          choam drives scan
    """.trimIndent()
) {
    override fun run() {
        val detector = DriveDetector()
        val drives = detector.scanMountedDrives()

        if (drives.isEmpty()) {
            echo("No external drives detected in /Volumes/")
            return
        }

        echo("Detected Drives:")
        echo()
        for (drive in drives) {
            echo("  ${drive.label}")
            echo("    UUID:       ${drive.uuid}")
            echo("    Mount:      ${drive.mountPoint}")
            echo("    Total:      ${detector.formatDriveSize(drive.totalSpace)}")
            echo("    Free:       ${detector.formatDriveSize(drive.freeSpace)}")
            echo()
            echo("    Config snippet:")
            echo("    [drives.${drive.label.lowercase().replace(" ", "-")}]")
            echo("    uuid = \"${drive.uuid}\"")
            echo("    label = \"${drive.label}\"")
            echo()
        }
    }
}

fun drivesCommand(): DrivesCommand = DrivesCommand().subcommands(DrivesScanCommand())

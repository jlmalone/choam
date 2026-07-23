package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import java.net.InetAddress
import vision.salient.choam.config.ChoamConfig
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.config.MachineProfile
import vision.salient.choam.config.MachineType
import vision.salient.choam.config.RepositoryConfig
import vision.salient.choam.config.RepositoryType

class InitCommand : CliktCommand(
    name = "init",
    help = """
        Create a default ~/.choam/config.json with this machine and starter repositories.

        Generates a config file with the current machine's hostname, two default repositories (~/media and ~/archive), and sensible defaults. Edit the generated file to add remote machines, drives, and custom repositories.

        Key behaviors:
          - Detects hostname automatically for the local machine entry
          - Creates 'media' and 'archive' repositories pointing to ~/media and ~/archive
          - Overwrites existing config if present — back up first if needed

        Safety: Writes to ~/.choam/config.json. Will overwrite an existing config file.

        Examples:
          choam init
    """.trimIndent()
) {
    override fun run() {
        val hostname = InetAddress.getLocalHost().hostName
        val machineName = "local"

        val mediaRepo = System.getProperty("user.home") + "/media"
        val archiveRepo = System.getProperty("user.home") + "/archive"

        val machine = MachineProfile(
            name = machineName,
            hostname = hostname,
            type = MachineType.DESKTOP,
            repositories = mapOf(
                "media" to mediaRepo,
                "archive" to archiveRepo
            )
        )

        val config = ChoamConfig(
            machines = mapOf(machineName to machine),
            repositories = mapOf(
                "media" to RepositoryConfig(
                    name = "media",
                    localPath = mediaRepo,
                    type = RepositoryType.MEDIA
                ),
                "archive" to RepositoryConfig(
                    name = "archive",
                    localPath = archiveRepo,
                    type = RepositoryType.ARCHIVE
                )
            )
        )

        ChoamConfigLoader.save(config)
        echo("Initialized CHOAM configuration at ${ChoamConfigLoader.defaultPath()}")
        echo("Edit ~/.choam/config.json to customize your repositories and machines.")
    }
}

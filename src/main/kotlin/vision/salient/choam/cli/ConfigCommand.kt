package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import vision.salient.choam.config.ChoamConfig
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.dag.ConfigResolver

class ConfigCommand : CliktCommand(
    name = "config",
    help = """
        Display or manage the CHOAM configuration file (~/.choam/config.json).

        Without flags, prints a usage hint. Use --show to dump the full parsed configuration including machines, drives, repositories, and sync rules.

        Key behaviors:
          - Reads from ~/.choam/config.json (create with 'choam init' if missing)
          - Shows all configured machines, drives, repositories, and sync rules

        Safety: Read-only. Does not modify the config file.

        Examples:
          choam config --show
    """.trimIndent()
) {
    private val show by option("--show", help = "Print the full parsed configuration to stdout").flag()

    override fun run() {
        if (show) {
            val config = try {
                ConfigResolver.resolve()
            } catch (e: Exception) {
                echo("Failed to load CHOAM config: ${e.message}")
                return
            }
            echo("CHOAM config at ${ChoamConfigLoader.defaultPath()}:")
            echo(config.toString())
        } else {
            echo("Config command stub. Use --show to display current configuration.")
        }
    }
}


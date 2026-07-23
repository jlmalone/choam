package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import mu.KotlinLogging
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.dag.ConfigResolver
import vision.salient.choam.config.MachineProfile
import vision.salient.choam.lowPriority
import java.io.File
import java.net.InetAddress
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Result of a verify operation — extracted for testability.
 */
data class VerifyResult(
    val machineName: String,
    val registered: Long,
    val verified: Long,
    val missing: Long,
    val missingPaths: List<String>
) {
    val allVerified: Boolean get() = missing == 0L
}

class VerifyCommand : CliktCommand(
    name = "verify",
    help = """
        Verify that files registered in the unified catalog still exist at their recorded paths.

        Checks whether files in the unified registry actually exist on disk — locally via
        File.exists() or remotely via SSH batch check. Reports registered vs verified vs missing
        counts per machine.

        Key behaviors:
          - Checks local files directly, remote files via SSH
          - Use --machine to audit a single machine instead of all
          - Use --sample N to check a random subset (useful for large registries)
          - Use --verbose to list every missing file path
          - Does NOT delete anything — only reports and marks stale entries

        Safety: Read-only. No files or registry entries are modified or deleted.

        Examples:
          choam verify
          choam verify --machine server
          choam verify --machine server --sample 1000
          choam verify --verbose
    """.trimIndent()
) {
    private val machine by option("--machine", "-m", help = "Check a specific machine (default: local)")
    private val sample by option("--sample", "-s", help = "Check a random sample of N files instead of all").default("0")
    private val verbose by option("--verbose", "-v", help = "List missing files").flag()

    override fun run() {
        val config = try {
            ConfigResolver.resolve()
        } catch (e: Exception) {
            echo("Failed to load CHOAM config: ${e.message}", err = true)
            echo("Run 'choam init' first to create config.", err = true)
            return
        }

        val unifiedDbPath = "${System.getProperty("user.home")}/.choam/unified_registry.db"
        if (!File(unifiedDbPath).exists()) {
            echo("No unified registry at $unifiedDbPath — run 'choam catalog-sync' first.", err = true)
            return
        }

        // Build alias map for resolving machine names in the registry
        val machineNameMap = mutableMapOf<String, String>()
        for ((configKey, profile) in config.machines) {
            for (alias in profile.aliases) {
                machineNameMap[alias] = configKey
            }
        }

        val hostname = try {
            InetAddress.getLocalHost().hostName
        } catch (_: Exception) { "unknown" }

        val localMachineKey = config.machines.entries
            .find { it.value.hostname == hostname || it.value.hostname.startsWith(hostname) }
            ?.key

        val sampleSize = sample.toIntOrNull() ?: 0

        val targetMachine = machine
        if (targetMachine != null) {
            // Specific machine requested
            val machineEntry = config.machines[targetMachine]
            if (machineEntry == null) {
                echo("Machine '$targetMachine' not found in config. Available: ${config.machines.keys.joinToString(", ")}")
                return
            }

            val isLocal = targetMachine == localMachineKey
            if (isLocal) {
                verifyLocal(unifiedDbPath, targetMachine, machineNameMap, sampleSize)
            } else {
                verifyRemote(unifiedDbPath, targetMachine, machineEntry, machineNameMap, sampleSize)
            }
        } else {
            // Default: verify local machine
            if (localMachineKey == null) {
                echo("Could not determine local machine from hostname '$hostname'.")
                echo("Use --machine <name> to specify. Available: ${config.machines.keys.joinToString(", ")}")
                return
            }
            verifyLocal(unifiedDbPath, localMachineKey, machineNameMap, sampleSize)
        }
    }

    private fun verifyLocal(unifiedDbPath: String, machineName: String, machineNameMap: Map<String, String>, sampleSize: Int) {
        echo("Verify — $machineName (local)")
        echo()

        val paths = loadRegisteredPaths(unifiedDbPath, machineName, machineNameMap, sampleSize)

        if (paths.isEmpty()) {
            echo("No registered files for '$machineName' in unified registry.")
            return
        }

        if (sampleSize > 0 && sampleSize < paths.size) {
            echo("  Sampling $sampleSize of ${"%,d".format(paths.size.toLong())} registered files")
        }

        val result = verifyLocalPaths(machineName, paths)
        printResult(result)
    }

    private fun verifyRemote(
        unifiedDbPath: String,
        machineName: String,
        machineProfile: MachineProfile,
        machineNameMap: Map<String, String>,
        sampleSize: Int
    ) {
        echo("Verify — $machineName (remote)")
        echo()

        val ip = machineProfile.tailscaleIp ?: machineProfile.hostname
        echo("  Checking reachability ($ip)...")
        val reachable = try {
            InetAddress.getByName(ip).isReachable(3000)
        } catch (_: Exception) { false }

        if (!reachable) {
            echo("  \u001b[31mUnreachable\u001b[0m — cannot verify")
            return
        }
        echo("  \u001b[32mReachable\u001b[0m")

        val paths = loadRegisteredPaths(unifiedDbPath, machineName, machineNameMap, sampleSize)

        if (paths.isEmpty()) {
            echo("  No registered files for '$machineName' in unified registry.")
            return
        }

        if (sampleSize > 0 && sampleSize < paths.size) {
            echo("  Sampling $sampleSize of ${"%,d".format(paths.size.toLong())} registered files")
        }

        echo("  Sending ${"%,d".format(paths.size.toLong())} paths for remote check...")
        val result = verifyRemotePaths(machineName, machineProfile, ip, paths)
        printResult(result)
    }

    private fun printResult(result: VerifyResult) {
        echo()
        val statusColor = if (result.allVerified) "\u001b[32m" else "\u001b[33m"
        echo("${result.machineName}: ${"%,d".format(result.registered)} registered, " +
                "${statusColor}${"%,d".format(result.verified)} verified\u001b[0m, " +
                "${if (result.missing > 0) "\u001b[31m" else ""}${"%,d".format(result.missing)} missing${if (result.missing > 0) "\u001b[0m" else ""}")

        if (verbose && result.missingPaths.isNotEmpty()) {
            echo()
            echo("Missing files:")
            for (path in result.missingPaths) {
                echo("  $path")
            }
        }
    }

    companion object {
        /**
         * Load registered file paths for a machine from the unified registry.
         * Resolves aliases so both old and new machine names are found.
         * Extracted for testability.
         */
        fun loadRegisteredPaths(
            unifiedDbPath: String,
            machineName: String,
            machineNameMap: Map<String, String>,
            sampleSize: Int
        ): List<String> {
            // Collect all names that map to this machine:
            // the config key itself, plus any aliases that map to it
            val names = mutableSetOf(machineName)
            for ((alias, target) in machineNameMap) {
                if (target == machineName) names.add(alias)
            }

            val placeholders = names.joinToString(", ") { "?" }
            val query = if (sampleSize > 0) {
                "SELECT file_path FROM content_locations WHERE machine_name IN ($placeholders) ORDER BY RANDOM() LIMIT ?"
            } else {
                "SELECT file_path FROM content_locations WHERE machine_name IN ($placeholders)"
            }

            return try {
                val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedDbPath")
                val stmt = conn.prepareStatement(query)
                var idx = 1
                for (name in names) {
                    stmt.setString(idx++, name)
                }
                if (sampleSize > 0) {
                    stmt.setInt(idx, sampleSize)
                }

                val rs = stmt.executeQuery()
                val paths = mutableListOf<String>()
                while (rs.next()) {
                    paths.add(rs.getString("file_path"))
                }
                rs.close()
                stmt.close()
                conn.close()
                paths
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load paths for $machineName" }
                emptyList()
            }
        }

        /**
         * Verify paths exist on the local filesystem.
         * Extracted for testability.
         */
        fun verifyLocalPaths(machineName: String, paths: List<String>): VerifyResult {
            val missingPaths = mutableListOf<String>()
            var verified = 0L

            for (path in paths) {
                if (File(path).exists()) {
                    verified++
                } else {
                    missingPaths.add(path)
                }
            }

            return VerifyResult(
                machineName = machineName,
                registered = paths.size.toLong(),
                verified = verified,
                missing = missingPaths.size.toLong(),
                missingPaths = missingPaths
            )
        }

        /**
         * Verify paths exist on a remote machine via SSH.
         * Sends paths via stdin to a remote script that checks existence.
         */
        fun verifyRemotePaths(
            machineName: String,
            machine: MachineProfile,
            ip: String,
            paths: List<String>
        ): VerifyResult {
            val sshUser = machine.sshUser?.let { "$it@" } ?: ""
            val portArgs = if (machine.sshPort != 22) listOf("-p", machine.sshPort.toString()) else emptyList()

            // Remote script: read paths from stdin, print ones that DON'T exist
            val remoteScript = "while IFS= read -r p; do [ ! -e \"\$p\" ] && echo \"\$p\"; done"
            val cmd = lowPriority(listOf("ssh") + portArgs + listOf(
                "-o", "BatchMode=yes",
                "-o", "ConnectTimeout=10",
                "$sshUser$ip",
                remoteScript
            ))

            return try {
                logger.debug { "Remote verify: ${cmd.joinToString(" ")}" }
                val process = ProcessBuilder(cmd)
                    .redirectErrorStream(false)
                    .start()

                // Write paths to stdin
                process.outputStream.bufferedWriter().use { writer ->
                    for (path in paths) {
                        writer.write(path)
                        writer.newLine()
                    }
                }

                val missingPaths = mutableListOf<String>()
                process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        if (line.isNotBlank()) {
                            missingPaths.add(line.trim())
                        }
                    }
                }

                val finished = process.waitFor(10, TimeUnit.MINUTES)
                if (!finished) {
                    logger.warn { "Remote verify timed out after 10 minutes" }
                    process.destroyForcibly()
                }

                val verified = paths.size.toLong() - missingPaths.size.toLong()
                VerifyResult(
                    machineName = machineName,
                    registered = paths.size.toLong(),
                    verified = verified,
                    missing = missingPaths.size.toLong(),
                    missingPaths = missingPaths
                )
            } catch (e: Exception) {
                logger.warn(e) { "Remote verify failed for $machineName" }
                // Return unknown result — we can't verify
                VerifyResult(
                    machineName = machineName,
                    registered = paths.size.toLong(),
                    verified = 0,
                    missing = paths.size.toLong(),
                    missingPaths = paths
                )
            }
        }
    }
}

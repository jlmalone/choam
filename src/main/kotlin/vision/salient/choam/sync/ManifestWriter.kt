package vision.salient.choam.sync

import mu.KotlinLogging
import vision.salient.choam.config.MachineProfile
import vision.salient.choam.lowPriority
import vision.salient.choam.network.NetworkRoute
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Writes .choam_manifest.json after successful directory transfers.
 *
 * Manifest write failures are NON-FATAL — the transfer already succeeded.
 * The manifest is an optimization for future pre-flight checks, not a
 * requirement for correctness.
 */
object ManifestWriter {

    /**
     * Resolve the remote directory path that the manifest should be written to.
     * Matches the resolution logic in SendPreflight.directoryPreflightCascade().
     *
     * When destPath ends with "/", the remote directory is destPath + sourceDir.name.
     * Otherwise, destPath IS the remote directory.
     */
    fun resolveRemoteDirPath(sourceDir: File, destPath: String): String {
        return if (destPath.endsWith("/")) {
            "${destPath}${sourceDir.name}"
        } else {
            destPath
        }
    }

    /**
     * Write manifests after a successful directory transfer.
     *
     * @param sourceDir     Local source directory
     * @param remoteDirPath Resolved remote directory path (use resolveRemoteDirPath)
     * @param machine       Remote machine profile
     * @param route         Network route to remote
     * @param writeLocal    Whether to write the local manifest (false for MOVE — source is gone)
     * @param echo          Output function for status messages
     */
    fun writeManifestsAfterTransfer(
        sourceDir: File,
        remoteDirPath: String,
        machine: MachineProfile,
        route: NetworkRoute,
        writeLocal: Boolean = true,
        echo: (String) -> Unit = {}
    ) {
        try {
            // Build tree from source directory (manifest-aware if one already exists)
            val existingManifest = ChoamManifest.load(sourceDir)
            val tree = DirectoryMerkleTree.buildLocal(sourceDir, existingManifest)
            val manifest = tree.toManifest(sourceDir)

            // Write local manifest
            if (writeLocal && sourceDir.exists()) {
                try {
                    manifest.save(sourceDir)
                    logger.info { "Wrote local manifest: ${sourceDir.absolutePath}/$MANIFEST_FILENAME" }
                } catch (e: Exception) {
                    logger.warn { "Failed to write local manifest (non-fatal): ${e.message}" }
                    echo("  Warning: local manifest write failed — ${e.message}")
                }
            }

            // Write remote manifest via SSH
            writeRemoteManifest(manifest, remoteDirPath, machine, route, echo)
        } catch (e: Exception) {
            logger.warn { "Manifest generation failed (non-fatal): ${e.message}" }
            echo("  Warning: manifest generation failed — ${e.message}")
        }
    }

    /**
     * Write a pre-computed manifest to the remote only (no local write).
     * Used for MOVE transfers where the source is deleted after verification.
     * The manifest must be built BEFORE the source is deleted.
     */
    fun writeRemoteManifestOnly(
        manifest: ChoamManifest,
        remoteDirPath: String,
        machine: MachineProfile,
        route: NetworkRoute,
        echo: (String) -> Unit = {}
    ) {
        writeRemoteManifest(manifest, remoteDirPath, machine, route, echo)
    }

    /**
     * Write a manifest to a remote directory via SSH.
     * Non-fatal on failure.
     */
    private fun writeRemoteManifest(
        manifest: ChoamManifest,
        remoteDirPath: String,
        machine: MachineProfile,
        route: NetworkRoute,
        echo: (String) -> Unit
    ) {
        val user = machine.sshUser ?: run {
            logger.warn { "No SSH user for ${machine.name} — cannot write remote manifest" }
            return
        }
        val host = route.targetAddress
        val port = machine.sshPort

        val json = kotlinx.serialization.json.Json {
            prettyPrint = true
            encodeDefaults = true
        }
        // Trailing newline matters: loadRemote cats this file and expects its END
        // marker on a fresh line (older newline-less manifests wedged re-transfers).
        val manifestJson = json.encodeToString(ChoamManifest.serializer(), manifest) + "\n"
        val escapedPath = "$remoteDirPath/$MANIFEST_FILENAME".replace("'", "'\\''")

        val portArgs = if (port != 22) listOf("-p", "$port") else emptyList()
        val cmd = lowPriority(
            listOf("ssh") + portArgs + listOf(
                "-o", "ConnectTimeout=10",
                "-o", "BatchMode=yes",
                "$user@$host",
                "cat > '$escapedPath'"
            )
        )

        try {
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val stdinThread = Thread {
                try {
                    process.outputStream.bufferedWriter().use { writer ->
                        writer.write(manifestJson)
                        writer.flush()
                    }
                } catch (_: Exception) { }
            }.apply { isDaemon = true; start() }

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            stdinThread.join(5000)

            if (exitCode == 0) {
                logger.info { "Wrote remote manifest: ${machine.name}:$remoteDirPath/$MANIFEST_FILENAME" }
            } else {
                logger.warn { "Remote manifest write failed (exit=$exitCode): $output" }
                echo("  Warning: remote manifest write failed — exit code $exitCode")
            }
        } catch (e: Exception) {
            logger.warn { "Remote manifest write SSH failed (non-fatal): ${e.message}" }
            echo("  Warning: remote manifest write failed — ${e.message}")
        }
    }
}

package vision.salient.choam.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import vision.salient.choam.config.MachineProfile
import vision.salient.choam.lowPriority
import vision.salient.choam.niceRemote
import vision.salient.choam.network.NetworkRoute
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Internal result type for manifest loading. Mapped to PreflightOutcome at boundary.
 */
sealed class ManifestLoadResult {
    data class Loaded(val manifest: ChoamManifest) : ManifestLoadResult()
    object Missing : ManifestLoadResult()  // clean absence → maps to FallThrough
    data class Corrupt(val reason: String) : ManifestLoadResult()  // maps to UnsafeAbort
}

/**
 * Manifest file describing a directory's content state.
 *
 * Written as `.choam_manifest.json` inside the directory it describes.
 * Used for fast pre-flight comparison: if rootHash matches and manifest is fresh,
 * no per-file SSH checks are needed.
 *
 * IMPORTANT: .choam_manifest.json is always excluded from:
 * - local/remote tree building
 * - root hash computation
 * - file counts
 * - freshness checks (the manifest itself is excluded from find -newer)
 * - rsync transfers (added as --exclude)
 */
@Serializable
data class ChoamManifest(
    val builtAt: String,
    val rootHash: String,
    val fileCount: Int,
    val totalBytes: Long,
    val hashConfidence: String,
    val files: Map<String, FileEntry>
) {
    @Serializable
    data class FileEntry(
        val size: Long,
        val mtime: Long,
        val contentHash: String?,
        val metadataHash: String
    )

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        /**
         * Load manifest from a local directory. Returns null if missing (clean absence).
         * Throws no exceptions — corrupt JSON returns null.
         */
        fun load(directory: File): ChoamManifest? {
            val manifestFile = File(directory, MANIFEST_FILENAME)
            if (!manifestFile.exists()) return null
            return try {
                val content = manifestFile.readText()
                if (content.isBlank()) return null
                json.decodeFromString(serializer(), content)
            } catch (e: Exception) {
                logger.warn { "Failed to parse local manifest in ${directory.absolutePath}: ${e.message}" }
                null
            }
        }

        /**
         * Load and validate a remote manifest via SSH.
         *
         * Performs freshness validation in the same SSH call:
         * 1. cat the manifest
         * 2. find files newer than the manifest (excluding the manifest itself)
         * 3. count actual files on disk (excluding the manifest)
         *
         * Returns ManifestLoadResult (mapped to PreflightOutcome at boundary).
         */
        fun loadRemote(
            destPath: String,
            machine: MachineProfile,
            route: NetworkRoute
        ): ManifestLoadResult {
            val user = machine.sshUser ?: return ManifestLoadResult.Missing
            val host = route.targetAddress
            val port = machine.sshPort

            val portArgs = if (port != 22) listOf("-p", "$port") else emptyList()
            val escapedPath = destPath.replace("'", "'\\''")

            // Single SSH call: cat manifest + freshness checks
            val script = """
                MANIFEST_PATH='$escapedPath/$MANIFEST_FILENAME'
                if [ ! -f "${'$'}MANIFEST_PATH" ]; then
                    echo "CHOAM_MANIFEST_MISSING"
                    exit 0
                fi
                echo "CHOAM_MANIFEST_START"
                cat "${'$'}MANIFEST_PATH"
                # Manifests historically lack a trailing newline; without this the END
                # marker glues onto the JSON tail and the parser sees no marker at all.
                echo ""
                echo "CHOAM_MANIFEST_END"
                # Freshness check 1: any file/dir newer than manifest (excluding manifest itself)
                NEWER=${'$'}(find '$escapedPath' -newer "${'$'}MANIFEST_PATH" -not -name '$MANIFEST_FILENAME' | head -1)
                # Freshness check 2: actual file count (excluding manifest)
                COUNT=${'$'}(find '$escapedPath' -type f -not -name '$MANIFEST_FILENAME' | wc -l | tr -d ' ')
                echo "CHOAM_FRESHNESS|${'$'}NEWER|${'$'}COUNT"
            """.trimIndent()

            val cmd = lowPriority(
                listOf("ssh") + portArgs + listOf(
                    "-o", "ConnectTimeout=10",
                    "-o", "BatchMode=yes",
                    "$user@$host",
                    niceRemote("bash -s")
                )
            )

            val process = try {
                ProcessBuilder(cmd).redirectErrorStream(true).start()
            } catch (e: Exception) {
                logger.warn { "Remote manifest load SSH failed: ${e.message}" }
                return ManifestLoadResult.Missing
            }

            val stdinThread = Thread {
                try {
                    process.outputStream.bufferedWriter().use { writer ->
                        writer.write(script)
                        writer.flush()
                    }
                } catch (_: Exception) { }
            }.apply { isDaemon = true; start() }

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            stdinThread.join(5000)

            return parseRemoteManifestOutput(output, exitCode)
        }

        /**
         * Parse the marker-delimited output of the loadRemote SSH script.
         * Pure; extracted for testability.
         *
         * Tolerates an END marker glued onto the JSON's last line: manifests were
         * historically written without a trailing newline, so `cat manifest; echo
         * MARKER` produced `}CHOAM_MANIFEST_END`, which the strict line match read
         * as "no marker" and wedged re-transfers of completed directories forever.
         */
        internal fun parseRemoteManifestOutput(output: String, exitCode: Int): ManifestLoadResult {
            if (exitCode != 0 && output.isBlank()) {
                return ManifestLoadResult.Missing // network issue
            }

            val lines = output.lines()

            // Check for missing manifest
            if (lines.any { it.trim() == "CHOAM_MANIFEST_MISSING" }) {
                return ManifestLoadResult.Missing
            }

            // Extract manifest JSON
            val endMarker = "CHOAM_MANIFEST_END"
            val startIdx = lines.indexOfFirst { it.trim() == "CHOAM_MANIFEST_START" }
            val endIdx = lines.indexOfFirst { it.trim().endsWith(endMarker) }
            if (startIdx == -1 || endIdx == -1 || endIdx <= startIdx) {
                return ManifestLoadResult.Corrupt("Truncated manifest output (missing start/end markers)")
            }

            // A glued marker means the line's prefix is the JSON tail.
            val gluedTail = lines[endIdx].trim().removeSuffix(endMarker)
            val manifestJson = (lines.subList(startIdx + 1, endIdx) + gluedTail)
                .joinToString("\n")
            val manifest = try {
                json.decodeFromString(serializer(), manifestJson)
            } catch (e: Exception) {
                return ManifestLoadResult.Corrupt("Corrupt manifest JSON: ${e.message}")
            }

            // Parse freshness check
            val freshnessLine = lines.find { it.startsWith("CHOAM_FRESHNESS|") }
            if (freshnessLine != null) {
                val parts = freshnessLine.split("|")
                if (parts.size >= 3) {
                    val newerFile = parts[1].trim()
                    val actualCount = parts[2].trim().toIntOrNull()

                    // Stale check 1: any file newer than manifest
                    if (newerFile.isNotEmpty()) {
                        return ManifestLoadResult.Corrupt(
                            "Stale manifest: file(s) newer than manifest (e.g., ${newerFile.take(80)})"
                        )
                    }

                    // Stale check 2: file count mismatch
                    if (actualCount != null && actualCount != manifest.fileCount) {
                        return ManifestLoadResult.Corrupt(
                            "Stale manifest: file count mismatch (manifest=${manifest.fileCount}, actual=$actualCount)"
                        )
                    }
                }
            }

            return ManifestLoadResult.Loaded(manifest)
        }
    }

    /**
     * Save this manifest to a directory as .choam_manifest.json.
     */
    fun save(directory: File) {
        val manifestFile = File(directory, MANIFEST_FILENAME)
        // Trailing newline matters: loadRemote cats this file and expects its END
        // marker on a fresh line (older newline-less manifests wedged re-transfers).
        val content = Companion.json.encodeToString(serializer(), this) + "\n"
        manifestFile.writeText(content)
        logger.info { "Wrote manifest to ${manifestFile.absolutePath} (${fileCount} files, rootHash=${rootHash.take(12)}...)" }
    }
}

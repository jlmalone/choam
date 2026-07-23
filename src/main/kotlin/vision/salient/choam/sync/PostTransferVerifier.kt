package vision.salient.choam.sync

import mu.KotlinLogging
import vision.salient.choam.SSH_PROBE_TIMEOUT_SEC
import vision.salient.choam.config.MachineProfile
import vision.salient.choam.lowPriority
import vision.salient.choam.network.NetworkRoute
import vision.salient.choam.runBounded
import java.io.File
import java.security.MessageDigest

private val logger = KotlinLogging.logger {}

/**
 * Result of verifying a directory move. Includes hash-mismatch detail.
 */
data class DirectoryVerifyResult(
    val verified: Int,
    val failed: List<String>,
    /** Files where size matched but SHA-256 did not — potential corruption. */
    val hashMismatches: List<String>
)

/**
 * Content-backed post-transfer verification for MOVE operations.
 *
 * Every file is verified by BOTH size AND SHA-256. There is NO fallback to
 * size-only verification. If the remote hash cannot be obtained for any reason
 * (tool missing, SSH failure, unparsable output), the file is NOT verified and
 * the source is kept.
 *
 * Used by both QueueProcessor and SendCommand — one implementation, no drift.
 */
object PostTransferVerifier {

    /**
     * Verify a single remote file by size + SHA-256.
     * Returns true ONLY if both size and hash match.
     * Returns false (source kept) on any failure.
     */
    fun verifySingleFile(
        localFile: File,
        remotePath: String,
        machine: MachineProfile,
        route: NetworkRoute
    ): Boolean {
        val user = machine.sshUser ?: run {
            logger.warn { "No SSH user for ${machine.name} — cannot verify" }
            return false
        }
        val host = route.targetAddress
        val port = machine.sshPort

        val remoteFull = if (remotePath.endsWith("/")) "$remotePath${localFile.name}" else remotePath
        val escapedPath = remoteFull.replace("'", "'\\''")

        val portArgs = if (port != 22) listOf("-p", "$port") else emptyList()
        val script = """
            SIZE=${'$'}(stat -f '%z' '$escapedPath' 2>/dev/null || stat -c '%s' '$escapedPath' 2>/dev/null)
            HASH=${'$'}(shasum -a 256 '$escapedPath' 2>/dev/null | cut -d' ' -f1)
            if [ -z "${'$'}HASH" ]; then
                HASH=${'$'}(sha256sum '$escapedPath' 2>/dev/null | cut -d' ' -f1)
            fi
            echo "${'$'}SIZE|${'$'}HASH"
        """.trimIndent()

        val cmd = lowPriority(
            listOf("ssh") + portArgs + listOf(
                "-o", "ConnectTimeout=10",
                "-o", "BatchMode=yes",
                // Keepalives so a session that connects then stalls (saturated relay) is
                // dropped by ssh in ~60s instead of blocking the read forever.
                "-o", "ServerAliveInterval=15",
                "-o", "ServerAliveCountMax=4",
                "$user@$host",
                "bash -s"
            )
        )

        return try {
            val res = runBounded(cmd, stdin = script, timeoutSeconds = SSH_PROBE_TIMEOUT_SEC)
            if (res.timedOut) {
                logger.warn { "Single-file verification SSH timed out for ${localFile.name} — source kept" }
                return false
            }
            val output = res.output.trim()
            val exitCode = res.exitCode

            if (exitCode != 0) {
                logger.warn { "Single-file verification SSH failed (exit=$exitCode) for ${localFile.name}" }
                return false
            }

            val parts = output.lines().last().trim().split("|")
            if (parts.size < 2) {
                logger.warn { "Single-file verification: unparsable output for ${localFile.name}: $output" }
                return false
            }

            val remoteSize = parts[0].toLongOrNull()
            val remoteHash = parts[1].takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

            if (remoteSize == null) {
                logger.warn { "Single-file verification: invalid remote size for ${localFile.name}: ${parts[0]}" }
                return false
            }
            if (remoteSize != localFile.length()) {
                logger.warn { "Single-file verification: size mismatch for ${localFile.name}: local=${localFile.length()}, remote=$remoteSize" }
                return false
            }
            if (remoteHash == null) {
                logger.warn { "Single-file verification: missing or invalid remote hash for ${localFile.name}: ${parts[1]}" }
                return false
            }

            val localHash = computeSha256(localFile)
            if (localHash != remoteHash) {
                logger.warn { "HASH MISMATCH for ${localFile.name}: local=$localHash, remote=$remoteHash — possible corruption" }
                return false
            }

            logger.info { "Verified: ${localFile.name} (size=${localFile.length()}, sha256=${localHash.take(12)}...)" }
            true
        } catch (e: Exception) {
            logger.warn { "Single-file verification failed for ${localFile.name}: ${e.message}" }
            false
        }
    }

    /**
     * Verify all files in a source directory exist on the remote with matching
     * size AND SHA-256. Does NOT delete files — caller handles deletion.
     *
     * @return DirectoryVerifyResult with verified count, failed paths, and hash mismatches
     */
    fun verifyDirectory(
        sourceDir: File,
        remoteDirPath: String,
        machine: MachineProfile,
        route: NetworkRoute
    ): DirectoryVerifyResult {
        val user = machine.sshUser
            ?: return DirectoryVerifyResult(0, listOf("SSH user not configured"), emptyList())
        val host = route.targetAddress
        val port = machine.sshPort

        val allFiles = filesToVerify(sourceDir)
        if (allFiles.isEmpty()) {
            return DirectoryVerifyResult(0, emptyList(), emptyList())
        }

        val remoteBase = if (remoteDirPath.endsWith("/")) remoteDirPath.dropLast(1) else remoteDirPath
        val relativePaths = allFiles.map { it.relativeTo(sourceDir).path }

        // Batch SSH: get size + SHA-256 for each remote file
        // Output format: relativePath|size|sha256
        val remoteData = mutableMapOf<String, Pair<Long, String?>>() // relPath -> (size, hash?)
        for (batch in relativePaths.chunked(200)) {
            val script = buildString {
                for (relPath in batch) {
                    val remoteFull = "$remoteBase/$relPath"
                    val escaped = remoteFull.replace("'", "'\\''")
                    appendLine("""
                        SIZE=${'$'}(stat -f '%z' '$escaped' 2>/dev/null || stat -c '%s' '$escaped' 2>/dev/null)
                        if [ -n "${'$'}SIZE" ]; then
                            HASH=${'$'}(shasum -a 256 '$escaped' 2>/dev/null | cut -d' ' -f1)
                            if [ -z "${'$'}HASH" ]; then
                                HASH=${'$'}(sha256sum '$escaped' 2>/dev/null | cut -d' ' -f1)
                            fi
                            echo "$relPath|${'$'}SIZE|${'$'}HASH"
                        else
                            echo "$relPath|MISSING|NONE"
                        fi
                    """.trimIndent())
                }
            }

            val portArgs = if (port != 22) listOf("-p", "$port") else emptyList()
            val cmd = lowPriority(listOf("ssh") + portArgs + listOf(
                "-o", "ConnectTimeout=10",
                "-o", "BatchMode=yes",
                "-o", "ServerAliveInterval=15",
                "-o", "ServerAliveCountMax=4",
                "$user@$host",
                "bash -s"
            ))

            try {
                val res = runBounded(cmd, stdin = script, timeoutSeconds = SSH_PROBE_TIMEOUT_SEC)
                if (res.timedOut) {
                    logger.warn { "Directory verification batch SSH timed out — failing closed (source kept)" }
                    continue
                }
                val output = res.output
                val exitCode = res.exitCode

                if (exitCode != 0) {
                    logger.warn { "Directory verification batch SSH failed (exit=$exitCode)" }
                    // Don't parse partial output — fail closed
                    continue
                }

                for (line in output.lines()) {
                    val parts = line.trim().split("|", limit = 3)
                    if (parts.size >= 3) {
                        val relPath = parts[0]
                        val size = parts[1].toLongOrNull()
                        val hash = parts[2].takeIf {
                            it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' }
                        }
                        if (size != null) {
                            remoteData[relPath] = size to hash
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn { "Directory verification batch SSH failed: ${e.message}" }
            }
        }

        return classify(sourceDir, allFiles, remoteData)
    }

    /**
     * Files a MOVE must verify on the destination: every file EXCEPT CHOAM's own
     * manifest. The manifest is never transferred (excluded from rsync in
     * QueueProcessor/SendCommand) and is rewritten on the destination by
     * ManifestWriter, so verifying it against the remote would always fail and wedge
     * an otherwise-complete move as a permanent partial-fail. Any directory that
     * already holds a manifest from a prior CHOAM operation hits this on the normal
     * path, so the verifier MUST mirror the transfer's exclusion. The same applies to
     * volatile junk (.DS_Store, *.tmp, *.part): rsync excludes it, so it is absent at the
     * destination — verifying it would fail closed and wedge every move of a folder that
     * contains one. Exposed for testing.
     */
    fun filesToVerify(sourceDir: File): List<File> =
        sourceDir.walkTopDown().filter { it.isFile && !isExcludedFromTree(it.name) }.toList()

    /**
     * Classify each source file as verified / failed / hash-mismatch against a map of
     * remote (size, hash?) keyed by path relative to [sourceDir]. A file is verified
     * only when BOTH size and SHA-256 match; missing remote data, a size mismatch, or a
     * missing remote hash all fail closed (no size-only fallback). Pure apart from
     * local hashing, so the verification decision is unit-testable without SSH.
     */
    fun classify(
        sourceDir: File,
        allFiles: List<File>,
        remoteData: Map<String, Pair<Long, String?>>
    ): DirectoryVerifyResult {
        val verified = mutableListOf<File>()
        val failed = mutableListOf<String>()
        val hashMismatches = mutableListOf<String>()

        for (file in allFiles) {
            val relPath = file.relativeTo(sourceDir).path
            val remote = remoteData[relPath]

            if (remote == null) {
                // No remote data: file missing or SSH failed
                failed.add(relPath)
                continue
            }

            val (remoteSize, remoteHash) = remote

            if (remoteSize != file.length()) {
                failed.add(relPath)
                logger.warn { "Size mismatch: $relPath local=${file.length()}, remote=$remoteSize" }
                continue
            }

            if (remoteHash == null) {
                // Size matches but no hash: fail closed, do NOT fall back to size-only
                failed.add(relPath)
                logger.warn { "Missing remote hash for $relPath, NOT verified (no size-only fallback)" }
                continue
            }

            val localHash = computeSha256(file)
            if (localHash != remoteHash) {
                hashMismatches.add(relPath)
                failed.add(relPath)
                logger.warn { "HASH MISMATCH: $relPath local=$localHash, remote=$remoteHash, possible corruption" }
                continue
            }

            verified.add(file)
        }

        return DirectoryVerifyResult(verified.size, failed, hashMismatches)
    }

    /**
     * Delete verified files and clean up empty directories.
     * Call this AFTER verifyDirectory() returns with the files to keep/delete.
     */
    fun deleteVerifiedFiles(sourceDir: File, verifiedCount: Int, failedCount: Int) {
        if (failedCount > 0) {
            // Partial: only delete files that were verified
            // Re-walk to find files — verified files are those NOT in the failed set
            // Caller should use the verified list from verifyDirectory instead
            return
        }
        // All verified — delete everything
        sourceDir.deleteRecursively()
    }

    /** Compute SHA-256 of a local file. */
    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        file.inputStream().buffered().use { stream ->
            var n = stream.read(buffer)
            while (n != -1) {
                digest.update(buffer, 0, n)
                n = stream.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

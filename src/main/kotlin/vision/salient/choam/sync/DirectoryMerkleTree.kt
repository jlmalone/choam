package vision.salient.choam.sync

import mu.KotlinLogging
import vision.salient.choam.config.MachineProfile
import vision.salient.choam.lowPriority
import vision.salient.choam.network.NetworkRoute
import java.io.File
import java.security.MessageDigest

private val logger = KotlinLogging.logger {}

/** Whether a leaf hash is backed by content (CID/SHA-256) or just metadata (size+mtime). */
enum class HashConfidence { CONTENT, METADATA }

/**
 * Internal result type for remote tree building. Mapped to PreflightOutcome at boundary.
 */
sealed class RemoteTreeResult {
    data class Built(val tree: DirectoryMerkleTree) : RemoteTreeResult()
    object NetworkUnavailable : RemoteTreeResult()
    data class Failed(val reason: String) : RemoteTreeResult()
}

/** The manifest filename — excluded from all tree operations. */
const val MANIFEST_FILENAME = ".choam_manifest.json"

/**
 * Volatile junk that is NEVER payload and must be invisible to every identity, conflict,
 * manifest, verification, and MOVE-deletion decision — exactly as it is invisible to rsync.
 *
 * These are the same patterns rsync excludes (ChoamConfig.excludePatterns defaults). Before
 * this was centralized, only MANIFEST_FILENAME was filtered out of the tree/preflight walks,
 * so a stray `.DS_Store` (Finder rewrites them with differing size/content per directory) or a
 * `.tmp`/`.part` written by another process would be hashed, mismatch the other side, and be
 * classified CONFLICT — refusing an otherwise byte-identical directory MOVE and wedging the
 * queue. Excluding them here keeps the accounting aligned with what actually gets transferred.
 */
val ALWAYS_EXCLUDED_NAMES = setOf(MANIFEST_FILENAME, ".DS_Store")
/** Suffix globs (rsync-style `*.tmp`) that are never payload. */
val ALWAYS_EXCLUDED_SUFFIXES = listOf(".tmp", ".part")

/** True if a filename is volatile junk excluded from all tree/preflight/verify/move accounting. */
fun isExcludedFromTree(name: String): Boolean =
    name in ALWAYS_EXCLUDED_NAMES || ALWAYS_EXCLUDED_SUFFIXES.any { name.endsWith(it) }

/** rsync `--exclude` patterns for the always-excluded junk (drops MANIFEST handled separately). */
fun alwaysExcludedRsyncPatterns(): List<String> =
    (ALWAYS_EXCLUDED_NAMES.toList() + ALWAYS_EXCLUDED_SUFFIXES.map { "*$it" }).distinct()

/** `find` predicate args (e.g. `-not -name '.DS_Store' -not -name '*.tmp'`) for remote walks. */
fun alwaysExcludedFindArgs(): String =
    (ALWAYS_EXCLUDED_NAMES.map { "-not -name '$it'" } +
        ALWAYS_EXCLUDED_SUFFIXES.map { "-not -name '*$it'" }).joinToString(" ")

/**
 * A node in the Merkle tree. Leaves are files, internal nodes are directories.
 */
data class MerkleNode(
    val name: String,
    val relativePath: String,
    val hash: String,
    val confidence: HashConfidence,
    val isDirectory: Boolean,
    val size: Long,
    val children: List<MerkleNode>,
    /** Raw content identifier used in hash computation. For CONTENT: a CID. For METADATA: "meta:size:mtime". Null for directories. */
    val contentId: String? = null
)

/**
 * The result of diffing two Merkle trees.
 */
data class MerkleDiff(
    val newFiles: List<String>,
    val modifiedFiles: List<String>,
    val deletedFiles: List<String>
) {
    val isEmpty: Boolean get() = newFiles.isEmpty() && modifiedFiles.isEmpty() && deletedFiles.isEmpty()
}

/**
 * A Merkle tree built from a directory structure.
 *
 * Each leaf node represents a file. Internal nodes represent directories
 * whose hash is the hash of their sorted children's hashes concatenated.
 * The root hash is deterministic and sensitive to any file change.
 *
 * `.choam_manifest.json` is always excluded from the tree (self-reference prevention).
 *
 * Leaf hash formula:
 *   sha256(relativePath + "|" + size + "|" + contentIdentifier)
 * where contentIdentifier = CID (from manifest/Sietch) or "meta:" + size + ":" + mtime
 */
class DirectoryMerkleTree private constructor(
    val root: MerkleNode,
    val fileCount: Int
) {
    /** The root hash of the entire directory tree. */
    val rootHash: String get() = root.hash

    /**
     * Diff this tree against another, returning files that are new, modified, or deleted.
     */
    fun diff(other: DirectoryMerkleTree): MerkleDiff {
        val thisFiles = collectFilesFromRoot(root)
        val otherFiles = collectFilesFromRoot(other.root)

        val newFiles = mutableListOf<String>()
        val modifiedFiles = mutableListOf<String>()
        val deletedFiles = mutableListOf<String>()

        for (key in otherFiles.keys) {
            if (key !in thisFiles) {
                newFiles.add(key)
            } else if (otherFiles[key] != thisFiles[key]) {
                modifiedFiles.add(key)
            }
        }

        for (key in thisFiles.keys) {
            if (key !in otherFiles) {
                deletedFiles.add(key)
            }
        }

        return MerkleDiff(newFiles, modifiedFiles, deletedFiles)
    }

    /**
     * Convert this tree to a ChoamManifest for serialization.
     *
     * @param directory The source directory on disk — needed to stat files for real mtime values.
     *   If null, mtimes will be 0 (manifest won't be reusable for CONTENT confidence on reload).
     */
    fun toManifest(directory: File? = null): ChoamManifest {
        val files = mutableMapOf<String, ChoamManifest.FileEntry>()
        collectManifestEntries(root, directory, files)

        val lowestConfidence = if (files.values.any { it.contentHash == null }) "METADATA" else "CONTENT"

        return ChoamManifest(
            builtAt = java.time.Instant.now().toString(),
            rootHash = rootHash,
            fileCount = fileCount,
            totalBytes = root.size,
            hashConfidence = lowestConfidence,
            files = files
        )
    }

    private fun collectManifestEntries(node: MerkleNode, directory: File?, result: MutableMap<String, ChoamManifest.FileEntry>) {
        if (!node.isDirectory) {
            // Store the raw contentId (CID for CONTENT, null for METADATA) so
            // buildLocal() can reuse it. mtime comes from disk so the freshness
            // check works on reload.
            val realMtime = if (directory != null) {
                File(directory, node.relativePath).let { if (it.exists()) it.lastModified() else 0L }
            } else 0L

            val rawContentHash = if (node.confidence == HashConfidence.CONTENT) node.contentId else null

            result[node.relativePath] = ChoamManifest.FileEntry(
                size = node.size,
                mtime = realMtime,
                contentHash = rawContentHash,
                metadataHash = node.hash
            )
        } else {
            for (child in node.children) {
                collectManifestEntries(child, directory, result)
            }
        }
    }

    /**
     * Collect all leaf (file) nodes from root, using relative paths
     * that exclude the root directory name itself.
     */
    private fun collectFilesFromRoot(rootNode: MerkleNode): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (child in rootNode.children) {
            collectFilesRecursive(child, "", result)
        }
        return result
    }

    private fun collectFilesRecursive(node: MerkleNode, prefix: String, result: MutableMap<String, String>) {
        val currentPath = if (prefix.isEmpty()) node.name else "$prefix/${node.name}"

        if (!node.isDirectory) {
            if (node.name.isNotEmpty()) {
                result[currentPath] = node.hash
            }
        } else {
            for (child in node.children) {
                collectFilesRecursive(child, currentPath, result)
            }
        }
    }

    companion object {
        /**
         * Build a Merkle tree from a local directory.
         *
         * Hash sources for leaves (in priority order):
         * 1. Local .choam_manifest.json — reuse contentHash if file mtime unchanged
         * 2. Metadata fallback — size+mtime, METADATA confidence
         *
         * .choam_manifest.json itself is always excluded from the tree.
         */
        fun buildLocal(directory: File, manifest: ChoamManifest? = null): DirectoryMerkleTree {
            require(directory.isDirectory) { "Path must be a directory: ${directory.absolutePath}" }
            var count = 0
            val root = buildLocalNode(directory, directory, manifest) { count++ }
            return DirectoryMerkleTree(root, count)
        }

        /**
         * Backward-compatible builder. Uses old hash formula (relativePath:size) without mtime.
         * For new code, use buildLocal() which includes mtime and manifest-aware hash sources.
         */
        fun buildFrom(directory: File): DirectoryMerkleTree {
            require(directory.isDirectory) { "Path must be a directory: ${directory.absolutePath}" }
            var count = 0
            val root = buildLegacyNode(directory, directory) { count++ }
            return DirectoryMerkleTree(root, count)
        }

        /**
         * Build a Merkle tree from remote directory via SSH.
         *
         * Runs a single SSH command that outputs "relativePath|size|mtime" for each file.
         * Returns RemoteTreeResult (mapped to PreflightOutcome at boundary).
         */
        fun buildRemote(
            destPath: String,
            machine: MachineProfile,
            route: NetworkRoute
        ): RemoteTreeResult {
            val user = machine.sshUser ?: return RemoteTreeResult.NetworkUnavailable
            val host = route.targetAddress
            val port = machine.sshPort

            val portArgs = if (port != 22) listOf("-p", "$port") else emptyList()
            val escapedPath = destPath.replace("'", "'\\''")

            // Single SSH call: find all files (excluding manifest + volatile junk to match the
            // local tree walk), output relativePath|size|mtime
            val excludeArgs = alwaysExcludedFindArgs()
            val findScript = """
                cd '$escapedPath' 2>/dev/null || { echo "CHOAM_DIR_MISSING"; exit 0; }
                find . -type f $excludeArgs -exec stat -f '%N|%z|%m' {} \; 2>/dev/null || \
                find . -type f $excludeArgs -printf '%P|%s|%T@\n' 2>/dev/null
                echo "CHOAM_TREE_DONE"
            """.trimIndent()

            val cmd = lowPriority(
                listOf("ssh") + portArgs + listOf(
                    "-o", "ConnectTimeout=10",
                    "-o", "BatchMode=yes",
                    "-o", "ServerAliveInterval=30",
                    "-o", "ServerAliveCountMax=5",
                    "$user@$host",
                    "bash -s"
                )
            )

            val process = try {
                ProcessBuilder(cmd).redirectErrorStream(true).start()
            } catch (e: Exception) {
                logger.warn { "Remote tree build SSH failed: ${e.message}" }
                return RemoteTreeResult.NetworkUnavailable
            }

            // Write script to stdin
            val stdinThread = Thread {
                try {
                    process.outputStream.bufferedWriter().use { writer ->
                        writer.write(findScript)
                        writer.flush()
                    }
                } catch (_: Exception) { }
            }.apply { isDaemon = true; start() }

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            stdinThread.join(5000)

            // Connection refused / unreachable
            if (exitCode != 0 && output.isBlank()) {
                return RemoteTreeResult.NetworkUnavailable
            }

            // SSH connected but command returned non-zero with output — treat as failure
            if (exitCode != 0) {
                return RemoteTreeResult.Failed("SSH exited with code $exitCode: ${output.take(200)}")
            }

            val lines = output.trim().lines()

            // Check for missing directory (clean absence)
            if (lines.any { it.trim() == "CHOAM_DIR_MISSING" }) {
                return RemoteTreeResult.NetworkUnavailable // clean absence → maps to FallThrough
            }

            // Check for completion marker — truncated output without it is unsafe
            if (!lines.any { it.trim() == "CHOAM_TREE_DONE" }) {
                return RemoteTreeResult.Failed("Truncated remote tree output (no completion marker)")
            }

            // Parse file entries
            val fileNodes = mutableListOf<MerkleNode>()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed == "CHOAM_TREE_DONE" || trimmed == "CHOAM_DIR_MISSING") continue

                val parts = trimmed.split("|")
                if (parts.size < 3) {
                    return RemoteTreeResult.Failed("Parse error in remote tree line: $trimmed")
                }

                val relPath = parts[0].removePrefix("./")
                val size = parts[1].toLongOrNull()
                    ?: return RemoteTreeResult.Failed("Invalid size in remote tree line: $trimmed")
                val mtime = parts[2].substringBefore(".").toLongOrNull() ?: 0L

                val contentId = "meta:$size:$mtime"
                val hash = sha256("$relPath|$size|$contentId")

                fileNodes.add(MerkleNode(
                    name = relPath.substringAfterLast('/'),
                    relativePath = relPath,
                    hash = hash,
                    confidence = HashConfidence.METADATA,
                    isDirectory = false,
                    size = size,
                    children = emptyList()
                ))
            }

            // Build tree structure from flat list
            val tree = buildTreeFromFlatList(fileNodes, File(destPath).name)
            return RemoteTreeResult.Built(tree)
        }

        /**
         * Build a tree structure from a flat list of file nodes by grouping into directories.
         */
        private fun buildTreeFromFlatList(files: List<MerkleNode>, rootName: String): DirectoryMerkleTree {
            if (files.isEmpty()) {
                val emptyHash = sha256("empty-dir:")
                val root = MerkleNode(rootName, "", emptyHash, HashConfidence.METADATA, true, 0, emptyList())
                return DirectoryMerkleTree(root, 0)
            }

            // Group files by top-level directory
            val rootChildren = buildDirectoryNodes(files, "")

            val combinedHash = sha256(rootChildren.sortedBy { it.name }.joinToString("|") { it.hash })
            val root = MerkleNode(
                name = rootName,
                relativePath = "",
                hash = combinedHash,
                confidence = if (rootChildren.all { nodeMinConfidence(it) == HashConfidence.CONTENT }) HashConfidence.CONTENT else HashConfidence.METADATA,
                isDirectory = true,
                size = rootChildren.sumOf { it.size },
                children = rootChildren.sortedBy { it.name }
            )
            return DirectoryMerkleTree(root, files.size)
        }

        /**
         * Recursively build directory nodes from a flat file list.
         */
        private fun buildDirectoryNodes(files: List<MerkleNode>, prefix: String): List<MerkleNode> {
            val directChildren = mutableListOf<MerkleNode>()
            val subdirFiles = mutableMapOf<String, MutableList<MerkleNode>>()

            for (file in files) {
                val relFromPrefix = if (prefix.isEmpty()) file.relativePath else file.relativePath.removePrefix("$prefix/")
                val slashIdx = relFromPrefix.indexOf('/')
                if (slashIdx == -1) {
                    // Direct child file
                    directChildren.add(file)
                } else {
                    // File belongs in a subdirectory
                    val subDirName = relFromPrefix.substring(0, slashIdx)
                    subdirFiles.getOrPut(subDirName) { mutableListOf() }.add(file)
                }
            }

            // Build subdirectory nodes
            val subdirNodes = subdirFiles.map { (dirName, dirFiles) ->
                val dirPath = if (prefix.isEmpty()) dirName else "$prefix/$dirName"
                val children = buildDirectoryNodes(dirFiles, dirPath)
                val combinedHash = sha256(children.sortedBy { it.name }.joinToString("|") { it.hash })
                MerkleNode(
                    name = dirName,
                    relativePath = dirPath,
                    hash = combinedHash,
                    confidence = if (children.all { nodeMinConfidence(it) == HashConfidence.CONTENT }) HashConfidence.CONTENT else HashConfidence.METADATA,
                    isDirectory = true,
                    size = children.sumOf { it.size },
                    children = children.sortedBy { it.name }
                )
            }

            return (directChildren + subdirNodes).sortedBy { it.name }
        }

        private fun nodeMinConfidence(node: MerkleNode): HashConfidence {
            if (!node.isDirectory) return node.confidence
            return if (node.children.all { nodeMinConfidence(it) == HashConfidence.CONTENT })
                HashConfidence.CONTENT else HashConfidence.METADATA
        }

        private fun buildLocalNode(file: File, rootDir: File, manifest: ChoamManifest?, onFile: () -> Unit): MerkleNode {
            if (file.isFile) {
                onFile()
                val relativePath = file.relativeTo(rootDir).path
                val size = file.length()
                val mtime = file.lastModified()

                // Priority 1: local manifest with unchanged mtime — reuse contentHash (CONTENT confidence)
                val manifestEntry = manifest?.files?.get(relativePath)
                val (contentId, confidence) = if (manifestEntry != null && manifestEntry.mtime == mtime && manifestEntry.contentHash != null) {
                    manifestEntry.contentHash to HashConfidence.CONTENT
                } else {
                    // Priority 3: metadata fallback
                    "meta:$size:$mtime" to HashConfidence.METADATA
                }

                val hash = sha256("$relativePath|$size|$contentId")
                return MerkleNode(
                    name = file.name,
                    relativePath = relativePath,
                    hash = hash,
                    confidence = confidence,
                    isDirectory = false,
                    size = size,
                    children = emptyList(),
                    contentId = contentId
                )
            }

            // Directory: build children, excluding the manifest and volatile junk (.DS_Store,
            // *.tmp, *.part) so they never drive the rootHash / diff / conflict decisions.
            val children = (file.listFiles() ?: emptyArray())
                .filter { !isExcludedFromTree(it.name) }
                .sortedBy { it.name }
                .map { buildLocalNode(it, rootDir, manifest, onFile) }

            val combinedHash = if (children.isEmpty()) {
                sha256("empty-dir:${file.relativeTo(rootDir).path}")
            } else {
                sha256(children.joinToString("|") { it.hash })
            }

            return MerkleNode(
                name = file.name,
                relativePath = file.relativeTo(rootDir).path,
                hash = combinedHash,
                confidence = if (children.all { nodeMinConfidence(it) == HashConfidence.CONTENT }) HashConfidence.CONTENT else HashConfidence.METADATA,
                isDirectory = true,
                size = children.sumOf { it.size },
                children = children
            )
        }

        /**
         * Legacy node builder for buildFrom() backward compat.
         * Uses old hash formula: sha256(relativePath:size) — no mtime, no manifest lookup.
         */
        private fun buildLegacyNode(file: File, rootDir: File, onFile: () -> Unit): MerkleNode {
            if (file.isFile) {
                onFile()
                val relativePath = file.relativeTo(rootDir).path
                val hash = sha256("$relativePath:${file.length()}")
                return MerkleNode(
                    name = file.name,
                    relativePath = relativePath,
                    hash = hash,
                    confidence = HashConfidence.METADATA,
                    isDirectory = false,
                    size = file.length(),
                    children = emptyList()
                )
            }

            val children = (file.listFiles() ?: emptyArray())
                .filter { !isExcludedFromTree(it.name) }
                .sortedBy { it.name }
                .map { buildLegacyNode(it, rootDir, onFile) }

            val combinedHash = if (children.isEmpty()) {
                sha256("empty-dir:${file.relativeTo(rootDir).path}")
            } else {
                sha256(children.joinToString("|") { it.hash })
            }

            return MerkleNode(
                name = file.name,
                relativePath = file.relativeTo(rootDir).path,
                hash = combinedHash,
                confidence = HashConfidence.METADATA,
                isDirectory = true,
                size = children.sumOf { it.size },
                children = children
            )
        }

        internal fun sha256(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}

package vision.salient.choam.sync

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import kotlin.io.path.isRegularFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import vision.salient.choam.config.ChoamConfig
import vision.salient.choam.config.MachineProfile
import vision.salient.choam.config.MountedDrive
import vision.salient.choam.config.SyncRules
import vision.salient.choam.drive.DriveDetector
import vision.salient.choam.network.NetworkRoute
import vision.salient.choam.network.TransferManager
import vision.salient.choam.network.TransferProgress
import vision.salient.sietch.core.CatalogEntry
import vision.salient.sietch.core.indexDirectoryRelative
import vision.salient.sietch.core.parseCatalog
import vision.salient.sietch.core.writeCatalog

private val logger = KotlinLogging.logger {}

class SyncEngine(
    private val config: ChoamConfig,
    private val transferManager: TransferManager,
    private val conflictResolver: ConflictResolver
) {
    suspend fun sync(
        source: MachineProfile,
        target: MachineProfile,
        repositories: List<String>,
        rules: SyncRules,
        route: NetworkRoute,
        dryRun: Boolean = false,
        progressCallback: ((SyncSession, TransferProgress) -> Unit)? = null
    ): SyncSession {
        val start = Instant.now()
        logger.info { "Starting sync: ${source.name} -> ${target.name} for $repositories" }

        var session = SyncSession(
            sourceMachine = source.name,
            targetMachine = target.name,
            repositories = repositories,
            startTime = start,
            endTime = null,
            status = SyncStatus.PREPARING,
            statistics = SyncStatistics()
        )

        var filesScanned = 0L
        var filesTransferred = 0L
        var bytesTransferred = 0L
        var filesSkipped = 0L
        var conflicts = 0
        var errors = 0

        // Detect mounted drives once for all repos
        val driveDetector = DriveDetector()
        val mountedDrives = if (config.drives.isNotEmpty()) {
            driveDetector.detectConfiguredDrives(config.drives)
        } else {
            emptyMap()
        }

        for (repo in repositories) {
            val sourcePath = resolveRepoPath(repo, source, mountedDrives)
                ?: source.repositories[repo]
            val targetPath = resolveRepoPath(repo, target, mountedDrives)
                ?: target.repositories[repo]

            if (sourcePath == null || targetPath == null) {
                logger.warn {
                    "Repository '$repo' is not configured on both machines; " +
                        "sourcePath=$sourcePath, targetPath=$targetPath. Skipping."
                }
                filesSkipped++
                continue
            }

            session = session.copy(status = SyncStatus.CATALOGING)

            val sourceManifests = buildCatalog(source, sourcePath, rules.excludePatterns)
            val targetManifests = buildCatalog(target, targetPath, rules.excludePatterns)

            filesScanned += sourceManifests.size + targetManifests.size

            session = session.copy(status = SyncStatus.COMPARING)

            val sourceRoot = Paths.get(sourcePath)
            val targetRoot = Paths.get(targetPath)

            val filesToCopy = mutableListOf<Pair<Path, Path>>()
            val filesToDelete = mutableListOf<Path>()

            if (rules.bidirectional) {
                val forwardDiff = calculateDiff(sourceManifests, targetManifests)
                val backwardDiff = calculateDiff(targetManifests, sourceManifests)

                // New files on source -> copy to target.
                forwardDiff.newFiles.forEach { manifest ->
                    filesToCopy +=
                        sourceRoot.resolve(manifest.path) to
                            targetRoot.resolve(manifest.path)
                }

                // New files on target -> copy to source.
                backwardDiff.newFiles.forEach { manifest ->
                    filesToCopy +=
                        targetRoot.resolve(manifest.path) to
                            sourceRoot.resolve(manifest.path)
                }

                // Identify files modified on both sides (true conflicts)
                val forwardModifiedPaths = forwardDiff.modifiedFiles.map { it.first.path }.toSet()
                val backwardModifiedPaths = backwardDiff.modifiedFiles.map { it.first.path }.toSet()
                val conflictPaths = forwardModifiedPaths.intersect(backwardModifiedPaths)

                // Resolve conflicts for files modified on both sides
                forwardDiff.modifiedFiles.forEach { (srcManifest, tgtManifest) ->
                    if (conflictPaths.contains(srcManifest.path)) {
                        // Modified on both sides - use conflict resolver
                        val conflict = ConflictPair(
                            source = srcManifest,
                            target = tgtManifest,
                            reason = ConflictReason.BOTH_MODIFIED
                        )
                        val resolution = conflictResolver.resolveConflict(conflict, rules.conflictResolution)
                        when (resolution) {
                            is ConflictResolution.UseSource -> {
                                filesToCopy +=
                                    sourceRoot.resolve(resolution.file.path) to
                                        targetRoot.resolve(resolution.file.path)
                            }
                            is ConflictResolution.UseTarget -> {
                                filesToCopy +=
                                    targetRoot.resolve(resolution.file.path) to
                                        sourceRoot.resolve(resolution.file.path)
                            }
                            is ConflictResolution.KeepBoth -> {
                                // Implement KEEP_BOTH: copy both files with suffixes
                                val timestamp = java.time.ZonedDateTime.now().format(
                                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                                )
                                val basePath = srcManifest.path
                                val ext = if (basePath.contains(".")) {
                                    "." + basePath.substringAfterLast(".")
                                } else {
                                    ""
                                }
                                val nameWithoutExt = if (basePath.contains(".")) {
                                    basePath.substringBeforeLast(".")
                                } else {
                                    basePath
                                }

                                // Copy source version to target with .source suffix
                                filesToCopy +=
                                    sourceRoot.resolve(srcManifest.path) to
                                        targetRoot.resolve("${nameWithoutExt}.source-${timestamp}${ext}")
                                // Copy target version to source with .target suffix
                                filesToCopy +=
                                    targetRoot.resolve(tgtManifest.path) to
                                        sourceRoot.resolve("${nameWithoutExt}.target-${timestamp}${ext}")
                                logger.info {
                                    "Keeping both versions of ${srcManifest.path} with timestamp suffixes"
                                }
                            }
                            is ConflictResolution.RequiresManualReview -> {
                                logger.warn {
                                    "Conflict requires manual review for ${srcManifest.path}; skipping."
                                }
                                filesSkipped++
                            }
                        }
                        conflicts++
                    } else {
                        // Only modified on source - copy to target
                        filesToCopy +=
                            sourceRoot.resolve(srcManifest.path) to
                                targetRoot.resolve(srcManifest.path)
                    }
                }

                // Files only modified on target - copy to source
                backwardDiff.modifiedFiles.forEach { (tgtManifest, srcManifest) ->
                    if (!conflictPaths.contains(tgtManifest.path)) {
                        filesToCopy +=
                            targetRoot.resolve(tgtManifest.path) to
                                sourceRoot.resolve(tgtManifest.path)
                    }
                }

                // Handle deletions in bidirectional mode
                if (rules.deleteRemoved) {
                    logger.warn {
                        "Bidirectional sync with deleteRemoved=true is dangerous; " +
                            "skipping ${forwardDiff.deletedFiles.size + backwardDiff.deletedFiles.size} deletions. " +
                            "Consider using unidirectional sync for safe deletions."
                    }
                }
                filesSkipped += forwardDiff.deletedFiles.size + backwardDiff.deletedFiles.size
            } else {
                // Unidirectional sync: source -> target
                val diff = calculateDiff(sourceManifests, targetManifests)

                // Copy new files from source to target
                diff.newFiles.forEach { manifest ->
                    filesToCopy +=
                        sourceRoot.resolve(manifest.path) to
                            targetRoot.resolve(manifest.path)
                }

                // Copy modified files from source to target
                diff.modifiedFiles.forEach { (src, _) ->
                    filesToCopy +=
                        sourceRoot.resolve(src.path) to
                            targetRoot.resolve(src.path)
                }

                // Handle deletions (files that exist on target but not on source)
                if (rules.deleteRemoved) {
                    diff.deletedFiles.forEach { manifest ->
                        val fileToDelete = targetRoot.resolve(manifest.path)
                        filesToDelete.add(fileToDelete)
                        logger.info { "Marked for deletion: $fileToDelete" }
                    }
                } else {
                    filesSkipped += diff.deletedFiles.size
                }
            }

            if (dryRun) {
                session = session.copy(status = SyncStatus.COMPARING)
                filesToCopy.forEach { (srcPath, _) ->
                    try {
                        if (Files.exists(srcPath)) {
                            val size = Files.size(srcPath)
                            filesTransferred++
                            bytesTransferred += size
                        } else {
                            logger.warn { "Dry-run: source file does not exist: $srcPath" }
                            errors++
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "Dry-run: failed to stat file $srcPath" }
                        errors++
                    }
                }

                // Dry-run: also report what would be deleted
                if (filesToDelete.isNotEmpty()) {
                    logger.info { "Dry-run: would delete ${filesToDelete.size} files" }
                    filesToDelete.forEach { fileToDelete ->
                        logger.info { "Would delete: $fileToDelete" }
                    }
                }
            } else {
                session = session.copy(status = SyncStatus.TRANSFERRING)
                for ((srcPath, dstPath) in filesToCopy) {
                    val srcLocation = vision.salient.choam.network.FileLocation(
                        machine = source,
                        path = srcPath.toString()
                    )
                    val dstLocation = vision.salient.choam.network.FileLocation(
                        machine = target,
                        path = dstPath.toString()
                    )

                    val callback =
                        progressCallback?.let { cb ->
                            { progress: TransferProgress ->
                                cb(session, progress)
                            }
                        } ?: {}

                    try {
                        when (val result =
                            transferManager.transferFile(
                                source = srcLocation,
                                target = dstLocation,
                                route = route,
                                bandwidthLimitKBps = rules.bandwidthLimit,
                                progressCallback = callback
                            )
                        ) {
                            is vision.salient.choam.network.TransferResult.Success -> {
                                filesTransferred++
                                bytesTransferred += result.bytesTransferred
                            }
                            is vision.salient.choam.network.TransferResult.Failure -> {
                                errors++
                                logger.error(result.cause) {
                                    "Failed to transfer ${srcPath} -> ${dstPath}: ${result.message}"
                                }
                            }
                        }
                    } catch (e: Exception) {
                        errors++
                        logger.error(e) {
                            "Unexpected error during transfer ${srcPath} -> ${dstPath}"
                        }
                    }
                }

                // Perform deletions after all transfers complete successfully
                if (filesToDelete.isNotEmpty()) {
                    logger.info { "Deleting ${filesToDelete.size} files from target..." }
                    for (fileToDelete in filesToDelete) {
                        try {
                            if (Files.exists(fileToDelete)) {
                                Files.delete(fileToDelete)
                                filesTransferred++ // Count deletions as "transferred" operations
                                logger.info { "Deleted: $fileToDelete" }
                            } else {
                                logger.warn { "File already deleted: $fileToDelete" }
                            }
                        } catch (e: Exception) {
                            errors++
                            logger.error(e) { "Failed to delete $fileToDelete" }
                        }
                    }
                }
            }
        }

        val statistics =
            SyncStatistics(
                filesScanned = filesScanned,
                filesTransferred = filesTransferred,
                bytesTransferred = bytesTransferred,
                filesSkipped = filesSkipped,
                conflicts = conflicts,
                errors = errors
            )

        session =
            session.copy(
                endTime = Instant.now(),
                status = if (errors > 0) SyncStatus.FAILED else SyncStatus.COMPLETED,
                statistics = statistics
            )

        logger.info { "Completed sync session ${session.id} with status ${session.status}" }
        return session
    }

    /**
     * Build a catalog of files in a repository path using Sietch core.
     * Uses SHA-256 hashing by default. Set hashAlgorithm to "none" for fast scanning.
     */
    suspend fun buildCatalog(
        machine: MachineProfile,
        repoPath: String,
        excludePatterns: List<String> = emptyList(),
        hashAlgorithm: String = "none"
    ): List<FileManifest> {
        val path = Paths.get(repoPath)
        if (!Files.exists(path)) return emptyList()

        val pathMatcher = if (excludePatterns.isNotEmpty()) {
            PathMatcher(excludePatterns)
        } else {
            null
        }

        return withContext(Dispatchers.IO) {
            // Use Sietch core for directory walking and hashing
            val catalog = indexDirectoryRelative(path.toFile(), hashAlgorithm)

            // Convert Sietch CatalogEntry to CHOAM FileManifest, applying exclude patterns
            catalog.entries
                .filter { entry ->
                    if (pathMatcher != null) {
                        !pathMatcher.matches(entry.path)
                    } else {
                        true
                    }
                }
                .filter { !it.hash.startsWith("ERROR:") }
                .map { entry ->
                    val filePath = path.resolve(entry.path)
                    val modTime = try {
                        Files.getLastModifiedTime(filePath).toInstant()
                    } catch (e: Exception) {
                        Instant.EPOCH
                    }
                    FileManifest(
                        path = entry.path,
                        size = entry.size,
                        modifiedTime = modTime,
                        checksum = if (entry.hash == "-") null else entry.hash,
                        exists = true
                    )
                }
        }
    }

    /**
     * Build a catalog and cache it as a .sietch-catalog.txt file in the repo root.
     * When a drive is detected, the catalog is stored on the drive itself.
     */
    suspend fun buildAndCacheCatalog(
        machine: MachineProfile,
        repoPath: String,
        excludePatterns: List<String> = emptyList(),
        hashAlgorithm: String = "sha256"
    ): List<FileManifest> {
        val path = Paths.get(repoPath)
        if (!Files.exists(path)) return emptyList()

        val manifests = buildCatalog(machine, repoPath, excludePatterns, hashAlgorithm)

        // Cache the Sietch catalog on disk
        return withContext(Dispatchers.IO) {
            try {
                val sietchDir = path.resolve(".sietch")
                Files.createDirectories(sietchDir)
                val catalogFile = sietchDir.resolve(
                    "catalog-${java.time.LocalDate.now()}.txt"
                ).toFile()

                val catalog = indexDirectoryRelative(path.toFile(), hashAlgorithm)
                writeCatalog(catalog, catalogFile)
                logger.info { "Cached Sietch catalog at ${catalogFile.absolutePath}" }
            } catch (e: Exception) {
                logger.warn { "Failed to cache Sietch catalog: ${e.message}" }
            }
            manifests
        }
    }

    /**
     * Load a previously cached Sietch catalog from a drive/repo path.
     * Returns null if no cached catalog exists.
     */
    fun loadCachedCatalog(repoPath: String): List<FileManifest>? {
        val sietchDir = Paths.get(repoPath, ".sietch")
        if (!Files.exists(sietchDir)) return null

        // Find the most recent catalog file
        val catalogFiles = sietchDir.toFile().listFiles { f ->
            f.name.startsWith("catalog-") && f.name.endsWith(".txt")
        }?.sortedByDescending { it.name } ?: return null

        val latestCatalog = catalogFiles.firstOrNull() ?: return null
        logger.info { "Loading cached Sietch catalog from ${latestCatalog.absolutePath}" }

        return try {
            val catalog = parseCatalog(latestCatalog)
            val rootPath = Paths.get(repoPath)
            catalog.entries.map { entry ->
                val relativePath = if (entry.path.startsWith(repoPath)) {
                    rootPath.relativize(Paths.get(entry.path)).toString()
                } else {
                    entry.path
                }
                FileManifest(
                    path = relativePath,
                    size = entry.size,
                    modifiedTime = Instant.EPOCH, // Cached catalogs don't store mtime
                    checksum = if (entry.hash == "-") null else entry.hash,
                    exists = true
                )
            }
        } catch (e: Exception) {
            logger.warn { "Failed to parse cached catalog: ${e.message}" }
            null
        }
    }

    /**
     * Resolve the actual filesystem path for a repository.
     * Checks drives first (by UUID), then falls back to machine path resolution.
     */
    fun resolveRepoPath(
        repo: String,
        machine: MachineProfile,
        mountedDrives: Map<String, MountedDrive>
    ): String? {
        // Check if any configured drive has this repository
        val driveDetector = DriveDetector()
        for ((_, drive) in config.drives) {
            val relativePath = drive.repositories[repo] ?: continue
            val mounted = mountedDrives.values.find { it.uuid == drive.uuid }
            if (mounted != null) {
                val resolved = "${mounted.mountPoint}/$relativePath"
                logger.info { "Resolved repo '$repo' via drive '${drive.label}' at $resolved" }
                return resolved
            }
        }

        // Fall back to machine path resolution (existing behavior)
        return machine.repositories[repo]
    }

    fun calculateDiff(
        source: List<FileManifest>,
        target: List<FileManifest>
    ): SyncDiff {
        val sourceMap = source.associateBy { it.path }
        val targetMap = target.associateBy { it.path }

        val newFiles = mutableListOf<FileManifest>()
        val deletedFiles = mutableListOf<FileManifest>()
        val modifiedFiles = mutableListOf<Pair<FileManifest, FileManifest>>()

        for ((path, src) in sourceMap) {
            val tgt = targetMap[path]
            if (tgt == null) {
                newFiles.add(src)
            } else if (src.size != tgt.size || src.modifiedTime != tgt.modifiedTime) {
                modifiedFiles.add(src to tgt)
            }
        }

        for ((path, tgt) in targetMap) {
            if (path !in sourceMap) {
                deletedFiles.add(tgt)
            }
        }

        return SyncDiff(
            newFiles = newFiles,
            modifiedFiles = modifiedFiles,
            deletedFiles = deletedFiles
        )
    }
}

data class SyncDiff(
    val newFiles: List<FileManifest>,
    val modifiedFiles: List<Pair<FileManifest, FileManifest>>,
    val deletedFiles: List<FileManifest>
)

data class ConflictPair(
    val source: FileManifest,
    val target: FileManifest,
    val reason: ConflictReason
)

enum class ConflictReason {
    BOTH_MODIFIED,
    SIZE_MISMATCH,
    CHECKSUM_MISMATCH
}

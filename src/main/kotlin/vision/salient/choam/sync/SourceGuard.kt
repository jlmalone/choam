package vision.salient.choam.sync

import mu.KotlinLogging
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Source file protection during transfers.
 *
 * Prevents silent corruption by:
 * 1. Sidecar lock file (.choam_lock) — advisory, discoverable by any tool
 * 2. lsof pre-check — hard-fail if any process has source open for write
 * 3. Fingerprint verification — detect source changes during transfer
 *
 * Safety model: fail-safe but not race-free. Until ecosystem writers respect
 * .choam_lock, the lsof check is best-effort "don't start if already dirty."
 * Post-transfer verification is the real correctness gate. MOVE is safe because
 * delete is contingent on passing all post-checks. The cost of the race is
 * wasted transfer time, not silent data loss.
 */
class SourceGuard private constructor(
    val sourcePath: String,
    val mode: TransferMode,
    private val lockFile: File,
    val fingerprint: SourceFingerprint
) : AutoCloseable {

    companion object {
        private val SQLITE_EXTENSIONS = setOf("db", "sqlite", "sqlite3")

        /**
         * Acquire a SourceGuard for the given file/directory.
         *
         * Creates sidecar lock, runs double lsof check (for SQLite: checks .db, .db-wal, .db-shm),
         * records source fingerprint (SHA-256 for MOVE, mtime+size for COPY).
         *
         * @throws SourceGuardException if lsof detects writable opens or lock cannot be acquired
         */
        fun acquire(sourcePath: String, mode: TransferMode, transferId: String): SourceGuard {
            val sourceFile = File(sourcePath)
            if (!sourceFile.exists()) {
                throw SourceGuardException("Source not found: $sourcePath")
            }

            val lockFile = File("${sourcePath}.choam_lock")

            // Check for stale lock — if PID is dead, safe to replace.
            // FAIL CLOSED: if lock file exists but is unreadable/corrupted, treat as active lock.
            // An unreadable lock means "something is happening, don't proceed" — never "everything's fine."
            if (lockFile.exists()) {
                val stalePid = parseLockPid(lockFile)
                if (stalePid != null && !isProcessAlive(stalePid)) {
                    logger.info { "Removing stale lock (PID $stalePid is dead): ${lockFile.name}" }
                    lockFile.delete()
                } else if (stalePid != null) {
                    throw SourceGuardException(
                        "Source is locked by another transfer (PID $stalePid): ${lockFile.name}"
                    )
                } else {
                    // Lock file exists but PID could not be parsed — corrupted or malformed.
                    // Fail closed: refuse to proceed. Manual cleanup via 'choam lock --clean'.
                    logger.error { "Lock file exists but is unreadable/corrupted: ${lockFile.name}" }
                    throw SourceGuardException(
                        "Source lock file is corrupted (cannot parse PID): ${lockFile.name}. " +
                        "If no transfer is running, remove it manually or run 'choam lock --force-clean ${File(sourcePath).absolutePath}'"
                    )
                }
            }

            // Create sidecar lock atomically — createNewFile() fails if file already exists,
            // preventing two concurrent transfers from both "winning" the lock
            val lockContent = """{"pid":${ProcessHandle.current().pid()},"transfer_id":"$transferId","started":"${Instant.now()}","mode":"$mode"}"""
            if (!lockFile.createNewFile()) {
                // Another process created the lock between our exists() check and createNewFile()
                val racePid = parseLockPid(lockFile)
                throw SourceGuardException(
                    "Source lock race: another transfer (PID ${racePid ?: "unknown"}) acquired the lock first"
                )
            }
            lockFile.writeText(lockContent)
            logger.info { "SourceGuard: locked ${sourceFile.name} (mode=$mode, id=$transferId)" }

            try {
                // First lsof check
                val filesToCheck = if (isSqlite(sourcePath)) sqliteTrio(sourcePath) else listOf(sourcePath)
                checkLsof(filesToCheck, "pre-transfer")

                // Record fingerprint
                val fingerprint = recordFingerprint(sourceFile, mode)
                logger.info { "SourceGuard: fingerprint recorded for ${sourceFile.name} ($fingerprint)" }

                // Second lsof check — narrows race window for near-zero cost
                checkLsof(filesToCheck, "pre-rsync")

                return SourceGuard(sourcePath, mode, lockFile, fingerprint)
            } catch (e: Exception) {
                // Cleanup lock on failure
                lockFile.delete()
                throw e
            }
        }

        /**
         * Check lsof for writable opens on the given paths.
         * Hard-fails if any process has any of the files open for write.
         */
        internal fun checkLsof(paths: List<String>, phase: String) {
            val existingPaths = paths.filter { File(it).exists() }
            if (existingPaths.isEmpty()) return

            try {
                val process = ProcessBuilder("lsof", *existingPaths.toTypedArray())
                    .redirectErrorStream(true)
                    .start()

                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()

                // lsof exit 0 = found open files, exit 1 = no open files
                if (exitCode == 0) {
                    val writableOpens = parseLsofForWriters(output)
                    if (writableOpens.isNotEmpty()) {
                        val details = writableOpens.joinToString("; ") { "${it.command}(${it.pid}) has ${it.path} open for write (fd=${it.fd})" }
                        throw SourceGuardException("$phase: source has writable opens — $details")
                    }
                }
            } catch (e: SourceGuardException) {
                throw e
            } catch (e: Exception) {
                // lsof execution failure = we can't verify safety. Fail closed.
                throw SourceGuardException("$phase: lsof check failed — cannot verify source safety: ${e.message}")
            }
        }

        internal fun parseLsofForWriters(lsofOutput: String): List<LsofEntry> {
            val writers = mutableListOf<LsofEntry>()
            for (line in lsofOutput.lines()) {
                if (line.startsWith("COMMAND")) continue // header
                val parts = line.split(Regex("\\s+"), limit = 10)
                if (parts.size < 9) continue

                val command = parts[0]
                val pid = parts[1].toLongOrNull() ?: continue
                val fd = parts[3]
                val path = parts.last()

                // Skip our own PID (rsync child will inherit our opens)
                if (pid == ProcessHandle.current().pid()) continue

                // 'u' = read/write, 'w' = write-only. 'r' = read-only (safe).
                // FD column looks like: "4u", "5w", "txt", "cwd", etc.
                val fdMode = fd.lastOrNull()
                if (fdMode == 'u' || fdMode == 'w') {
                    writers.add(LsofEntry(command, pid, fd, path))
                }
            }
            return writers
        }

        private fun recordFingerprint(file: File, mode: TransferMode): SourceFingerprint {
            if (file.isDirectory) {
                return recordDirectoryFingerprint(file, mode)
            }

            val mtime = Files.getLastModifiedTime(file.toPath()).toInstant()
            val size = file.length()

            return if (mode == TransferMode.MOVE) {
                // MOVE: full SHA-256 — non-negotiable
                SourceFingerprint(mtime, size, sha256(file))
            } else {
                // COPY: mtime + size only — no doubling source I/O
                SourceFingerprint(mtime, size, sha256 = null)
            }
        }

        /**
         * Directory fingerprint: walk all files, build a content digest from
         * relative paths + sizes + mtimes. This catches file modifications that
         * don't update the parent directory mtime (which is most edits).
         *
         * For MOVE: SHA-256 of the manifest string (path|size|mtime for each file, sorted).
         * For COPY: total size + max mtime across all files.
         */
        private fun recordDirectoryFingerprint(dir: File, mode: TransferMode): SourceFingerprint {
            val files = dir.walkTopDown()
                .filter { it.isFile }
                .map { file ->
                    val rel = file.relativeTo(dir).path
                    val size = file.length()
                    val mtime = Files.getLastModifiedTime(file.toPath()).toInstant()
                    Triple(rel, size, mtime)
                }
                .sortedBy { it.first }
                .toList()

            val totalSize = files.sumOf { it.second }
            val maxMtime = files.maxOfOrNull { it.third } ?: Instant.EPOCH

            return if (mode == TransferMode.MOVE) {
                // Build a manifest string and hash it — any file change alters the hash
                val manifest = files.joinToString("\n") { "${it.first}|${it.second}|${it.third}" }
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(manifest.toByteArray())
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                SourceFingerprint(maxMtime, totalSize, hash)
            } else {
                // COPY: total size + max mtime — catches most modifications
                SourceFingerprint(maxMtime, totalSize, sha256 = null)
            }
        }

        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            file.inputStream().buffered().use { input ->
                var bytesRead = input.read(buffer)
                while (bytesRead != -1) {
                    digest.update(buffer, 0, bytesRead)
                    bytesRead = input.read(buffer)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        internal fun isSqlite(path: String): Boolean {
            val ext = path.substringAfterLast('.', "").lowercase()
            return ext in SQLITE_EXTENSIONS
        }

        internal fun sqliteTrio(dbPath: String): List<String> =
            listOf(dbPath, "$dbPath-wal", "$dbPath-shm")

        internal fun parseLockPid(lockFile: File): Long? {
            return try {
                val content = lockFile.readText()
                val pidMatch = Regex(""""pid"\s*:\s*(\d+)""").find(content)
                pidMatch?.groupValues?.get(1)?.toLongOrNull()
            } catch (_: Exception) { null }
        }

        internal fun isProcessAlive(pid: Long): Boolean {
            return ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
        }
    }

    /**
     * Verify that the source has not changed since the guard was acquired.
     * For MOVE: compares SHA-256. For COPY: compares mtime + size.
     *
     * @return VerifyResult with pass/fail and details
     */
    fun verifySourceUnchanged(): VerifyResult {
        val file = File(sourcePath)
        if (!file.exists()) {
            return VerifyResult(false, "Source file no longer exists")
        }

        if (file.isDirectory) {
            return verifyDirectoryUnchanged(file)
        }

        val currentMtime = Files.getLastModifiedTime(file.toPath()).toInstant()
        val currentSize = file.length()

        if (currentMtime != fingerprint.mtime) {
            return VerifyResult(false, "Source mtime changed: ${fingerprint.mtime} → $currentMtime")
        }
        if (currentSize != fingerprint.size) {
            return VerifyResult(false, "Source size changed: ${fingerprint.size} → $currentSize")
        }

        // For MOVE: also verify SHA-256
        if (mode == TransferMode.MOVE && fingerprint.sha256 != null) {
            val currentHash = sha256(file)
            if (currentHash != fingerprint.sha256) {
                return VerifyResult(false, "Source SHA-256 changed: ${fingerprint.sha256.take(16)}... → ${currentHash.take(16)}...")
            }
        }

        return VerifyResult(true, "Source unchanged")
    }

    private fun verifyDirectoryUnchanged(dir: File): VerifyResult {
        // Re-compute directory fingerprint and compare
        val currentFingerprint = recordDirectoryFingerprint(dir, mode)

        if (currentFingerprint.size != fingerprint.size) {
            return VerifyResult(false, "Directory total size changed: ${fingerprint.size} → ${currentFingerprint.size}")
        }
        if (currentFingerprint.mtime != fingerprint.mtime) {
            return VerifyResult(false, "Directory max mtime changed: ${fingerprint.mtime} → ${currentFingerprint.mtime}")
        }
        if (mode == TransferMode.MOVE && fingerprint.sha256 != null && currentFingerprint.sha256 != fingerprint.sha256) {
            return VerifyResult(false, "Directory content hash changed: ${fingerprint.sha256?.take(16)}... → ${currentFingerprint.sha256?.take(16)}...")
        }

        return VerifyResult(true, "Directory unchanged")
    }

    /**
     * For SQLite MOVE: verify the database is still quiescent after transfer.
     * Checks lsof again + verifies WAL is still absent/empty + SHM unchanged.
     *
     * Must be called BEFORE deleting the source in a MOVE operation.
     */
    fun verifySqliteMoveQuiescent(): VerifyResult {
        if (!isSqlite(sourcePath)) {
            return VerifyResult(true, "Not a SQLite file")
        }

        // Re-check lsof for all three files
        val filesToCheck = sqliteTrio(sourcePath)
        try {
            checkLsof(filesToCheck, "post-transfer")
        } catch (e: SourceGuardException) {
            return VerifyResult(false, "Writer appeared during transfer: ${e.message}")
        }

        // Verify WAL is still absent or empty (header-only = 32 bytes)
        val walFile = File("$sourcePath-wal")
        if (walFile.exists() && walFile.length() > 32) {
            return VerifyResult(false, "WAL file grew during transfer (${walFile.length()} bytes) — a writer touched the DB")
        }

        // Verify SHM hasn't reappeared unexpectedly — a non-empty SHM means
        // a process has the DB open (reader or writer). For MOVE we need full
        // quiescence: no connections at all, because we're about to delete.
        val shmFile = File("$sourcePath-shm")
        if (shmFile.exists() && shmFile.length() > 0) {
            return VerifyResult(false, "SHM file exists (${shmFile.length()} bytes) — a process has the DB open")
        }

        return VerifyResult(true, "SQLite still quiescent")
    }

    /**
     * For SQLite MOVE: checkpoint WAL and verify it's empty before transfer.
     * Call this AFTER acquiring the guard but BEFORE starting rsync.
     *
     * @throws SourceGuardException if checkpoint fails or WAL is not empty after
     */
    fun checkpointAndVerifyWal() {
        if (!isSqlite(sourcePath)) return

        val walFile = File("$sourcePath-wal")
        if (!walFile.exists() || walFile.length() <= 32) {
            logger.info { "WAL already absent/empty for ${File(sourcePath).name}" }
            return
        }

        logger.info { "Checkpointing WAL for ${File(sourcePath).name} (${walFile.length()} bytes)" }

        try {
            // Use sqlite3 CLI for checkpoint — avoids linking JDBC just for this
            val process = ProcessBuilder("sqlite3", sourcePath, "PRAGMA wal_checkpoint(TRUNCATE);")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                throw SourceGuardException("WAL checkpoint failed (exit $exitCode): $output")
            }

            // Verify WAL is now empty
            if (walFile.exists() && walFile.length() > 32) {
                throw SourceGuardException("WAL still not empty after checkpoint (${walFile.length()} bytes) — another writer may be active")
            }

            logger.info { "WAL checkpoint complete, WAL is empty" }
        } catch (e: SourceGuardException) {
            throw e
        } catch (e: Exception) {
            throw SourceGuardException("WAL checkpoint error: ${e.message}")
        }
    }

    override fun close() {
        try {
            if (lockFile.exists()) {
                lockFile.delete()
                logger.info { "SourceGuard: released lock for ${File(sourcePath).name}" }
            }
        } catch (e: Exception) {
            logger.warn { "Failed to remove lock file ${lockFile.name}: ${e.message}" }
        }
    }
}

data class SourceFingerprint(
    val mtime: Instant,
    val size: Long,
    val sha256: String?
) {
    override fun toString(): String {
        val hashStr = sha256?.let { ", sha256=${it.take(16)}..." } ?: ""
        return "mtime=$mtime, size=$size$hashStr"
    }
}

data class VerifyResult(
    val passed: Boolean,
    val detail: String
)

data class LsofEntry(
    val command: String,
    val pid: Long,
    val fd: String,
    val path: String
)

class SourceGuardException(message: String) : RuntimeException(message)

package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import mu.KotlinLogging
import vision.salient.choam.DEFAULT_BWLIMIT_KBPS
import vision.salient.choam.catalog.CatalogIndex
import vision.salient.choam.config.ChoamConfigLoader
import vision.salient.choam.dag.ConfigResolver
import vision.salient.choam.config.MachineProfile
import vision.salient.choam.lowPriority
import vision.salient.choam.niceRemote
import vision.salient.sietch.core.ensureGlobalIgnore
import java.io.File
import java.net.InetAddress
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

class CatalogSyncCommand : CliktCommand(
    name = "catalog-sync",
    help = """
        Download catalog registries from remote machines, merge into the unified registry, and rebuild the FTS5 search index.

        Connects to each remote machine via SSH/rsync (Tailscale or hostname), downloads their sietch_registry.db (compressed with gzip when possible, resumable via rsync --partial), and merges rows into ~/.choam/unified_registry.db. After merging, rebuilds the search index at ~/.choam/catalog-index.db.

        Key behaviors:
          - Delta sync: after first full sync, only merges rows newer than the stored watermark
          - Remaps machine name aliases (e.g. old hostname → config key) during merge
          - Runs WAL checkpoint on remote before download to ensure consistency
          - Falls back to uncompressed transfer or 3-file copy (db + wal + shm) if compression fails
          - Skips unreachable machines gracefully

        Safety: Read-only on remote machines. Writes to local unified_registry.db and catalog-index.db. Safe to re-run — uses INSERT OR REPLACE.

        Examples:
          choam catalog-sync
          choam catalog-sync --from server
    """.trimIndent()
) {
    private val from by option("--from", help = "Sync only from this machine name (must match a key in config)")

    override fun run() {
        val config = try {
            ConfigResolver.resolve()
        } catch (e: Exception) {
            echo("Failed to load CHOAM config: ${e.message}", err = true)
            echo("Run 'choam init' first to create config.", err = true)
            return
        }

        val hostname = try {
            InetAddress.getLocalHost().hostName
        } catch (_: Exception) { "unknown" }

        val localMachineKey = config.machines.entries
            .find { it.value.hostname == hostname || it.value.hostname.startsWith(hostname) }
            ?.key

        val choamDir = File(System.getProperty("user.home"), ".choam")
        val syncTempDir = File(choamDir, "sync_temp")
        syncTempDir.mkdirs()

        val unifiedDbPath = File(choamDir, "unified_registry.db").absolutePath

        val remoteMachines = config.machines.entries
            .filter { it.key != localMachineKey }
            .filter { from == null || it.key == from }

        if (remoteMachines.isEmpty()) {
            if (from != null) {
                echo("Machine '$from' not found in config. Available: ${config.machines.keys.joinToString(", ")}")
            } else {
                echo("No remote machines configured.")
            }
            return
        }

        // Build machine name alias map: old hostname -> config key
        val machineNameMap = mutableMapOf<String, String>()
        for ((configKey, profile) in config.machines) {
            for (alias in profile.aliases) {
                machineNameMap[alias] = configKey
            }
        }

        echo("Catalog Sync")
        echo("  Local machine: ${localMachineKey ?: hostname}")
        echo("  Targets: ${remoteMachines.map { it.key }.joinToString(", ")}")
        if (machineNameMap.isNotEmpty()) {
            echo("  Aliases: ${machineNameMap.entries.joinToString(", ") { "${it.key} → ${it.value}" }}")
        }
        echo()

        // Ensure local global ignore exists with defaults
        ensureGlobalIgnore()

        // One-time backfill: canonicalize existing alias rows in unified registry
        if (machineNameMap.isNotEmpty() && File(unifiedDbPath).exists()) {
            backfillAliases(unifiedDbPath, machineNameMap)
        }

        var totalMerged = 0L
        var machinesSynced = 0
        // Collect remote ignore patterns for union merge
        val remoteIgnorePatterns = mutableSetOf<String>()

        for ((name, machine) in remoteMachines) {
            echo("[$name] Checking reachability...")
            val ip = machine.tailscaleIp ?: machine.hostname
            val reachable = try {
                InetAddress.getByName(ip).isReachable(10000)
            } catch (_: Exception) { false }

            if (!reachable) {
                echo("[$name] \u001b[31mUnreachable\u001b[0m ($ip) — skipping")
                echo()
                continue
            }
            echo("[$name] \u001b[32mReachable\u001b[0m ($ip)")

            // Sync .sietch/ignore — collect remote patterns for union merge
            syncIgnoreFile(machine, ip, name, remoteIgnorePatterns)

            val remoteRegistryPath = "~/.choam/catalogs/sietch_registry.db"
            val localTemp = File(syncTempDir, "${name}_registry.db")

            // WAL checkpoint on remote
            echo("[$name] Checkpointing WAL on remote...")
            val checkpointOk = walCheckpoint(machine, ip, remoteRegistryPath)
            if (!checkpointOk) {
                echo("[$name] \u001b[33mWAL checkpoint failed (sqlite3 may not be available). Trying 3-file copy...\u001b[0m")
            }

            // Download registry DB to local temp (rsync --compress --partial for resumability)
            echo("[$name] Downloading registry...")
            val downloadOk = if (checkpointOk) {
                rsyncDirect(machine, ip, remoteRegistryPath, localTemp.absolutePath)
            } else {
                rsyncThreeFiles(machine, ip, remoteRegistryPath, syncTempDir.absolutePath, name)
            }

            if (!downloadOk) {
                echo("[$name] \u001b[31mFailed to download registry\u001b[0m — skipping")
                echo()
                continue
            }

            if (!localTemp.exists() || localTemp.length() == 0L) {
                echo("[$name] \u001b[31mDownloaded file is empty or missing\u001b[0m — skipping")
                echo()
                continue
            }

            // Merge into unified registry (delta sync: use watermark if available)
            val watermark = getWatermark(unifiedDbPath, name)
            if (watermark != null) {
                echo("[$name] Delta sync from watermark: $watermark")
            } else {
                echo("[$name] Full sync (no watermark)")
            }
            echo("[$name] Merging into unified registry...")
            val merged = mergeRegistry(localTemp.absolutePath, unifiedDbPath, name, machineNameMap, watermark)
            totalMerged += merged
            machinesSynced++
            echo("[$name] \u001b[32mMerged ${"%,d".format(merged)} rows\u001b[0m")

            // Persist watermark from remote's max registered_at
            val maxRegisteredAt = getMaxRegisteredAt(localTemp.absolutePath)
            if (maxRegisteredAt != null) {
                setWatermark(unifiedDbPath, name, maxRegisteredAt)
            }

            echo()
        }

        // Union merge: add any new patterns from remotes into local ignore, then push back
        if (remoteIgnorePatterns.isNotEmpty()) {
            val mergedCount = mergeIgnorePatterns(remoteIgnorePatterns)
            if (mergedCount > 0) {
                echo("Ignore sync: added $mergedCount new patterns from remote machines")
                // Push updated ignore back to all reachable remotes
                val localIgnore = File(System.getProperty("user.home"), ".sietch/ignore")
                for ((name, machine) in remoteMachines) {
                    val ip = machine.tailscaleIp ?: machine.hostname
                    val reachable = try { InetAddress.getByName(ip).isReachable(5000) } catch (_: Exception) { false }
                    if (reachable) pushIgnoreFile(machine, ip, name, localIgnore)
                }
            } else {
                echo("Ignore sync: all machines in sync")
            }
        }
        echo()

        if (machinesSynced == 0) {
            echo("No machines were synced. Check connectivity and try again.")
            return
        }

        // Rebuild CatalogIndex FTS from unified registry
        echo("Rebuilding search index from unified registry...")
        val indexDbPath = "${System.getProperty("user.home")}/.choam/catalog-index.db"
        val catalogIndex = CatalogIndex(indexDbPath)
        val conn = catalogIndex.open()
        val rebuiltCount = catalogIndex.rebuildFromRegistry(conn, unifiedDbPath, config.drives, machineNameMap)
        conn.close()

        echo()
        echo("Sync complete:")
        echo("  Machines synced: $machinesSynced")
        echo("  Total rows merged: ${"%,d".format(totalMerged)}")
        echo("  Search index rebuilt: ${"%,d".format(rebuiltCount)} files")

        // Show per-machine summary from unified DB
        showUnifiedSummary(unifiedDbPath, machineNameMap)
    }

    /**
     * Fetch ~/.sietch/ignore from a remote machine and collect its patterns.
     * Non-fatal — if the file doesn't exist remotely, we skip silently.
     */
    private fun syncIgnoreFile(machine: MachineProfile, ip: String, name: String, patterns: MutableSet<String>) {
        try {
            val sshUser = machine.sshUser?.let { "$it@" } ?: ""
            val portArgs = if (machine.sshPort != 22) listOf("-p", machine.sshPort.toString()) else emptyList()
            val cmd = lowPriority(listOf("ssh") + portArgs + listOf(
                "$sshUser$ip",
                "cat ~/.sietch/ignore 2>/dev/null"
            ))
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(15, TimeUnit.SECONDS)
            if (!finished) { process.destroyForcibly(); return }
            if (process.exitValue() != 0 || output.isBlank()) return

            // Parse non-comment, non-blank lines as patterns
            output.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { patterns.add(it) }

            logger.debug { "[$name] Collected ${patterns.size} ignore patterns" }
        } catch (e: Exception) {
            logger.debug(e) { "[$name] Failed to fetch remote ignore (non-fatal)" }
        }
    }

    /**
     * Merge remote patterns into the local ~/.sietch/ignore file.
     * Adds any patterns not already present. Preserves comments and structure.
     * @return Number of new patterns added
     */
    private fun mergeIgnorePatterns(remotePatterns: Set<String>): Int {
        val ignoreFile = File(System.getProperty("user.home"), ".sietch/ignore")
        ensureGlobalIgnore() // create with defaults if missing

        val existingLines = ignoreFile.readLines()
        val existingPatterns = existingLines
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()

        val newPatterns = remotePatterns - existingPatterns
        if (newPatterns.isEmpty()) return 0

        // Append new patterns with a section header
        ignoreFile.appendText(buildString {
            appendLine()
            appendLine("# Added by catalog-sync (${java.time.LocalDate.now()})")
            newPatterns.sorted().forEach { appendLine(it) }
        })

        return newPatterns.size
    }

    /**
     * Push the local ~/.sietch/ignore to a remote machine via rsync.
     */
    private fun pushIgnoreFile(machine: MachineProfile, ip: String, name: String, localIgnore: File) {
        try {
            val sshUser = machine.sshUser?.let { "$it@" } ?: ""
            val sshPortArgs = if (machine.sshPort != 22) listOf("-p", machine.sshPort.toString()) else emptyList()
            val sshCmd = (listOf("ssh") + sshPortArgs).joinToString(" ")

            // Ensure remote ~/.sietch/ directory exists
            val mkdirCmd = lowPriority(listOf("ssh") + sshPortArgs + listOf(
                "$sshUser$ip", "mkdir -p ~/.sietch"
            ))
            ProcessBuilder(mkdirCmd).redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)

            val cmd = lowPriority(listOf(
                "rsync", "--partial", "--timeout=30",
                "-e", sshCmd,
                localIgnore.absolutePath,
                "$sshUser$ip:~/.sietch/ignore"
            ))
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) { process.destroyForcibly(); return }
            if (process.exitValue() == 0) {
                logger.debug { "[$name] Pushed ignore file" }
            }
        } catch (e: Exception) {
            logger.debug(e) { "[$name] Failed to push ignore file (non-fatal)" }
        }
    }

    private fun walCheckpoint(machine: MachineProfile, ip: String, remotePath: String): Boolean {
        return try {
            val sshUser = machine.sshUser?.let { "$it@" } ?: ""
            val portArgs = if (machine.sshPort != 22) listOf("-p", machine.sshPort.toString()) else emptyList()
            val cmd = lowPriority(listOf("ssh") + portArgs + listOf(
                "$sshUser$ip",
                niceRemote("sqlite3 $remotePath \"PRAGMA wal_checkpoint(TRUNCATE)\"")
            ))
            logger.debug { "WAL checkpoint: ${cmd.joinToString(" ")}" }
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) {
                logger.warn { "WAL checkpoint timed out after 60s, killing process" }
                process.destroyForcibly()
                return false
            }
            process.exitValue() == 0
        } catch (e: Exception) {
            logger.warn(e) { "WAL checkpoint failed" }
            false
        }
    }

    /**
     * Transfer a file from a remote machine using rsync.
     *
     * Strategy: compress on remote → rsync --partial the .gz (resumable) → decompress locally.
     * Falls back to rsync --partial --compress on the raw file if remote gzip fails.
     * All transfers are resumable — interrupted transfers continue from where they left off.
     */
    private fun rsyncFile(machine: MachineProfile, ip: String, remotePath: String, localPath: String): Boolean {
        val sshUser = machine.sshUser?.let { "$it@" } ?: ""
        val sshPortArgs = if (machine.sshPort != 22) listOf("-p", machine.sshPort.toString()) else emptyList()
        val remoteTempGz = "/tmp/sietch_registry.db.gz"
        val localGzPath = "$localPath.gz"
        val sshCmd = (listOf("ssh") + sshPortArgs).joinToString(" ")

        // Step 1: Compress on remote
        echo("  Compressing on remote...")
        val compressOk = try {
            val cmd = lowPriority(listOf("ssh") + sshPortArgs + listOf(
                "$sshUser$ip",
                niceRemote("gzip -c $remotePath > $remoteTempGz")
            ))
            logger.debug { "Remote gzip: ${cmd.joinToString(" ")}" }
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(10, TimeUnit.MINUTES)
            if (!finished) {
                logger.warn { "Remote gzip timed out after 10min" }
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (e: Exception) {
            logger.warn(e) { "Remote gzip failed" }
            false
        }

        if (!compressOk) {
            echo("  \u001b[33mRemote compression failed, falling back to rsync --compress\u001b[0m")
            return rsyncDirect(machine, ip, remotePath, localPath)
        }

        // Step 2: rsync the compressed file (resumable with --partial)
        echo("  Downloading compressed registry (rsync --partial, resumable)...")
        val rsyncOk = try {
            val cmd = lowPriority(listOf(
                "rsync", "--partial", "--progress", "--timeout=600",
                "--bwlimit=$DEFAULT_BWLIMIT_KBPS",
                "-e", sshCmd,
                "$sshUser$ip:$remoteTempGz",
                localGzPath
            ))
            logger.debug { "rsync compressed: ${cmd.joinToString(" ")}" }
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            // Stream rsync progress output
            val reader = process.inputStream.bufferedReader()
            var lastProgressLine = ""
            reader.forEachLine { line ->
                if (line.contains("%")) {
                    lastProgressLine = line.trim()
                    // Print progress on same line
                    echo("\r  $lastProgressLine", trailingNewline = false)
                }
            }
            if (lastProgressLine.isNotEmpty()) echo() // newline after progress
            val finished = process.waitFor(2, TimeUnit.HOURS)
            if (!finished) {
                logger.warn { "rsync timed out after 2 hours" }
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (e: Exception) {
            logger.warn(e) { "rsync of compressed file failed" }
            false
        }

        // Clean up remote temp file (best-effort)
        cleanupRemoteTempFile(sshUser, ip, sshPortArgs, remoteTempGz)

        if (!rsyncOk) {
            File(localGzPath).delete()
            echo("  \u001b[33mCompressed download failed, falling back to rsync --compress\u001b[0m")
            return rsyncDirect(machine, ip, remotePath, localPath)
        }

        // Step 3: Decompress locally
        echo("  Decompressing locally...")
        return try {
            val gzFile = File(localGzPath)
            val outFile = File(localPath)
            java.util.zip.GZIPInputStream(gzFile.inputStream().buffered()).use { gzIn ->
                outFile.outputStream().buffered().use { out ->
                    gzIn.copyTo(out)
                }
            }
            gzFile.delete()
            val sizeMb = outFile.length() / (1024 * 1024)
            echo("  Decompressed to ${sizeMb}MB")
            true
        } catch (e: Exception) {
            logger.warn(e) { "Local decompression failed" }
            File(localGzPath).delete()
            File(localPath).delete()
            echo("  \u001b[33mDecompression failed, falling back to rsync --compress\u001b[0m")
            rsyncDirect(machine, ip, remotePath, localPath)
        }
    }

    private fun cleanupRemoteTempFile(sshUser: String, ip: String, sshPortArgs: List<String>, remotePath: String) {
        try {
            val cmd = lowPriority(listOf("ssh") + sshPortArgs + listOf(
                "$sshUser$ip",
                "rm -f $remotePath"
            ))
            logger.debug { "Remote cleanup: ${cmd.joinToString(" ")}" }
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            process.waitFor(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.debug(e) { "Remote cleanup failed (non-fatal)" }
        }
    }

    /**
     * Direct rsync transfer with built-in compression (no pre-gzip step).
     * Resumable via --partial. Used as fallback when remote gzip fails.
     */
    private fun rsyncDirect(machine: MachineProfile, ip: String, remotePath: String, localPath: String): Boolean {
        return try {
            val sshUser = machine.sshUser?.let { "$it@" } ?: ""
            val sshPortArgs = if (machine.sshPort != 22) listOf("-p", machine.sshPort.toString()) else emptyList()
            val sshCmd = (listOf("ssh") + sshPortArgs).joinToString(" ")
            echo("  Downloading registry (rsync --compress --partial, resumable)...")
            val cmd = lowPriority(listOf(
                "rsync", "--partial", "--compress", "--progress", "--timeout=600",
                "--bwlimit=$DEFAULT_BWLIMIT_KBPS",
                "-e", sshCmd,
                "$sshUser$ip:$remotePath",
                localPath
            ))
            logger.debug { "rsync direct: ${cmd.joinToString(" ")}" }
            val process = ProcessBuilder(cmd)
                .start()
            // Drain stdout in background (rsync progress uses \r, not \n — forEachLine won't work well)
            val outputCapture = StringBuilder()
            val stdoutThread = Thread {
                try {
                    val buf = ByteArray(4096)
                    val stream = process.inputStream
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        val chunk = String(buf, 0, n)
                        outputCapture.append(chunk)
                        // Extract and display last percentage
                        val pctMatch = Regex("""(\d+)%""").findAll(chunk).lastOrNull()
                        if (pctMatch != null) {
                            echo("\r  Progress: ${pctMatch.groupValues[1]}%", trailingNewline = false)
                        }
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }
            // Capture stderr for error reporting
            val stderrCapture = StringBuilder()
            val stderrThread = Thread {
                try {
                    process.errorStream.bufferedReader().forEachLine { stderrCapture.appendLine(it) }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }

            val finished = process.waitFor(2, TimeUnit.HOURS)
            stdoutThread.join(5000)
            stderrThread.join(5000)
            echo() // newline after progress

            if (!finished) {
                logger.warn { "rsync timed out after 2 hours" }
                process.destroyForcibly()
                false
            } else {
                val exitCode = process.exitValue()
                if (exitCode != 0) {
                    val stderr = stderrCapture.toString().trim()
                    echo("  \u001b[33mrsync exit code $exitCode${if (stderr.isNotEmpty()) ": $stderr" else ""}\u001b[0m")
                    logger.warn { "rsync failed with exit code $exitCode: $stderr" }
                }
                exitCode == 0
            }
        } catch (e: Exception) {
            logger.warn(e) { "rsync direct failed" }
            false
        }
    }

    /**
     * Download SQLite DB + WAL + SHM files via rsync (for when WAL checkpoint isn't available).
     * Each file transferred with --partial for resumability.
     */
    private fun rsyncThreeFiles(machine: MachineProfile, ip: String, remotePath: String, localDir: String, machineName: String): Boolean {
        val sshUser = machine.sshUser?.let { "$it@" } ?: ""
        val sshPortArgs = if (machine.sshPort != 22) listOf("-p", machine.sshPort.toString()) else emptyList()
        val sshCmd = (listOf("ssh") + sshPortArgs).joinToString(" ")
        var success = false
        for (suffix in listOf("", "-wal", "-shm")) {
            try {
                val local = "$localDir/${machineName}_registry.db$suffix"
                val cmd = lowPriority(listOf(
                    "rsync", "--partial", "--timeout=600",
                    "--bwlimit=$DEFAULT_BWLIMIT_KBPS",
                    "-e", sshCmd,
                    "$sshUser$ip:$remotePath$suffix",
                    local
                ))
                logger.debug { "rsync 3-file ($suffix): ${cmd.joinToString(" ")}" }
                val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
                process.inputStream.bufferedReader().readText() // drain output
                val finished = process.waitFor(2, TimeUnit.HOURS)
                if (!finished) {
                    logger.warn { "rsync timed out for $remotePath$suffix" }
                    process.destroyForcibly()
                    continue
                }
                if (suffix == "" && process.exitValue() == 0) success = true
            } catch (e: Exception) {
                logger.debug(e) { "rsync 3-file failed for suffix=$suffix" }
            }
        }
        return success
    }

    private fun showUnifiedSummary(unifiedDbPath: String, machineNameMap: Map<String, String> = emptyMap()) {
        val file = File(unifiedDbPath)
        if (!file.exists()) return
        try {
            val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedDbPath")
            val rs = conn.createStatement().executeQuery(
                "SELECT machine_name, COUNT(*) as cnt, MAX(last_synced_at) as last_sync FROM content_locations GROUP BY machine_name"
            )
            echo()
            echo("Unified Registry:")
            var total = 0L
            while (rs.next()) {
                val rawName = rs.getString("machine_name")
                val displayName = machineNameMap[rawName] ?: rawName
                val count = rs.getLong("cnt")
                val lastSync = rs.getString("last_sync") ?: "unknown"
                total += count
                echo("  ${displayName.padEnd(24)} ${"%,d".format(count)} files    last synced: $lastSync")
            }
            echo("  Total: ${"%,d".format(total)} files")
            rs.close()
            conn.close()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read unified registry summary" }
        }
    }

    private fun backfillAliases(unifiedDbPath: String, machineNameMap: Map<String, String>) {
        try {
            val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedDbPath")
            val updateStmt = conn.prepareStatement(
                "UPDATE content_locations SET machine_name = ? WHERE machine_name = ?"
            )
            for ((alias, configKey) in machineNameMap) {
                updateStmt.setString(1, configKey)
                updateStmt.setString(2, alias)
                val updated = updateStmt.executeUpdate()
                if (updated > 0) {
                    echo("  Backfill: remapped ${"%,d".format(updated)} rows from '$alias' → '$configKey'")
                }
            }
            updateStmt.close()
            conn.close()
        } catch (e: Exception) {
            logger.warn(e) { "Alias backfill failed" }
        }
    }

    private fun getMaxRegisteredAt(remoteDbPath: String): String? {
        return try {
            val conn = DriverManager.getConnection("jdbc:sqlite:$remoteDbPath")
            conn.createStatement().executeUpdate("PRAGMA query_only=ON")
            val rs = conn.createStatement().executeQuery(
                "SELECT MAX(registered_at) FROM content_locations"
            )
            val result = if (rs.next()) rs.getString(1) else null
            rs.close()
            conn.close()
            result
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read max registered_at from remote" }
            null
        }
    }

    companion object {
        /**
         * Merge a remote registry DB into the unified registry.
         * Extracted as a companion function for testability.
         *
         * @param machineNameMap Maps old hostnames (aliases) to config keys for remapping
         * @param sinceTimestamp When non-null, only merge rows with registered_at >= this value (delta sync)
         */
        fun mergeRegistry(
            remoteDbPath: String,
            unifiedDbPath: String,
            machineName: String,
            machineNameMap: Map<String, String> = emptyMap(),
            sinceTimestamp: String? = null
        ): Long {
            val unifiedDir = File(unifiedDbPath).parentFile
            unifiedDir?.mkdirs()

            val unifiedConn = DriverManager.getConnection("jdbc:sqlite:$unifiedDbPath")
            val us = unifiedConn.createStatement()
            us.executeUpdate("PRAGMA journal_mode=WAL")
            us.executeUpdate("PRAGMA synchronous=NORMAL")

            // Create unified table with last_synced_at
            us.executeUpdate("""
                CREATE TABLE IF NOT EXISTS content_locations (
                    cid TEXT NOT NULL,
                    machine_name TEXT NOT NULL,
                    file_path TEXT NOT NULL,
                    file_size INTEGER,
                    verified_at TEXT,
                    registered_at TEXT NOT NULL DEFAULT (datetime('now')),
                    last_synced_at TEXT NOT NULL DEFAULT (datetime('now')),
                    PRIMARY KEY (cid, machine_name, file_path)
                )
            """)
            us.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ucl_machine ON content_locations(machine_name)")
            us.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ucl_cid ON content_locations(cid)")

            // Create sync_metadata table for watermark persistence
            us.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sync_metadata (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
            """)

            // Migration: add last_synced_at if table already exists without it
            try { us.executeUpdate("ALTER TABLE content_locations ADD COLUMN last_synced_at TEXT NOT NULL DEFAULT (datetime('now'))") } catch (_: Exception) {}

            us.close()

            // Read from remote DB
            val remoteConn = DriverManager.getConnection("jdbc:sqlite:$remoteDbPath")
            remoteConn.createStatement().executeUpdate("PRAGMA query_only=ON")

            val query = if (sinceTimestamp != null) {
                "SELECT cid, machine_name, file_path, file_size, verified_at, registered_at FROM content_locations WHERE registered_at >= ?"
            } else {
                "SELECT cid, machine_name, file_path, file_size, verified_at, registered_at FROM content_locations"
            }

            val queryStmt = remoteConn.prepareStatement(query)
            if (sinceTimestamp != null) {
                queryStmt.setString(1, sinceTimestamp)
            }
            val rs = queryStmt.executeQuery()

            val insertStmt = unifiedConn.prepareStatement("""
                INSERT OR REPLACE INTO content_locations
                (cid, machine_name, file_path, file_size, verified_at, registered_at, last_synced_at)
                VALUES (?, ?, ?, ?, ?, ?, datetime('now'))
            """)

            unifiedConn.autoCommit = false
            var count = 0L
            while (rs.next()) {
                insertStmt.setString(1, rs.getString("cid"))
                // Remap machine name if alias mapping exists
                val originalMachine = rs.getString("machine_name")
                val finalMachineName = machineNameMap[originalMachine] ?: originalMachine
                insertStmt.setString(2, finalMachineName)
                insertStmt.setString(3, rs.getString("file_path"))
                val fileSize = rs.getLong("file_size")
                if (rs.wasNull()) insertStmt.setNull(4, java.sql.Types.INTEGER)
                else insertStmt.setLong(4, fileSize)
                insertStmt.setString(5, rs.getString("verified_at"))
                insertStmt.setString(6, rs.getString("registered_at"))
                insertStmt.executeUpdate()
                count++
            }
            unifiedConn.commit()
            unifiedConn.autoCommit = true

            insertStmt.close()
            rs.close()
            queryStmt.close()
            remoteConn.close()
            unifiedConn.close()

            return count
        }

        fun getWatermark(unifiedDbPath: String, machineName: String): String? {
            return try {
                val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedDbPath")
                val stmt = conn.createStatement()
                // Ensure table exists
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS sync_metadata (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                """)
                val rs = conn.prepareStatement(
                    "SELECT value FROM sync_metadata WHERE key = ?"
                ).apply { setString(1, "watermark:$machineName") }.executeQuery()
                val result = if (rs.next()) rs.getString(1) else null
                rs.close()
                conn.close()
                result
            } catch (_: Exception) {
                null
            }
        }

        fun setWatermark(unifiedDbPath: String, machineName: String, timestamp: String) {
            try {
                val conn = DriverManager.getConnection("jdbc:sqlite:$unifiedDbPath")
                val stmt = conn.createStatement()
                // Ensure table exists
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS sync_metadata (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                """)
                val upsert = conn.prepareStatement(
                    "INSERT OR REPLACE INTO sync_metadata (key, value) VALUES (?, ?)"
                )
                upsert.setString(1, "watermark:$machineName")
                upsert.setString(2, timestamp)
                upsert.executeUpdate()
                upsert.close()
                stmt.close()
                conn.close()
            } catch (e: Exception) {
                logger.warn(e) { "Failed to set watermark for $machineName" }
            }
        }
    }
}

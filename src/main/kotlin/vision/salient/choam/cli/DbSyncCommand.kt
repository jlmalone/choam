package vision.salient.choam.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import mu.KotlinLogging
import vision.salient.choam.DEFAULT_BWLIMIT_KBPS
import vision.salient.choam.dag.ConfigResolver
import vision.salient.choam.config.MachineProfile
import vision.salient.choam.lowPriority
import vision.salient.choam.niceRemote
import vision.salient.choam.sync.GenericDbMerger
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

class DbSyncCommand : CliktCommand(
    name = "db-sync",
    help = """
        Sync any SQLite database between machines.

        Downloads a database from remote machines via SSH/rsync, then merges rows
        into the local copy using primary key-based deduplication.

        Tables with AUTOINCREMENT primary keys are skipped by default (IDs collide
        across machines). Use --tables to explicitly select tables with natural keys.

        Examples:
          choam db-sync ~/data/tracker.db
          choam db-sync ~/data/tracker.db --from server-a
          choam db-sync ~/data/tracker.db --tables records,batches
          choam db-sync ~/data/tracker.db --strategy insert-or-replace
          choam db-sync ~/data/tracker.db --strategy timestamp-wins
          choam db-sync ~/data/tracker.db --push
          choam db-sync ~/data/tracker.db --dry-run
    """.trimIndent()
) {
    private val localDbPath by argument(help = "Path to the local SQLite database")
    private val from by option("--from", help = "Sync only from this machine (must match a config key)")
    private val remotePath by option("--remote-path", help = "Path to DB on remote machine (default: same as local)")
    private val strategy by option(
        "--strategy",
        help = "Merge strategy: insert-or-ignore (default), insert-or-replace, timestamp-wins"
    )
        .default("insert-or-ignore")
    private val tables by option("--tables", help = "Comma-separated list of tables to sync (default: all)")
    private val excludeTables by option("--exclude-tables", help = "Comma-separated list of tables to skip")
    private val push by option("--push", help = "Push local DB to remotes instead of pulling").flag()
    private val dryRun by option("--dry-run", help = "Show what would be done without doing it").flag()
    private val allowUncheckedWal by option(
        "--allow-unchecked-wal",
        help = "Continue with rsync even if the remote WAL checkpoint failed. Default is to abort " +
                "that machine's sync, since an unchecked DB may carry uncommitted WAL state."
    ).flag()

    override fun run() {
        val config = try {
            ConfigResolver.resolve()
        } catch (e: Exception) {
            echo("Failed to load CHOAM config: ${e.message}", err = true)
            echo("Run 'choam init' first to create config.", err = true)
            return
        }

        val expandedLocalPath = localDbPath.replaceFirst("~", System.getProperty("user.home"))
        val expandedRemotePath = remotePath ?: localDbPath // Keep ~ for remote SSH

        val hostname = try {
            InetAddress.getLocalHost().hostName
        } catch (_: Exception) { "unknown" }

        val localMachineKey = config.machines.entries
            .find { it.value.hostname == hostname || it.value.hostname.startsWith(hostname) }
            ?.key

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

        val mergeStrategy = when (strategy) {
            "insert-or-ignore" -> GenericDbMerger.MergeStrategy.INSERT_OR_IGNORE
            "insert-or-replace" -> GenericDbMerger.MergeStrategy.INSERT_OR_REPLACE
            "timestamp-wins" -> GenericDbMerger.MergeStrategy.TIMESTAMP_WINS
            else -> {
                echo("Unknown strategy '$strategy'. Use insert-or-ignore, insert-or-replace, or timestamp-wins.", err = true)
                return
            }
        }

        val includeTablesList = tables?.split(",")?.map { it.trim() }
        val excludeTablesList = excludeTables?.split(",")?.map { it.trim() } ?: emptyList()

        echo("Database Sync")
        echo("  Local DB: $expandedLocalPath")
        echo("  Remote DB: $expandedRemotePath")
        echo("  Strategy: $strategy")
        echo("  Direction: ${if (push) "push" else "pull"}")
        if (includeTablesList != null) echo("  Tables: ${includeTablesList.joinToString(", ")}")
        if (excludeTablesList.isNotEmpty()) echo("  Exclude: ${excludeTablesList.joinToString(", ")}")
        echo("  Targets: ${remoteMachines.map { it.key }.joinToString(", ")}")
        if (dryRun) echo("  *** DRY RUN ***")
        echo()

        val syncTempDir = File(System.getProperty("user.home"), ".choam/db_sync_temp")
        syncTempDir.mkdirs()

        var machinesSynced = 0
        var totalInserted = 0L
        var totalUpdated = 0L

        for ((name, machine) in remoteMachines) {
            echo("[$name] Checking reachability...")
            val ip = machine.tailscaleIp ?: machine.hostname
            val reachable = try {
                InetAddress.getByName(ip).isReachable(10000)
            } catch (_: Exception) { false }

            if (!reachable) {
                echo("[$name] Unreachable ($ip) — skipping")
                echo()
                continue
            }
            echo("[$name] Reachable ($ip)")

            if (dryRun) {
                echo("[$name] Would ${if (push) "push" else "pull"} $expandedRemotePath")
                echo()
                machinesSynced++
                continue
            }

            if (push) {
                val localFile = File(expandedLocalPath)
                if (!localFile.exists()) {
                    echo("[$name] Local DB does not exist — nothing to push")
                    continue
                }
                echo("[$name] Pushing local DB to remote...")
                val pushOk = rsyncPush(machine, ip, expandedLocalPath, expandedRemotePath)
                if (pushOk) {
                    echo("[$name] Push complete")
                    machinesSynced++
                } else {
                    echo("[$name] Push failed")
                }
            } else {
                // Pull: download remote DB, merge locally
                val localTemp = File(syncTempDir, "${name}_db_sync.db")

                // WAL checkpoint on remote. If this fails the on-disk DB may not include the latest
                // committed transactions (still in -wal). By default we abort this machine's sync;
                // pass --allow-unchecked-wal to fall through to a direct rsync anyway.
                echo("[$name] Checkpointing WAL on remote...")
                val checkpointOk = walCheckpoint(machine, ip, expandedRemotePath)
                if (!checkpointOk) {
                    if (allowUncheckedWal) {
                        echo("[$name] WAL checkpoint failed — proceeding with direct rsync (--allow-unchecked-wal)")
                    } else {
                        echo("[$name] WAL checkpoint failed — skipping. Re-run with --allow-unchecked-wal to override.")
                        echo()
                        continue
                    }
                }

                echo("[$name] Downloading...")
                val downloadOk = rsyncDirect(machine, ip, expandedRemotePath, localTemp.absolutePath)

                if (!downloadOk || !localTemp.exists() || localTemp.length() == 0L) {
                    echo("[$name] Download failed or empty — skipping")
                    localTemp.delete()
                    echo()
                    continue
                }

                val sizeMb = localTemp.length() / 1024.0 / 1024.0
                echo("[$name] Downloaded (%.1f MB)".format(sizeMb))

                // Merge
                echo("[$name] Merging...")
                val result = GenericDbMerger.merge(
                    sourceDbPath = localTemp.absolutePath,
                    targetDbPath = expandedLocalPath,
                    strategy = mergeStrategy,
                    includeTables = includeTablesList,
                    excludeTables = excludeTablesList
                )

                echo(
                    "[$name] Tables: ${result.tablesProcessed}, " +
                            "Inserted: ${"%,d".format(result.rowsInserted)}, " +
                            "Updated: ${"%,d".format(result.rowsUpdated)}, " +
                            "Skipped: ${"%,d".format(result.rowsSkipped)}"
                )
                if (result.tablesSkipped.isNotEmpty()) {
                    echo("[$name] Skipped tables: ${result.tablesSkipped.joinToString(", ")}")
                }
                if (result.tablesCreated.isNotEmpty()) {
                    echo("[$name] Created tables: ${result.tablesCreated.joinToString(", ")}")
                }
                if (result.errors.isNotEmpty()) {
                    for (err in result.errors) echo("[$name] ERROR: $err")
                }

                totalInserted += result.rowsInserted
                totalUpdated += result.rowsUpdated
                machinesSynced++

                // Cleanup temp file
                localTemp.delete()
            }
            echo()
        }

        echo("Sync complete:")
        echo("  Machines synced: $machinesSynced")
        if (!push) {
            echo("  Total rows inserted: ${"%,d".format(totalInserted)}")
            echo("  Total rows updated: ${"%,d".format(totalUpdated)}")
        }
    }

    private fun walCheckpoint(machine: MachineProfile, ip: String, remotePath: String): Boolean {
        return try {
            val sshUser = machine.sshUser?.let { "$it@" } ?: ""
            val portArgs = if (machine.sshPort != 22) listOf("-p", machine.sshPort.toString()) else emptyList()
            val cmd = lowPriority(listOf("ssh") + portArgs + listOf(
                "$sshUser$ip",
                niceRemote("sqlite3 ${shellQuote(remotePath)} \"PRAGMA wal_checkpoint(TRUNCATE)\"")
            ))
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return false
            }
            process.exitValue() == 0
        } catch (e: Exception) {
            logger.warn(e) { "WAL checkpoint failed" }
            false
        }
    }

    private fun rsyncDirect(machine: MachineProfile, ip: String, remotePath: String, localPath: String): Boolean {
        return try {
            val sshUser = machine.sshUser?.let { "$it@" } ?: ""
            val sshPortArgs = if (machine.sshPort != 22) listOf("-p", machine.sshPort.toString()) else emptyList()
            val sshCmd = (listOf("ssh") + sshPortArgs).joinToString(" ")
            // Single-quote the remote path. rsync passes it through ssh to the remote shell,
            // which unquotes back to the literal path. This is portable across rsync versions
            // (macOS's bundled rsync doesn't support -s/--protect-args).
            val cmd = lowPriority(listOf(
                "rsync", "--partial", "--compress", "--timeout=600",
                "--bwlimit=$DEFAULT_BWLIMIT_KBPS",
                "-e", sshCmd,
                "$sshUser$ip:${shellQuote(remotePath)}",
                localPath
            ))
            logger.debug { "rsync: ${cmd.joinToString(" ")}" }
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().readText() // drain
            val finished = process.waitFor(30, TimeUnit.MINUTES)
            if (!finished) {
                process.destroyForcibly()
                return false
            }
            process.exitValue() == 0
        } catch (e: Exception) {
            logger.warn(e) { "rsync failed" }
            false
        }
    }

    private fun rsyncPush(machine: MachineProfile, ip: String, localPath: String, remotePath: String): Boolean {
        return try {
            val sshUser = machine.sshUser?.let { "$it@" } ?: ""
            val sshPortArgs = if (machine.sshPort != 22) listOf("-p", machine.sshPort.toString()) else emptyList()
            val sshCmd = (listOf("ssh") + sshPortArgs).joinToString(" ")

            // Ensure remote directory exists. The path goes through the remote shell (the ssh
            // command runs in /bin/sh on the far side and $(dirname …) is shell substitution),
            // so the path argument has to be single-quoted defensively.
            val mkdirCmd = lowPriority(listOf("ssh") + sshPortArgs + listOf(
                "$sshUser$ip", "mkdir -p \"\$(dirname ${shellQuote(remotePath)})\""
            ))
            ProcessBuilder(mkdirCmd).redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)

            // See rsyncDirect for the single-quoting rationale (portable across rsync versions).
            val cmd = lowPriority(listOf(
                "rsync", "--partial", "--compress", "--timeout=600",
                "--bwlimit=$DEFAULT_BWLIMIT_KBPS",
                "-e", sshCmd,
                localPath,
                "$sshUser$ip:${shellQuote(remotePath)}"
            ))
            logger.debug { "rsync push: ${cmd.joinToString(" ")}" }
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(30, TimeUnit.MINUTES)
            if (!finished) {
                process.destroyForcibly()
                return false
            }
            process.exitValue() == 0
        } catch (e: Exception) {
            logger.warn(e) { "rsync push failed" }
            false
        }
    }

    /**
     * POSIX-shell-safe single quoting. Wraps the input in `'…'` and escapes any embedded `'`
     * by closing the quote, inserting a literal `\'`, and reopening. Use for any string that
     * crosses into a remote shell context (ssh "<command-string>" or `$(…)` substitution).
     */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}

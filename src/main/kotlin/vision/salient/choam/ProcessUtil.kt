package vision.salient.choam

import java.util.concurrent.TimeUnit

/**
 * Outcome of a bounded external process run.
 *
 * [timedOut] is the load-bearing field: when true the process exceeded its wall-clock
 * budget and was force-killed, so [exitCode]/[output] are incomplete and the caller MUST
 * treat the run as a failure (fail-closed). Never infer success from partial output.
 */
data class BoundedResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean
) {
    /** True only when the process finished on its own with a zero exit code. */
    val ok: Boolean get() = !timedOut && exitCode == 0
}

/**
 * Run [cmd] with a hard wall-clock [timeoutSeconds], returning merged stdout/stderr.
 *
 * Why this exists: a plain `process.waitFor()` (and the `inputStream.readText()` that
 * usually precedes it) blocks forever if the child connects but then stalls without
 * closing its pipes — the classic case being an SSH session that survives `ConnectTimeout`
 * but wedges mid-command over a saturated relay. A choam drain JVM stuck in that state
 * keeps its queue entry RUNNING and its `queue --run` lock held, which freezes the whole
 * queue (neither the lock's stale-cleanup nor the RUNNING watchdog can reclaim a process
 * that is hung-but-alive). Bounding every external call means the JVM can always make
 * progress or fail an entry and move on — it can never hang alive.
 *
 * Output is drained on a daemon thread so that force-killing a stalled child actually
 * unblocks the read (killing the process closes the pipe → the reader sees EOF). If
 * [stdin] is provided it is written on its own daemon thread to avoid deadlocking on a
 * full stdin buffer. On timeout the process is destroyForcibly()'d and [BoundedResult.timedOut]
 * is set; callers decide the fail-closed behaviour for their context.
 */
fun runBounded(
    cmd: List<String>,
    stdin: String? = null,
    timeoutSeconds: Long = 900
): BoundedResult {
    val process = ProcessBuilder(cmd).redirectErrorStream(true).start()

    val out = StringBuilder()
    val reader = Thread {
        try {
            process.inputStream.bufferedReader().use { r -> out.append(r.readText()) }
        } catch (_: Exception) { /* pipe closed on kill — expected */ }
    }.apply { isDaemon = true; name = "runBounded-reader"; start() }

    if (stdin != null) {
        Thread {
            try {
                process.outputStream.bufferedWriter().use { w -> w.write(stdin); w.flush() }
            } catch (_: Exception) { /* child may exit before consuming stdin */ }
        }.apply { isDaemon = true; name = "runBounded-stdin"; start() }
    }

    val finished = try {
        process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    if (!finished) {
        process.destroyForcibly()
        try { process.waitFor(5, TimeUnit.SECONDS) } catch (_: Exception) {}
        reader.join(2000)
        return BoundedResult(exitCode = -1, output = out.toString(), timedOut = true)
    }

    reader.join(2000)
    return BoundedResult(exitCode = process.exitValue(), output = out.toString(), timedOut = false)
}

/**
 * Default wall-clock ceiling for a single SSH probe/verify call in the transfer path.
 * Generous on purpose: SSH keepalives (ServerAliveInterval) drop a genuinely stalled
 * session in ~60s, so this only backstops the rarer "session alive but remote command
 * wedged" case. Large enough that legitimate remote hashing never trips it.
 */
const val SSH_PROBE_TIMEOUT_SEC = 900L

/**
 * Wraps a command list with `nice -n 19` for lowest CPU scheduling priority.
 * Use for all I/O-heavy operations (rsync, gzip, ssh transfers) to avoid
 * competing with foreground work — especially important on machines with
 * attached external drives that are sensitive to heavy concurrent I/O.
 */
fun lowPriority(cmd: List<String>): List<String> =
    listOf("nice", "-n", "19") + cmd

/**
 * Wraps a raw command string with nice for use in SSH remote execution.
 * Example: niceRemote("gzip -c foo.db > bar.gz") → "nice -n 19 gzip -c foo.db > bar.gz"
 */
fun niceRemote(cmd: String): String = "nice -n 19 $cmd"

/**
 * Default bandwidth limit for rsync transfers in KB/s.
 * 10 MB/s — gentle on attached drives while still completing in reasonable time.
 * An 882MB registry transfers in ~88 seconds at this rate.
 */
const val DEFAULT_BWLIMIT_KBPS = 10_240
